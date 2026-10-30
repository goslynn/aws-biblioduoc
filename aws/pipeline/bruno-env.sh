#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / bruno-env.sh   --   Genera los entornos de la coleccion Bruno
# ==============================================================================
#
#  QUE HACE
#    Lee los Outputs de los stacks, consigue un token por cada CREDENCIAL que
#    interesa probar y escribe un archivo en bruno/environments/ por cada una.
#    No ejecuta ninguna prueba: solo deja la coleccion lista para que la corras
#    tu, con "bru run" o desde la aplicacion de Bruno.
#
#  >>> POR QUE HAY QUE FABRICAR LOS TOKENS DE TRES MANERAS DISTINTAS <<<
#
#    1) Clientes M2M, grant_type=client_credentials
#         Emiten un access_token CON custom scope, en una sola llamada HTTP,
#         sin usuario y sin navegador. Es el unico camino automatizable para
#         obtener los casos "todo correcto". Son dos:
#           * M2M        -> scope "acceso":  un ESTUDIANTE. En /api/gestion
#                           recibe 403: lo rechaza Spring (RBAC), no el gateway.
#           * backoffice -> scope "gestion": un EMPLEADO (sistema de
#                           inventario). Aprueba, devuelve y carga stock.
#
#    2) admin-initiate-auth (usuario + contrasena, por AWS CLI)
#         Emite un access_token SIN custom scopes —solo lleva
#         "aws.cognito.signin.user.admin"— y ademas un id_token. Parece una
#         limitacion y es justo lo que hace falta: con el primero se reproduce
#         "autenticado pero no autorizado" —401 a traves del gateway— y con el
#         segundo el 401 de un
#         id_token (no tiene claim "scope", que es lo que exige el metodo).
#
#    3) Ninguna, o una cadena inventada
#         El 401 mas simple: no hay credencial que validar.
#
#    El flujo del navegador (code + PKCE) tambien emite el token bueno, pero
#    EXIGE una pantalla de login: por eso no sirve para automatizar nada.
#
#  CADA ENTORNO DECLARA LO QUE ESPERA
#    Ademas del token, cada archivo lleva ESPERADO_LECTURA, ESPERADO_CREACION y
#    ESPERADO_GESTION. Asi la coleccion tiene UNA request por endpoint (sin
#    duplicar peticiones para cada codigo de error) y aun asi
#    "bru run --env <caso>" pasa en verde en los cinco casos: la matriz de
#    seguridad completa, automatizada.
#
#  USO
#    ./aws/pipeline/bruno-env.sh
#
#  Los archivos generados LLEVAN TOKENS REALES y estan en .gitignore.
#  Los tokens caducan en 60 minutos: vuelve a ejecutar el script cuando
#  empieces a ver 401 donde antes habia 200.
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)/_comun.sh"

requiere aws curl python3
cuenta_aws >/dev/null

DESTINO="${ROOT}/bruno/environments"
mkdir -p "$DESTINO"

log "Leyendo los Outputs de los stacks"
API_ENDPOINT="$(output_obligatorio "$STACK_API" ApiEndpoint)"
USER_POOL_ID="$(output_obligatorio "$STACK_COGNITO" UserPoolId)"
SPA_CLIENT_ID="$(output_obligatorio "$STACK_COGNITO" SpaClientId)"
M2M_CLIENT_ID="$(output_obligatorio "$STACK_COGNITO" M2mClientId)"
BACKOFFICE_CLIENT_ID="$(output_obligatorio "$STACK_COGNITO" BackofficeClientId)"
TOKEN_URL="$(output_obligatorio "$STACK_COGNITO" TokenUrl)"
SCOPE_COMPLETO="$(output_obligatorio "$STACK_COGNITO" ScopeCompleto)"
SCOPE_GESTION="$(output_obligatorio "$STACK_COGNITO" ScopeGestion)"
info "api     ${API_ENDPOINT}"
info "scopes  ${SCOPE_COMPLETO} | ${SCOPE_GESTION}"

