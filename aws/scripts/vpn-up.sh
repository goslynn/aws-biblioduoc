#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / vpn-up.sh   --   Levanta la VPN opcional y conecta este equipo
# ==============================================================================
#
#  QUE HACE, DE PRINCIPIO A FIN (no hay que tocar nada a mano)
#    1. Genera (una sola vez) el par de llaves WireGuard de ESTE equipo en
#       aws/.local/vpn/. La privada nunca sale de ahi.
#    2. Detecta tu IP publica: el servidor solo aceptara UDP desde ella.
#    3. Despliega el stack vpn.yaml (idempotente) con tu llave PUBLICA.
#    4. Lee en vivo la IP publica del servidor y su llave publica (la anuncia
#       en su consola serie; se guarda en cache por instancia).
#    5. Escribe la configuracion WireGuard local y levanta la interfaz
#       (pide sudo: crear una interfaz de red es cosa de root).
#    6. Descarga la CA de RDS y deja en aws/.local/vpn/conexion.txt la URL JDBC
#       lista para DataGrip ("Data Source from URL") y la equivalente de psql.
#
#  ES IDEMPOTENTE. Vuelve a correrlo cuando:
#    * reabras el lab (la IP publica del servidor cambia al detenerse);
#    * cambies de red (tu IP publica cambia);
#    * la conexion deje de responder.
#
#  QUE NO HACE
#    No se conecta a la base ni prueba nada: deja el tunel y los datos de
#    conexion listos. La consulta la haces tu con tu cliente.
#
#  USO
#    ./aws/scripts/vpn-up.sh
#    ./aws/scripts/vpn-down.sh      cuando termines (cuesta por hora)
#
#  VARIABLES OPCIONALES
#    PROJECT_NAME (biblioteca)  AWS_REGION (us-east-1)
#    VPN_ORIGEN   (tu IP/32)    rango desde el que se acepta el tunel. Si tu
#                               IP cambia a menudo, puedes ampliarlo, pero cada
#                               ampliacion es superficie expuesta.
#    VPN_USUARIO_BD (app)       "app" -> biblioteca_app (solo SELECT/INSERT);
#                               "maestro" -> el usuario de Flyway. Con el
#                               maestro se puede romper el esquema: evitalo.
#
#  REQUISITOS
#    aws, curl, jq, sudo, ip, y wireguard-tools (wg, wg-quick). Si no estan
#    instalados pero hay nix-shell, se usan desde nixpkgs sin instalar nada.
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)/_comun.sh"

VPN_USUARIO_BD="${VPN_USUARIO_BD:-app}"
VPN_DIR="${LOCAL_DIR}/vpn"
# wg-quick llama a la interfaz como al archivo, y Linux limita el nombre a 15.
INTERFAZ="$(printf 'wg%s' "$PROJECT_NAME" | tr -cd 'a-z0-9' | cut -c1-15)"
CONF="${VPN_DIR}/${INTERFAZ}.conf"

requiere aws curl jq sudo ip
[ "$(uname -s)" = "Linux" ] || die "este script configura el cliente con wg-quick en Linux"

# ---- wireguard-tools: del sistema, o de nixpkgs si no estan --------------------
if ! command -v wg-quick >/dev/null 2>&1; then
  command -v nix-shell >/dev/null 2>&1 || die \
"faltan wireguard-tools (wg, wg-quick). Instalalas con el gestor de paquetes
    de tu distribucion (apt install wireguard-tools, dnf install wireguard-tools...)"
  info "wireguard-tools no esta en el PATH: se toma de nixpkgs"
  WG_BIN="$(nix-shell -p wireguard-tools --run 'dirname "$(command -v wg)"' 2>/dev/null | tail -1)"
  [ -x "${WG_BIN}/wg-quick" ] || die "nix-shell no pudo proporcionar wireguard-tools"
  PATH="${WG_BIN}:${PATH}"
fi

cuenta_aws >/dev/null

log "Leyendo los stacks de los que depende la VPN"
VPC_ID="$(output_obligatorio "$STACK_NETWORK" VpcId)"
# Despliegues anteriores de network.yaml no tenian este Output: se lee de la VPC.
VPC_CIDR="$(output_de "$STACK_NETWORK" VpcCidr)"
[ -n "$VPC_CIDR" ] || VPC_CIDR="$(aws ec2 describe-vpcs --vpc-ids "$VPC_ID" \
  --region "$AWS_REGION" --query 'Vpcs[0].CidrBlock' --output text)"
