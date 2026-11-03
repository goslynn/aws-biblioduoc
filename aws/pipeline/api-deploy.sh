#!/usr/bin/env bash
# ==============================================================================
#  biblioteca / api-deploy.sh   --   Actualizar la REST API y publicarla en su stage
# ==============================================================================
#
#  POR QUE EXISTE ESTE SCRIPT (la trampa clasica de REST API v1)
#    En API Gateway REST, lo que sirve el stage es una FOTO inmutable llamada
#    "deployment". El recurso AWS::ApiGateway::Deployment de api.yaml crea esa
#    foto una vez... y CloudFormation NO la recrea aunque cambien los metodos,
#    porque desde su punto de vista el recurso no ha cambiado.
#
#    Resultado: modificas un scope o anades un metodo, el stack se actualiza
#    "correctamente", y el stage sigue sirviendo la version anterior. Se pierden
#    horas buscando el error en el sitio equivocado.
#
#    La solucion es hacer una foto nueva despues de cada actualizacion del
#    stack de la API. Eso es exactamente lo que hace este script, y es la razon
#    por la que en HTTP API (v2, con AutoDeploy) este paso no existe.
#
#  QUE HACE, EN DOS PASOS
#    1. DESPLIEGA  crea o actualiza el stack de la API con aws/api.yaml. La
#                  primera vez, el VPC Link tarda varios minutos.
#    2. PUBLICA    hace la foto nueva y la deja en el stage.
#
#  DEPENDE DE que ya existan los stacks de Cognito, backend y web.
#
#  USO
#    ./aws/pipeline/api-deploy.sh
#
#  Este script NO prueba la API: solo la publica.
# ==============================================================================
set -euo pipefail

source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../scripts" && pwd)/_comun.sh"

requiere aws
cuenta_aws >/dev/null

log "Desplegando el stack '${STACK_API}'"
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

API_ID="$(output_obligatorio "$STACK_API" ApiId)"
API_ENDPOINT="$(output_obligatorio "$STACK_API" ApiEndpoint)"

log "Publicando la API ${API_ID} en el stage '${STAGE_NAME}'"
DEPLOYMENT_ID="$(aws apigateway create-deployment \
  --rest-api-id "$API_ID" \
  --stage-name "$STAGE_NAME" \
  --description "Publicado por aws/pipeline/api-deploy.sh el $(date -u +%Y-%m-%dT%H:%M:%SZ)" \
  --region "$AWS_REGION" \
  --query id --output text)"

info "deployment ${DEPLOYMENT_ID}"
cat <<EOF

    La API vigente es ahora:
        ${API_ENDPOINT}/api/libros
        ${API_ENDPOINT}/api/solicitudes

    Para probarla:  cd bruno && bru run --env aws
EOF
