# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Todo el repositorio (codigo, comentarios, documentacion, nombres de clases y de
variables) esta en **castellano y sin tildes**. Mantenlo asi en cualquier
archivo que toques.

---

## Skills obligatorios

* **Antes del primer comando `aws` o de tocar cualquier `.yaml` de `aws/`**,
  invoca el skill `aws-academy-cloudformation`.
* **Antes de tocar `bruno/`**, invoca el skill `bruno-collections`.

Si un skill contradice a un README, **gana el skill** y hay que corregir el
documento.

---

## Comandos

### Infraestructura (AWS Academy Learner Lab)

```sh
awsacademy start                   # credenciales frescas; caducan cada ~4 h
SMTP_HOST=... SMTP_USER=... SMTP_PASSWORD=... SMTP_FROM=... \
./aws/scripts/bootstrap.sh         # crea TODO en orden; idempotente (SMTP_* solo la 1.a vez)
# o bien: cp aws/.env.example aws/.env (SMTP_*, DEMO_USER...; en .gitignore) y
./aws/scripts/bootstrap.sh         # la terminal gana sobre aws/.env
./aws/scripts/teardown.sh          # borra todo (pide escribir BORRAR; --yes lo salta)

./aws/scripts/vpn-up.sh            # opcional: VPN WireGuard hasta la base y el panel de RabbitMQ (sudo)
./aws/scripts/vpn-down.sh          # la baja y borra el stack (cuesta por hora)
```

Pipelines parciales, cuando ya hay stacks desplegados:

```sh
./aws/pipeline/build-backend.sh    # cambiaste codigo Java (mvnw package -> jar a S3 -> stack -> reinicio por SSM)
./aws/pipeline/api-deploy.sh       # cambiaste rutas, metodos o scopes en api.yaml
./aws/pipeline/publish-web.sh      # cambiaste el frontend (config + tsc + vite build + s3 sync)
./aws/pipeline/bruno-env.sh        # los tokens caducaron (60 min)

SKIP_TESTS=true ./aws/pipeline/build-backend.sh
SOLO_CONFIG=true ./aws/pipeline/publish-web.sh   # solo genera los archivos del frontend
DEBUG=true       ./aws/pipeline/publish-web.sh   # + la vista /diagnostico (no entregable)
SKIP_WEB=true    ./aws/scripts/bootstrap.sh
REMINDER_LEAD=PT23H58M ./aws/pipeline/build-backend.sh   # recordatorio a los ~2 min (demo)
```

Variables comunes a todos los scripts: `PROJECT_NAME` (biblioteca),
`AWS_REGION` (us-east-1), `STAGE_NAME` (test), `DEMO_USER`, `DEMO_EMPLOYEE`.

**Un cambio en `api.yaml` no llega al stage solo**: `AWS::ApiGateway::Deployment`
es inmutable, asi que siempre hay que correr `api-deploy.sh` despues.

### Backend

```sh
cd back
./mvnw package                                          # compila y corre los 34 tests (necesita Docker:
                                                        #  PostgreSQL y RabbitMQ con Testcontainers, GreenMail)
./mvnw test
./mvnw test -Dtest=AvisosIntegracionTest                # una clase
./mvnw test -Dtest=LibroControllerTest#nombreDelMetodo  # un test
```

### Frontend

```sh
cd front
npm ci && npm run build            # tsc -b (tipos) + vite build; deja dist/

# para compilar sin desplegar: los dos archivos que genera el pipeline
cp src/app/aws-config.example.ts        src/app/aws-config.ts
cp src/app/diagnostico/rutas.example.ts src/app/diagnostico/rutas.ts
```

React 19 + React Router 8 + Vite. No hay script de test ni de servidor de
desarrollo (`npm run dev` no existe a proposito). La verificacion del frontend
es el recorrido en navegador del `front/README.md` §4.

### Pruebas de la API

