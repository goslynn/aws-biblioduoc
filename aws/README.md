# Infraestructura · Biblioteca Duoc

Toda la infraestructura del proyecto, en **CloudFormation**: una plantilla YAML
por servicio, un stack por plantilla, desplegadas con **AWS CLI v2** sobre una
cuenta de **AWS Academy Learner Lab**.

Aqui no hay clics de consola ni comandos imperativos que creen recursos: si
algo existe en AWS, esta declarado en un `.yaml` de este directorio. (Los
usuarios de demostracion y su grupo son DATOS, no infraestructura: los crea
`bootstrap.sh` con la CLI. El reinicio de la aplicacion tras subir un jar, y
el del servidor web tras publicar el sitio, son pasos de los pipelines, por SSM
Run Command.)

---

## 1. Que se construye

```
                       ┌──────────────────────────────┐
  Navegador ── HTTPS ─▶│  EC2 + Caddy  <ip>.sslip.io  │   web.yaml
                       │  IP elastica · Let's Encrypt │
                       │  React compilado, CSP, HSTS  │
                       │  /api/* ─▶ API Gateway  (③)  │
                       └──────────────┬───────────────┘
                                      │  ① login  (code + PKCE)
                                      ▼
                       ┌──────────────────────────────┐
                       │  Cognito                     │   cognito.yaml
                       │   User Pool + grupo empleados│
                       │   Resource Server            │
                       │     biblioteca-api/acceso    │
                       │     biblioteca-api/gestion   │
                       │   App Client SPA  (publico)  │
                       │   App Client M2M  (secreto)  │
                       │   App Client backoffice      │
                       │   Managed Login + marca      │
                       └──────────────┬───────────────┘
                                      │ ② access_token
  ③ Caddy: /api/libros         ▼
     -> /test/api/libros     ┌──────────────────────────────┐
     Authorization: Bearer   │  API Gateway · REST · test   │  api.yaml
  ──────────────────────────▶│                              │
                             │   authorizer COGNITO_USER_.. │
                             │   AuthorizationScopes        │
                             │   OPTIONS MOCK + Gateway-    │
                             │     Responses (CORS)         │
                             │   VPC Link                   │
                             └──────────────┬───────────────┘
                                            │ ④ HTTP_PROXY por el VPC Link
          ┌── VPC (network.yaml) ───────────┼─────────────────────────────────┐
          │  subredes PRIVADAS (sin ruta)   ▼                                 │
          │                ┌──────────────────────────────┐                   │
          │                │  NLB interno :80             │  backend.yaml     │
          │                └──────────────┬───────────────┘                   │
          │  subred PUBLICA (IGW)         │ ⑤ :8080, health check /health     │
          │                ┌──────────────▼───────────────┐                   │
          │                │  EC2 Amazon Linux 2023       │  backend.yaml     │
          │                │   [app] Spring Boot: valida  │                   │
          │                │     otra vez el token, RBAC, │── ⑦ SMTP 587 ──▶ proveedor
          │                │     consumidores, @Scheduled │── 443 ─▶ S3, Secrets Manager,
          │                │   [rabbitmq] 4.1 + panel     │         SSM, Cognito, imagenes
          │                │   SG: entra solo la VPC      │                   │
          │                └──────────────┬───────────────┘                   │
          │  subredes PRIVADAS            │ ⑥ TLS verify-full, 5432           │
          │                ┌──────────────▼───────────────┐                   │
          │                │  RDS PostgreSQL 16           │  database.yaml    │
          │                │   sin IP publica, cifrada    │                   │
          │                │   SG: solo desde la EC2      │                   │
          │                └──────────────────────────────┘                   │
          └───────────────────────────────────────────────────────────────────┘
                                            ⑧ el jar, al arrancar
                             ┌──────────────────────────────┐
                             │  S3 biblioteca-artifacts-…   │  artifacts.yaml
                             │  privado · versionado · 7 d  │
                             └──────────────────────────────┘
```

### Quien habla con quien, y con que credencial

