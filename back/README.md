# Backend · biblioteca-backend

API de la Biblioteca Duoc. **Spring Boot 4.1 sobre Java 21**, compilada con el
Maven Wrapper (no hace falta tener Maven instalado).

El artefacto es un **fat jar** que corre como un proceso que vive siempre, en
un contenedor de la EC2 de `aws/backend.yaml`, junto al broker **RabbitMQ**:

```sh
java -jar target/biblioteca-backend-0.0.1-SNAPSHOT.jar
```

En ese mismo proceso conviven tres cosas:

* la **API REST** que recibe API Gateway (por el NLB, puerto 8080);
* los **consumidores de RabbitMQ** (`@RabbitListener`), que necesitan una
  conexion abierta todo el tiempo;
* dos **tareas periodicas** (`@Scheduled`): el relay de la bandeja de salida y
  el programador de recordatorios.

Una Lambda no sirve para lo segundo ni para lo tercero: se congela entre
peticiones, y Lambda solo se dispara desde colas de **Amazon MQ**, que el
Learner Lab no permite (medido: `mq:*` con *explicitDeny*).

---

## 1. Estructura

```
src/main/java/cl/duoc/biblioteca/
├── BibliotecaApplication.java      @EnableScheduling
├── catalogo/
│   ├── Libro.java                  record inmutable; "disponible" es calculado
│   ├── CatalogoLibros.java         tabla libro; descontar/sumar stock con UPDATE condicional
│   ├── ServicioInventario.java     carga y reposicion de stock; publica stock.repuesto (0 -> n)
│   ├── SuscripcionesLibro.java     "avisame cuando vuelva" (tabla suscripcion_libro)
│   ├── LibroController.java        GET /api/libros, /generos, /{id}
│   ├── AvisameController.java      GET /api/libros/avisame, POST|DELETE /api/libros/{id}/avisame
│   └── GestionStockController.java POST /api/gestion/libros/{id}/stock          (EMPLEADO)
├── solicitudes/
│   ├── Sede.java  EstadoSolicitud.java                RECIBIDA, APROBADA, RECHAZADA, DEVUELTA
│   ├── ItemSolicitado.java  NuevaSolicitud.java       DTO de ENTRADA, con validaciones
│   ├── LineaSolicitud.java  Solicitud.java            DTO de SALIDA
│   ├── RepositorioSolicitudes.java transiciones condicionales (UPDATE ... WHERE estado = ...)
│   ├── ServicioPrestamos.java      crear/aprobar/rechazar/devolver + su evento, en UNA transaccion
│   ├── ProgramadorRecordatorios.java  @Scheduled: publica recordatorio.vence
│   ├── SolicitudController.java    POST /api/solicitudes, GET las propias
│   └── GestionSolicitudController.java  /api/gestion/solicitudes/**              (EMPLEADO)
├── eventos/
│   ├── Evento.java  TiposEvento.java   el sobre de todos los eventos y sus routing keys
│   ├── BandejaDeSalida.java        outbox: el evento se escribe en la transaccion del negocio
│   ├── RelayEventos.java           @Scheduled: publica la bandeja con publisher confirms
│   └── TopologiaRabbit.java        exchanges, colas, DLX, TTL y fabricas de listeners (ACK manual)
├── avisos/
│   ├── ContactoController.java     GET|PUT|DELETE /api/avisos/correo (el consentimiento)
│   ├── DirectorioUsuarios.java  DirectorioCognito.java   email verificado por sub (ListUsers)
│   ├── ContactosAviso.java  CorreosEnviados.java         tablas contacto_aviso y correo_enviado
│   ├── ConsumidorPrestamos.java    notif.prestamos      -> un correo transaccional
│   ├── ConsumidorDisponibilidad.java notif.disponibilidad -> un correo masivo por interesado
│   ├── RedactorCorreos.java  Correo.java    el texto de cada correo
│   ├── PublicadorCorreo.java       exchange direct "correo" (carriles transaccional / masivo)
│   ├── EnviadorSmtp.java           el unico que habla con el SMTP: reintento con espera y DLQ
│   └── LectorEventos.java  MensajeInvalido.java   lo ilegible va a la DLQ, sin reintentar
├── bitacora/
│   ├── ConsumidorBitacora.java     cola "bitacora" (binding #): copia de TODO evento
│   ├── Bitacora.java  BitacoraController.java     GET /api/gestion/bitacora    (EMPLEADO)
├── web/
│   ├── SaludController.java        GET /health, el health check del NLB
│   └── ManejadorDeErrores.java     convierte los fallos de validacion en 400 utiles
└── config/
    ├── SecurityConfig.java         segunda validacion del JWT, RBAC (rol EMPLEADO) y CORS
    └── Roles.java
src/main/resources/
├── application.properties
└── db/migration/                   las aplica Flyway al arrancar, como usuario maestro
    ├── V1__esquema.sql             tablas, CHECKs, secuencia y el rol biblioteca_app
    ├── V2__catalogo_inicial.sql    SEED: los 20 libros del catalogo
    └── V3__prestamos_y_avisos.sql  ciclo de vida, contactos, suscripciones, outbox, bitacora
src/test/java/cl/duoc/biblioteca/
├── ServiciosDePrueba.java          PostgreSQL y RabbitMQ en Docker, GreenMail, Cognito falso
├── Tokens.java                     tokens simulados por actor, con el convertidor REAL de roles
└── avisos/AvisosIntegracionTest.java  las historias de usuario de punta a punta
```

