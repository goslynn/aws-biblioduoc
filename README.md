# Biblioteca Duoc · Plataforma de prestamos

Aplicacion full stack cloud native en AWS para la asignatura **DSY1107**
(evaluacion EA1). Permite a la comunidad Duoc **crear una cuenta**, **buscar en
el catalogo**, **solicitar uno o varios libros** en prestamo y **recibir avisos
por correo** de cada paso. El personal de biblioteca (**empleados**) aprueba o
rechaza las solicitudes, registra devoluciones y carga stock.

```
React en S3    ──▶  API Gateway (REST + Cognito)  ──VPC Link──▶  NLB interno
      │                        │                                    │ :80 → :8080
   Amplify Auth          authorizer + scopes             ┌── EC2 (subred publica, SG cerrado) ──┐
   (code + PKCE)          + CORS + stage                 │  Spring Boot: API, 2.ª validacion    │
      │                                                  │   del JWT, RBAC, consumidores        │
      │                                                  │      │ AMQP (red de Docker)          │
      │                                                  │  RabbitMQ 4.1 ── SMTP 587 ──▶ correo │
      │                                                  └──────┼───────────────────────────────┘
      │                                                         │ TLS verify-full, 5432
      │                                                  RDS PostgreSQL 16 (subred privada, sin IP publica)
      └──────────────▶  Amazon Cognito  ── JWKS (lo baja el pipeline al desplegar) ──▶ Spring
```

Todo se crea con **CloudFormation** (una plantilla por servicio) desde la **AWS
CLI v2**, sobre una cuenta de **AWS Academy Learner Lab**.

---

## 1. Que hay en cada directorio

| Directorio | Que contiene |
|---|---|
| `back/` | Backend Spring Boot 4.1 (Java 21): API REST, RBAC, productores y consumidores de RabbitMQ, envio de correo. Tiene su propio [README](back/README.md) |
| `front/` | Frontend React 19 (Vite) con AWS Amplify, publicado como sitio estatico en S3. Tiene su propio [README](front/README.md) |
| `aws/` | Las siete plantillas de CloudFormation, los scripts de bootstrap/teardown y los pipelines. Tiene su propio [README](aws/README.md) |
| `bruno/` | Coleccion de pruebas HTTP versionada, con la matriz de seguridad completa. Tiene su propio [README](bruno/README.md) |

---

## 2. Que hace la aplicacion

### Historias de usuario

| # | Como... | quiero... | para... |
|---|---|---|---|
| H0 | estudiante | **activar los avisos por correo** | enterarme de mis prestamos sin entrar a la plataforma |
| H1 | estudiante | recibir un **comprobante** al enviar una solicitud | tener constancia de lo que pedi |
| H2 | empleado | ver la **bandeja** y **aprobar o rechazar** con motivo | resolver los prestamos; el estudiante recibe la resolucion |
| H3 | estudiante | un **recordatorio** antes del vencimiento | devolver a tiempo |
| H4 | empleado | **registrar la devolucion** | reponer el stock |
| H5 | estudiante | **"avisame cuando vuelva"** en un libro agotado | no revisar el catalogo todos los dias |
| H6 | empleado | **cargar stock** | reponer ejemplares; quienes esperaban reciben un correo |
| H7 | empleado | una **bitacora** de quien hizo que | auditar aprobaciones, rechazos y cargas |

Los caminos tristes de cada una (SMTP caido, stock que se agota entre pedir y
aprobar, doble aprobacion, devolucion antes del recordatorio...) estan en
[`back/README.md`](back/README.md) §3 y cubiertos por los tests.

### Ciclo de vida de una solicitud

```
RECIBIDA --empleado aprueba--> APROBADA --empleado registra devolucion--> DEVUELTA
    \
     `--empleado rechaza (con motivo)--> RECHAZADA