| Origen | Destino | Como | Credencial |
|---|---|---|---|
| Navegador | Managed Login (`/login`, `/signup`) | redireccion HTTP | ninguna (cliente publico + PKCE) |
| Navegador | `/oauth2/token` | POST form-urlencoded | `code` + `code_verifier`, **sin secreto** |
| Navegador | Caddy (`/api/*`, mismo origen) | `Authorization: Bearer` | `access_token` |
| Caddy | API Gateway | `reverse_proxy` HTTPS; reescribe `/api/x` a `/<stage>/api/x` | la misma cabecera, intacta: Caddy no anade ni valida nada |
| Caddy | Let's Encrypt | ACME HTTP-01 por el 80; renueva solo | ninguna |
| EC2 del sitio | S3 artefactos / SSM | baja el sitio (`web/sitio.tar.gz`) y lee `/biblioteca/web/sitio.env` en cada arranque | perfil de instancia |
| `publish-web.sh` | EC2 del sitio | SSM Run Command: anota la version y reinicia Caddy | credenciales del lab |
| API Gateway | Cognito | descarga el JWKS | ninguna (endpoint publico) |
| API Gateway | NLB → EC2 | `HTTP_PROXY` por el **VPC Link** (PrivateLink) | ninguna: la red; la EC2 no acepta nada desde Internet |
| EC2 | S3 artefactos | baja el jar en cada arranque | **perfil de instancia** `LabInstanceProfile`, sin claves estaticas |
| EC2 | SSM Parameter Store | lee `/biblioteca/backend/app.env` en cada arranque | perfil de instancia |
| EC2 | Secrets Manager | lee las contrasenas (BD, RabbitMQ, SMTP) en cada arranque | perfil de instancia |
| Spring Boot | Cognito | `ListUsers` por `sub`, al activar avisos | perfil de instancia (SDK) |
| Spring Boot | RabbitMQ | AMQP por la red de Docker (no sale de la maquina) | secreto `RabbitSecret` |
| Spring Boot | proveedor SMTP | 587 + STARTTLS | secreto `SmtpSecret` |
| `build-backend.sh` | Cognito | descarga el JWKS al desplegar | ninguna (endpoint publico) |
| `build-backend.sh` | EC2 | SSM Run Command: anota la version y reinicia | credenciales del lab |
| Spring Boot | RDS | JDBC, TLS `verify-full`, solo dentro de la VPC | rol `biblioteca_app` (secreto `AppSecret`) |
| Flyway (al arrancar) | RDS | migraciones: esquema, rol y seed | usuario maestro (secreto `MasterSecret`) |
| `bruno-env.sh` | `/oauth2/token` | `client_credentials` | App Clients M2M y backoffice + secreto |
| Scripts | todo lo demas | AWS CLI | credenciales temporales del lab |

En ningun punto del sistema hay una Access Key estatica.

---

## 2. Mapa de archivos

### Plantillas

