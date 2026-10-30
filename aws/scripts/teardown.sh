#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / teardown.sh   --   Borra TODO lo que creo bootstrap.sh
# ==============================================================================
#
#  QUE HACE, Y POR QUE EN ESTE ORDEN
#    Se borra al reves de como se creo, porque un stack no se puede eliminar
#    mientras otro depende de sus recursos:
#
#      0. vpn        (opcional; vive en la red y abre la base) -> vpn-down.sh,
#                       que ademas baja la interfaz local
#      1. api        (depende del NLB y del pool; borra el VPC Link)
#      2. backend    (EC2 + RabbitMQ + NLB; depende del bucket, la red y la base)
#      3. database   <- se borra la instancia SIN snapshot: es un laboratorio
#      4. network    <- puede tardar unos minutos: AWS libera con calma las
#                       interfaces de red del NLB y del VPC Link
#      5. cognito
#      6. web        <- hay que VACIAR el bucket primero
#      7. artifacts  <- hay que vaciarlo, y ademas borrar TODAS las versiones
#
#  >>> POR QUE HAY QUE VACIAR LOS BUCKETS A MANO <<<
#    CloudFormation se niega a borrar un bucket que tenga objetos dentro, y el
#    stack entero queda en DELETE_FAILED. En un bucket VERSIONADO, ademas, no
#    basta con borrar los objetos: hay que eliminar cada version y cada delete
#    marker, porque siguen ocupando el bucket aunque "s3 ls" no los muestre.
#
#  USO
#    ./aws/scripts/teardown.sh          pide confirmacion escrita
#    ./aws/scripts/teardown.sh --yes    sin preguntar (para CI)
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_comun.sh"

CONFIRMADO="${1:-}"

requiere aws python3
ACCOUNT_ID="$(cuenta_aws)"

cat <<EOF

  Se van a ELIMINAR estos stacks de la cuenta ${ACCOUNT_ID} (${AWS_REGION}):

      ${STACK_VPN}         (si existe) + la interfaz WireGuard de este equipo
      ${STACK_API}
      ${STACK_BACKEND}    + la EC2, RabbitMQ y los mensajes que queden en sus colas
      ${STACK_DATABASE}   + la base PostgreSQL entera, sin snapshot
      ${STACK_NETWORK}
      ${STACK_COGNITO}
      ${STACK_WEB}        + todo el contenido del bucket del sitio
      ${STACK_ARTIFACTS}  + todas las versiones de los artefactos

  Se pierden tambien los usuarios de Cognito y todas las solicitudes.
  Es irreversible.

EOF

if [ "$CONFIRMADO" != "--yes" ]; then
  printf '  Escribe BORRAR para continuar: '
  read -r respuesta
  [ "$respuesta" = "BORRAR" ] || die "cancelado"
fi

# Vacia un bucket versionado por completo: objetos, versiones y delete markers.
vacia_bucket() {
  local bucket="$1"
  [ -n "$bucket" ] || return 0
  aws s3api head-bucket --bucket "$bucket" --region "$AWS_REGION" >/dev/null 2>&1 || {
    info "el bucket ${bucket} ya no existe"
    return 0
  }

  log "Vaciando s3://${bucket}"
  # Primero lo facil: los objetos vigentes.
  aws s3 rm "s3://${bucket}" --recursive --region "$AWS_REGION" --only-show-errors || true

  # Y ahora lo que "s3 rm" no toca: versiones antiguas y delete markers. Se
  # borran en lotes de 1000, que es el maximo que admite delete-objects.
  while :; do
    local lote
    lote="$(aws s3api list-object-versions \
      --bucket "$bucket" \
      --region "$AWS_REGION" \
      --max-items 1000 \
      --query '{Objects: (([Versions, DeleteMarkers][]||`[]`)[].{Key:Key,VersionId:VersionId})}' \
      --output json 2>/dev/null || echo '{"Objects":null}')"

    local cuantos
    cuantos="$(printf '%s' "$lote" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(len(d.get("Objects") or []))')"
    [ "$cuantos" -gt 0 ] || break

    printf '%s' "$lote" > /tmp/borrar-lote-$$.json
    aws s3api delete-objects --bucket "$bucket" --region "$AWS_REGION" \
      --delete "file:///tmp/borrar-lote-$$.json" --output text >/dev/null 2>&1 || true
    rm -f /tmp/borrar-lote-$$.json
    info "borradas ${cuantos} versiones"
  done
}

borra_stack() {
  local stack="$1"
  if existe_stack "$stack"; then
    log "Borrando el stack ${stack}"
    aws cloudformation delete-stack --stack-name "$stack" --region "$AWS_REGION"
    aws cloudformation wait stack-delete-complete --stack-name "$stack" --region "$AWS_REGION" \
      && info "${stack} eliminado" \
      || { warn "${stack} no se pudo borrar:"; por_que_fallo "$stack"; }
  else
    info "${stack} no existe"
  fi
}

# Los buckets, antes de que desaparezcan sus stacks.
vacia_bucket "$(output_de "$STACK_WEB" SiteBucketName)"
vacia_bucket "$(output_de "$STACK_ARTIFACTS" ArtifactsBucketName)"

# La VPN primero: su regla vive en el security group de la base y su subred en
# la VPC. vpn-down.sh baja tambien la interfaz local, si esta levantada.
"${ROOT}/aws/scripts/vpn-down.sh"
borra_stack "$STACK_API"
borra_stack "$STACK_BACKEND"
borra_stack "$STACK_DATABASE"
info "la red puede tardar unos minutos: AWS esta liberando las interfaces del NLB y del VPC Link"
borra_stack "$STACK_NETWORK"
borra_stack "$STACK_COGNITO"
borra_stack "$STACK_WEB"
borra_stack "$STACK_ARTIFACTS"

# Los generados en local dejan de tener sentido: apuntan a cosas que ya no existen.
log "Limpiando los archivos generados en local"
rm -f "${ROOT}"/bruno/environments/aws*.yml
rm -f "${ROOT}/front/src/app/aws-config.ts"
rm -rf "$LOCAL_DIR"
info "entornos de Bruno, configuracion del frontend y credenciales de demo, eliminados"

log "Cuenta limpia"