```

El stock se descuenta al **aprobar** (de forma atomica: si dos empleados se
llevan el ultimo ejemplar a la vez, uno recibe 409) y se repone al **devolver**.
El plazo de devolucion cuenta desde la aprobacion.

### Rutas

| Metodo | Ruta | Quien | Respuestas |
|---|---|---|---|
| GET | `/api/libros?q=&genero=&soloDisponibles=` | todos | 200 |
| GET | `/api/libros/generos` | todos | 200 |
| GET | `/api/libros/{id}` | todos | 200 · 404 |
| GET | `/api/libros/avisame` | todos | 200 (libros que espero) |
| POST · DELETE | `/api/libros/{id}/avisame` | todos | 201 / 204 · 404 · 409 (tiene stock) |
| POST | `/api/solicitudes` | todos | 201 · 400 · 404 · 409 |
| GET | `/api/solicitudes` | todos | 200, **solo las propias** |
| GET | `/api/solicitudes/{id}` | dueno o empleado | 200 · 404 (tambien si es ajena) |
| GET · PUT · DELETE | `/api/avisos/correo` | todos | 200 / 200 / 204 · 409 (sin email verificado) |
| GET | `/api/gestion/solicitudes?estado=` | **empleado** | 200 · 403 |
| POST | `/api/gestion/solicitudes/{id}/aprobacion` | **empleado** | 200 · 403 · 404 · 409 |
| POST | `/api/gestion/solicitudes/{id}/rechazo` | **empleado** | 200 · 400 · 403 · 404 · 409 |
| POST | `/api/gestion/solicitudes/{id}/devolucion` | **empleado** | 200 · 403 · 404 · 409 |
| POST | `/api/gestion/libros/{id}/stock` | **empleado** | 200 · 400 · 403 · 404 |
| GET | `/api/gestion/bitacora?limite=` | **empleado** | 200 · 403 |
| GET | `/health` | nadie (NLB) | 200, sin autenticacion y sin publicar en API Gateway |

Las mismas rutas existen bajo `/v1/api/...` (ver §6).

### Los avisos viajan por RabbitMQ

Mandar un correo es lento y depende de un tercero. Si cada peticion esperara al
SMTP, una caida del proveedor tumbaria las solicitudes. Por eso ninguna
peticion manda correos: deja un **evento**, y RabbitMQ se encarga del resto.

```
cambio de negocio + evento   (MISMA transaccion: bandeja de salida / outbox)
          │  RelayEventos, cada segundo, con publisher confirms
          ▼
topic  biblioteca.eventos ── solicitud.* recordatorio.* aviso.* ──▶ notif.prestamos      ─┐
                          ── stock.repuesto ──────────────────────▶ notif.disponibilidad ─┤ redactan
                          ── # ───────────────────────────────────▶ bitacora              │
                                                                                          ▼
direct correo ── transaccional ──▶ correo.transaccional ─┐
              ── masivo ─────────▶ correo.masivo ────────┴─▶ EnviadorSmtp ──▶ SMTP
   fallo transitorio: NACK ──▶ correo.reintento ──▶ correo.espera (TTL 60 s) ──▶ vuelve
   3 intentos, o fallo permanente ──▶ correo.fallidos (DLQ)
```

| Pieza de RabbitMQ | Donde | Para que |
|---|---|---|
| **topic** | `biblioteca.eventos` | cada consumidor se suscribe por patron; `#` (bitacora) recibe una copia de todo |
| **direct** | `correo` | dos carriles: 200 avisos de un libro repuesto no retrasan el comprobante de nadie |
| **fanout** | `correo.reintento` | lleva a la espera lo rechazado, sea cual sea su carril, conservando su routing key |
| **ACK manual** | todos los consumidores | el mensaje no se da por procesado hasta que el codigo lo dice |
| **NACK con requeue** | consumidores de eventos | fallo breve (base reiniciando): reintento inmediato; la cola **quorum** cuenta entregas y a la 5.ª lo aparta sola (`x-delivery-limit`) |
| **NACK sin requeue + DLX** | eventos invalidos; SMTP caido | mensaje imposible directo a la DLQ; correo a la espera con **TTL** |
| **DLQ** | `eventos.fallidos`, `correo.fallidos` | lo que no se pudo procesar queda a la vista en el panel, con su motivo |
| **publisher confirms** | relay y publicador de correo | nada se da por publicado hasta que el broker lo confirma |
| **prefetch distinto** | 5 transaccional, 1 masivo | el masivo no acapara el SMTP |

