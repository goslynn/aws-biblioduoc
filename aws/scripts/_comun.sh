#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / _comun.sh   --   Funciones compartidas por los demas scripts
# ==============================================================================
#
#  NO SE EJECUTA SOLO: se carga con "source" desde bootstrap.sh, teardown.sh y
#  los scripts de aws/pipeline/. Aqui vive lo que si no estaria copiado cinco
#  veces: mensajes, comprobacion de requisitos, lectura de Outputs y nombres de
#  los stacks.
#
#  Configuracion por variables de entorno, todas con valor por defecto:
#    PROJECT_NAME   (biblioteca)   prefijo de stacks, buckets y funcion
#    AWS_REGION     (us-east-1)    unica region utilizable en el Learner Lab
#    STAGE_NAME     (test)         stage de API Gateway
#
#  Nunca se usa --profile ni AWS_PROFILE, y ningun script llama a "awsacademy":
#  se asume que "aws" ya funciona. Si no funciona, se avisa y se para.
# ==============================================================================

PROJECT_NAME="${PROJECT_NAME:-biblioteca}"
AWS_REGION="${AWS_REGION:-us-east-1}"
STAGE_NAME="${STAGE_NAME:-test}"

STACK_ARTIFACTS="${PROJECT_NAME}-artifacts"
STACK_WEB="${PROJECT_NAME}-web"
STACK_COGNITO="${PROJECT_NAME}-cognito"
STACK_NETWORK="${PROJECT_NAME}-network"
STACK_DATABASE="${PROJECT_NAME}-database"
STACK_BACKEND="${PROJECT_NAME}-backend"
STACK_API="${PROJECT_NAME}-api"
# Opcional: no lo crea bootstrap.sh, sino vpn-up.sh.
STACK_VPN="${PROJECT_NAME}-vpn"

# Raiz del repositorio, calculada desde la ubicacion de ESTE archivo, para que
# los scripts funcionen igual invocados desde cualquier directorio.
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# Donde se guardan los datos generados que no se versionan (contrasena del
# usuario de demostracion, por ejemplo). Esta en .gitignore.
LOCAL_DIR="${ROOT}/aws/.local"

log()  { printf '\n\033[1;36m==> %s\033[0m\n' "$*"; }
info() { printf '    %s\n' "$*"; }
warn() { printf '\033[1;33m    aviso: %s\033[0m\n' "$*" >&2; }
die()  { printf '\n\033[1;31mERROR: %s\033[0m\n' "$*" >&2; exit 1; }

# Comprueba que existan las herramientas indicadas. Se llama al principio de
# cada script: es mejor fallar en un segundo que a los cuatro minutos.
requiere() {
  local falta=()
  for herramienta in "$@"; do
    command -v "$herramienta" >/dev/null 2>&1 || falta+=("$herramienta")
  done
  [ ${#falta[@]} -eq 0 ] || die "faltan herramientas en el PATH: ${falta[*]}"
}

# Credenciales del laboratorio. Caducan cada ~4 horas y son, de lejos, la
# causa numero uno de errores raros.
cuenta_aws() {
  local id
  id="$(aws sts get-caller-identity --query Account --output text 2>/dev/null)" || die \
"no hay credenciales de AWS validas.

    Arranca el laboratorio y vuelve a intentarlo:
        awsacademy start"
  printf '%s' "$id"
}

# Lee un Output de un stack. Si el stack o el Output no existen, devuelve
# cadena vacia (no aborta): quien llama decide si eso es un error.
output_de() {
  aws cloudformation describe-stacks \
    --stack-name "$1" \
    --region "$AWS_REGION" \
    --query "Stacks[0].Outputs[?OutputKey=='$2'].OutputValue" \
    --output text 2>/dev/null | sed 's/^None$//'
}

# Igual, pero aborta con un mensaje util si no hay valor.
output_obligatorio() {
  local valor
  valor="$(output_de "$1" "$2")"
  [ -n "$valor" ] || die "el stack '$1' no expone el Output '$2'. ¿Esta desplegado? Prueba con: ./aws/scripts/bootstrap.sh"
  printf '%s' "$valor"
}

existe_stack() {
  aws cloudformation describe-stacks --stack-name "$1" --region "$AWS_REGION" >/dev/null 2>&1
}

# Despliegue idempotente de un stack. --no-fail-on-empty-changeset hace que
# volver a ejecutarlo sin cambios no sea un error.
despliega_stack() {
  local plantilla="$1" nombre="$2"; shift 2
  local orden=(aws cloudformation deploy
    --template-file "${ROOT}/aws/${plantilla}"
    --stack-name "$nombre"
    --region "$AWS_REGION"
    --no-fail-on-empty-changeset)
  # --parameter-overrides solo se anade si hay parametros: pasarlo vacio es un
  # error de sintaxis del CLI.
  [ $# -eq 0 ] || orden+=(--parameter-overrides "$@")
  "${orden[@]}"
}

# Por que fallo un stack. Se imprime cuando un despliegue se cae.
por_que_fallo() {
  aws cloudformation describe-stack-events \
    --stack-name "$1" \
    --region "$AWS_REGION" \
    --query "StackEvents[?contains(ResourceStatus,'FAILED')].[LogicalResourceId,ResourceStatusReason]" \
    --output table 2>/dev/null | head -30
}
