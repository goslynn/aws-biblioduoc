#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / build-backend.sh   --   Pipeline del backend: build, publish, deploy
# ==============================================================================
#
#  QUE HACE, EN CINCO PASOS
#    1. COMPILA    ./mvnw package  ->  back/target/biblioteca-backend-*.jar
#                  (los tests levantan PostgreSQL y RabbitMQ en Docker: hace
#                  falta Docker, o SKIP_TESTS=true)
#    2. PUBLICA    sube el fat jar al bucket de artefactos y anota su VersionId
#    3. DESPLIEGA  crea o actualiza el stack del backend (EC2 + RabbitMQ + NLB).
#                  La PRIMERA vez, CloudFormation espera a que /health responda
#                  dentro de la instancia (CreationPolicy) antes de darlo por
#                  creado.
#    4. REINICIA   si la instancia ya existia, le pide por SSM Run Command que
#                  anote la version nueva del jar y reinicie el servicio
#                  biblioteca-app. El script de arranque vuelve a leer TODO:
#                  jar, configuracion (SSM) y secretos.
#    5. ESPERA     a que el NLB vuelva a dar la instancia por sana. Es estado
#                  del despliegue, no una prueba de la API.
#
#  POR QUE EL PASO 4 Y NO "RECREAR LA INSTANCIA"
#    Recrear la EC2 en cada despliegue tardaria minutos y borraria el volumen
#    de RabbitMQ (colas y mensajes pendientes). El stack sigue siendo la fuente
#    de la verdad de la INFRAESTRUCTURA y de la configuracion (parametro de
#    SSM); el jar es un artefacto versionado en S3, y la version que corre
#    queda escrita en /etc/biblioteca/version.
#
#  USO
#    SMTP_HOST=smtp.gmail.com SMTP_USER=yo@gmail.com SMTP_PASSWORD='...' \
#    SMTP_FROM=yo@gmail.com ./aws/pipeline/build-backend.sh
#
#    Las cuatro SMTP_* son obligatorias SOLO la primera vez. Despues, si no se
#    pasan, CloudFormation conserva las del despliegue anterior. En vez de la
#    terminal pueden ir en aws/.env (plantilla: aws/.env.example).
#
#  VARIABLES OPCIONALES
#    PROJECT_NAME (biblioteca)  AWS_REGION (us-east-1)  SKIP_TESTS (false)
#    REMINDER_LEAD (PT24H): antelacion del recordatorio de vencimiento
#    INSTANCE_TYPE (t3.medium)
#
#  DESCARGA UNA COSA PUBLICA (lectura de configuracion, no una prueba)
#    El JWKS de Cognito: las claves publicas con que se firman los tokens.
#    Se fijan al desplegar (parametro JwksJson). Si Cognito rotara sus claves,
#    basta con volver a correr este script.
#
#  DEPENDE DE que ya existan los stacks de artefactos, web, Cognito, red y
#  base de datos.
#  Este script NO prueba la API: eso es trabajo de Bruno y del navegador.
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)/_comun.sh"

SKIP_TESTS="${SKIP_TESTS:-false}"
OBJECT_KEY="backend/${PROJECT_NAME}-backend.jar"

requiere aws curl jq
[ -x "${ROOT}/back/mvnw" ] || die "no encuentro back/mvnw"

cuenta_aws >/dev/null

# Primera vez: el stack no existe y no hay valores anteriores que conservar.
EXISTIA=false
existe_stack "$STACK_BACKEND" && EXISTIA=true
if [ "$EXISTIA" = "false" ]; then
  for v in SMTP_HOST SMTP_USER SMTP_PASSWORD SMTP_FROM; do
    [ -n "${!v:-}" ] || die "falta ${v}. La primera vez hay que indicar el proveedor de correo,
    en aws/.env (cp aws/.env.example aws/.env) o en la terminal:

    SMTP_HOST=smtp.gmail.com SMTP_USER=tu@gmail.com SMTP_PASSWORD='<contrasena de aplicacion>' \\
    SMTP_FROM=tu@gmail.com ./aws/scripts/bootstrap.sh

    (Gmail exige verificacion en dos pasos y una contrasena de aplicacion;
    Brevo usa smtp-relay.brevo.com y un remitente verificado.)"
  done
fi