DB_SG="$(output_obligatorio "$STACK_NETWORK" DatabaseSecurityGroupId)"
DB_ENDPOINT="$(output_obligatorio "$STACK_DATABASE" DbEndpoint)"
DB_PORT="$(output_obligatorio "$STACK_DATABASE" DbPort)"
DB_NAME="$(output_obligatorio "$STACK_DATABASE" DbName)"
case "$VPN_USUARIO_BD" in
  app)     SECRETO="$(output_obligatorio "$STACK_DATABASE" AppSecretArn)" ;;
  maestro) SECRETO="$(output_obligatorio "$STACK_DATABASE" MasterSecretArn)" ;;
  *)       die "VPN_USUARIO_BD debe ser 'app' o 'maestro'" ;;
esac

# ---- 1. Llaves de este equipo ---------------------------------------------------
log "Llaves WireGuard de este equipo"
mkdir -p "$VPN_DIR"
chmod 700 "$VPN_DIR"
if [ ! -f "${VPN_DIR}/cliente.key" ]; then
  (umask 077; wg genkey > "${VPN_DIR}/cliente.key")
  info "par nuevo generado en ${VPN_DIR}"
else
  info "se reutiliza el par de ${VPN_DIR}"
fi
CLIENTE_PUB="$(wg pubkey < "${VPN_DIR}/cliente.key")"

# ---- 2. Desde donde se acepta el tunel ------------------------------------------
if [ -z "${VPN_ORIGEN:-}" ]; then
  # Lectura de configuracion (tu IP publica), no una prueba de nada.
  MI_IP="$(curl -fsS https://checkip.amazonaws.com | tr -d '[:space:]')" \
    || die "no pude detectar tu IP publica; pasala con VPN_ORIGEN=<ip>/32"
  VPN_ORIGEN="${MI_IP}/32"
fi
info "el servidor aceptara WireGuard solo desde ${VPN_ORIGEN}"

# ---- 3. Stack -----------------------------------------------------------------
# El UserData solo corre en el primer arranque: si la llave del cliente cambio
# (borraste aws/.local), el servidor seguiria esperando la vieja. Se recrea.
if existe_stack "$STACK_VPN"; then
  PUB_DESPLEGADA="$(aws cloudformation describe-stacks --stack-name "$STACK_VPN" \
    --region "$AWS_REGION" \
    --query "Stacks[0].Parameters[?ParameterKey=='ClientPublicKey'].ParameterValue" \
    --output text)"
  if [ "$PUB_DESPLEGADA" != "$CLIENTE_PUB" ]; then
    warn "el servidor tiene registrada otra llave de cliente: se recrea el stack"
    aws cloudformation delete-stack --stack-name "$STACK_VPN" --region "$AWS_REGION"
    aws cloudformation wait stack-delete-complete --stack-name "$STACK_VPN" --region "$AWS_REGION"
  fi
fi

log "Desplegando ${STACK_VPN} (la primera vez, ~3 min)"
despliega_stack vpn.yaml "$STACK_VPN" \
  "ProjectName=${PROJECT_NAME}" \
  "VpcId=${VPC_ID}" \
  "VpcCidr=${VPC_CIDR}" \
  "DatabaseSecurityGroupId=${DB_SG}" \
  "DbPort=${DB_PORT}" \
  "ClientPublicKey=${CLIENTE_PUB}" \
  "ClientCidr=${VPN_ORIGEN}" \
  || { por_que_fallo "$STACK_VPN"; die "no se pudo desplegar ${STACK_VPN}"; }

INSTANCIA="$(output_obligatorio "$STACK_VPN" InstanceId)"
VPN_PORT="$(output_obligatorio "$STACK_VPN" VpnPort)"
TUNEL_CLIENTE="$(aws cloudformation describe-stacks --stack-name "$STACK_VPN" \
  --region "$AWS_REGION" \
  --query "Stacks[0].Parameters[?ParameterKey=='TunnelClientAddress'].ParameterValue" \
  --output text)"

# ---- 4. Datos del servidor, en vivo ---------------------------------------------
log "Esperando al servidor ${INSTANCIA}"
ESTADO="$(aws ec2 describe-instances --instance-ids "$INSTANCIA" --region "$AWS_REGION" \
  --query 'Reservations[0].Instances[0].State.Name' --output text)"
[ "$ESTADO" != "stopped" ] || die \
"el servidor VPN esta detenido (el lab lo para al cerrar la sesion).
    Recrealo: ./aws/scripts/vpn-down.sh && ./aws/scripts/vpn-up.sh"
aws ec2 wait instance-running --instance-ids "$INSTANCIA" --region "$AWS_REGION"
SERVIDOR_IP="$(aws ec2 describe-instances --instance-ids "$INSTANCIA" --region "$AWS_REGION" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' --output text)"
info "IP publica: ${SERVIDOR_IP}"

