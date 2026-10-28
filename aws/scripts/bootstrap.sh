#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / bootstrap.sh   --   Levanta TODO el proyecto, de cero
# ==============================================================================
#
#  QUE HACE, Y EN QUE ORDEN (el orden no es decorativo)
#
#    0. Comprueba herramientas y credenciales, y CALCULA la URL del sitio.
#    1. artifacts + web + cognito + network -> en paralelo: no dependen de nadie
#    1b. database                        -> PostgreSQL privado (~10 min), dentro
#                                           de la red de 1
#    2. pipeline/build-backend.sh        -> compila, sube el ZIP y crea la Lambda
#                                           (necesita 1 y 1b: bucket, issuer,
#                                           origen, subredes, base)
#    3. api                              -> necesita el pool y la funcion
#    4. pipeline/api-deploy.sh           -> publica la API en el stage
#    5. usuario de demostracion          -> para el login y para el caso
#                                           "token sin el custom scope"
#    6. pipeline/bruno-env.sh            -> entornos de prueba con tokens reales
#    7. pipeline/publish-web.sh          -> configura, compila y sube el frontend
#
#  >>> COMO SE ROMPE EL CICLO APARENTE <<<
#    Cognito necesita la URL del frontend (lista blanca de callbacks) y el
#    frontend necesita los datos de Cognito. Parece un huevo y una gallina.
#    Se rompe en el paso 0: el bucket del sitio SIEMPRE se llama
#    "<proyecto>-web-<AccountId>", asi que la URL se puede calcular con un solo
#    "sts get-caller-identity", antes de que el bucket exista. Cero despliegues
#    dobles.
#
#  ES IDEMPOTENTE: se puede volver a ejecutar tantas veces como haga falta.
#
#  USO
#    ./aws/scripts/bootstrap.sh
#
#  VARIABLES OPCIONALES
#    PROJECT_NAME (biblioteca)  AWS_REGION (us-east-1)  STAGE_NAME (test)
#    DEMO_USER (alumno@duoc.cl) SKIP_WEB (false, para no compilar el frontend)
#    DEBUG (false): la hereda publish-web.sh y publica la vista /diagnostico,
#      que ensena el access token en claro. Solo para explicar la arquitectura.
#
#  Este script NO prueba nada: al final imprime las URLs y el comando de Bruno.
#  Las pruebas se hacen con Bruno y con el navegador.
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_comun.sh"

DEMO_USER="${DEMO_USER:-alumno@duoc.cl}"
SKIP_WEB="${SKIP_WEB:-false}"

# ------------------------------------------------------------------ paso 0 ----
log "Comprobando el entorno"
requiere aws zip curl jq python3
[ "$SKIP_WEB" = "true" ] || requiere npm

ACCOUNT_ID="$(cuenta_aws)"
LAB_ROLE_ARN="${LAB_ROLE_ARN:-arn:aws:iam::${ACCOUNT_ID}:role/LabRole}"

# La URL del sitio, calculada ANTES de que exista el bucket. Ver la cabecera.
SITE_ORIGIN="https://${PROJECT_NAME}-web-${ACCOUNT_ID}.s3.${AWS_REGION}.amazonaws.com"
SITE_URL="${SITE_ORIGIN}/index.html"

info "cuenta   ${ACCOUNT_ID}"
info "region   ${AWS_REGION}"
info "proyecto ${PROJECT_NAME}"
info "sitio    ${SITE_URL}  (calculado, todavia no existe)"

mkdir -p "$LOCAL_DIR"

# ------------------------------------------------------------------ paso 1 ----
# Los tres stacks independientes van en paralelo. Cada uno escribe su salida en
# un archivo para que, si uno falla, se pueda mostrar SU log y no una mezcla.
log "Desplegando los stacks independientes (artefactos, sitio, Cognito, red)"
TMP_LOGS="$(mktemp -d)"
trap 'rm -rf "$TMP_LOGS"' EXIT

despliega_stack artifacts.yaml "$STACK_ARTIFACTS" \
  "ProjectName=${PROJECT_NAME}" > "${TMP_LOGS}/artifacts" 2>&1 &
PID_ARTIFACTS=$!

despliega_stack web.yaml "$STACK_WEB" \
  "ProjectName=${PROJECT_NAME}" > "${TMP_LOGS}/web" 2>&1 &
PID_WEB=$!

despliega_stack cognito.yaml "$STACK_COGNITO" \
  "ProjectName=${PROJECT_NAME}" \
  "CallbackUrls=${SITE_URL}" \
  "LogoutUrls=${SITE_URL}" > "${TMP_LOGS}/cognito" 2>&1 &
PID_COGNITO=$!

despliega_stack network.yaml "$STACK_NETWORK" \
  "ProjectName=${PROJECT_NAME}" > "${TMP_LOGS}/network" 2>&1 &