Detalles, decisiones (por que el recordatorio es un `@Scheduled` y no un TTL
por mensaje) y casos de fallo: [`back/README.md`](back/README.md).

### Lo que el cliente no decide

El campo `solicitante` **no viaja en el formulario**: el backend lo lee del JWT
ya validado (claim `sub` de la persona, o `client_id` si es un cliente maquina
a maquina). Por la misma razon, **activar los avisos no manda ningun correo en
el cuerpo**: el backend lo lee de Cognito (email verificado) por el `sub`. Si
cualquiera de los dos viniera del cliente, se podria pedir libros a nombre de
otro o mandar avisos al correo de otra persona.

### Persistencia: PostgreSQL privado

Las solicitudes, el catalogo, los contactos, las suscripciones, la bitacora y
la bandeja de salida viven en **RDS PostgreSQL 16**.

La base **no esta expuesta**: no tiene IP publica, vive en subredes sin ruta a
Internet (ni Internet Gateway ni NAT) y su security group solo acepta el 5432
desde el security group de la EC2 de la aplicacion. Por encima de eso:

| Capa | Medida |
|---|---|
| Transporte | `rds.force_ssl=1`, y la aplicacion verifica certificado y nombre (`sslmode=verify-full`) |
| Credenciales | Aleatorias, generadas por Secrets Manager desde CloudFormation; nunca pasan por el repo. La EC2 las lee al arrancar |
| Privilegios | La aplicacion usa el rol `biblioteca_app`: `SELECT`/`INSERT`, y `UPDATE` solo de las columnas del ciclo de vida y del stock (V3). El maestro solo lo usa Flyway al arrancar |
| Reposo | Almacenamiento y backups cifrados |

El esquema y el **seed** del catalogo los aplica **Flyway** al arrancar la
aplicacion: no hay bastion ni ninguna otra via permanente hacia la base, a
proposito.

Para **consultarla desde tu equipo** (DataGrip, psql) —y ver el **panel de
RabbitMQ**— hay una VPN WireGuard opcional y temporal: `./aws/scripts/vpn-up.sh`
la levanta y configura el cliente; `./aws/scripts/vpn-down.sh` la borra.
Detalles en `aws/README.md` §4.

### Como se consigue una cuenta, y quien es empleado

| Via | Quien la usa | Verificacion |
|---|---|---|
| **Autoservicio**: boton *Crear cuenta* → `/signup` de Managed Login (en castellano, con la marca de la Biblioteca) | cualquier persona | Cognito manda un **codigo de 6 digitos** al correo indicado; hasta teclearlo la cuenta queda `UNCONFIRMED` |
| **Administrador**: `admin-create-user` desde `bootstrap.sh` | el laboratorio: `alumno@duoc.cl` y `empleado@duoc.cl` | ninguna: se crean ya confirmadas, con contrasena conocida |

**Empleado = miembro del grupo de Cognito `empleados`.** Cognito pone los
grupos en el claim `cognito:groups` del access token, y Spring deduce de ahi el
rol `EMPLEADO`. `bootstrap.sh` mete en el grupo a `empleado@duoc.cl`; en la vida
real lo haria un administrador. Los scopes no sirven para esto: se asignan por
App Client, no por persona.

**Para recibir correos hace falta una casilla real.** Las cuentas de
demostracion tienen direcciones inventadas: registrate con la tuya desde
*Crear cuenta* y activa los avisos en *Mis solicitudes*.

### Las pantallas de login y registro

Son de Cognito (**Managed Login**, `ManagedLoginVersion: 2`), no de la
aplicacion, pero se ven como ella: salen **en castellano** (el frontend pide
`lang=es`) y con los colores, el logo y el favicon de la Biblioteca
(`LoginBranding` en `aws/cognito.yaml`; los SVG estan en `aws/marca/`).

No son formularios propios a proposito. Un formulario dentro del frontend
llamaria a Cognito con `InitiateAuth` (SRP), y ese flujo **no emite custom
scopes**: el access token saldria sin `biblioteca-api/acceso` y la API
responderia 401 a todo. Arreglarlo exigiria una Lambda (trigger Pre Token
Generation V2) y que la contrasena pasara por el JavaScript de la aplicacion.
Con Managed Login el flujo sigue siendo code + PKCE y la contrasena solo la ve
Cognito.