log "Leyendo los stacks de los que depende el backend"
ARTIFACTS_BUCKET="$(output_obligatorio "$STACK_ARTIFACTS" ArtifactsBucketName)"
ISSUER_URL="$(output_obligatorio "$STACK_COGNITO" IssuerUrl)"
USER_POOL_ID="$(output_obligatorio "$STACK_COGNITO" UserPoolId)"
SCOPE_COMPLETO="$(output_obligatorio "$STACK_COGNITO" ScopeCompleto)"
SCOPE_GESTION="$(output_obligatorio "$STACK_COGNITO" ScopeGestion)"
SITE_ORIGIN="$(output_obligatorio "$STACK_WEB" SiteOrigin)"
PUBLIC_SUBNET="$(output_obligatorio "$STACK_NETWORK" PublicSubnetId)"
PRIVATE_SUBNETS="$(output_obligatorio "$STACK_NETWORK" PrivateSubnetIds)"
APP_SG="$(output_obligatorio "$STACK_NETWORK" AppSecurityGroupId)"
DB_ENDPOINT="$(output_obligatorio "$STACK_DATABASE" DbEndpoint)"
DB_PORT="$(output_obligatorio "$STACK_DATABASE" DbPort)"
DB_NAME="$(output_obligatorio "$STACK_DATABASE" DbName)"
MASTER_SECRET_ARN="$(output_obligatorio "$STACK_DATABASE" MasterSecretArn)"
APP_SECRET_ARN="$(output_obligatorio "$STACK_DATABASE" AppSecretArn)"
info "bucket  ${ARTIFACTS_BUCKET}"
info "issuer  ${ISSUER_URL}"
info "scopes  ${SCOPE_COMPLETO} | ${SCOPE_GESTION}"
info "origen  ${SITE_ORIGIN}"
info "base    ${DB_ENDPOINT}:${DB_PORT}/${DB_NAME} (privada)"

# El JWKS, compactado a una linea: va como valor de un parametro y de una
# linea VARIABLE=valor. jq ademas comprueba que sea JSON con claves de verdad.
log "Descargando las claves publicas de Cognito (JWKS)"
JWKS="$(curl -fsS "${ISSUER_URL}/.well-known/jwks.json" | jq -ce 'select((.keys | length) > 0)')" \
  || die "no pude descargar el JWKS de ${ISSUER_URL}"
info "$(jq -r '.keys | map(.kid) | "claves: " + join(", ")' <<<"$JWKS")"

# --- 1. Compilar --------------------------------------------------------------
log "Compilando el backend con Maven"
if [ "$SKIP_TESTS" = "true" ]; then
  ( cd "${ROOT}/back" && ./mvnw -q -B -DskipTests package )
else
  ( cd "${ROOT}/back" && ./mvnw -q -B package )
fi

# El plugin de Spring Boot deja tambien un .jar.original, que es el jar SIN las
# dependencias dentro. Ese no arranca solo: hay que quedarse con el fat jar.
JAR="$(find "${ROOT}/back/target" -maxdepth 1 -name '*.jar' ! -name '*.original' ! -name '*-sources.jar' | head -n1)"
[ -n "$JAR" ] || die "no encontre el jar en back/target/"
info "jar $(basename "$JAR") ($(du -h "$JAR" | cut -f1))"

# --- 2. Publicar en S3 --------------------------------------------------------
log "Subiendo a s3://${ARTIFACTS_BUCKET}/${OBJECT_KEY}"
# put-object en vez de "s3 cp" porque devuelve el VersionId: es lo que la EC2
# descarga exactamente, y lo que queda anotado como version en ejecucion.
VERSION_ID="$(aws s3api put-object \
  --bucket "$ARTIFACTS_BUCKET" \
  --key "$OBJECT_KEY" \
  --body "$JAR" \
  --region "$AWS_REGION" \
  --query VersionId --output text)"
info "VersionId ${VERSION_ID}"

# --- 3. Desplegar el stack ----------------------------------------------------
log "Desplegando el stack '${STACK_BACKEND}'"
[ "$EXISTIA" = "true" ] || info "primera vez: la instancia instala Docker y arranca (~5 min)"