PID_NETWORK=$!

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
espera "$PID_ARTIFACTS" artifacts "$STACK_ARTIFACTS"
espera "$PID_WEB"       web       "$STACK_WEB"
espera "$PID_COGNITO"   cognito   "$STACK_COGNITO"
espera "$PID_NETWORK"   network   "$STACK_NETWORK"
[ "$FALLO" -eq 0 ] || die "no se pudieron crear los stacks base"

# ----------------------------------------------------------------- paso 1b ----
# La base va despues de la red porque vive en sus subredes. Es el paso mas
# lento de todo el bootstrap: RDS tarda unos 10 minutos en crear la instancia.
# Si ya existe y no cambio nada, es instantaneo.
log "Base de datos PostgreSQL (privada)"
info "si es la primera vez, RDS tarda ~10 minutos en crear la instancia"
despliega_stack database.yaml "$STACK_DATABASE" \
  "ProjectName=${PROJECT_NAME}" \
  "PrivateSubnetIds=$(output_obligatorio "$STACK_NETWORK" PrivateSubnetIds)" \
  "DatabaseSecurityGroupId=$(output_obligatorio "$STACK_NETWORK" DatabaseSecurityGroupId)" \
  || { por_que_fallo "$STACK_DATABASE"; die "fallo el despliegue de la base de datos"; }


# ------------------------------------------------------------------ paso 2 ----
log "Backend: compilar, empaquetar, publicar y desplegar"
LAB_ROLE_ARN="$LAB_ROLE_ARN" "${ROOT}/aws/pipeline/build-backend.sh"

# ------------------------------------------------------------------ paso 3 ----
log "Desplegando la API"
USER_POOL_ARN="$(output_obligatorio "$STACK_COGNITO" UserPoolArn)"
SCOPE_COMPLETO="$(output_obligatorio "$STACK_COGNITO" ScopeCompleto)"
FUNCTION_ARN="$(output_obligatorio "$STACK_BACKEND" FunctionArn)"
FUNCTION_NAME="$(output_obligatorio "$STACK_BACKEND" FunctionName)"
SITE_ORIGIN_REAL="$(output_obligatorio "$STACK_WEB" SiteOrigin)"

despliega_stack api.yaml "$STACK_API" \
  "ProjectName=${PROJECT_NAME}" \
  "UserPoolArn=${USER_POOL_ARN}" \
  "LambdaFunctionArn=${FUNCTION_ARN}" \
  "LambdaFunctionName=${FUNCTION_NAME}" \
  "RequiredScope=${SCOPE_COMPLETO}" \
  "AllowOrigin=${SITE_ORIGIN_REAL}" \
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
log "Usuario de demostracion"
USER_POOL_ID="$(output_obligatorio "$STACK_COGNITO" UserPoolId)"

# Contrasena aleatoria en cada ejecucion: asi no hay ninguna credencial
# escrita en el repositorio, ni siquiera de mentira.
DEMO_PASSWORD="Duoc$(python3 -c 'import secrets,string; a=string.ascii_letters+string.digits; print("".join(secrets.choice(a) for _ in range(14)))')1a"

if aws cognito-idp admin-get-user --user-pool-id "$USER_POOL_ID" --username "$DEMO_USER" \
     --region "$AWS_REGION" >/dev/null 2>&1; then
  info "el usuario ${DEMO_USER} ya existe; se le pone una contrasena nueva"
else
  aws cognito-idp admin-create-user \
    --user-pool-id "$USER_POOL_ID" \
    --username "$DEMO_USER" \
    --user-attributes "Name=email,Value=${DEMO_USER}" "Name=email_verified,Value=true" \
    --message-action SUPPRESS \
    --region "$AWS_REGION" >/dev/null
  info "usuario ${DEMO_USER} creado"
fi

# --permanent evita el estado FORCE_CHANGE_PASSWORD, que bloquearia tanto el
# login de la Hosted UI como el admin-initiate-auth de bruno-env.sh.
aws cognito-idp admin-set-user-password \
  --user-pool-id "$USER_POOL_ID" \
  --username "$DEMO_USER" \
  --password "$DEMO_PASSWORD" \
  --permanent \
  --region "$AWS_REGION"

cat > "${LOCAL_DIR}/usuario-demo.env" <<EOF
# Generado por aws/scripts/bootstrap.sh. Esta en .gitignore.
# Lo lee bruno-env.sh para pedir los tokens del usuario, y te sirve a ti para
# entrar por la Hosted UI.
DEMO_USER='${DEMO_USER}'
DEMO_PASSWORD='${DEMO_PASSWORD}'
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
    Hosted UI    ${HOSTED_UI}
    Logs         aws logs tail ${LOG_GROUP} --follow --region ${AWS_REGION}

    Entra con    ${DEMO_USER}
                 (la contrasena esta en aws/.local/usuario-demo.env)

    Probar la API:
        cd bruno && bru run --env aws

    Probar la aplicacion:
        abre ${SITE_URL} en el navegador, con DevTools en la pestana Network

    Para desmontarlo todo:
        ./aws/scripts/teardown.sh
EOF