---

## 3. Como levantarlo

```sh
awsacademy start                # arranca el laboratorio y refresca credenciales

SMTP_HOST=smtp.gmail.com SMTP_USER=tu@gmail.com \
SMTP_PASSWORD='<contrasena de aplicacion>' SMTP_FROM=tu@gmail.com \
./aws/scripts/bootstrap.sh      # crea TODO, en orden, de forma idempotente
```

Las `SMTP_*` (el proveedor de correo; el lab bloquea SES) solo hacen falta la
primera vez, y pueden ir en `aws/.env` en vez de la terminal
(`cp aws/.env.example aws/.env`; esta en `.gitignore`). Al terminar imprime la URL del sitio, la de la API, las cuentas de
demostracion y los comandos para probar. La primera vez tarda unos 20 minutos
(RDS, la EC2 instalando Docker y el VPC Link). Requisitos locales: `aws`, `jq`,
`curl`, `python3`, `npm` y **Docker** (los tests del backend levantan
PostgreSQL y RabbitMQ con Testcontainers; `SKIP_TESTS=true` los salta). Para
desmontarlo:

```sh
./aws/scripts/teardown.sh
```

El detalle de cada stack, cada script y cada parametro esta en
[`aws/README.md`](aws/README.md).

### Como se prueba

* **La API**, con Bruno: `cd bruno && bru run --env aws` (y `--env aws-backoffice` para la gestion)
* **La aplicacion**, en el navegador, abriendo la URL del sitio con DevTools en
  la pestana Network. El recorrido completo esta en `front/README.md` §4.
* **Las colas**, en el panel de RabbitMQ por la VPN (`vpn-up.sh` imprime la URL y las credenciales).

Ningun script prueba la API por su cuenta: los scripts construyen, y la
verificacion se hace con Bruno y con el navegador.

---

## 4. La seguridad, en dos capas

Las filas sin marca estan **medidas** contra el despliegue real. Las marcadas
con ⧗ llegaron con el backend en EC2 y estan **pendientes de medir**: se
deducen del codigo y de los tests, y hay que confirmarlas y quitar la marca.

| Credencial | Por API Gateway | Directo a la EC2 (VPN) | Quien rechaza |
|---|---|---|---|
| sin cabecera `Authorization` | **401** | **401** | authorizer / Spring Security |
| token inventado (`Bearer abc`) | **401** | **401** | firma invalida, en las dos capas |
| **id token** | **401** | **401** | falta el claim `scope` / `token_use != access` |
| access token **sin** ningun scope de la API | **401** | **403** | ver la nota de abajo |
| access token **con** el scope | **200** | **200** | nadie: pasa las dos capas |
| estudiante en `/api/gestion/**` ⧗ | **403** | **403** | **Spring**, RBAC (`hasRole("EMPLEADO")`) |
| empleado (grupo) o backoffice en `/api/gestion/**` ⧗ | **200** | **200** | nadie |
| solicitud de OTRO estudiante ⧗ | **404** | **404** | **Spring**: no confirma que exista |
| id inexistente, token valido | **404** | **404** | **Spring** |
| formulario invalido | **400** | — | **Spring**, Bean Validation |
| libro sin ejemplares | **409** | — | **Spring**, regla de negocio |
| activar avisos sin email verificado (M2M) ⧗ | **409** | — | **Spring** |
| ruta que no existe | **403** | — | API Gateway, *Missing Authentication Token* |

La columna "directo" se midio cuando el backend era una Lambda (con
`aws lambda invoke`); la regla de Spring que la produce no cambio. Con la EC2
se reproduce por la VPN: ver `aws/README.md` §5.

### 401 por API Gateway, 403 desde Spring

Un access token valido pero sin el scope requerido responde **401** a traves de
API Gateway, con el mismo cuerpo generico que un token invalido
(`{"message":"Unauthorized"}`): el authorizer `COGNITO_USER_POOLS` no distingue
autenticacion de autorizacion. Spring si hace esa distincion y responde **403**.