---

## 2. Compilar y probar

```sh
cd back
./mvnw package          # compila y pasa los 34 tests
./mvnw test
./mvnw test -Dtest=AvisosIntegracionTest
```

**Hace falta Docker**: los tests levantan un **PostgreSQL 16** y un **RabbitMQ
4.1** reales con Testcontainers, y un servidor SMTP en memoria (**GreenMail**).
A la base le aplican las mismas migraciones que en AWS y reproducen la
separacion de usuarios: Flyway entra como superusuario y la aplicacion como
`biblioteca_app`, asi que si una consulta necesitara un permiso que ese rol no
tiene, el test falla aqui y no en el despliegue. La topologia de RabbitMQ la
declara la propia aplicacion, igual que en la EC2.

La autenticacion se **simula** con el post-processor `jwt()` de
`spring-security-test`, pero pasando las claims por el **mismo convertidor de
autoridades** que usa la aplicacion (`Tokens.java`): lo que se prueba es la
regla real de quien es empleado, no una lista de authorities escrita a mano.

`AvisosIntegracionTest` recorre las historias de punta a punta (peticion HTTP →
bandeja de salida → RabbitMQ → consumidor → correo en GreenMail), y tambien los
fallos: SMTP parado (tres vueltas por `correo.espera` y a `correo.fallidos`),
SMTP que vuelve a tiempo, direccion invalida (directo a la DLQ), evento con JSON
roto (NACK sin requeue a `eventos.fallidos`).

### Configuracion que recibe

Nada de esto esta escrito en el repositorio. En AWS, la configuracion no
secreta sale del parametro de SSM `/<proyecto>/backend/app.env` y las
contrasenas de Secrets Manager; el script de arranque de la EC2 las junta en el
archivo de entorno del contenedor (ver `aws/backend.yaml`).