# client_credentials para un cliente. El secreto NO se publica como Output del
# stack (cualquiera con describe-stacks lo veria): se lee aqui, en el momento.
# $1 client_id  $2 scope  ->  access_token por stdout
token_m2m() {
  local cliente="$1" scope="$2" secreto respuesta token
  secreto="$(aws cognito-idp describe-user-pool-client \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$cliente" \
    --region "$AWS_REGION" \
    --query 'UserPoolClient.ClientSecret' --output text)"
  [ -n "$secreto" ] && [ "$secreto" != "None" ] || die "el cliente ${cliente} no tiene secreto"

  respuesta="$(curl -s -X POST "$TOKEN_URL" \
    --user "${cliente}:${secreto}" \
    -H 'Content-Type: application/x-www-form-urlencoded' \
    --data-urlencode 'grant_type=client_credentials' \
    --data-urlencode "scope=${scope}")"
  token="$(printf '%s' "$respuesta" | python3 -c '
import json, sys
try:
    print(json.load(sys.stdin).get("access_token", ""))
except Exception:
    print("")
')"
  [ -n "$token" ] || die "Cognito no devolvio access_token para ${cliente}. Respuesta: ${respuesta}"
  printf '%s' "$token"
}

# --- 1) Tokens de los clientes M2M: CON custom scope ----------------------------
log "Pidiendo el access_token del cliente M2M (client_credentials, scope de acceso)"
TOKEN_M2M="$(token_m2m "$M2M_CLIENT_ID" "$SCOPE_COMPLETO")"
info "token M2M obtenido (${#TOKEN_M2M} caracteres)"

log "Pidiendo el access_token del backoffice (client_credentials, scope de gestion)"
TOKEN_BACKOFFICE="$(token_m2m "$BACKOFFICE_CLIENT_ID" "$SCOPE_GESTION")"
info "token backoffice obtenido (${#TOKEN_BACKOFFICE} caracteres)"

# --- 2) Tokens del usuario: SIN custom scope ----------------------------------
TOKEN_USUARIO=""
TOKEN_ID=""
USUARIO_ENV="${LOCAL_DIR}/usuario-demo.env"

if [ -f "$USUARIO_ENV" ]; then
  # shellcheck disable=SC1090
  source "$USUARIO_ENV"
  log "Pidiendo los tokens del usuario de demostracion (admin-initiate-auth)"
  RESULTADO="$(aws cognito-idp admin-initiate-auth \
    --user-pool-id "$USER_POOL_ID" \
    --client-id "$SPA_CLIENT_ID" \
    --auth-flow ADMIN_USER_PASSWORD_AUTH \
    --auth-parameters "USERNAME=${DEMO_USER},PASSWORD=${DEMO_PASSWORD}" \
    --region "$AWS_REGION" \
    --query 'AuthenticationResult.[AccessToken,IdToken]' --output text 2>/dev/null)" || true

  if [ -n "$RESULTADO" ]; then
    TOKEN_USUARIO="$(printf '%s' "$RESULTADO" | awk '{print $1}')"
    TOKEN_ID="$(printf '%s' "$RESULTADO" | awk '{print $2}')"
    info "tokens del usuario ${DEMO_USER} obtenidos"
  else
    warn "no se pudieron obtener los tokens del usuario; los entornos aws-usuario-sin-scope y aws-id-token quedaran sin token"
  fi
else
  warn "no existe ${USUARIO_ENV}; ejecuta ./aws/scripts/bootstrap.sh para crear el usuario de demostracion"
fi

# --- Escritura de los entornos ------------------------------------------------
# $1 archivo  $2 nombre  $3 descripcion  $4 base url  $5 token
# $6 status esperado en las lecturas  $7 status esperado al crear
# $8 status esperado en /api/gestion
escribe_entorno() {
  local archivo="$1" nombre="$2" descripcion="$3" base="$4" token="$5" lectura="$6" creacion="$7" gestion="$8"
  cat > "${DESTINO}/${archivo}" <<EOF
# ARCHIVO GENERADO por aws/pipeline/bruno-env.sh -- NO LO EDITES A MANO.
# Contiene tokens reales y esta en .gitignore. Caducan en 60 minutos:
# vuelve a ejecutar el script cuando veas 401 donde esperabas otra cosa.
name: ${nombre}
description: >-
  ${descripcion}
variables:
  - name: BASE_URL
    value: "${base}"
    description: Raiz de la API para este caso.
  - name: ACCESS_TOKEN
    value: "${token}"
    description: Credencial de este caso. Vacia significa que no hay token que presentar.
  - name: ESPERADO_LECTURA
    value: "${lectura}"
    description: Codigo HTTP que esta credencial debe obtener en los GET.
  - name: ESPERADO_CREACION
    value: "${creacion}"
    description: Codigo HTTP que esta credencial debe obtener en el POST de solicitudes.
  - name: ESPERADO_GESTION
    value: "${gestion}"
    description: Codigo HTTP que esta credencial debe obtener en las rutas de empleado (/api/gestion).
EOF
  info "escrito environments/${archivo}"
}