# La llave publica del servidor no cambia mientras la instancia sea la misma.
CACHE_PUB="${VPN_DIR}/servidor-${INSTANCIA}.pub"
if [ ! -s "$CACHE_PUB" ]; then
  info "leyendo su llave publica de la consola serie (puede tardar 1-3 min)"
  for _ in $(seq 1 36); do
    SERVIDOR_PUB="$(aws ec2 get-console-output --instance-id "$INSTANCIA" --latest \
      --region "$AWS_REGION" --output text --query Output 2>/dev/null \
      | grep -ao 'WIREGUARD_CLAVE_PUBLICA=[A-Za-z0-9+/]\{43\}=' | tail -1 | cut -d= -f2-)" || true
    [ -n "${SERVIDOR_PUB:-}" ] && break
    sleep 10
  done
  [ -n "${SERVIDOR_PUB:-}" ] || die \
"el servidor no anuncio su llave en 6 min. Mira su consola:
    aws ec2 get-console-output --instance-id ${INSTANCIA} --latest --output text"
  rm -f "${VPN_DIR}"/servidor-*.pub
  printf '%s\n' "$SERVIDOR_PUB" > "$CACHE_PUB"
fi
SERVIDOR_PUB="$(cat "$CACHE_PUB")"

# ---- 5. Interfaz local ----------------------------------------------------------
log "Configurando la interfaz ${INTERFAZ}"
# Solo se enruta por el tunel el rango de la VPC: el resto del trafico de tu
# equipo sigue saliendo por donde siempre. Sin DNS propio: el nombre de RDS ya
# resuelve a su IP privada desde fuera.
(umask 077; cat > "$CONF" <<EOF
# GENERADO por aws/scripts/vpn-up.sh. Lleva tu llave privada: no lo compartas.
[Interface]
PrivateKey = $(cat "${VPN_DIR}/cliente.key")
Address = ${TUNEL_CLIENTE}/32

[Peer]
PublicKey = ${SERVIDOR_PUB}
Endpoint = ${SERVIDOR_IP}:${VPN_PORT}
AllowedIPs = ${VPC_CIDR}
PersistentKeepalive = 25
EOF
)

if ip link show "$INTERFAZ" >/dev/null 2>&1; then
  info "la interfaz ya estaba levantada: se reinicia con los datos nuevos"
  sudo env PATH="$PATH" wg-quick down "$CONF" >/dev/null 2>&1 || true
fi
sudo env PATH="$PATH" wg-quick up "$CONF"

# ---- 6. Datos de conexion -------------------------------------------------------
log "Preparando los datos de conexion"
curl -fsS -o "${VPN_DIR}/rds-ca.pem" \
  "https://truststore.pki.rds.amazonaws.com/${AWS_REGION}/${AWS_REGION}-bundle.pem" \
  || die "no pude descargar la CA de RDS"

SECRETO_JSON="$(aws secretsmanager get-secret-value --secret-id "$SECRETO" \
  --region "$AWS_REGION" --query SecretString --output text)" \
  || die "no pude leer el secreto de la base (Secrets Manager)"
BD_USUARIO="$(printf '%s' "$SECRETO_JSON" | jq -r .username)"
BD_CLAVE="$(printf '%s' "$SECRETO_JSON" | jq -r .password)"
# Las claves solo llevan letras y numeros (ExcludePunctuation en
# database.yaml), asi que caben en una URL sin escapar nada.

(umask 077; cat > "${VPN_DIR}/conexion.txt" <<EOF
# GENERADO por aws/scripts/vpn-up.sh. Lleva la contrasena de ${BD_USUARIO}.

# DataGrip: Database > + > Data Source from URL, y pega esta linea:
jdbc:postgresql://${DB_ENDPOINT}:${DB_PORT}/${DB_NAME}?user=${BD_USUARIO}&password=${BD_CLAVE}&sslmode=verify-full&sslrootcert=${VPN_DIR}/rds-ca.pem

# psql:
psql "postgresql://${BD_USUARIO}:${BD_CLAVE}@${DB_ENDPOINT}:${DB_PORT}/${DB_NAME}?sslmode=verify-full&sslrootcert=${VPN_DIR}/rds-ca.pem"
EOF
)

log "Tunel levantado"
cat <<EOF

    Base:        ${DB_ENDPOINT}:${DB_PORT}/${DB_NAME}
    Usuario:     ${BD_USUARIO}
    TLS:         verify-full con ${VPN_DIR}/rds-ca.pem
    Conexion:    ${VPN_DIR}/conexion.txt   (URL para DataGrip y comando psql)

    El tunel solo enruta ${VPC_CIDR}; el resto de tu trafico no cambia.
    Al terminar:  ./aws/scripts/vpn-down.sh   (el servidor cuesta por hora)

EOF