```sh
cd bruno && bru run --env aws                   # estudiante: 200/201, y 403 en /gestion (RBAC)
bru run --env aws-backoffice                    # empleado (M2M scope gestion): 200 en /gestion
bru run --env aws-usuario-sin-scope             # 401
bru run --env aws-id-token                      # 401
bru run --env aws-token-invalido                # 401
SSL_CERT_DIR=/etc/ssl/certs bru run --env aws   # NixOS, si OpenSSL se queja
```

Los entornos **se generan** con `bruno-env.sh` y llevan tokens reales: estan en
`.gitignore`. **Ningun script prueba la API**: los scripts construyen; la
verificacion es Bruno y el navegador.

### Diagnostico

```sh
aws logs tail /biblioteca/backend --follow --region us-east-1   # streams app y rabbitmq
aws ec2 get-console-output --instance-id <id> --latest --output text   # primer arranque de la EC2
aws cloudformation describe-stacks --stack-name biblioteca-api --query 'Stacks[0].Outputs' --output table
aws cloudformation validate-template --template-body file://aws/api.yaml
```

Los 401 del authorizer **no aparecen en los logs del backend**: API Gateway
rechaza antes de reenviar. Un 500 en todas las rutas casi siempre es el NLB sin
destino sano (la app arrancando o caida): `aws elbv2 describe-target-health`.

---

## Arquitectura

```
React (S3, endpoint REST)    ──▶  API Gateway REST (authorizer Cognito + scopes)
                                          │ HTTP_PROXY por VPC Link
                                          ▼
                              NLB interno (subredes privadas)
                                          │ :8080, health check /health
                                          ▼
                              EC2 (subred PUBLICA, SG: entra solo la VPC)   ── SMTP 587 ──▶ correo
                               [app]      Spring Boot: revalida el token, RBAC,
                                          consumidores @RabbitListener, @Scheduled
                               [rabbitmq] 4.1 + panel (solo por la VPN)
                                          │ TLS verify-full
                                          ▼
                              RDS PostgreSQL 16 (subredes privadas, sin IP publica)
```

Por que EC2 y no Lambda: RabbitMQ necesita consumidores vivos todo el tiempo, y
Lambda solo consume colas de **Amazon MQ**, que el lab bloquea (`mq:*`, `kafka:*`
y `ses:*` con *explicitDeny*, medido con `simulate-principal-policy`). El broker
va en la misma EC2; el backend no se parte entre Lambda y EC2 (decision del
usuario).

Los avisos por correo viajan asi: cambio de negocio + evento en la MISMA
transaccion (`evento_pendiente`, outbox) → `RelayEventos` lo publica con
publisher confirms en el topic `biblioteca.eventos` → consumidores con ACK
manual → exchange direct `correo` (carriles `transaccional`/`masivo`) →
`EnviadorSmtp`. Reintento con espera por DLX + TTL (`correo.espera`); DLQ en
`correo.fallidos` y `eventos.fallidos`. Detalle en `back/README.md` §4.

Siete stacks, uno por plantilla, sin `Export`/`Fn::ImportValue`: se encadenan
con **parametros explicitos que pasa `bootstrap.sh`** leyendo Outputs.

| Plantilla | Stack | Depende de |
|---|---|---|
| `aws/artifacts.yaml` | `biblioteca-artifacts` | — |
| `aws/web.yaml` | `biblioteca-web` | — |
| `aws/cognito.yaml` | `biblioteca-cognito` | — (recibe la URL del sitio *calculada*) |
| `aws/network.yaml` | `biblioteca-network` | — |
| `aws/database.yaml` | `biblioteca-database` | network |
| `aws/backend.yaml` | `biblioteca-backend` | artifacts, cognito, web, network, database + jar subido |
| `aws/api.yaml` | `biblioteca-api` | cognito, backend, web |
| `aws/vpn.yaml` (opcional) | `biblioteca-vpn` | network, database (+ backend para el panel); lo crea `vpn-up.sh`, no el bootstrap |

