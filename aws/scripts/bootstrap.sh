#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / bootstrap.sh   --   Levanta TODO el proyecto, de cero
# ==============================================================================
#
#  QUE HACE, Y EN QUE ORDEN (el orden no es decorativo)
#
#    0. Comprueba herramientas y credenciales.
#    1. artifacts + network              -> en paralelo: no dependen de nadie
#    1b. web + database                  -> en paralelo, los dos dentro de la
#                                           red de 1: el servidor del sitio
#                                           (IP elastica, Caddy) y PostgreSQL
#                                           privado (~10 min la primera vez)
#    1c. cognito                         -> recibe la URL REAL del sitio (Output
#                                           SiteUrl de web) para los callbacks
#    2. pipeline/build-backend.sh        -> compila, sube el jar y crea la EC2
#                                           con RabbitMQ y el NLB
#    3. api                              -> necesita el pool y el NLB (VPC Link)
#    4. pipeline/api-deploy.sh           -> publica la API en el stage
#    5. usuarios de demostracion         -> un estudiante y un EMPLEADO (en el
#                                           grupo de Cognito), para el login y
#                                           para el caso "token sin el custom
#                                           scope"
#    6. pipeline/bruno-env.sh            -> entornos de prueba con tokens reales
#    7. pipeline/publish-web.sh          -> configura, compila, empaqueta y
#                                           publica el frontend en el servidor
#
#  >>> POR QUE NO HAY CICLO ENTRE COGNITO Y EL SITIO <<<
#    Cognito necesita la URL del sitio (lista blanca de callbacks) y el sitio
#    necesita los datos de Cognito. Pero el stack web solo crea la MAQUINA y
#    su nombre (<ip-elastica>.sslip.io, o SITE_DOMAIN): los datos de Cognito
#    y de la API llegan despues, dentro del artefacto que publica el paso 7.
#    Asi que el orden es simplemente web -> cognito -> ... -> publish-web.
#
#  ES IDEMPOTENTE: se puede volver a ejecutar tantas veces como haga falta.
#
#  USO
#    SMTP_HOST=smtp.gmail.com SMTP_USER=tu@gmail.com SMTP_PASSWORD='...' \
#    SMTP_FROM=tu@gmail.com ./aws/scripts/bootstrap.sh
#
#    Las SMTP_* (el proveedor de los avisos por correo) solo hacen falta la
#    PRIMERA vez; ver aws/pipeline/build-backend.sh. Tambien pueden ir en
#    aws/.env (cp aws/.env.example aws/.env), igual que DEMO_USER.
#
#  VARIABLES OPCIONALES
#    PROJECT_NAME (biblioteca)  AWS_REGION (us-east-1)  STAGE_NAME (test)
#    DEMO_USER (alumno@duoc.cl) DEMO_EMPLOYEE (empleado@duoc.cl)
#    SKIP_WEB (false, para no compilar el frontend)
#    SITE_DOMAIN (vacio): dominio propio del sitio. Vacio = <ip>.sslip.io.
#      Antes, su registro A tiene que apuntar a la IP elastica (Output
#      SitePublicIp de biblioteca-web); ver aws/README.md.
#    DEBUG (false): la hereda publish-web.sh y publica la vista /diagnostico,
#      que ensena el access token en claro. Solo para explicar la arquitectura.
#
#  Este script NO prueba nada: al final imprime las URLs y el comando de Bruno.
#  Las pruebas se hacen con Bruno y con el navegador.
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_comun.sh"

DEMO_USER="${DEMO_USER:-alumno@duoc.cl}"
DEMO_EMPLOYEE="${DEMO_EMPLOYEE:-empleado@duoc.cl}"
SKIP_WEB="${SKIP_WEB:-false}"
SITE_DOMAIN="${SITE_DOMAIN:-}"

# ------------------------------------------------------------------ paso 0 ----
log "Comprobando el entorno"
requiere aws curl jq python3
[ "$SKIP_WEB" = "true" ] || requiere npm