PARAMETROS=(
  "ProjectName=${PROJECT_NAME}"
  "ArtifactsBucketName=${ARTIFACTS_BUCKET}"
  "ArtifactsObjectKey=${OBJECT_KEY}"
  "IssuerUrl=${ISSUER_URL}"
  "UserPoolId=${USER_POOL_ID}"
  "JwksJson=${JWKS}"
  "RequiredScope=${SCOPE_COMPLETO}"
  "ManagementScope=${SCOPE_GESTION}"
  "WebOrigin=${SITE_ORIGIN}"
  "PublicSubnetId=${PUBLIC_SUBNET}"
  "PrivateSubnetIds=${PRIVATE_SUBNETS}"
  "AppSecurityGroupId=${APP_SG}"
  "DbEndpoint=${DB_ENDPOINT}"
  "DbPort=${DB_PORT}"
  "DbName=${DB_NAME}"
  "MasterSecretArn=${MASTER_SECRET_ARN}"
  "AppSecretArn=${APP_SECRET_ARN}"
)
# Opcionales: si no se pasan, el stack conserva el valor anterior (o el
# Default de la plantilla, la primera vez).
for par in SmtpHost:SMTP_HOST SmtpUser:SMTP_USER SmtpPassword:SMTP_PASSWORD SmtpFrom:SMTP_FROM \
           ReminderLead:REMINDER_LEAD InstanceType:INSTANCE_TYPE; do
  variable="${par#*:}"
  [ -z "${!variable:-}" ] || PARAMETROS+=("${par%%:*}=${!variable}")
done

despliega_stack backend.yaml "$STACK_BACKEND" "${PARAMETROS[@]}" \
  || { por_que_fallo "$STACK_BACKEND"; die "fallo el despliegue del backend.
    Si la instancia no llego a dar la senal, mira su consola:
        aws ec2 get-console-output --instance-id <id> --latest --output text"; }

INSTANCE_ID="$(output_obligatorio "$STACK_BACKEND" InstanceId)"
TARGET_GROUP="$(output_obligatorio "$STACK_BACKEND" TargetGroupArn)"
LOG_GROUP="$(output_obligatorio "$STACK_BACKEND" LogGroupName)"

# --- 4. Reiniciar con el jar nuevo ----------------------------------------------
if [ "$EXISTIA" = "true" ]; then
  log "Reiniciando la aplicacion en ${INSTANCE_ID} (SSM Run Command)"
  COMANDOS="$(jq -nc --arg v "$VERSION_ID" \
    '{commands: ["echo \($v) > /etc/biblioteca/version", "systemctl restart biblioteca-app"]}')"
  COMMAND_ID="$(aws ssm send-command \
    --instance-ids "$INSTANCE_ID" \
    --document-name AWS-RunShellScript \
    --comment "biblioteca: desplegar jar ${VERSION_ID}" \
    --parameters "$COMANDOS" \
    --region "$AWS_REGION" \
    --query Command.CommandId --output text)" \
    || die "SSM no acepto el comando. ¿Esta la instancia encendida? (el lab la detiene al cerrar la sesion)"
  aws ssm wait command-executed --command-id "$COMMAND_ID" --instance-id "$INSTANCE_ID" --region "$AWS_REGION" \
    || die "el reinicio fallo: aws ssm get-command-invocation --command-id ${COMMAND_ID} --instance-id ${INSTANCE_ID}"
  info "servicio reiniciado con la version ${VERSION_ID}"

  # --- 5. Esperar a que el NLB la de por sana ----------------------------------
  # Spring tarda unos segundos en levantar; durante ese rato el NLB la marca
  # como no sana y API Gateway responderia 500. Se espera a que vuelva.
  log "Esperando a que el NLB vuelva a dar la instancia por sana"
  sleep 30
  aws elbv2 wait target-in-service --target-group-arn "$TARGET_GROUP" \
    --targets "Id=${INSTANCE_ID}" --region "$AWS_REGION" \
    || die "la instancia no paso los health checks. Mira los logs:
        aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}"
fi

log "Backend publicado"
cat <<EOF
    Instancia ${INSTANCE_ID}
    Codigo    s3://${ARTIFACTS_BUCKET}/${OBJECT_KEY} (version ${VERSION_ID})

    Logs de la app y de RabbitMQ:
        aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}

    Si cambiaste rutas o metodos, acuerdate de volver a publicar la API:
        ./aws/pipeline/api-deploy.sh
EOF