Con RBAC, ese 403 **se ve tambien a traves del gateway**: el token de un
estudiante es valido y trae el scope de la API, asi que el gateway lo deja
pasar, y es Spring quien lo rechaza en `/api/gestion/**`. El entorno `aws` de
Bruno lo comprueba.

### Por que el id token da 401 y no 200

Cuando un metodo declara `AuthorizationScopes`, API Gateway exige que el token
traiga el claim `scope`. Un id token no lo lleva, por diseno de OIDC, asi que
usar el access token es un requisito tecnico y no una recomendacion. El
entorno `aws-id-token` de Bruno verifica este caso.

### Dos scopes, un grupo, y por que

| Literal | Quien lo trae | Para que |
|---|---|---|
| scope `biblioteca-api/acceso` | el SPA (personas) y el cliente M2M | toda la API de estudiante |
| scope `biblioteca-api/gestion` | solo el cliente M2M **backoffice** | ser EMPLEADO sin ser persona (un sistema de inventario; y Bruno) |
| grupo `empleados` | las personas que un administrador agrega | ser EMPLEADO siendo persona |

El gateway acepta cualquiera de los dos scopes en todos los metodos; el rol lo
decide Spring. Cada literal de scope tiene que coincidir **caracter por
caracter** en: el Resource Server y los App Clients de `cognito.yaml`,
`AuthorizationScopes` en `api.yaml`, `SecurityConfig` (por `BIBLIOTECA_SCOPE` y
`BIBLIOTECA_SCOPE_GESTION`) y, el de acceso, la lista de scopes de Amplify. El
nombre del grupo, en `cognito.yaml`, `application.properties` y el frontend.

---

## 5. Frontend

React 19 con React Router 8 (rutas de datos), compilado con Vite, y Amplify
Auth 6. Detalle en [`front/README.md`](front/README.md).

| Pieza | Archivo | Que hace |
|---|---|---|
| Configuracion de Amplify | `front/src/main.tsx` | Se configura **antes** de montar React |
| Cliente HTTP | `front/src/app/auth/http.ts` | Unico punto que llama a la API; anade el `Bearer` a toda peticion |
| Guardas | `front/src/app/auth/guardas.ts` | `loader` de las rutas: `requiereSesion` (privadas) y `requiereEmpleado` (gestion) |
| Servicios | `libros`, `solicitudes`, `avisos`, `gestion` | Encapsulan URL y llamadas; los componentes no saben de HTTP |
| Gestion | `front/src/app/gestion/` | Bandeja (aprobar, rechazar, devolver) y carga de stock |
| Configuracion AWS | `aws-config.ts` | **Generada** por el pipeline; no se versiona |
| Vista de diagnostico | `diagnostico/` | **Opt-in**: solo se empaqueta con `DEBUG=true` (ver abajo) |

### Guarda y cliente HTTP no son lo mismo

Una **guarda** controla la navegacion: que rutas puede abrir el usuario. El
**cliente HTTP** controla las llamadas: que cabecera llevan. Y **ninguno de
los dos protege la API**: los dos corren en el navegador del usuario, que puede
saltarselos con la consola abierta. Si las guardas desaparecieran, la API
seguiria igual de protegida, porque quien protege son API Gateway y Spring
Security.

Lo mismo vale para `requiereEmpleado` y los enlaces *Bandeja* y *Stock*: se
muestran segun el claim `cognito:groups` del access token, pero un estudiante
que forzara la ruta solo veria errores 403 de Spring.

### La vista de diagnostico: opt-in, y apagada por defecto

No es una pantalla de negocio: existe para ver lo que normalmente esta
escondido. Muestra el access token, su payload decodificado (`token_use`,
`client_id`, `scope`) y hace dos llamadas **manuales con `fetch`**, una con la
cabecera puesta a mano y otra sin ninguna. Es redundante respecto del
cliente HTTP: sirve para mostrar de forma explicita la cabecera que
`auth/http.ts` agrega automaticamente en el resto de la aplicacion.

Y precisamente por eso **no se entrega**: ensena una credencial valida en
claro, a la vista de cualquiera que mire la pantalla. Es material para explicar
la arquitectura, no parte del producto. Se enciende a proposito:

```sh
DEBUG=true ./aws/pipeline/publish-web.sh      # publica ademas /diagnostico
./aws/pipeline/publish-web.sh                 # por defecto: DEBUG=false
```