ACCOUNT_ID="$(cuenta_aws)"

info "cuenta   ${ACCOUNT_ID}"
info "region   ${AWS_REGION}"
info "proyecto ${PROJECT_NAME}"
info "dominio  ${SITE_DOMAIN:-(ninguno: <ip-elastica>.sslip.io)}"

mkdir -p "$LOCAL_DIR"

# ------------------------------------------------------------------ paso 1 ----
# Los stacks independientes van en paralelo. Cada uno escribe su salida en un
# archivo para que, si uno falla, se pueda mostrar SU log y no una mezcla.
log "Desplegando los stacks independientes (artefactos, red)"
TMP_LOGS="$(mktemp -d)"
trap 'rm -rf "$TMP_LOGS"' EXIT

FALLO=0
espera() {
  local pid="$1" nombre="$2" stack="$3"
  if wait "$pid"; then
    info "ok ${stack}"
  else
    FALLO=1
    warn "fallo ${stack}:"
    cat "${TMP_LOGS}/${nombre}" >&2
    por_que_fallo "$stack" >&2
  fi
}

despliega_stack artifacts.yaml "$STACK_ARTIFACTS" \
  "ProjectName=${PROJECT_NAME}" > "${TMP_LOGS}/artifacts" 2>&1 &
PID_ARTIFACTS=$!

despliega_stack network.yaml "$STACK_NETWORK" \
  "ProjectName=${PROJECT_NAME}" > "${TMP_LOGS}/network" 2>&1 &
PID_NETWORK=$!

espera "$PID_ARTIFACTS" artifacts "$STACK_ARTIFACTS"
espera "$PID_NETWORK"   network   "$STACK_NETWORK"
[ "$FALLO" -eq 0 ] || die "no se pudieron crear los stacks base"

# ----------------------------------------------------------------- paso 1b ----
# El servidor del sitio y la base, en paralelo: los dos viven en la red de 1.
# La base es el paso mas lento de todo el bootstrap: RDS tarda unos 10
# minutos en crear la instancia. Si ya existe y no cambio nada, es instantaneo.
log "Servidor del sitio (Caddy) y base de datos PostgreSQL (privada)"
info "si es la primera vez, RDS tarda ~10 minutos en crear la instancia"

despliega_stack web.yaml "$STACK_WEB" \
  "ProjectName=${PROJECT_NAME}" \
  "SiteDomain=${SITE_DOMAIN}" \
  "VpcId=$(output_obligatorio "$STACK_NETWORK" VpcId)" \
  "PublicSubnetId=$(output_obligatorio "$STACK_NETWORK" PublicSubnetId)" \
  "ArtifactsBucketName=$(output_obligatorio "$STACK_ARTIFACTS" ArtifactsBucketName)" > "${TMP_LOGS}/web" 2>&1 &
PID_WEB=$!

despliega_stack database.yaml "$STACK_DATABASE" \
  "ProjectName=${PROJECT_NAME}" \
  "PrivateSubnetIds=$(output_obligatorio "$STACK_NETWORK" PrivateSubnetIds)" \
  "DatabaseSecurityGroupId=$(output_obligatorio "$STACK_NETWORK" DatabaseSecurityGroupId)" > "${TMP_LOGS}/database" 2>&1 &
PID_DATABASE=$!

espera "$PID_WEB"      web      "$STACK_WEB"
espera "$PID_DATABASE" database "$STACK_DATABASE"
[ "$FALLO" -eq 0 ] || die "no se pudieron crear el servidor del sitio o la base"

SITE_URL="$(output_obligatorio "$STACK_WEB" SiteUrl)"
info "sitio    ${SITE_URL}"