El ciclo aparente (Cognito necesita la URL del frontend; el frontend necesita
Cognito) se rompe **calculando** `https://<proyecto>-web-<AccountId>.s3.<region>.amazonaws.com`
con un `sts get-caller-identity` antes de que el bucket exista.

`aws/README.md` §3 tiene el contrato completo de entradas y salidas de cada
stack: **leelo antes de cambiar un parametro**, porque cada Output alimenta a
otro stack o a un script.

### Seguridad en dos capas, y donde vive cada literal

Dos scopes y un grupo. `biblioteca-api/acceso` (estudiantes) tiene que
coincidir **caracter por caracter** en: el Resource Server y los App Clients SPA
y M2M de `cognito.yaml`, `AuthorizationScopes` en `api.yaml`, `SecurityConfig`
(por `BIBLIOTECA_SCOPE`) y la lista de scopes de Amplify. `biblioteca-api/gestion`
(solo el App Client backoffice) en: Resource Server, `api.yaml` y `SecurityConfig`
(por `BIBLIOTECA_SCOPE_GESTION`). Se propagan desde los Outputs `ScopeCompleto`
y `ScopeGestion`. El grupo `empleados` en `cognito.yaml`,
`biblioteca.grupo-empleados` de `application.properties` y `auth/sesion.ts`.

**RBAC**: el gateway acepta cualquiera de los dos scopes en todos los metodos;
el rol lo decide Spring. `ROLE_EMPLEADO` = (grupo `empleados` en
`cognito:groups` **y** scope de acceso) **o** scope de gestion. La regla vive en
la ruta: `/api/gestion/**` → `hasRole("EMPLEADO")`. Los scopes se asignan por
App Client, no por persona: el rol de una persona NO puede ser un scope.

Codigos **medidos** contra el despliegue real (no supuestos):

* Token valido **sin** el scope → API Gateway responde **401**, no 403. El 403
  por autorizacion lo da **Spring**: directo a la EC2 por la VPN
  (`aws/README.md` §5) y, con RBAC, tambien a traves del gateway en
  `/api/gestion/**` con un token de estudiante (pendiente de medir).
* Un **id token** siempre da 401: no lleva claim `scope`, y `SecurityConfig`
  ademas exige `token_use == "access"`.
* Una ruta inexistente da **403 Missing Authentication Token**, no 404.

### CORS: tres piezas, tres responsables

Con integracion `HTTP_PROXY`, API Gateway **no anade** cabeceras a la respuesta
real. El reparto es fijo:

1. preflight `OPTIONS` → API Gateway, integracion MOCK, `AuthorizationType: NONE`;
2. cabecera del 200 → **Spring** (`corsConfigurationSource`, origen exacto desde `BIBLIOTECA_ORIGEN`);
3. errores del propio gateway (401/403) → `GatewayResponse` `DEFAULT_4XX`/`5XX` en `api.yaml`.

Si falta la tercera, un 401 se ve en el navegador como *blocked by CORS*.

---

## Convenciones que no se pueden romper

* **Nada de `localhost` ni entornos de desarrollo.** Solo se entrega el acceso
  publico: `localhost` no esta en las listas blancas de Cognito ni en el CORS.
  No propongas `vite` / `npm run dev` como via de verificacion.
* **Cero identificadores de AWS escritos a mano.** Todo sale de Outputs de
  CloudFormation. Archivos generados y no versionados:
  `front/src/app/aws-config.ts` (plantilla: `aws-config.example.ts`),
  `front/src/app/diagnostico/rutas.ts` (plantillas: `rutas.example.ts` y
  `rutas.example-debug.ts`), `bruno/environments/aws*.yml`, `aws/.local/`,
  `aws/.env` (plantilla: `aws/.env.example`; lleva `SMTP_PASSWORD`).
