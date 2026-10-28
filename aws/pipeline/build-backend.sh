#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / build-backend.sh   --   Pipeline del backend: build, publish, deploy
# ==============================================================================
#
#  QUE HACE, EN CUATRO PASOS
#    1. COMPILA    ./mvnw package  ->  back/target/biblioteca-backend-*.jar
#                  (los tests levantan un PostgreSQL en Docker: hace falta
#                  Docker, o SKIP_TESTS=true)
#    2. EMPAQUETA  el fat jar + un run.sh + la CA de RDS dentro de un ZIP
#    3. PUBLICA    sube el ZIP al bucket de artefactos y anota su VersionId
#    4. DESPLIEGA  actualiza el stack de la Lambda apuntando a ese VersionId
#
#  POR QUE EL PASO 4 ES "cloudformation deploy" Y NO "lambda update-function-code"
#    Porque asi la plantilla sigue siendo la unica fuente de la verdad: el
#    stack sabe exactamente que version del codigo esta corriendo. Con
#    update-function-code el codigo y el stack se desincronizan, y el siguiente
#    despliegue de CloudFormation revierte la funcion sin avisar.
#
#  USO
#    ./aws/pipeline/build-backend.sh
#    EnableBypassDemoUrl=true ./aws/pipeline/build-backend.sh   (demo Fase 13)
#
#  VARIABLES OPCIONALES
#    PROJECT_NAME (biblioteca)  AWS_REGION (us-east-1)  SKIP_TESTS (false)
#    LAB_ROLE_ARN (se deduce)   EnableBypassDemoUrl (false)
#
#  DESCARGA DOS COSAS PUBLICAS (lecturas de configuracion, no pruebas)
#    * El JWKS de Cognito: las claves publicas con que se firman los tokens.
#      La Lambda vive en una VPC sin salida a Internet y no puede bajarlas
#      ella misma, asi que se le entregan como parametro del stack.
#    * El bundle de certificados de la CA de RDS para la region: con el, la
#      aplicacion verifica que habla con SU base (sslmode=verify-full).
#    Si Cognito rotara sus claves, basta con volver a correr este script.
#
#  DEPENDE DE que ya existan los stacks de artefactos, web, Cognito, red y
#  base de datos.
#  Este script NO prueba la API: eso es trabajo de Bruno y del navegador.
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)/_comun.sh"

SKIP_TESTS="${SKIP_TESTS:-false}"
OBJECT_KEY="backend/${PROJECT_NAME}-lambda.zip"
ENABLE_BYPASS="${EnableBypassDemoUrl:-false}"

requiere aws zip curl jq
[ -x "${ROOT}/back/mvnw" ] || die "no encuentro back/mvnw"

ACCOUNT_ID="$(cuenta_aws)"
LAB_ROLE_ARN="${LAB_ROLE_ARN:-arn:aws:iam::${ACCOUNT_ID}:role/LabRole}"

log "Leyendo los stacks de los que depende el backend"
ARTIFACTS_BUCKET="$(output_obligatorio "$STACK_ARTIFACTS" ArtifactsBucketName)"
ISSUER_URL="$(output_obligatorio "$STACK_COGNITO" IssuerUrl)"
SCOPE_COMPLETO="$(output_obligatorio "$STACK_COGNITO" ScopeCompleto)"
SITE_ORIGIN="$(output_obligatorio "$STACK_WEB" SiteOrigin)"
SUBNET_IDS="$(output_obligatorio "$STACK_NETWORK" PrivateSubnetIds)"
LAMBDA_SG="$(output_obligatorio "$STACK_NETWORK" LambdaSecurityGroupId)"
DB_ENDPOINT="$(output_obligatorio "$STACK_DATABASE" DbEndpoint)"
DB_PORT="$(output_obligatorio "$STACK_DATABASE" DbPort)"
DB_NAME="$(output_obligatorio "$STACK_DATABASE" DbName)"
MASTER_SECRET_ARN="$(output_obligatorio "$STACK_DATABASE" MasterSecretArn)"
APP_SECRET_ARN="$(output_obligatorio "$STACK_DATABASE" AppSecretArn)"
info "bucket  ${ARTIFACTS_BUCKET}"
info "issuer  ${ISSUER_URL}"
info "scope   ${SCOPE_COMPLETO}"
info "origen  ${SITE_ORIGIN}"
info "base    ${DB_ENDPOINT}:${DB_PORT}/${DB_NAME} (privada)"

# El JWKS, compactado a una linea: va como valor de un parametro y de una
# variable de entorno. jq ademas comprueba que sea JSON con claves de verdad.
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

# --- 2. Empaquetar ------------------------------------------------------------
# Contenido del ZIP, todo en la raiz (Lambda lo descomprime en /var/task):
#     app.jar     -> el fat jar, sin modificar
#     run.sh      -> el "Handler" de la funcion, que ejecuta el Web Adapter
#     rds-ca.pem  -> la CA de RDS, para verificar el certificado de la base
log "Armando el ZIP de la Lambda"
TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

