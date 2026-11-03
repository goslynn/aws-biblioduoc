#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / rota-claves-demo.sh   --   Contrasenas de los usuarios de demo
# ==============================================================================
#
#  QUE HACE
#    1. Genera contrasenas nuevas para el estudiante y el empleado de demo.
#    2. Las aplica en Cognito a los usuarios que ya existan.
#    3. Reescribe aws/.local/usuario-demo.env.
#    4. Las sube como secretos de GitHub Actions: DEMO_USER, DEMO_EMPLOYEE,
#       DEMO_PASSWORD y EMPLOYEE_PASSWORD.
#
#  POR QUE ES UN SCRIPT APARTE
#    bootstrap.sh ya no cambia las contrasenas de quien existe. Si lo hiciera
#    desde GitHub Actions, la clave nueva se perderia con el runner (y los logs
#    de este repositorio son publicos). Cambiarlas es siempre un acto explicito,
#    y se hace desde aqui.
#
#  ANTES DEL PRIMER BOOTSTRAP EN LA NUBE
#    Si todavia no hay stacks, los pasos 1, 3 y 4 bastan: bootstrap.sh crea los
#    usuarios con las contrasenas que encuentre en los secretos.
#
#  USO
#    ./aws/pipeline/rota-claves-demo.sh
#
#    Despues, los tokens de Bruno del usuario siguen validos hasta caducar; los
#    siguientes los pide ./aws/pipeline/bruno-env.sh con la contrasena nueva.
#
#  VARIABLES OPCIONALES
#    DEMO_USER (alumno@duoc.cl)  DEMO_EMPLOYEE (empleado@duoc.cl)
#    GITHUB_REPO (el de este clon, dueno/nombre)
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)/_comun.sh"

requiere aws gh python3
cuenta_aws >/dev/null
REPO="$(repo_github)" || die "no pude averiguar el repositorio. ¿Hiciste gh auth login?"

# Del archivo solo interesan los nombres: las contrasenas se reemplazan.
carga_env "${LOCAL_DIR}/usuario-demo.env"
DEMO_USER="${DEMO_USER:-alumno@duoc.cl}"
DEMO_EMPLOYEE="${DEMO_EMPLOYEE:-empleado@duoc.cl}"
DEMO_PASSWORD="$(contrasena)"
EMPLOYEE_PASSWORD="$(contrasena)"

log "Aplicando las contrasenas en Cognito"
USER_POOL_ID="$(output_de "$STACK_COGNITO" UserPoolId)"
aplica() {
  local usuario="$1" clave="$2"
  if [ -n "$USER_POOL_ID" ] && aws cognito-idp admin-get-user --user-pool-id "$USER_POOL_ID" \
       --username "$usuario" --region "$AWS_REGION" >/dev/null 2>&1; then
    aws cognito-idp admin-set-user-password --user-pool-id "$USER_POOL_ID" \
      --username "$usuario" --password "$clave" --permanent --region "$AWS_REGION"
    info "${usuario}: contrasena cambiada"
  else
    info "${usuario}: aun no existe; bootstrap.sh lo creara con esta contrasena"
  fi
}
aplica "$DEMO_USER" "$DEMO_PASSWORD"
aplica "$DEMO_EMPLOYEE" "$EMPLOYEE_PASSWORD"

# Antes de GitHub: si la subida falla, la clave vigente no se pierde.
guarda_usuarios_demo
info "credenciales guardadas en aws/.local/usuario-demo.env"

log "Actualizando los secretos en ${REPO}"
sube_secreto "$REPO" DEMO_USER "$DEMO_USER"
sube_secreto "$REPO" DEMO_EMPLOYEE "$DEMO_EMPLOYEE"
sube_secreto "$REPO" DEMO_PASSWORD "$DEMO_PASSWORD"
sube_secreto "$REPO" EMPLOYEE_PASSWORD "$EMPLOYEE_PASSWORD"