# ----------------------------------------------------------------- paso 1c ----
# Cognito, con la URL REAL del sitio como callback y logout.
# El logo y el favicon de las pantallas de login viajan en base64: los SVG de
# aws/marca/ son la fuente, y el recurso ManagedLoginBranding los quiere asi.
log "Cognito (callbacks hacia ${SITE_URL})"
despliega_stack cognito.yaml "$STACK_COGNITO" \
  "ProjectName=${PROJECT_NAME}" \
  "CallbackUrls=${SITE_URL}" \
  "LogoutUrls=${SITE_URL}" \
  "LogoLoginSvg=$(base64 -w0 "${ROOT}/aws/marca/logo-login.svg")" \
  "FaviconLoginSvg=$(base64 -w0 "${ROOT}/aws/marca/favicon-login.svg")" \
  || { por_que_fallo "$STACK_COGNITO"; die "fallo el despliegue de Cognito"; }

# ------------------------------------------------------------------ paso 2 ----
log "Backend: compilar, publicar y desplegar (EC2 + RabbitMQ + NLB)"
"${ROOT}/aws/pipeline/build-backend.sh"

# ------------------------------------------------------------------ paso 3 ----
log "Desplegando la API"
info "la primera vez, el VPC Link tarda varios minutos en quedar disponible"
USER_POOL_ARN="$(output_obligatorio "$STACK_COGNITO" UserPoolArn)"
SCOPE_COMPLETO="$(output_obligatorio "$STACK_COGNITO" ScopeCompleto)"
SCOPE_GESTION="$(output_obligatorio "$STACK_COGNITO" ScopeGestion)"
NLB_ARN="$(output_obligatorio "$STACK_BACKEND" NlbArn)"
NLB_DNS="$(output_obligatorio "$STACK_BACKEND" NlbDnsName)"
SITE_ORIGIN="$(output_obligatorio "$STACK_WEB" SiteOrigin)"

despliega_stack api.yaml "$STACK_API" \
  "ProjectName=${PROJECT_NAME}" \
  "UserPoolArn=${USER_POOL_ARN}" \
  "NlbArn=${NLB_ARN}" \
  "NlbDnsName=${NLB_DNS}" \
  "RequiredScope=${SCOPE_COMPLETO}" \
  "ManagementScope=${SCOPE_GESTION}" \
  "AllowOrigin=${SITE_ORIGIN}" \
  "StageName=${STAGE_NAME}" \
  || { por_que_fallo "$STACK_API"; die "fallo el despliegue de la API"; }

# ------------------------------------------------------------------ paso 4 ----
# Siempre, aunque el stack no haya cambiado: el Deployment es inmutable y el
# stage podria estar sirviendo una version anterior. Ver api-deploy.sh.
"${ROOT}/aws/pipeline/api-deploy.sh"

# ------------------------------------------------------------------ paso 5 ----
# Crear el usuario de demostracion NO es infraestructura, es un dato: por eso
# se hace con la CLI y no con CloudFormation (que ademas no sabe crear
# usuarios de Cognito con contrasena).
log "Usuarios de demostracion"
USER_POOL_ID="$(output_obligatorio "$STACK_COGNITO" UserPoolId)"
GRUPO_EMPLEADOS="$(output_obligatorio "$STACK_COGNITO" GrupoEmpleados)"

# Contrasena aleatoria en cada ejecucion: asi no hay ninguna credencial
# escrita en el repositorio, ni siquiera de mentira.
contrasena() {
  printf 'Duoc%s1a' "$(python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("".join(secrets.choice(a) for _ in range(14)))')"
}

