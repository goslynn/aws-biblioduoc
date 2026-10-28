#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / vpn-down.sh   --   Desconecta este equipo y borra la VPN
# ==============================================================================
#
#  QUE HACE
#    1. Baja la interfaz WireGuard local, si esta levantada (pide sudo).
#    2. Borra el stack de la VPN: servidor, subred publica, Internet Gateway y
#       la regla que abria la base al tunel. La VPC vuelve a quedar sin salida.
#    3. Borra los archivos locales que llevan secretos (configuracion del tunel
#       y contrasena de la base). Conserva el par de llaves del equipo para la
#       proxima vez; si quieres uno nuevo, borra aws/.local/vpn/ entero.
#
#  No toca ningun otro stack. Tambien lo llama teardown.sh, antes que nada.
#
#  USO
#    ./aws/scripts/vpn-down.sh
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_comun.sh"

VPN_DIR="${LOCAL_DIR}/vpn"
INTERFAZ="$(printf 'wg%s' "$PROJECT_NAME" | tr -cd 'a-z0-9' | cut -c1-15)"

requiere aws ip

# ---- 1. Interfaz local ----------------------------------------------------------
if ip link show "$INTERFAZ" >/dev/null 2>&1; then
  log "Bajando la interfaz ${INTERFAZ}"
  # Basta con borrar la interfaz: wg-quick no anadio nada mas (ni DNS ni ruta
  # por defecto), y sus rutas desaparecen con ella.
  sudo ip link delete dev "$INTERFAZ"
else
  info "la interfaz ${INTERFAZ} no esta levantada"
fi

# ---- 2. Stack -----------------------------------------------------------------
cuenta_aws >/dev/null
if existe_stack "$STACK_VPN"; then
  log "Borrando el stack ${STACK_VPN}"
  aws cloudformation delete-stack --stack-name "$STACK_VPN" --region "$AWS_REGION"
  aws cloudformation wait stack-delete-complete --stack-name "$STACK_VPN" --region "$AWS_REGION" \
    && info "${STACK_VPN} eliminado" \
    || { por_que_fallo "$STACK_VPN"; die "${STACK_VPN} no se pudo borrar"; }
else
  info "${STACK_VPN} no existe"
fi

# ---- 3. Secretos locales ----------------------------------------------------------
rm -f "${VPN_DIR}/${INTERFAZ}.conf" "${VPN_DIR}/conexion.txt" "${VPN_DIR}"/servidor-*.pub
info "configuracion del tunel y datos de conexion eliminados (las llaves se conservan)"

log "VPN cerrada: la base vuelve a ser alcanzable solo desde la Lambda"
