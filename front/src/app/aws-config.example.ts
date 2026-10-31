// PLANTILLA de referencia. El archivo que de verdad usa la aplicacion es
// aws-config.ts, que GENERA aws/pipeline/publish-web.sh a partir de los
// Outputs de CloudFormation y que no se versiona (ver .gitignore): sus valores
// son de una cuenta AWS concreta y cambian en cada despliegue.
//
// Para trabajar sin desplegar nada, copia este archivo a aws-config.ts. Para
// generarlo de verdad:
//     ./aws/pipeline/publish-web.sh            (genera, compila y publica)
//     SOLO_CONFIG=true ./aws/pipeline/publish-web.sh   (solo genera)
//
// Ninguno de estos valores es secreto: el App Client del SPA se crea SIN
// client_secret a proposito, porque todo lo que va en un bundle de JavaScript
// es publico. Lo que protege el flujo es PKCE.
export const awsConfig = {
  /** Output UserPoolId de aws/cognito.yaml */
  userPoolId: 'us-east-1_XXXXXXXXX',
  /** Output SpaClientId: el cliente publico, sin secreto */
  userPoolClientId: 'xxxxxxxxxxxxxxxxxxxxxxxxxx',
  /** Output HostedUiDomain SIN el https:// (Amplify lo quiere asi) */
  hostedUiDomain: 'biblioteca-000000000000.auth.us-east-1.amazoncognito.com',
  /** Output ScopeCompleto: el mismo literal que exigen API Gateway y Spring */
  scope: 'biblioteca-api/acceso',
  /** Output SiteUrl de aws/web.yaml: tiene que coincidir con el CallbackURL, barra final incluida */
  redirectUrl: 'https://203.0.113.10.sslip.io/',
  // La API no esta aqui: se llama en el MISMO origen (/api/...) y Caddy la
  // reenvia a API Gateway (front/Caddyfile). Ver auth/http.ts.
};