**Apagada no es "escondida": es que no existe.** Lo que la variable genera es
la RUTA (`front/src/app/diagnostico/rutas.ts`), no un `if`. Con `DEBUG=false`
esa lista llega vacia, nadie importa el componente, Vite no lo empaqueta y en
`dist/` no queda ningun chunk que subir al bucket. Medido:

| `DEBUG` | Chunk en `dist/` | Texto de la vista en `dist/` |
|---|---|---|
| `false` | ninguno | cero coincidencias |
| `true` | `assets/diagnostico-*.js` · 3,75 kB | solo en ese chunk |

Un `if` sobre un flag habria dejado el componente compilado y subido al
bucket: sin enlace y sin ruta, pero descargable. El token sigue viviendo en el
navegador (lo guarda Amplify, y con DevTools se ve): lo que se quita es que la
aplicacion lo publique en pantalla por su cuenta.

El token tampoco viaja en el estado compartido de la sesion (`EstadoSesion`
lleva `autenticado`, `usuario` y `empleado`): quien lo necesita se lo pide a
Amplify, y eso ocurre en exactamente dos sitios, `auth/http.ts` y esta vista.
(`refrescar()` mira el payload del access token para saber si hay sesion y si
es empleado, y lo descarta.)

### Por que las rutas llevan `#`

El sitio se sirve por el **endpoint REST** de S3, que devuelve los objetos que
existen y nada mas: con rutas limpias, recargar en `/catalogo` daria 404. El
*website endpoint* de S3 si sabe redirigir a `index.html`, pero es HTTP puro y
Cognito **rechaza callbacks sin TLS**. Con CloudFront delante se podria volver a
rutas limpias; queda como evolucion.

---

## 6. Versionamiento

Las mismas rutas se exponen bajo `/v1/api/...`, con un recurso proxy
`/v1/{proxy+}` en API Gateway y un segundo prefijo en los `@RequestMapping` de
los controladores.

El punto no es la URL: es poder enrutar `/v1` y `/v2` a integraciones distintas
sin romper a los consumidores existentes.

**Cuando subiria a `/v2`:** un cambio **incompatible** del contrato — quitar o
renombrar un campo, cambiar su tipo, volver obligatorio algo que era opcional,
o cambiar el significado de un codigo de respuesta.

**Cuando no:** anadir un campo opcional a la respuesta, un parametro de
busqueda nuevo, o un endpoint nuevo. Eso son cambios compatibles y caben en la
version que ya existe.

**Una excepcion declarada:** `GET /api/solicitudes` sin parametros devolvia
TODAS las solicitudes y ahora devuelve solo las del portador. Es un cambio
incompatible que no paso a `/v2` porque cerrarlo es una correccion de
seguridad (cualquier estudiante veia las solicitudes de los demas), no una
evolucion del contrato. La bandeja completa sigue existiendo, para empleados,
en `/api/gestion/solicitudes`.

---

## 7. Desviaciones declaradas respecto del canon de la guia

Se declaran aqui en lugar de esconderlas.

