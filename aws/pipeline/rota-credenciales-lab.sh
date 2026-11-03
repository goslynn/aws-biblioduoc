#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / rota-credenciales-lab.sh   --   Credenciales del lab -> GitHub
# ==============================================================================
#
#  QUE HACE
#    Toma las credenciales de AWS validas en este equipo y las sube como
#    secretos de GitHub Actions, para que despliegue.yml pueda usar la CLI:
#        AWS_ACCESS_KEY_ID  AWS_SECRET_ACCESS_KEY  AWS_SESSION_TOKEN
#    Solo rota esas tres. No despliega ni toca nada en AWS.
#
#  POR QUE VARIABLES DE ENTORNO Y NO UN PERFIL
#    La CLI v2 las toma sola, sin configurar nada en el runner. Y ningun script
#    del proyecto usa --profile ni AWS_PROFILE.
#
#  DE DONDE SALEN
#    De "aws configure export-credentials": la propia CLI resuelve lo que este
#    configurado en el equipo. Este script no sabe que existe awsacademy.
#
#  POR QUE SECRETOS Y NO OIDC
#    OIDC es lo normal en GitHub, pero exige crear un rol IAM, y el lab no lo
#    permite (README §7). A cambio, estas credenciales caducan: hay que volver a
#    correr este script cada vez que se renueven.
#
#  USO
#    awsacademy start && ./aws/pipeline/rota-credenciales-lab.sh
#
#  VARIABLES OPCIONALES
#    GITHUB_REPO (el de este clon, dueno/nombre)
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)/_comun.sh"

requiere aws gh jq
cuenta_aws >/dev/null

REPO="$(repo_github)" || die "no pude averiguar el repositorio. ¿Hiciste gh auth login?"
CREDENCIALES="$(aws configure export-credentials --format process)" \
  || die "la CLI no pudo exportar las credenciales"

log "Actualizando las credenciales del lab en ${REPO}"
sube_secreto "$REPO" AWS_ACCESS_KEY_ID     "$(jq -r .AccessKeyId     <<<"$CREDENCIALES")"
sube_secreto "$REPO" AWS_SECRET_ACCESS_KEY "$(jq -r .SecretAccessKey <<<"$CREDENCIALES")"
sube_secreto "$REPO" AWS_SESSION_TOKEN     "$(jq -r .SessionToken    <<<"$CREDENCIALES")"

CADUCA="$(jq -r '.Expiration // empty' <<<"$CREDENCIALES")"
if [ -n "$CADUCA" ]; then
  MINUTOS=$(( ( $(date -d "$CADUCA" +%s) - $(date +%s) ) / 60 ))
  info "caducan el $(date -d "$CADUCA" '+%F %H:%M') (en ${MINUTOS} min)"
  # En el runner no se renuevan: si caducan a medio despliegue, este se cae.
  [ "$MINUTOS" -ge 60 ] || warn "queda menos de 1 h: un despliegue largo podria caerse a medias"
fi