log "Escribiendo los entornos en bruno/environments/"

escribe_entorno aws.yml aws \
  "Cliente M2M con el scope de acceso: un ESTUDIANTE. Funciona de punta a punta en catalogo, solicitudes y avisos. En /api/gestion recibe 403, y ese 403 lo emite Spring (RBAC): el gateway lo dejo pasar porque el token si es de esta API." \
  "$API_ENDPOINT" "$TOKEN_M2M" 200 201 403

escribe_entorno aws-backoffice.yml aws-backoffice \
  "Cliente M2M backoffice con el scope de gestion: un EMPLEADO para Spring. Aprueba, devuelve, consulta la bitacora y carga stock. En Bruno hace de empleado, porque un token de PERSONA con custom scope solo sale del navegador (desviacion declarada, README §7)." \
  "$API_ENDPOINT" "$TOKEN_BACKOFFICE" 200 201 200

# MEDIDO, no supuesto: la guia dice "403 o rechazo de autorizacion segun
# configuracion". El authorizer COGNITO_USER_POOLS de API Gateway responde
# 401 {"message":"Unauthorized"} cuando el token es valido pero sus scopes no
# incluyen el exigido. El 403 por falta de autorizacion SI aparece, pero lo da
# SPRING SECURITY: en /api/gestion con el entorno "aws".
escribe_entorno aws-usuario-sin-scope.yml aws-usuario-sin-scope \
  "Access token de un USUARIO real obtenido con admin-initiate-auth. Esta bien firmado y no ha caducado, pero ese flujo no emite custom scopes. API Gateway lo rechaza con 401 (medido): no distingue este caso del de un token invalido. El 403 de autorizacion lo da Spring, y se ve con el entorno aws en /api/gestion." \
  "$API_ENDPOINT" "$TOKEN_USUARIO" 401 401 401

escribe_entorno aws-id-token.yml aws-id-token \
  "ID TOKEN del mismo usuario. Sirve para saber QUIEN es, no para autorizar: no lleva claim scope, y el metodo exige uno. Es la diferencia entre id_token y access_token, comprobada en vez de recitada." \
  "$API_ENDPOINT" "$TOKEN_ID" 401 401 401

escribe_entorno aws-token-invalido.yml aws-token-invalido \
  "Una cadena inventada como token. Demuestra que el authorizer valida la FIRMA de verdad y no se limita a mirar si la cabecera existe." \
  "$API_ENDPOINT" "no-es-un-jwt-de-verdad" 401 401 401

# Restos de cuando el backend era una Lambda con Function URL de demostracion.
rm -f "${DESTINO}/aws-directo.yml" "${DESTINO}/aws-directo-sin-token.yml"

log "Entornos listos"
cat <<EOF
    La matriz de seguridad, un entorno por credencial:

        cd bruno
        bru run --env aws                     # 200 / 201, y 403 en /gestion (RBAC de Spring)
        bru run --env aws-backoffice          # 200 / 201, y 200 en /gestion (empleado)
        bru run --env aws-usuario-sin-scope   # 401        token valido, scope insuficiente
        bru run --env aws-id-token            # 401        un id_token no autoriza
        bru run --env aws-token-invalido      # 401        firma invalida

    Los cinco deben pasar en VERDE: cada entorno declara el codigo que espera.

    La segunda capa sin el gateway delante (Spring rechazando una peticion que
    llega directo a la EC2) se prueba a mano por la VPN; ver aws/README.md §5.
EOF