| La guia dice | Aqui se hizo | Por que |
|---|---|---|
| Backend en **EC2** (`dnf install corretto`, puerto 8888, SG abierto) | **EC2 con Docker, detras de un NLB interno y un VPC Link**, sin ningun puerto abierto a Internet | Se vuelve a EC2 (antes era Lambda) porque RabbitMQ necesita consumidores que vivan siempre, y Lambda solo se dispara desde **Amazon MQ**, que el lab bloquea (`mq:*` con *explicitDeny*, medido). El broker va en la misma EC2. API Gateway llega por VPC Link; la EC2 no acepta nada desde Internet |
| **SG abriendo 8888 a `0.0.0.0/0`** | Entrada solo desde la VPC (NLB) | La propia guia avisa de que no es una arquitectura recomendable |
| Bypass directo a `<EC2_IP>:8888` | Llamada directa a `<IP privada>:8080` **por la VPN** | La segunda capa se demuestra igual (Spring rechaza sin el gateway delante), sin abrir la instancia a Internet |
| **REST API** | **REST API. Se conserva** | Por el authorizer `COGNITO_USER_POOLS`, por los `GatewayResponse` que hacen visible un 401 en el navegador y por el paso explicito de desplegar al stage. (Su VPC Link exige un NLB; el de HTTP API aceptaria un ALB) |
| Consola web de AWS | **AWS CLI v2 + CloudFormation** | Reproducible, versionado e idempotente |
| **Postman** | **Bruno**, versionado en el repositorio | Asserts automaticos, vive en git y corre en terminal |
| Red sin salida a Internet | **La EC2 va en una subred PUBLICA**, con IP publica solo para salir | Necesita salir al SMTP (587; **SES esta bloqueado** en el lab) y a 443 (imagenes, S3, Secrets Manager, SSM, Cognito). Un NAT Gateway cobraria por hora aunque el lab este apagado. Su security group no acepta nada desde Internet. **RDS sigue en subredes privadas sin ruta a Internet** |
| — | **JWKS de Cognito fijado al desplegar** (lo baja `build-backend.sh`) | Viene de la etapa Lambda (sin salida a Internet). Con la EC2 ya no haria falta, pero se mantiene: arranca sin depender de la red. Si Cognito rotara sus claves habria que volver a correr el pipeline |
| — | **Las contrasenas viajan al contenedor como variables de entorno** | La EC2 las lee de Secrets Manager en cada arranque y las deja en un archivo en `/run` (tmpfs, 600). No estan en el UserData ni en la plantilla, pero quien tenga acceso root a la instancia (o `docker inspect`) las ve |
| — | **Correo por un proveedor SMTP externo** (Gmail, Brevo...) | SES tiene *explicitDeny* en el lab. El proveedor se elige al desplegar; su contrasena va a Secrets Manager (parametro `NoEcho`) |
| Un empleado real en las pruebas automaticas | **El cliente M2M `backoffice` hace de empleado en Bruno** | Un token de PERSONA con custom scope solo sale del flujo del navegador (`admin-initiate-auth` no emite custom scopes). El empleado humano (grupo `empleados`) se prueba en el navegador |
| Base sin ninguna via desde fuera | **VPN WireGuard opcional** (`aws/vpn.yaml`), fuera del bootstrap | Para consultar la base con DataGrip o psql y ver el panel de RabbitMQ. Usa la subred publica del proyecto; las subredes de RDS siguen sin ruta a Internet. El servidor solo acepta UDP desde la IP del equipo y solo paquetes firmados con su llave; no tiene SSH ni rol IAM. Se borra con `vpn-down.sh` |
| Frontend en **Angular** | **React 19 + Vite** | Decision del usuario. Mismas vistas, misma integracion con Cognito (Amplify, code + PKCE) y mismo contrato con la API; el interceptor de `HttpClient` pasa a ser un cliente HTTP propio (`auth/http.ts`) y los Guards, `loader` de React Router |
| **Hosted UI** clasica de Cognito | **Managed Login** con marca propia y en castellano | La clasica solo existe en ingles y su logo no se puede poner desde CloudFormation. Exige el plan **Essentials** del User Pool, que ya era el de por defecto (medido) |
| `ng serve` en `localhost:4200` | **Sitio publicado en S3** | Se pidio construir solo el acceso publico: no se entrega ningun entorno de desarrollo. `localhost` no esta en las listas blancas de Cognito ni en el CORS |

---

## 8. Herramientas

| Herramienta | Para que | Comprobar |
|---|---|---|
| AWS CLI v2 | toda la infraestructura | `aws --version` |
| Java 21 | compilar el backend | `java -version` |
| Maven Wrapper | build reproducible, sin instalar Maven | `cd back && ./mvnw -v` |
| Docker | los tests del backend (PostgreSQL y RabbitMQ con Testcontainers) | `docker info` |
| Node.js + npm | compilar el frontend | `node -v` |
| `python3`, `jq` | leer JSON en los scripts | `python3 --version` |
| Bruno (`bru`) | pruebas HTTP versionadas | `bru --version` |
| Navegador con DevTools | diagnostico de CORS y del preflight | — |

Las credenciales del Learner Lab caducan cada ~4 horas y son la causa numero
uno de errores raros. `awsacademy start` las refresca.