* **La vista de diagnostico es opt-in y no se entrega.** Ensena el access token
  en claro. Solo aparece con `DEBUG=true`, y lo que esa variable genera es la
  RUTA, no un `if`: apagada, el componente no lo importa nadie y Vite no lo
  empaqueta, asi que no llega a `dist/` ni como codigo muerto. El token tampoco
  vive en `EstadoSesion`: lo piden a Amplify `auth/http.ts` y esa vista, nadie
  mas.
* **Toda llamada a la API pasa por `auth/http.ts`**, que pone el `Bearer`. Un
  `fetch` suelto en un componente sale sin token (401); la unica excepcion
  declarada es la vista de diagnostico.
* **Las pantallas de login y registro son de Cognito (Managed Login v2), no del
  frontend.** Un formulario propio usaria `InitiateAuth` (SRP), que no emite
  custom scopes: 401 en toda la API. La marca vive en `LoginBranding`
  (`cognito.yaml`) y los SVG en `aws/marca/`; el idioma lo pide el frontend
  con `lang=es` (`auth/sesion.ts`, tambien en la URL de `/signup`).
* **Cero recursos `AWS::IAM::*`**: el Learner Lab no permite crear roles.
  La EC2 usa el perfil `LabInstanceProfile` (parametro `InstanceProfileName`).
  Tampoco hay ninguna Access Key estatica en el sistema.
* **Ninguna peticion manda correos ni publica en RabbitMQ directamente**: deja
  el evento en `BandejaDeSalida` dentro de su transaccion (`Propagation.MANDATORY`).
  Los consumidores son idempotentes (entrega "al menos una vez"). El email del
  destinatario sale de Cognito por el `sub`, nunca del cuerpo.
* **El `solicitante` nunca viaja en el cuerpo**: sale de `Principal.getName()`
  (claim `sub`, o `client_id` en M2M).
* **Dos prefijos por controlador**: `@RequestMapping({"/api/x", "/v1/api/x"})`.
  En API Gateway `/v1/{proxy+}` es un unico recurso y el enrutado fino lo hace
  Spring. Un `/v2` seria otra clase y convivirian.
* **Toda desviacion se declara**, no se esconde: README §7 (canon de la guia,
  EC2 en subred publica, SMTP externo, JWKS fijado al desplegar, contrasenas
  como variables del contenedor, backoffice como empleado en Bruno, VPN).
* **La base no se expone.** Nada de `PubliclyAccessible: true`, NAT ni
  bastion. RDS vive en las subredes PRIVADAS, que no tienen ruta a Internet: la
  unica via permanente es el SG de la EC2 de la app. La subred PUBLICA de
  `network.yaml` (con el unico IGW de la VPC) es solo para la EC2 de la app
  —cuyo SG no acepta nada desde Internet— y el servidor de la VPN opcional. No
  anadas rutas a Internet a las privadas ni puertos abiertos a `0.0.0.0/0`. El
  seed y el esquema son migraciones Flyway
  (`back/src/main/resources/db/migration`) que aplica la propia app al arrancar.
  Las migraciones aplicadas no se editan: se anade una `V4__...sql`.
* **Dos usuarios de BD**: Flyway entra como maestro (`MasterSecret`); la app
  como `biblioteca_app` (`AppSecret`): `SELECT`/`INSERT`, y desde V3 `UPDATE`
  solo de columnas concretas (stock, ciclo de vida) y `DELETE` solo donde borrar
  es negocio. Si una consulta nueva necesita mas permisos, se conceden en una
  migracion, no se cambia de usuario. `SELECT ... FOR UPDATE` exige `UPDATE`:
  el relay usa `DELETE ... RETURNING` para no necesitarlo.
* **La configuracion de la EC2 no va en el UserData** (solo corre el primer
  arranque, y cambiarlo detiene la instancia): va en el parametro SSM
  `/<proyecto>/backend/app.env`, que los scripts de arranque releen en cada
  inicio del servicio. Los secretos se leen de Secrets Manager en ese momento.