# Crea (si falta) un usuario con el email ya verificado y le fija la clave.
usuario_demo() {
  local usuario="$1" clave="$2"
  if aws cognito-idp admin-get-user --user-pool-id "$USER_POOL_ID" --username "$usuario" \
       --region "$AWS_REGION" >/dev/null 2>&1; then
    info "el usuario ${usuario} ya existe; se le pone una contrasena nueva"
  else
    aws cognito-idp admin-create-user \
      --user-pool-id "$USER_POOL_ID" \
      --username "$usuario" \
      --user-attributes "Name=email,Value=${usuario}" "Name=email_verified,Value=true" \
      --message-action SUPPRESS \
      --region "$AWS_REGION" >/dev/null
    info "usuario ${usuario} creado"
  fi
  # --permanent evita el estado FORCE_CHANGE_PASSWORD, que bloquearia tanto el
  # login de Managed Login como el admin-initiate-auth de bruno-env.sh.
  aws cognito-idp admin-set-user-password \
    --user-pool-id "$USER_POOL_ID" \
    --username "$usuario" \
    --password "$clave" \
    --permanent \
    --region "$AWS_REGION"
}

DEMO_PASSWORD="$(contrasena)"
EMPLOYEE_PASSWORD="$(contrasena)"
usuario_demo "$DEMO_USER" "$DEMO_PASSWORD"
usuario_demo "$DEMO_EMPLOYEE" "$EMPLOYEE_PASSWORD"

# El RBAC: meter al empleado en el grupo. Idempotente (agregarlo dos veces no
# falla). Como el usuario, es un DATO y no infraestructura.
aws cognito-idp admin-add-user-to-group \
  --user-pool-id "$USER_POOL_ID" \
  --username "$DEMO_EMPLOYEE" \
  --group-name "$GRUPO_EMPLEADOS" \
  --region "$AWS_REGION"
info "${DEMO_EMPLOYEE} esta en el grupo ${GRUPO_EMPLEADOS}"

cat > "${LOCAL_DIR}/usuario-demo.env" <<EOF
# Generado por aws/scripts/bootstrap.sh. Esta en .gitignore.
# Lo lee bruno-env.sh para pedir los tokens del usuario, y te sirve a ti para
# entrar por la pantalla de login de Cognito.
DEMO_USER='${DEMO_USER}'
DEMO_PASSWORD='${DEMO_PASSWORD}'
DEMO_EMPLOYEE='${DEMO_EMPLOYEE}'
EMPLOYEE_PASSWORD='${EMPLOYEE_PASSWORD}'
EOF
chmod 600 "${LOCAL_DIR}/usuario-demo.env"
info "credenciales guardadas en aws/.local/usuario-demo.env"

# ------------------------------------------------------------------ paso 6 ----
"${ROOT}/aws/pipeline/bruno-env.sh"

# ------------------------------------------------------------------ paso 7 ----
if [ "$SKIP_WEB" = "true" ]; then
  log "SKIP_WEB=true: el frontend no se compila ni se publica"
  SOLO_CONFIG=true "${ROOT}/aws/pipeline/publish-web.sh"
else
  "${ROOT}/aws/pipeline/publish-web.sh"
fi

# ------------------------------------------------------------------ resumen ---
API_ENDPOINT="$(output_obligatorio "$STACK_API" ApiEndpoint)"
HOSTED_UI="$(output_obligatorio "$STACK_COGNITO" HostedUiDomain)"
LOG_GROUP="$(output_obligatorio "$STACK_BACKEND" LogGroupName)"

log "Todo desplegado"
cat <<EOF
    Sitio        ${SITE_URL}
    API          ${API_ENDPOINT}/api/libros
    Login        ${HOSTED_UI}
    Logs         aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}

    Entra con    ${DEMO_USER}      (estudiante)
                 ${DEMO_EMPLOYEE}   (empleado: ve la gestion)
                 (las contrasenas estan en aws/.local/usuario-demo.env)

    Los avisos por correo solo llegan a un email REAL: registrate con el
    tuyo con "Crear cuenta" y activa los avisos en "Mis solicitudes".

    Probar la API:
        cd bruno && bru run --env aws
        cd bruno && bru run --env aws-backoffice

    Probar la aplicacion:
        abre ${SITE_URL} en el navegador, con DevTools en la pestana Network

    Para desmontarlo todo:
        ./aws/scripts/teardown.sh
EOF