| Variable | Que es |
|---|---|
| `BIBLIOTECA_ISSUER` | Emisor exigido en el claim `iss` (Cognito) |
| `BIBLIOTECA_JWKS` | Claves publicas de Cognito en JSON; las baja `build-backend.sh` |
| `BIBLIOTECA_SCOPE` | Scope de acceso (`biblioteca-api/acceso`) |
| `BIBLIOTECA_SCOPE_GESTION` | Scope del backoffice (`biblioteca-api/gestion`): quien lo trae es EMPLEADO |
| `BIBLIOTECA_USER_POOL_ID` | Pool donde se lee el email verificado al activar avisos |
| `BIBLIOTECA_ORIGEN` | Origen del frontend, para el CORS |
| `BIBLIOTECA_RECORDATORIO_ANTELACION` | Cuanto antes del vencimiento se recuerda (`PT24H`) |
| `SPRING_DATASOURCE_URL` | JDBC a RDS con `sslmode=verify-full` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | Rol `biblioteca_app` |
| `SPRING_FLYWAY_USER` / `_PASSWORD` | Usuario maestro, solo para las migraciones |
| `BIBLIOTECA_APP_PASSWORD` | La de `biblioteca_app`, para que V1 cree el rol |
| `RABBITMQ_HOST` / `_USER` / `_PASSWORD` | El broker, en el contenedor vecino (`rabbitmq`) |
| `SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_FROM` | El proveedor de correo (587 + STARTTLS) |

Las credenciales de AWS (para `ListUsers` de Cognito) no son variables: el SDK
las toma del perfil de instancia `LabInstanceProfile`.

---

## 3. Historias de usuario y sus caminos tristes

Cada fila esta cubierta por un test de `AvisosIntegracionTest` (o de los
controladores).

| Historia | Camino feliz | Caminos tristes |
|---|---|---|
| **H0** activar avisos | PUT → 200 con el email de Cognito → correo de bienvenida | sin activar: ningun correo, todo lo demas funciona · cliente M2M: 409 · desactivar: los mensajes en vuelo se descartan con ACK |
| **H1** comprobante | 201 inmediato → correo "Recibimos tu solicitud" | SMTP caido: 201 igual, el correo llega al volver · reintentos agotados: `correo.fallidos` · mensaje corrupto: a la DLQ sin reintentar |
| **H2** aprobar / rechazar | 200 → stock descontado, `venceEn` fijado → correo | ya resuelta: 409 · stock agotado entre pedir y aprobar: 409, sigue RECIBIDA · rechazo sin motivo: 400 · estudiante: 403 |
| **H3** recordatorio | `antelacion` antes de `venceEn` → un correo | devuelto antes: nada · ya enviado: no se repite · prestamo mas corto que la antelacion: sin recordatorio |
| **H4** devolucion | 200 → stock repuesto → correo; si estaba en 0, H6 | no estaba APROBADA: 409 · no existe: 404 |
| **H5** avisame | 201 (idempotente); al volver el libro, correo y suscripcion cerrada | el libro tiene stock: 409 · no existe: 404 · baja: 204 |
| **H6** cargar stock | 200 → si pasa de 0 a n, un correo por interesado (carril masivo) | cantidad fuera de 1..100: 400 · no existe: 404 |
| **H7** bitacora | GET → que paso, quien y cuando | estudiante: 403 |

---

## 4. Decisiones que conviene entender

**Bandeja de salida (outbox), no "guardar y publicar".** Aprobar son dos
escrituras en dos sistemas: el `UPDATE` en PostgreSQL y el mensaje en RabbitMQ.
Hechas por separado, tarde o temprano una sale bien y la otra no. El evento se
escribe en `evento_pendiente` **en la misma transaccion** que el cambio, y
`RelayEventos` lo publica despues con **publisher confirms**: solo borra la
fila cuando el broker confirmo. Lo peor que puede pasar es un evento
**duplicado**, nunca uno perdido; por eso todos los consumidores son
idempotentes (`correo_enviado`, `bitacora.evento_id`, transiciones
condicionales).

**ACK manual, y tres salidas por mensaje.** Los listeners usan
`AcknowledgeMode.MANUAL` y deciden en cada rama:

| Salida | Cuando | Efecto |
|---|---|---|
| `basicAck` | procesado (con correo encolado, o sin nada que hacer) | el broker lo borra |
| `basicNack(requeue=false)` | el mensaje es invalido (`MensajeInvalido`) | va al DLX → DLQ, sin reintentos inutiles |
| `basicNack(requeue=true)` | fallo del entorno (base caida) | vuelve a la cola; la cola **quorum** cuenta entregas y a la 5.ª lo aparta sola |

**Dos estrategias de reintento, a proposito.** Los consumidores de eventos
fallan por causas internas y breves: reintento inmediato (requeue) con limite
de entregas. El SMTP falla por el proveedor, que suele necesitar minutos:
`EnviadorSmtp` hace NACK **sin** requeue, la cola manda el mensaje por el DLX
`correo.reintento` (fanout) a `correo.espera`, que nadie consume; al vencer su
**TTL de cola** el broker lo devuelve a su cola de origen con su routing key
original. Un reintento con espera sin un solo temporizador en Java. El header
`x-death` cuenta las vueltas; con `max-intentos` gastados, a `correo.fallidos`
con el motivo en `x-motivo-fallo`.

**El recordatorio es un `@Scheduled`, no un mensaje con TTL.** RabbitMQ solo
expira mensajes en la **cabeza** de la cola: un prestamo de 30 dias delante de
uno de 1 dia bloquearia al corto. Y un recordatorio debe poder **anularse** si
el libro se devuelve, cosa imposible con un mensaje ya publicado. El cuando lo
decide una consulta (`UPDATE ... RETURNING`, atomica); RabbitMQ entra despues
para repartir y reintentar. El TTL si se usa donde todos esperan lo mismo:
`correo.espera`.

**Dos carriles de correo.** Un libro repuesto con 200 interesados son 200
correos de golpe. Van por `correo.masivo` (un consumidor, prefetch 1); los
comprobantes y resoluciones por `correo.transaccional` (dos consumidores,
prefetch 5). Los primeros nunca retrasan a los segundos.

**El email sale de Cognito, no del cliente.** El access token no trae `email`.
`DirectorioCognito` lo pide con `cognito-idp:ListUsers` filtrando por el `sub`
del token ya validado, y solo lo acepta si esta verificado. Si viniera del
cuerpo, cualquiera podria mandar avisos al correo de otra persona.

**RBAC por grupo, no por scope.** Los scopes se asignan por App Client: todas
las personas que entran por el SPA reciben los mismos. El rol de una persona es
el grupo de Cognito `empleados` (claim `cognito:groups`); `SecurityConfig` lo
convierte en `ROLE_EMPLEADO`, y tambien se lo da a quien traiga el scope
`gestion` (el cliente M2M backoffice). La regla vive en la ruta
(`/api/gestion/**` → `hasRole("EMPLEADO")`), no en cada controlador: una ruta
nueva bajo `/gestion` nace protegida.

**Una solicitud ajena responde 404, no 403.** Un 403 confirmaria que ese id
existe, y los ids son correlativos faciles de recorrer.

**PostgreSQL con JDBC, sin JPA.** `JdbcClient` sobre records inmutables.
Todos los filtros van como parametros ligados, nunca concatenados: la
inyeccion SQL no tiene por donde entrar. El stock se descuenta con
`UPDATE ... SET ejemplares = ejemplares - n WHERE ejemplares >= n`: la
condicion la evalua Postgres con la fila bloqueada, y dos aprobaciones
simultaneas del ultimo ejemplar no pueden ganar las dos.

**El seed es una migracion.** `V2__catalogo_inicial.sql` contiene los 20 libros.
Las migraciones aplicadas **no se editan**: todo cambio es una migracion nueva
(V3 trajo el ciclo de vida y los avisos).

**Dos usuarios de base de datos.** Flyway usa el maestro, solo al arrancar. La
aplicacion atiende como `biblioteca_app`: `SELECT`/`INSERT`, `UPDATE` solo de
las columnas que cambian de verdad (stock y ciclo de vida) y `DELETE` solo
donde borrar es parte del negocio (darse de baja, vaciar la bandeja de salida).
Ni DDL, ni reescribir lo que el estudiante pidio, ni tocar la bitacora.