* Si cambias comportamiento medido, **vuelve a medirlo** y actualiza la tabla
  del `README.md` §4 y el `ESPERADO_*` de los entornos de Bruno.

---

## Trampas ya pagadas (no volver a tropezar)

* **`.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` en
  `SecurityConfig` es imprescindible.** Sin eso, el reenvio interno a `/error`
  vuelve a pasar por las reglas, cae en el `denyAll()` final, y **todo 404 y 409
  sale como 403 con cuerpo vacio**. MockMvc no reproduce ese reenvio: los tests
  pasaban en verde.
* **`/health` es la unica ruta publica** y no se publica en API Gateway: es el
  health check del NLB y la senal (cfn-signal) del primer arranque de la EC2.
* **Con `ManagedLoginVersion: 2`, un App Client sin `ManagedLoginBranding` no
  tiene pantalla de login**: `/login` da error. El logo (`FORM_LOGO`) tiene que
  tener proporcion entre 1:1 y 4:1 o el recurso falla al crearse; los colores
  van en RGBA hexadecimal sin `#` (`0f6b5cff`).
* **Rutas con `#` (`createHashRouter`)**: el endpoint REST de S3 no redirige a
  `index.html`, y el *website endpoint* es HTTP puro, que Cognito rechaza como
  callback.
* **`admin-initiate-auth` no emite custom scopes.** El token bueno solo sale de
  los App Clients M2M (`client_credentials`) o del flujo del navegador. Esa
  limitacion da gratis los casos 401 de la matriz, y es por lo que en Bruno el
  empleado es el cliente backoffice y no una persona del grupo.
* **El JWKS se fija al desplegar** (lo baja `build-backend.sh`, entra por
  `BIBLIOTECA_JWKS`): viene de la etapa Lambda sin Internet y se mantiene. No
  uses `spring.security.oauth2.resourceserver.jwt.issuer-uri`; el emisor va en
  `BIBLIOTECA_ISSUER`.
* **`HttpPutResponseHopLimit: 2`** en la EC2: con IMDSv2 y el limite por
  defecto, el contenedor de la app no alcanza el servicio de metadatos y el SDK
  (Cognito `ListUsers`) se queda sin credenciales.
* **Argumentos de cola inmutables**: cambiar un TTL, un DLX o el tipo de una
  cola ya declarada hace fallar el arranque con `PRECONDITION_FAILED`; hay que
  borrar la cola en el panel.
* **El recordatorio NO es un mensaje con TTL**: RabbitMQ solo expira en la
  cabeza de la cola (uno de 30 dias bloquearia al de 1) y un mensaje publicado
  no se anula al devolver el libro. Es un `@Scheduled` con `UPDATE ... RETURNING`.
* **El host del SMTP de los tests va en `application.properties` de test**, no
  en el `DynamicPropertyRegistrar`: la autoconfiguracion del `JavaMailSender`
  mira esa propiedad antes de que los contenedores registren las suyas.
* **`src/test/resources/application.properties` reemplaza al principal**, no
  se suma: cualquier propiedad sin valor por defecto (p. ej. el placeholder
  `appPassword` de Flyway) hay que repetirla ahi.
* **Flyway sustituye placeholders tambien en los comentarios SQL**: no
  escribas `${...}` en un comentario de una migracion.

La tabla completa de sintomas y causas esta en `aws/README.md` §8.

---

## Donde esta cada cosa

| Documento | Para que |
|---|---|
| `README.md` | Vision general, arquitectura, matriz de seguridad, desviaciones declaradas |
| `aws/README.md` | Contrato de cada stack, orden de despliegue, restricciones del Learner Lab, tabla de problemas |
| `back/README.md` | Historias de usuario, RabbitMQ (topologia, ACK/NACK, DLQ), decisiones y quien produce cada codigo |
| `front/README.md` | Estructura React y recorrido de comprobacion en navegador |
| `bruno/README.md` | Por que la matriz vive en los entornos y no en peticiones duplicadas |