cp "$JAR" "${TMP}/app.jar"

# La CA de RDS de la region: la que firma el certificado de la instancia. La
# URL de jdbc (backend.yaml) la busca en /var/task/rds-ca.pem.
curl -fsS -o "${TMP}/rds-ca.pem" \
  "https://truststore.pki.rds.amazonaws.com/${AWS_REGION}/${AWS_REGION}-bundle.pem" \
  || die "no pude descargar el bundle de la CA de RDS"
grep -q 'BEGIN CERTIFICATE' "${TMP}/rds-ca.pem" || die "el bundle de la CA de RDS no contiene certificados"

cat > "${TMP}/run.sh" <<'RUNSH'
#!/bin/bash
# Punto de entrada de la Lambda. El AWS Lambda Web Adapter ejecuta este script
# en lugar del arranque normal de Java (por la variable AWS_LAMBDA_EXEC_WRAPPER)
# y despues traduce cada invocacion en un request HTTP contra $PORT. Por eso la
# aplicacion Spring Boot corre exactamente igual que en tu maquina.
exec java -jar /var/task/app.jar
RUNSH

# CRITICO: sin el bit de ejecucion, la funcion muere al arrancar con un error
# generico e ilegible. En Linux, "zip" conserva los permisos del archivo.
chmod +x "${TMP}/run.sh"

ZIP="${TMP}/lambda.zip"
( cd "$TMP" && zip -q -X "$ZIP" app.jar run.sh rds-ca.pem )
info "zip $(du -h "$ZIP" | cut -f1)"

# --- 3. Publicar en S3 --------------------------------------------------------
log "Subiendo a s3://${ARTIFACTS_BUCKET}/${OBJECT_KEY}"
# Se usa put-object en vez de "s3 cp" porque devuelve el VersionId, y ese
# VersionId es lo que le demuestra a CloudFormation que el codigo cambio.
VERSION_ID="$(aws s3api put-object \
  --bucket "$ARTIFACTS_BUCKET" \
  --key "$OBJECT_KEY" \
  --body "$ZIP" \
  --region "$AWS_REGION" \
  --query VersionId --output text)"
info "VersionId ${VERSION_ID}"

# --- 4. Desplegar el stack ----------------------------------------------------
log "Desplegando el stack '${STACK_BACKEND}'"
if [ "$ENABLE_BYPASS" = "true" ]; then
  warn "EnableBypassDemoUrl=true: se va a exponer una Function URL SIN autenticacion."
  warn "Es solo para la demostracion de Defense in Depth. Apagala al terminar."
fi

despliega_stack backend.yaml "$STACK_BACKEND" \
  "ProjectName=${PROJECT_NAME}" \
  "LabRoleArn=${LAB_ROLE_ARN}" \
  "ArtifactsBucketName=${ARTIFACTS_BUCKET}" \
  "ArtifactsObjectKey=${OBJECT_KEY}" \
  "ArtifactsObjectVersion=${VERSION_ID}" \
  "IssuerUrl=${ISSUER_URL}" \
  "JwksJson=${JWKS}" \
  "RequiredScope=${SCOPE_COMPLETO}" \
  "WebOrigin=${SITE_ORIGIN}" \
  "PrivateSubnetIds=${SUBNET_IDS}" \
  "LambdaSecurityGroupId=${LAMBDA_SG}" \
  "DbEndpoint=${DB_ENDPOINT}" \
  "DbPort=${DB_PORT}" \
  "DbName=${DB_NAME}" \
  "MasterSecretArn=${MASTER_SECRET_ARN}" \
  "AppSecretArn=${APP_SECRET_ARN}" \
  "EnableBypassDemoUrl=${ENABLE_BYPASS}" \
  || { por_que_fallo "$STACK_BACKEND"; die "fallo el despliegue del backend"; }

FUNCTION_NAME="$(output_obligatorio "$STACK_BACKEND" FunctionName)"
LOG_GROUP="$(output_obligatorio "$STACK_BACKEND" LogGroupName)"
BYPASS_URL="$(output_de "$STACK_BACKEND" BypassUrl)"

log "Backend publicado"
cat <<EOF
    Funcion   ${FUNCTION_NAME}
    Codigo    s3://${ARTIFACTS_BUCKET}/${OBJECT_KEY} (version ${VERSION_ID})

    Ver el arranque de Spring en la primera llamada:
        aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}
EOF

if [ -n "$BYPASS_URL" ]; then
  cat <<EOF

    PUERTA TRASERA ACTIVA (demo de Defense in Depth):
        ${BYPASS_URL}
    Llega a la aplicacion sin pasar por API Gateway. Apagala al terminar con:
        EnableBypassDemoUrl=false ./aws/pipeline/build-backend.sh
EOF
fi

cat <<EOF

    Si cambiaste rutas o metodos, acuerdate de volver a publicar la API:
        ./aws/pipeline/api-deploy.sh
EOF