**El JWKS llega embebido.** Viene de cuando el backend era una Lambda sin
salida a Internet. Se mantiene: arranca sin depender de la red y fija las
claves aceptadas. Si Cognito las rotara, todo token daria 401 hasta volver a
correr `build-backend.sh`.

**El solicitante sale del token.** `Principal.getName()`, que con Spring
Security es el claim `sub` (una persona) o el `client_id` (un cliente M2M).

**Dos prefijos por controlador.** `@RequestMapping({"/api/libros",
"/v1/api/libros"})`: en API Gateway `/v1` es un unico recurso proxy, asi que el
enrutado fino lo hace Spring.

**`/health` es la unica ruta publica.** Es el health check del NLB: solo le
manda trafico a la EC2 mientras responde 2xx. No se publica en API Gateway y
no revela nada.

**El dispatcher `ERROR` esta permitido, y hace falta.** Sin
`.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`, el reenvio interno
a `/error` cae en el `denyAll()` final y **todo 404 y 409 sale convertido en un
403 con cuerpo vacio**. MockMvc no reproduce ese reenvio: los tests pasaban.

**El CORS lo pone Spring en las respuestas reales.** Con integracion
`HTTP_PROXY`, API Gateway devuelve lo que diga la EC2. El preflight `OPTIONS`
lo responde API Gateway (MOCK), la cabecera del 200 la pone Spring, y los
errores del propio gateway los cubren los `GatewayResponses`. Spring admite
`GET`, `POST`, `PUT` y `DELETE`.

---

## 5. Codigos de respuesta, y quien los produce

| Codigo | Cuando | Quien |
|---|---|---|
| 200 / 201 / 204 | todo correcto | el controlador |
| 400 | el formulario no valida, o el JSON no se puede leer | Bean Validation → `ManejadorDeErrores` |
| 401 | sin token, token invalido, caducado, un id token, o un token valido **sin** ningun scope de la API | API Gateway **y** Spring Security |
| 403 | un NO empleado en `/api/gestion/**` (tambien a traves del gateway) | **Spring Security** (`hasRole`) |
| 403 | token valido sin el scope, llamando a la EC2 **sin** pasar por el gateway | **Spring Security** (`hasAnyAuthority`); por el gateway ese caso es 401 |
| 404 | el id no existe, o la solicitud es de otro estudiante | **Spring** (`ResponseStatusException`) |
| 409 | sin ejemplares; transicion desde un estado que no corresponde; activar avisos sin email verificado; avisame de un libro con stock | el servicio o el controlador |

El 404 es util como diagnostico: API Gateway no puede producirlo (una ruta
desconocida la contesta con 403), asi que recibirlo demuestra que la peticion
atraveso el authorizer, el VPC Link y llego al controlador.

---

## 6. Si algo falla en RabbitMQ

| Sintoma | Causa | Solucion |
|---|---|---|
| La app no arranca: `PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'` | Cambiaste `biblioteca.correo.espera-reintento` (u otro argumento de cola) con la cola ya creada | Borra esa cola desde el panel y reinicia la app: la vuelve a declarar |
| Los correos no llegan y `correo.fallidos` crece | El SMTP rechaza (credenciales, remitente no verificado) | Lee el header `x-motivo-fallo` en el panel; corrige y mueve los mensajes de vuelta a `correo.transaccional` (*Move messages*) |
| `eventos.fallidos` tiene mensajes | Un evento que ningun consumidor pudo procesar | Inspeccionalo en el panel; si era un fallo del entorno, muevelo de vuelta |
| Una solicitud creada no genera correo | Avisos no activados, o la bandeja de salida no se vacia (broker caido) | `GET /api/avisos/correo`; mira los logs de `RelayEventos` |