| Archivo | Stack | Crea | Depende de |
|---|---|---|---|
| `artifacts.yaml` | `biblioteca-artifacts` | Bucket privado, versionado, con ciclo de vida de 7 dias | — |
| `web.yaml` | `biblioteca-web` | EC2 con Caddy en Docker (HTTPS de Let's Encrypt, proxy de `/api`), IP elastica, su security group (80/443 desde Internet), parametro SSM de configuracion, LogGroup | `network`, `artifacts` |
| `cognito.yaml` | `biblioteca-cognito` | User Pool (plan Essentials), grupo `empleados`, Resource Server + 2 scopes, App Clients SPA, M2M y backoffice, dominio con Managed Login y su marca (`aws/marca/*.svg`) | `web` (la URL del sitio) |
| `network.yaml` | `biblioteca-network` | VPC; dos subredes privadas sin ruta (RDS, NLB); una subred publica con IGW (EC2 de la app, servidor web, VPN); security groups App ↔ RDS | — |
| `database.yaml` | `biblioteca-database` | RDS PostgreSQL 16 privado y cifrado, parameter group con TLS obligatorio, dos secretos | `network` |
| `backend.yaml` | `biblioteca-backend` | EC2 con Docker (Spring Boot + RabbitMQ), NLB interno, secretos de RabbitMQ y SMTP, parametro SSM de configuracion, LogGroup | `artifacts`, `cognito`, `web`, `network`, `database`, y el jar ya subido |
| `api.yaml` | `biblioteca-api` | REST API: recursos, metodos, authorizer, scopes, CORS, VPC Link, stage | `cognito`, `backend`, `web` |
| `vpn.yaml` (opcional) | `biblioteca-vpn` | Servidor WireGuard en la subred publica, y las reglas que abren la base y el panel de RabbitMQ al tunel. **No lo crea el bootstrap** | `network`, `database` |

### Scripts

| Archivo | Que hace |
|---|---|
| `scripts/bootstrap.sh` | Levanta todo en orden de dependencia. Idempotente. Es el unico que necesitas a diario |
| `scripts/teardown.sh` | Borra todo en orden inverso, vaciando el bucket de artefactos primero. Pide confirmacion |
| `scripts/vpn-up.sh` | Opcional. Llaves del equipo → stack `vpn` → interfaz WireGuard local (sudo) → datos de conexion para DataGrip/psql y el panel de RabbitMQ. Idempotente |
| `scripts/vpn-down.sh` | Baja la interfaz local, borra el stack `vpn` y los archivos locales con secretos |
| `scripts/_comun.sh` | No se ejecuta solo: funciones compartidas (mensajes, requisitos, lectura de Outputs) |
| `pipeline/build-backend.sh` | `mvnw package` → JWKS de Cognito → jar a S3 → stack del backend → (si ya existia) reinicio por SSM → espera al health check del NLB |
| `pipeline/api-deploy.sh` | `create-deployment` al stage. **Imprescindible tras cada cambio de la API** |
| `pipeline/publish-web.sh` | Genera la config del frontend (y la ruta de diagnostico, segun `DEBUG`) → `tsc` + `vite build` → `.tar.gz` con `dist/`, `front/Caddyfile` y la API/Cognito → bucket de artefactos → reinicio de Caddy por SSM |
| `pipeline/bruno-env.sh` | Obtiene los tokens de cada caso y escribe los entornos de Bruno |

Ningun script prueba la API. Eso es trabajo de Bruno y del navegador.

---

## 3. Contrato de cada stack

Los stacks **no se acoplan** con `Export`/`Fn::ImportValue`: se encadenan con
parametros explicitos que pasa `bootstrap.sh`. Es mas verboso, pero se ve el
flujo de datos y se puede recrear cualquier stack sin tocar los demas.

```
artifacts.yaml
  entra : ProjectName, RetencionVersionesDias
  sale  : ArtifactsBucketName ───────────────▶ build-backend.sh, backend.yaml

web.yaml
  entra : ProjectName, SiteDomain (vacio = <ip>.sslip.io), VpcId y
          PublicSubnetId (network), ArtifactsBucketName (artifacts),
          InstanceType, SiteObjectKey
  sale  : SiteOrigin ────────────────────────▶ api.yaml (CORS), backend.yaml
          SiteUrl ───────────────────────────▶ cognito.yaml (callbacks), publish-web.sh, navegador
          InstanceId, SiteObjectKey ─────────▶ publish-web.sh
          SitePublicIp ──────────────────────▶ el registro A del dominio propio
          SiteHost, LogGroupName ────────────▶ informativos

cognito.yaml
  entra : ProjectName, CallbackUrls, LogoutUrls, ResourceServerId, ScopeName,
          ManagementScopeName, EmployeesGroupName, DuracionTokenMinutos,
          LogoLoginSvg, FaviconLoginSvg (aws/marca/*.svg en base64, de bootstrap.sh)
  sale  : UserPoolArn ───────────────────────▶ api.yaml (authorizer)
          UserPoolId ────────────────────────▶ backend.yaml (email de avisos), bruno-env.sh, usuarios demo
          SpaClientId ───────────────────────▶ frontend, bruno-env.sh
          M2mClientId, BackofficeClientId ───▶ bruno-env.sh
          IssuerUrl ─────────────────────────▶ backend.yaml, build-backend.sh (JWKS)
          ScopeCompleto ─────────────────────▶ api.yaml, backend.yaml, frontend
          ScopeGestion ──────────────────────▶ api.yaml, backend.yaml, bruno-env.sh
          GrupoEmpleados ────────────────────▶ bootstrap.sh (empleado de demostracion)
          HostedUiDomain, TokenUrl, LoginUrl ▶ frontend, bruno-env.sh

network.yaml
  entra : ProjectName, VpcCidr, SubnetACidr, SubnetBCidr, PublicSubnetCidr,
          DatabasePort, AppPort
  sale  : PrivateSubnetIds ──────────────────▶ database.yaml, backend.yaml (NLB)
          PublicSubnetId ────────────────────▶ backend.yaml (EC2), vpn.yaml
          AppSecurityGroupId ────────────────▶ backend.yaml, vpn.yaml
          DatabaseSecurityGroupId ───────────▶ database.yaml, vpn.yaml
          VpcId, VpcCidr ────────────────────▶ vpn.yaml

database.yaml
  entra : ProjectName, PrivateSubnetIds, DatabaseSecurityGroupId, DbName,
          DbPort, DbEngineVersion, DbInstanceClass
  sale  : DbEndpoint, DbPort, DbName ────────▶ backend.yaml (URL de JDBC)
          MasterSecretArn ───────────────────▶ backend.yaml (Flyway)
          AppSecretArn ──────────────────────▶ backend.yaml (datasource), vpn-up.sh

backend.yaml
  entra : ProjectName, InstanceProfileName, InstanceType, ImageId, AppPort,
          ArtifactsBucketName, ArtifactsObjectKey, IssuerUrl, UserPoolId,
          JwksJson, RequiredScope, ManagementScope, WebOrigin,
          PublicSubnetId, PrivateSubnetIds, AppSecurityGroupId,
          DbEndpoint, DbPort, DbName, MasterSecretArn, AppSecretArn,
          SmtpHost, SmtpUser, SmtpPassword (NoEcho), SmtpFrom, ReminderLead
  sale  : NlbArn, NlbDnsName ────────────────▶ api.yaml (VPC Link, integraciones)
          InstanceId, TargetGroupArn ────────▶ build-backend.sh (reinicio y espera)
          AppPrivateIp, RabbitSecretArn ─────▶ vpn-up.sh (panel de RabbitMQ)
          LogGroupName ──────────────────────▶ aws logs tail

api.yaml
  entra : ProjectName, UserPoolArn, NlbArn, NlbDnsName,
          RequiredScope, ManagementScope, AllowOrigin, StageName
  sale  : ApiEndpoint ───────────────────────▶ frontend, Bruno
          ApiId ─────────────────────────────▶ api-deploy.sh

vpn.yaml (opcional, lo despliega vpn-up.sh)
  entra : ProjectName, VpcId, VpcCidr, PublicSubnetId, DatabaseSecurityGroupId,
          AppSecurityGroupId, RabbitPanelPort, DbPort, VpnPort, ClientPublicKey,
          ClientCidr, TunnelServerAddress, TunnelClientAddress, InstanceType, ImageId
  sale  : InstanceId ────────────────────────▶ vpn-up.sh (IP en vivo, llave del servidor)
          VpnPort ───────────────────────────▶ vpn-up.sh (Endpoint del cliente)
```

### La configuracion de la EC2 no vive en la instancia

El UserData solo corre en el PRIMER arranque, y cambiarlo detiene la instancia.
Por eso no lleva configuracion: `backend.yaml` la escribe en el parametro de SSM
`/<proyecto>/backend/app.env` (una linea `VARIABLE=valor` por variable; las
`SECRETO_*` son ARN, no secretos). Cada vez que arranca un servicio
(`biblioteca-rabbitmq`, `biblioteca-app`), su script lee ese parametro, resuelve
los secretos en Secrets Manager y lanza el contenedor. Cambiar un parametro del
stack y reiniciar el servicio basta; `build-backend.sh` lo hace solo.

### El ciclo aparente, y por que no existe

Cognito necesita la URL del frontend (lista blanca de callbacks) y el frontend
necesita los datos de Cognito. Parece un huevo y una gallina.

No lo es, porque `web.yaml` solo crea la **maquina y su nombre**
(`<ip-elastica>.sslip.io`, o `SiteDomain`), y para eso no necesita nada de
Cognito ni de la API. Los valores de Cognito y de la API llegan despues,
**dentro del artefacto** que publica `publish-web.sh` (`aws-config.ts` en el
bundle; host y stage de la API y dominio de Cognito en `sitio.env`). El orden
es simplemente `web` → `cognito` → ... → `publish-web.sh`. Cero despliegues
dobles.

El servidor web lee `/biblioteca/web/sitio.env` (SSM: host, region y de donde
bajar el sitio) en cada arranque del servicio `biblioteca-web`, igual que el
backend lee el suyo.

---

## 4. Desplegar

```sh
awsacademy start                # credenciales frescas (caducan cada ~4 h)

SMTP_HOST=smtp.gmail.com SMTP_USER=tu@gmail.com \
SMTP_PASSWORD='<contrasena de aplicacion>' SMTP_FROM=tu@gmail.com \
./aws/scripts/bootstrap.sh
```

Las `SMTP_*` son el proveedor de los avisos por correo (el lab bloquea SES) y
solo hacen falta la **primera vez**: despues, si no se pasan, el stack conserva
las anteriores. En vez de la terminal pueden ir en `aws/.env`
(`cp aws/.env.example aws/.env`, en `.gitignore`), igual que cualquier otra
variable de esta tabla; lo que se pase en la terminal gana sobre el archivo. Gmail exige verificacion en dos pasos y una *contrasena de
aplicacion*; Brevo usa `smtp-relay.brevo.com` y un remitente verificado. La
contrasena va a Secrets Manager (`NoEcho`): no queda en ningun Output ni log.

Orden que sigue, y por que:

1. `artifacts` y `network` **en paralelo**: no dependen de nadie.
2. `web` y `database` **en paralelo**: viven en las subredes de `network`.
   `web` crea la IP elastica, la EC2 de Caddy y espera a que escuche (el
   certificado lo pide Caddy en segundo plano). `database` tarda ~10 minutos
   la primera vez (crear la instancia RDS).
3. `cognito`: con la URL real del sitio (Output `SiteUrl`) como callback.
4. `build-backend.sh`: necesita el bucket (1), el issuer, los scopes y el pool
   (Cognito), el origen del sitio (web), las subredes y el security group
   (network) y el endpoint y los secretos (database). Descarga el JWKS de
   Cognito. La primera vez la EC2 instala Docker, baja las imagenes y arranca:
   CloudFormation **espera a que `/health` responda** (CreationPolicy).
5. `api`: necesita el ARN del pool y el NLB. La primera vez el **VPC Link**
   tarda varios minutos en quedar disponible.
6. `api-deploy.sh`: publica al stage.
7. Usuarios de demostracion (estudiante y empleado, este en el grupo), con
   contrasenas aleatorias.
8. `bruno-env.sh`: entornos de prueba con tokens reales.
9. `publish-web.sh`: configura, compila, empaqueta y publica el frontend en el
   servidor.

El primer arranque de la aplicacion aplica las migraciones de Flyway (esquema,
rol `biblioteca_app`, seed del catalogo, y V3) y declara la topologia de
RabbitMQ. Se ve en `aws logs tail /biblioteca/backend`.

### Variables que acepta

| Variable | Por defecto | Para que |
|---|---|---|
| `PROJECT_NAME` | `biblioteca` | Prefijo de stacks, buckets y recursos |
| `AWS_REGION` | `us-east-1` | Unica region utilizable en el lab |
| `STAGE_NAME` | `test` | Stage de API Gateway |
| `SMTP_HOST`, `SMTP_USER`, `SMTP_PASSWORD`, `SMTP_FROM` | — (obligatorias la 1.ª vez) | Proveedor de correo |
| `REMINDER_LEAD` | `PT24H` | Antelacion del recordatorio. Para una demo, `PT23H58M`: un prestamo de 1 dia avisa a los 2 min |
| `INSTANCE_TYPE` | `t3.medium` | Tamano de la EC2 de la app |
| `SITE_DOMAIN` | vacio (`<ip>.sslip.io`) | Dominio propio del sitio; ver *Dominio propio* |
| `DEMO_USER` | `alumno@duoc.cl` | Estudiante de demostracion |
| `DEMO_EMPLOYEE` | `empleado@duoc.cl` | Empleado de demostracion (grupo `empleados`) |
| `SKIP_WEB` | `false` | No compilar ni subir el frontend |
| `DEBUG` | `false` | La hereda `publish-web.sh`: publica ademas la vista `/diagnostico` |

`DEBUG=true` publica una pantalla que **ensena el access token en claro**. Es
material para explicar la arquitectura, no parte de la entrega: con el valor
por defecto esa vista ni siquiera se compila (README raiz §5).

### Solo una parte

```sh
./aws/pipeline/build-backend.sh    # cambiaste codigo Java (o la config del backend)
./aws/pipeline/publish-web.sh      # cambiaste el frontend
DEBUG=true ./aws/pipeline/publish-web.sh   # + la vista /diagnostico (no entregable)
./aws/pipeline/api-deploy.sh       # cambiaste rutas, metodos o scopes
./aws/pipeline/bruno-env.sh        # los tokens caducaron (60 min)
REMINDER_LEAD=PT23H58M ./aws/pipeline/build-backend.sh   # recordatorios rapidos para la demo
```

### Dominio propio

Mientras no haya dominio, el sitio vive en `https://<ip-elastica>.sslip.io/`.
sslip.io es un DNS publico que resuelve cualquier nombre con una IP dentro a
esa IP; como la IP es elastica, el nombre no cambia aunque el lab detenga la
instancia. Para pasar a un dominio propio:

1. En tu proveedor DNS, un registro **A** de `biblioteca.midominio.cl` a la IP
   elastica (Output `SitePublicIp` de `biblioteca-web`). El lab no deja
   registrar dominios en Route 53 (`route53domains:RegisterDomain`,
   *explicitDeny*, medido); comprarlo fuera y gestionar ahi el DNS es lo normal.
2. Espera a que resuelva (`dig +short biblioteca.midominio.cl`).
3. Pon `SITE_DOMAIN=biblioteca.midominio.cl` en `aws/.env` y corre
   `./aws/scripts/bootstrap.sh`. Cambia el host en SSM, y los Outputs nuevos
   llegan a los callbacks de Cognito, al CORS del gateway y de Spring y al
   `aws-config.ts` del frontend en la misma pasada. Caddy pide el certificado
   del dominio nuevo al reiniciar.

Dejalo en `aws/.env` y no solo en la terminal: cada bootstrap despliega
`web.yaml` con ese valor, y sin el el sitio volveria a sslip.io.

### Consultar la base y el panel de RabbitMQ (VPN opcional)

La base no tiene ninguna via desde fuera, y el panel de RabbitMQ (15672) solo
se abre al tunel. Para mirarlos:

```sh
./aws/scripts/vpn-up.sh      # ~3 min la primera vez; pide sudo para la interfaz
# DataGrip: Database > + > Data Source from URL, pega la linea jdbc: de
#           aws/.local/vpn/conexion.txt
# RabbitMQ: la URL, el usuario y la contrasena que imprime el script
./aws/scripts/vpn-down.sh    # al terminar: el servidor cuesta por hora
```

Que hace `vpn-up.sh`, sin pasos manuales:

1. Genera el par de llaves WireGuard del equipo en `aws/.local/vpn/` (fuera de
   git). Al stack solo viaja la **publica**.
2. Detecta tu IP publica: el security group del servidor solo acepta UDP desde
   ella (`VPN_ORIGEN` para otro rango).
3. Despliega `vpn.yaml` en la subred publica del proyecto: Ubuntu sin SSH ni
   rol IAM, una regla mas en el SG de RDS (5432) y otra en el de la EC2 (15672).
4. Lee en vivo la IP publica del servidor y su llave publica, que este anuncia
   en su consola serie. Su llave privada no sale de la instancia.
5. Levanta la interfaz `wgbiblioteca` con `wg-quick`. Solo enruta el rango de
   la VPC; el resto de tu trafico no cambia. Si no hay `wireguard-tools` pero
   si `nix-shell`, las toma de nixpkgs.
6. Descarga la CA de RDS y escribe `conexion.txt` (permisos 600) con la URL
   JDBC y el comando `psql`, ambos con `sslmode=verify-full`. Si el backend esta
   desplegado, imprime ademas la URL del panel de RabbitMQ
   (`http://<IP privada de la EC2>:15672`) y sus credenciales (`RabbitSecret`).

Por defecto conecta a la base como `biblioteca_app`.
`VPN_USUARIO_BD=maestro` usa el usuario de Flyway: con el se puede romper el
esquema que Flyway gestiona.

En el panel de RabbitMQ estan las colas, las DLQ (`correo.fallidos`,
`eventos.fallidos`) con el motivo de cada mensaje (`x-motivo-fallo`, `x-death`),
y *Move messages* (plugin shovel) para devolver un mensaje a su cola cuando se
arregla la causa.

Vuelve a correr `vpn-up.sh` al reabrir el lab o al cambiar de red: la IP del
servidor y la tuya cambian, y el script lo reajusta.

### Desmontar

```sh
./aws/scripts/teardown.sh          # pide escribir BORRAR
./aws/scripts/teardown.sh --yes    # sin preguntar
```

Vacia el bucket de artefactos **antes** de borrar los stacks, incluidas todas
las versiones y los delete markers: sin eso, CloudFormation deja el stack en
`DELETE_FAILED`.

Orden: `vpn` (si existe, via `vpn-down.sh`) → `api` → `backend` → `database` →
`web` → `network` → `cognito` → `artifacts`. La base se borra **sin snapshot**,
y la EC2 con su volumen: se pierden los mensajes que quedaran en las colas.
Borrar `web` libera la IP elastica: el nombre `<ip>.sslip.io` no se recupera.

**Mientras exista, cuesta por hora aunque el lab este apagado**: el NLB y la
IP elastica (las EC2 se detienen con el lab; esos dos no). Si no lo vas a usar
en dias, desmontalo.

### Si venias de la version con el sitio en S3

`web.yaml` era un bucket publico y ahora es una EC2. Actualizar encima borra el
bucket, y CloudFormation no borra un bucket con objetos: vacialo antes
(`aws s3 rm s3://biblioteca-web-<cuenta> --recursive`) y corre
`./aws/scripts/bootstrap.sh`, que despliega el servidor y lleva la URL nueva a
Cognito, al CORS y al frontend.

### Si venias de la version con Lambda

`network.yaml` cambio de forma (subred publica, IGW, el security group de la
Lambda pasa a ser el de la EC2) y el stack de la VPN ya no crea su propio IGW.
Actualizar encima de lo desplegado choca: el grupo viejo sigue en uso y una VPC
no admite dos IGW. Lo limpio es `./aws/scripts/teardown.sh` y despues
`bootstrap.sh`.

---

## 5. La demo de Defense in Depth

API Gateway valida el token, pero no es el unico camino a la aplicacion: desde
dentro de la VPC el puerto 8080 de la EC2 responde a cualquiera que llegue. La
demostracion es llegar por ahi, **sin el authorizer delante**, y ver que Spring
rechaza igual. Desde fuera no se puede (el security group no acepta nada de
Internet), asi que se hace por la VPN:

```sh
./aws/scripts/vpn-up.sh
IP=$(aws cloudformation describe-stacks --stack-name biblioteca-backend \
  --query "Stacks[0].Outputs[?OutputKey=='AppPrivateIp'].OutputValue" --output text)
TOKEN=$(python3 -c "import re;print(re.search(r'name: ACCESS_TOKEN\n    value: \"([^\"]*)\"',open('bruno/environments/aws.yml').read()).group(1))")

curl -s -o /dev/null -w '%{http_code}\n' http://$IP:8080/api/libros                                  # sin token
curl -s -o /dev/null -w '%{http_code}\n' -H 'Authorization: Bearer abc' http://$IP:8080/api/libros   # inventado
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://$IP:8080/api/libros # bueno
curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" http://$IP:8080/api/gestion/solicitudes
```

Esperado: **401 · 401 · 200 · 403** (el ultimo: un estudiante en gestion). Con
el token de usuario sin scope (`aws-usuario-sin-scope.yml`): **403**, donde el
gateway da 401. Lo mismo se midio en la etapa Lambda con `aws lambda invoke`
(401 · 401 · 403 · 200); con la EC2 esta **pendiente de medir**.

Este curl no es un script del repositorio a proposito: es una demostracion
manual, no una prueba automatizada (esas viven en Bruno).

---

## 6. Como ver que pasa

```sh
# Logs de la aplicacion y de RabbitMQ (streams "app" y "rabbitmq")
aws logs tail /biblioteca/backend --follow --region us-east-1

# ¿El NLB ve sana la instancia?
aws elbv2 describe-target-health --target-group-arn "$(aws cloudformation describe-stacks \
  --stack-name biblioteca-backend --query "Stacks[0].Outputs[?OutputKey=='TargetGroupArn'].OutputValue" --output text)"

# Que paso en el primer arranque de la EC2 (UserData)
aws ec2 get-console-output --instance-id <InstanceId> --latest --output text

# Una consola en la EC2 sin SSH (Session Manager)
aws ssm start-session --target <InstanceId>

# Outputs de un stack
aws cloudformation describe-stacks --stack-name biblioteca-api \
  --query 'Stacks[0].Outputs' --output table

# Por que fallo un despliegue
aws cloudformation describe-stack-events --stack-name biblioteca-api \
  --query "StackEvents[?contains(ResourceStatus,'FAILED')].[LogicalResourceId,ResourceStatusReason]" \
  --output table

# Validar una plantilla sin desplegarla
aws cloudformation validate-template --template-body file://aws/api.yaml
```

Los 401 y 403 del authorizer **no aparecen en los logs del backend**: API
Gateway rechaza antes de reenviar. Si buscas un 401 en CloudWatch y no esta,
es por eso, y es la respuesta correcta. Los 403 de RBAC (`/api/gestion`) si
pasan por Spring.

---

## 7. Restricciones de AWS Academy que condicionan todo

Medidas en la cuenta con `aws iam simulate-principal-policy` (rol `voclabs` y
`LabRole`):

1. **No se pueden crear roles ni politicas IAM.** Ninguna plantilla declara
   `AWS::IAM::*`: la EC2 usa el perfil preexistente `LabInstanceProfile`
   (parametro `InstanceProfileName`).
2. **Amazon MQ y MSK: `explicitDeny`.** Por eso RabbitMQ es autogestionado, en
   la EC2. Y por eso el backend no puede ser Lambda: Lambda solo consume colas
   de Amazon MQ.
3. **SES: `explicitDeny`.** El correo sale por un proveedor SMTP externo, y la
   EC2 necesita salida a Internet (subred publica; ver README raiz §7).
4. **Region `us-east-1`**, casi todo lo demas esta bloqueado.
5. **CloudFront: denegado** (`cloudfront:CreateDistribution`, *implicitDeny*,
   medido). Por eso el sitio lo sirve Caddy en una EC2 y no un bucket con
   CloudFront delante (README raiz §7).
6. **Las credenciales caducan cada ~4 horas.** Es la causa numero uno de fallos
   inexplicables. `awsacademy start`.
7. **Los nombres globales llevan `${AWS::AccountId}`**: los buckets y el dominio
   de Cognito son unicos en todo AWS, no solo en tu cuenta.
8. **RDS y EC2 solo en clases pequenas.** Al cerrar la sesion del lab, AWS
   Academy **detiene** RDS y la EC2; al reabrirla tardan unos minutos en volver
   (los servicios de systemd arrancan solos los contenedores).

---

## 8. Problemas conocidos y como se resuelven

| Sintoma | Causa | Solucion |
|---|---|---|
| Cambias un metodo y el stage sigue sirviendo lo viejo | `AWS::ApiGateway::Deployment` es inmutable y CloudFormation no lo recrea | `./aws/pipeline/api-deploy.sh` |
| `Allow-Origin` sale con comillas literales, o no sale | Faltan las comillas simples dentro de las dobles en `ResponseParameters` | `"'https://…'"` |
| Un 401 se ve en el navegador como `status 0` / *blocked by CORS* | Faltan los `GatewayResponse` `DEFAULT_4XX`/`5XX` | Estan en `api.yaml`; comprueba que el origen coincida |
| El 200 llega a Bruno pero el navegador lo bloquea | Con `HTTP_PROXY`, API Gateway no anade cabeceras a la respuesta real: la pone Spring | Revisa `BIBLIOTECA_ORIGEN` en `/biblioteca/backend/app.env` |
| `500 Internal Server Error` en todas las rutas | El NLB no tiene ningun destino sano: la app arrancando, caida, o el lab recien reabierto | `describe-target-health` (§6) y `aws logs tail /biblioteca/backend` |
| La EC2 se crea pero el stack falla por timeout de la senal | El UserData no llego a ver `/health` en 15 min (imagen que no baja, secreto ilegible, app que no arranca) | `aws ec2 get-console-output --instance-id <id> --latest` |
| La app no arranca: `PRECONDITION_FAILED - inequivalent arg` | Cambio un argumento de una cola ya creada (p. ej. el TTL de `correo.espera`) | Borra esa cola desde el panel y reinicia la app |
| El stack de Cognito falla en `LoginBranding`: *Invalid file dimension* | El logo (`FORM_LOGO`) tiene que tener proporcion ancho:alto entre 1:1 y 4:1 | Ajusta el `viewBox` de `aws/marca/logo-login.svg` |
| `/login` responde error y no hay pantalla | Con `ManagedLoginVersion: 2`, el App Client no tiene estilo de marca | `LoginBranding` en `cognito.yaml` (no se puede quitar sin volver a la version 1) |
| El login sale en ingles | Falta `lang=es` en la redireccion | `signInWithRedirect({ options: { lang } })` y la URL de `/signup`, en `front/src/app/auth/sesion.ts` |
| Activar avisos responde 500 | La app no consiguio credenciales para Cognito: el contenedor no alcanza IMDS | `HttpPutResponseHopLimit: 2` en `backend.yaml` (ya esta) |
| Los correos no llegan; `correo.fallidos` crece | El proveedor SMTP rechaza (credenciales, remitente no verificado, limite diario) | Header `x-motivo-fallo` en el panel; corrige con `SMTP_*` en `build-backend.sh` y mueve los mensajes de vuelta |
| `build-backend.sh` falla en el reinicio por SSM | La instancia esta detenida (el lab la paro) o el agente de SSM aun no se registro | Espera a que este `running` y repite |
| El preflight `OPTIONS` devuelve 401 | Se le puso authorizer | `AuthorizationType: NONE` en OPTIONS, siempre |
| `scope does not exist` al crear Cognito | Los App Clients se crearon antes que el Resource Server | `DependsOn: ResourceServer` (ya esta en la plantilla) |
| 401 con un token recien pedido por CLI | Vino de `admin-initiate-auth`, que **no emite custom scopes** | Usa los clientes M2M: `./aws/pipeline/bruno-env.sh` |
| El empleado ve 403 en la bandeja | El usuario no esta en el grupo, o su token es anterior a meterlo | `admin-add-user-to-group` (lo hace `bootstrap.sh`) y volver a iniciar sesion |
| 401 con un token que parece bueno | Caducado (60 min), de otro pool, o copiado a medias | `./aws/pipeline/bruno-env.sh` |
| `ExpiredToken` en cualquier `aws` | Credenciales del lab vencidas | `awsacademy start` |
| `DELETE_FAILED` en un stack con bucket | El bucket tiene objetos o versiones | `teardown.sh` los vacia primero |
| Un 404 de Spring llega al cliente como **403 con cuerpo vacio** | El reenvio interno a `/error` vuelve a pasar por Spring Security y cae en el `denyAll()`. **Los tests de MockMvc no lo detectan** | `.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` en `SecurityConfig` (ya esta) |
| Un token valido sin el scope devuelve **401 y no 403** | No es un fallo: el authorizer `COGNITO_USER_POOLS` responde asi. **Medido** | Es el comportamiento real; el 403 por autorizacion lo da Spring |
| Un 403 donde esperabas un 404 | API Gateway contesta **403 Missing Authentication Token** a cualquier ruta sin recurso declarado | Antes de mirar el token, comprueba que la ruta exista en `api.yaml` |
| Cognito rechaza el callback (`redirect_mismatch`) | Solo acepta `https://` (salvo `http://localhost`), y compara caracter por caracter | El callback es `SiteUrl` con la barra final; si cambiaste `SITE_DOMAIN`, vuelve a correr el bootstrap entero |
| El sitio responde "Sitio en preparacion" (503) | Todavia no se publico ninguna version: es el Caddyfile provisional del primer arranque | `./aws/pipeline/publish-web.sh` |
| El navegador avisa de certificado invalido, o no conecta por https | Caddy aun no obtuvo el certificado: el 80 cerrado, el DNS del dominio propio sin apuntar a la IP, o un limite de Let's Encrypt | `aws logs tail /biblioteca/web --follow`: Caddy dice que fallo en la validacion ACME |
| `web` falla en `SiteSecurityGroup`: *Invalid rule description* | Las descripciones de reglas solo admiten un juego corto de caracteres: un apostrofo (`Let's`) basta | Sin apostrofos ni tildes en esas descripciones |
| `/api/...` responde **403** desde el sitio pero Bruno da 200 | Caddy reenvia con el `Host` del sitio y API Gateway no sabe que API es | `header_up Host {upstream_hostport}` en `front/Caddyfile` (ya esta) |
| Una peticion aparece bloqueada en la consola con *Content Security Policy* | El JavaScript intenta hablar con un origen que la CSP no lista | Anadelo a `connect-src` en `front/Caddyfile` y vuelve a publicar |
| Recargar en `/catalogo` da la portada o 404 | El router no es `createBrowserRouter`, o falta `try_files` | `front/src/app/rutas.tsx` y `front/Caddyfile` |
| Al actualizar `web`, el bucket viejo queda `DELETE_FAILED` y huerfano | Venias de la version con el sitio en S3 y el bucket tenia objetos | §4, *Si venias de la version con el sitio en S3* |
| 401 con **todo** token, tambien los recien pedidos, y `kid` desconocido en los logs | Cognito cambio sus claves de firma y el backend tiene el JWKS viejo (se fija al desplegar) | `./aws/pipeline/build-backend.sh` (vuelve a descargarlo) |
| 500 con `Connection is not available` / `connect timed out` en los logs | RDS todavia arrancando tras reabrir el lab, o detenida | Espera a que `aws rds describe-db-instances` diga `available` |
| `database` falla con `Cannot find version 16` | RDS no acepto la version mayor sola | Pasa una concreta en `DbEngineVersion` (el comando esta en la descripcion del parametro) |
| `Validate failed: Detected applied migration not resolved locally` o checksum distinto | Se edito una migracion ya aplicada | No se editan: se anade una `V4__...sql` |
| `network` o `vpn` fallan con un IGW o un security group en uso | Despliegue de la version con Lambda debajo | §4, *Si venias de la version con Lambda* |
| `vpn-up.sh` espera la llave del servidor y no llega | El primer arranque instala WireGuard con apt (1-3 min), o la cuenta no permite `ec2:GetConsoleOutput` | Mira la consola con el comando que imprime el script; si es un `AccessDenied`, reportarlo |
| El tunel esta levantado pero DataGrip no conecta | Cambio tu IP publica, o el servidor se detuvo al cerrar la sesion del lab y cambio la suya | Vuelve a correr `./aws/scripts/vpn-up.sh` |
| `vpn-up.sh` dice que el servidor esta detenido | El lab paro la instancia al cerrar la sesion | `./aws/scripts/vpn-down.sh && ./aws/scripts/vpn-up.sh` |
| La API responde 500 y Flyway dice `password authentication failed for user "biblioteca_app"` | Alguien cambio a mano `AppSecret` despues de crear el rol | `ALTER ROLE` con la nueva, o recrear `database` |
| RabbitMQ no acepta la contrasena tras rotar `RabbitSecret` | `RABBITMQ_DEFAULT_PASS` solo se aplica al crear el volumen `rabbitmq-datos` | Cambiala desde el panel, o borra el volumen (se pierden las colas) |
