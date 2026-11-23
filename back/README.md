# Backend · biblioteca-backend

API de la Biblioteca Duoc. **Spring Boot 4.1 sobre Java 21**, compilada con el
Maven Wrapper (no hace falta tener Maven instalado). Es uno de los modulos del
build multi-modulo de la raiz (`pom.xml`): junto a `comun/` (validacion del
JWT, roles, errores), `admin-rabbit/` (el administrador de RabbitMQ) y
`admin-kafka/` (el administrador de Kafka), los otros dos microservicios.

El artefacto es un **fat jar** que corre como un proceso que vive siempre, en
un contenedor de la EC2 de `aws/backend.yaml`, junto al **cluster RabbitMQ**
de dos nodos y al **cluster Kafka** de tres brokers (`aws/backend/*.compose.yaml`):

```sh
java -jar back/target/biblioteca-backend-0.0.1-SNAPSHOT.jar
```

En ese mismo proceso conviven tres cosas:

* la **API REST** que recibe API Gateway (por el NLB, puerto 8080);
* los **consumidores de RabbitMQ** (`@RabbitListener`) y de **Kafka**
  (`@KafkaListener`), que necesitan una conexion abierta todo el tiempo;
* las **tareas periodicas** (`@Scheduled`): los relays de las dos bandejas de
  salida (RabbitMQ y Kafka), el programador de recordatorios y la creacion
  diferida de los topicos.

Una Lambda no sirve para lo segundo ni para lo tercero: se congela entre
peticiones, y Lambda solo se dispara desde colas de **Amazon MQ**, que el
Learner Lab no permite (medido: `mq:*` con *explicitDeny*).

---

## 1. Estructura

```
src/main/java/cl/duoc/biblioteca/
├── BibliotecaApplication.java      @EnableScheduling
├── catalogo/
│   ├── Libro.java                  record inmutable y entidad (tabla libro); "disponible" es calculado
│   ├── RepositorioLibros.java      Spring Data JDBC: busqueda unaccent, descontar/sumar atomicos
│   ├── CatalogoLibros.java         LECTURA del catalogo: buscar, generos, existente (404)
│   ├── ServicioInventario.java     TODO cambio de stock; publica stock.repuesto (0 -> n) y un CambioStock (Kafka)
│   ├── ProyeccionStock.java        el stock en vivo (H8), en memoria, alimentado por catalogo-vivo
│   ├── SuscripcionLibro.java  RepositorioSuscripciones.java   tabla suscripcion_libro (clave compuesta)
│   ├── ServicioAvisame.java        reglas del "avisame cuando vuelva" (404, 409)
│   ├── LibroController.java        GET /api/libros, /generos, /stock-vivo, /{id}
│   ├── AvisameController.java      GET /api/libros/avisame, POST|DELETE /api/libros/{id}/avisame
│   └── GestionStockController.java POST /api/gestion/libros/{id}/stock          (EMPLEADO)
├── solicitudes/
│   ├── Sede.java  EstadoSolicitud.java                RECIBIDA, APROBADA, RECHAZADA, DEVUELTA
│   ├── ItemSolicitado.java  NuevaSolicitud.java       DTO de ENTRADA, con validaciones
│   ├── LineaSolicitud.java  Solicitud.java            el AGREGADO (y lo que devuelve la API)
│   ├── RepositorioSolicitudes.java altas, findById y transiciones condicionales (UPDATE ... WHERE estado = ...)
│   ├── ListadoSolicitudes.java     modelo de LECTURA de los listados: 2 sentencias, sin N+1
│   ├── GeneradorIdSolicitud.java   callback de Spring Data: el id SOL-0001 de la secuencia
│   ├── ServicioPrestamos.java      crear/aprobar/rechazar/devolver + su evento y su hecho Kafka, en UNA transaccion
│   ├── ProyeccionBandeja.java      las pendientes en vivo (H9), en memoria, alimentada por bandeja-gestion
│   ├── BandejaVivoController.java  GET /api/gestion/bandeja-vivo                 (EMPLEADO)
│   ├── ProgramadorRecordatorios.java  @Scheduled: publica recordatorio.vence
│   ├── SolicitudController.java    POST /api/solicitudes, GET las propias
│   └── GestionSolicitudController.java  /api/gestion/solicitudes/**              (EMPLEADO)
├── eventos/
│   ├── Evento.java  TiposEvento.java   el sobre de todos los eventos y sus routing keys
│   ├── BandejaDeSalida.java        outbox: el evento se escribe en la transaccion del negocio
│   ├── EventoPendiente.java  RepositorioEventos.java   tabla evento_pendiente
│   ├── RelayEventos.java           @Scheduled: publica la bandeja con publisher confirms, en orden
│   ├── ProcesadorDeEventos.java    la politica ACK/NACK/DLQ de TODOS los consumidores (Estrategia)
│   ├── LectorEventos.java  MensajeInvalido.java   lo ilegible va a la DLQ, sin reintentar
│   ├── Entregas.java               x-delivery-count: reconoce el ultimo intento antes de la DLQ
│   └── TopologiaRabbit.java        exchanges, colas, una DLQ por cola, TTL y fabricas de listeners (ACK manual)
├── streaming/                      KAFKA (todo lo que sabe de topicos, particiones y offsets)
│   ├── TopologiaKafka.java  PropiedadesKafka.java   los topicos (3x3, cleanup y retencion por flujo) y los grupos
│   ├── CambioStock.java  HechoSolicitud.java   los DTO de cada topico
│   ├── BandejaKafka.java           el productor visto desde el negocio: escribe en evento_kafka (MANDATORY)
│   ├── RegistroKafka.java  RepositorioRegistrosKafka.java   tabla evento_kafka (V4)
│   ├── RelayKafka.java             @Scheduled: envia la bandeja con acks=all y clave por entidad
│   ├── AseguradorTopicos.java      reintenta crear los topicos si los brokers aun no estaban
│   ├── ConfigKafka.java            @Valid en los listeners, reintentos y DLT (DefaultErrorHandler)
│   ├── ConsumidorCatalogoVivo.java   grupo catalogo-vivo   -> ProyeccionStock
│   ├── ConsumidorBandejaGestion.java grupo bandeja-gestion -> ProyeccionBandeja
│   └── ConsumidorAuditoria.java    grupo auditoria-kafka: lee los dos topicos y los deja en el log
├── avisos/
│   ├── ContactoController.java  ServicioAvisos.java   GET|PUT|DELETE /api/avisos/correo (el consentimiento)
│   ├── DirectorioUsuarios.java     puerto: email verificado por sub
│   ├── DirectorioCognito.java  ConfigCognito.java     adaptador ListUsers (timeouts, 503 sin filtrar AWS)
│   ├── ContactoAviso.java  RepositorioContactos.java  tabla contacto_aviso
│   ├── CorreoEnviado.java  RepositorioCorreosEnviados.java   tabla correo_enviado (idempotencia)
│   ├── ConsumidorPrestamos.java    notif.prestamos      -> un correo transaccional
│   ├── ConsumidorDisponibilidad.java notif.disponibilidad -> un correo masivo por interesado
│   ├── RedactorCorreos.java  Correo.java    el texto de cada correo
│   ├── PublicadorCorreo.java       exchange direct "correo" (carriles transaccional / masivo)
│   └── EnviadorSmtp.java           el unico que habla con el SMTP: reintento con espera y DLQ
├── bitacora/
│   ├── RegistroBitacora.java  RepositorioBitacora.java   tabla bitacora (solo INSERT)
│   ├── ConsumidorBitacora.java     cola "bitacora" (binding #): copia de TODO evento
│   ├── Bitacora.java  BitacoraController.java     GET /api/gestion/bitacora    (EMPLEADO)
├── web/
│   └── SaludController.java        GET /health, el health check del NLB
└── config/
    └── SecurityConfig.java         reglas de ruta (RBAC: rol EMPLEADO) y CORS. El
                                    decodificador del JWT, el rol, ManejadorDeErrores,
                                    AtributosDeError y las excepciones de dominio
                                    estan en ../comun (cl.duoc.biblioteca.comun)
src/main/resources/
├── application.properties
└── db/migration/                   las aplica Flyway al arrancar, como usuario maestro
    ├── V1__esquema.sql             tablas, CHECKs, secuencia y el rol biblioteca_app
    ├── V2__catalogo_inicial.sql    SEED: los 20 libros del catalogo
    ├── V3__prestamos_y_avisos.sql  ciclo de vida, contactos, suscripciones, outbox, bitacora
    └── V4__bandeja_kafka.sql       la bandeja de salida de Kafka (evento_kafka)
src/test/java/cl/duoc/biblioteca/
├── ServiciosDePrueba.java          PostgreSQL, RabbitMQ y Kafka en Docker, GreenMail, Cognito falso
├── Tokens.java                     tokens simulados por actor, con el convertidor REAL de roles
├── avisos/AvisosIntegracionTest.java  las historias de usuario de punta a punta
├── streaming/StreamingIntegracionTest.java  H8 y H9 por Kafka, y la DLT
└── eventos/EntregasTest.java       cuando un NACK con requeue ya va a la DLQ
```

---

## 2. Compilar y probar

```sh
# desde la RAIZ del repositorio (el wrapper y el pom padre estan ahi)
./mvnw package                       # los tres modulos: 49 tests (36 de este)
./mvnw -pl back -am package          # solo este modulo y comun
./mvnw -pl back -am test -Dtest=AvisosIntegracionTest -Dsurefire.failIfNoSpecifiedTests=false
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
roto (NACK sin requeue a la DLQ de CADA cola que lo recibio: `notif.prestamos.dlq`
y `bitacora.dlq`).

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
| `RABBITMQ_ADDRESSES` / `_USER` / `_PASSWORD` | Los dos nodos del cluster (`rabbitmq-1:5672,rabbitmq-2:5672`), en la red de compose |
| `KAFKA_BROKERS` | Los tres brokers (`kafka-1:9092,kafka-2:9092,kafka-3:9092`), en la red de compose |
| `SMTP_HOST` / `_PORT` / `_USER` / `_PASSWORD` / `_FROM` | El proveedor de correo (587 + STARTTLS) |

Las credenciales de AWS (para `ListUsers` de Cognito) no son variables: el SDK
las toma del perfil de instancia `LabInstanceProfile`.

---

## 3. Historias de usuario y sus caminos tristes

Cada fila esta cubierta por un test de `AvisosIntegracionTest`,
`StreamingIntegracionTest` (H8, H9) o de los controladores.

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
| **H8** stock en vivo | aprobar, devolver o cargar stock → en 1-2 s `GET /api/libros/stock-vivo` trae el stock nuevo y el catalogo lo muestra sin recargar | Kafka caido: la API y los correos siguen, el registro espera en `evento_kafka` · registro ilegible o DTO invalido: a `biblioteca.inventario.dlt`, el consumidor sigue |
| **H9** bandeja en vivo | crear una solicitud → aparece en `GET /api/gestion/bandeja-vivo` y el contador de su sede sube; aprobarla o rechazarla la saca | estudiante: 403 · registro invalido: a `biblioteca.solicitudes.dlt` |

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
| `basicNack(requeue=true)` | fallo del entorno (base caida) | vuelve a la cola; la cola **quorum** cuenta las devoluciones (`x-delivery-count`) y, pasado `x-delivery-limit` (5), la aparta sola en su DLQ. En el ultimo intento el consumidor lo registra con `log.error` (`Entregas.esUltima`): sin eso, lo que aparta el broker no dejaria rastro en los logs |

**Una DLQ por cola de eventos.** Las tres colas de eventos mueren en el mismo
exchange direct `eventos.dlx`, pero cada una con SU nombre como
`x-dead-letter-routing-key`, y cada routing key lleva a su propia DLQ:
`notif.prestamos.dlq`, `notif.disponibilidad.dlq`, `bitacora.dlq`. En el panel
se ve de un vistazo QUE consumidor fallo. El correo conserva su esquema
propio (reintento con espera y `correo.fallidos`, abajo).

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

**PostgreSQL con Spring Data JDBC, sin JPA.** Los repositorios son
*interfaces* (`RepositorioLibros`, `RepositorioSolicitudes`...) y Spring genera
la implementacion: las lecturas y las altas salen del nombre del metodo
(`findById`, `existsById`, `findBySolicitante`, `save`), sin SQL ni `RowMapper`
a mano. El modelo sigue siendo de records inmutables, y no hay Hibernate (ni
sesion, ni lazy loading, ni el arranque lento que motivo no usar JPA).

Reglas que sostienen el diseno:

* **Cada repositorio extiende `Repository`, no `CrudRepository`**, y declara
  solo lo que los permisos de `biblioteca_app` permiten (Segregacion de
  Interfaces). Un `save()` de una solicitud existente haria `UPDATE` de todas
  las columnas y borraria y reinsertaria sus lineas: ni se ofrece, y la base lo
  rechazaria igual.
* **`@Query` solo donde la sentencia es la regla de negocio:** las transiciones
  condicionales (`UPDATE ... WHERE estado = 'RECIBIDA'`), el descuento atomico
  de stock (`... WHERE ejemplares >= n`, evaluado con la fila bloqueada), los
  `INSERT ... ON CONFLICT` idempotentes, `DELETE ... RETURNING` del relay y la
  busqueda con `unaccent`. Todo va con parametros ligados, nunca concatenado.
* **Ids que no genera la base**: una solicitud nueva llega con `id` null (asi
  Spring Data sabe que es un `INSERT`) y `GeneradorIdSolicitud` le asigna
  `SOL-0001` justo antes de guardarla. Las filas con UUID propio
  (`EventoPendiente`) implementan `Persistable` con `isNew() = true`.
* **Listados sin N+1.** Leer N agregados `Solicitud` con una consulta derivada
  cuesta 1 + N sentencias (las lineas de cada una por separado). El *single
  query loading* de Spring Data JDBC 4.1.1 se probo y se descarto: arma mal
  los agregados cuando Postgres cambia de plan. Los listados van por
  `ListadoSolicitudes`, un modelo de lectura con otra entidad (la cabecera, sin
  lineas) sobre la misma tabla: 2 sentencias sea cual sea el tamano, medido en
  `RepositorioSolicitudesTest`.

**Errores de dominio, no de HTTP.** Los servicios lanzan `RecursoNoEncontrado`
(404), `ConflictoDeNegocio` (409) y `DependenciaNoDisponible` (503), del modulo
comun, con `@ResponseStatus`: el cuerpo de la respuesta es el de siempre de
Spring (`message`, que leen el frontend y Bruno). En un **5xx** el mensaje se
reemplaza por uno generico (`AtributosDeError`): antes un fallo de SQL o de AWS
devolvia su texto al cliente (consulta, tablas, ARN del rol).

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

### Kafka: streaming al lado de RabbitMQ, no en su lugar

**Que va por cada broker.** RabbitMQ lleva TAREAS (un correo que alguien tiene
que mandar una vez); el mensaje desaparece al confirmarse. Kafka lleva HECHOS
(el stock de L-005 quedo en 0; llego SOL-0042) que varios grupos leen, cada
uno con sus offsets, y que se pueden releer. Las dos vistas en vivo (H8, H9)
necesitan eso: reconstruirse desde el principio al arrancar y no quitarle el
registro a nadie.

**Una bandeja de salida propia (V4), no la de RabbitMQ.** Mismo patron
outbox: el registro se escribe en `evento_kafka` en la transaccion del cambio
(`BandejaKafka`, `MANDATORY`) y `RelayKafka` lo envia con `acks=all` y lo borra
al confirmarse. Pero en otra tabla, con otro relay y otro hilo del planificador
(`spring.task.scheduling.pool.size`): con una sola, un Kafka caido haria
rollback del lote y frenaria los correos. La pauta pide que Kafka complemente
**sin afectar** a RabbitMQ; por la misma razon la API no espera a los brokers
al arrancar (`AseguradorTopicos` crea los topicos cuando respondan).

**DTO tipados, uno por topico.** `CambioStock` lleva el stock RESULTANTE y no
la diferencia: el topico se puede compactar (basta el ultimo de cada libro) y
aplicar dos veces el mismo registro da lo mismo. `HechoSolicitud` lleva lo justo
para dibujar la bandeja sin consultar la base, y nada personal (ni `sub` ni
email). Viajan como JSON con la cabecera de tipo corta (`cambioStock`,
`hechoSolicitud`), no el nombre de la clase Java.

**Topicos ajustados a su flujo** (`TopologiaKafka`, valores en
`application.properties`):

| Topico | Clave | Part. x repl. | Cleanup | Retencion | Por que |
|---|---|---|---|---|---|
| `biblioteca.inventario` | id del libro | 3 x 3 | `compact` | el ultimo por clave, siempre | es ESTADO: un lector nuevo solo necesita el ultimo stock de cada libro |
| `biblioteca.solicitudes` | id de la solicitud | 3 x 3 | `delete` | 30 dias | es HISTORIA: cubre un prestamo largo y deja reconstruir la bandeja |
| `*.dlt` | la original | 3 x 3 | `delete` | 14 dias | tiempo para revisar lo que fallo |

Todos con `min.insync.replicas=2`: con `acks=all`, una escritura confirmada
esta en dos brokers como minimo. La clave por entidad es lo que permite 3
particiones sin desordenar: todo lo de L-005 cae en la misma particion.

**Consumer groups, uno por responsabilidad:**

| Grupo | Topico | Hilos | Hace | Al arrancar |
|---|---|---|---|---|
| `catalogo-vivo` | inventario | 3 | `ProyeccionStock` (H8) | relee desde el offset 0 (`seekToBeginning`) |
| `bandeja-gestion` | solicitudes | 3 | `ProyeccionBandeja` (H9) | relee desde el offset 0 |
| `auditoria-kafka` | los dos | 1 | deja cada registro en el log | sigue desde su ultimo offset confirmado |

Dentro de un grupo, `concurrency = 3` pone tres consumidores y Kafka da una
particion a cada uno (balanceo). Entre grupos, cada uno lee TODO con sus
propios offsets (reparto de responsabilidades). Las proyecciones viven en
memoria porque hay una sola instancia de la API; con varias, cada una
necesitaria su propio grupo.

**Errores** (`ConfigKafka`): el valor pasa por `ErrorHandlingDeserializer`,
asi que un registro ilegible no rompe el bucle del consumidor. Ilegible, DTO
que no pasa `@Valid` o `MensajeInvalido` → directo a `<topico>.dlt`; cualquier
otro fallo → 3 reintentos (0,5 s, 1 s, 2 s) y luego a la DLT. Siempre con
`log.error` (topico, particion, offset, clave, motivo) y la cabecera
`kafka_dlt-original-consumer-group` en el registro muerto. El consumidor sigue
con el siguiente: un registro envenenado no bloquea la particion.

## 5. Codigos de respuesta, y quien los produce

| Codigo | Cuando | Quien |
|---|---|---|
| 200 / 201 / 204 | todo correcto | el controlador |
| 400 | el formulario no valida, o el JSON no se puede leer | Bean Validation → `ManejadorDeErrores` |
| 401 | sin token, token invalido, caducado, un id token, o un token valido **sin** ningun scope de la API | API Gateway **y** Spring Security |
| 403 | un NO empleado en `/api/gestion/**` (tambien a traves del gateway) | **Spring Security** (`hasRole`) |
| 403 | token valido sin el scope, llamando a la EC2 **sin** pasar por el gateway | **Spring Security** (`hasAnyAuthority`); por el gateway ese caso es 401 |
| 400 | un mismo libro repetido en dos lineas (esquivaba el tope de 3 copias); una busqueda de mas de 100 caracteres | Bean Validation → `ManejadorDeErrores` |
| 404 | el id no existe, o la solicitud es de otro estudiante | el servicio (`RecursoNoEncontrado`) |
| 409 | sin ejemplares; transicion desde un estado que no corresponde; activar avisos sin email verificado; avisame de un libro con stock | el servicio (`ConflictoDeNegocio`) |
| 503 | Cognito no responde al activar los avisos (mensaje generico, el detalle va al log) | `DirectorioCognito` (`DependenciaNoDisponible`) |

El 404 es util como diagnostico: API Gateway no puede producirlo (una ruta
desconocida la contesta con 403), asi que recibirlo demuestra que la peticion
atraveso el authorizer, el VPC Link y llego al controlador.

---

## 6. Si algo falla en Kafka

| Sintoma | Causa | Solucion |
|---|---|---|
| `evento_kafka` crece y el log dice "Kafka no confirmo N registros" | Brokers caidos o sin mayoria de replicas (`NotEnoughReplicas`: menos de 2 en el ISR) | `docker compose -p biblioteca ps`; al volver los brokers, el relay vacia la tabla solo |
| "los topicos de Kafka aun no estan listos" cada 30 s | Los brokers no responden, o hay menos de 3 vivos (no se pueden crear 3 replicas) | Igual que arriba; `AseguradorTopicos` los crea en cuanto puedan |
| Una `*.dlt` tiene registros | Un registro ilegible, invalido o que fallo 4 veces | Kafka UI → el topico `.dlt` → Messages: las cabeceras `kafka_dlt-exception-*` dicen por que; el `log.error` del stream `app` tambien |
| El catalogo no se actualiza solo | `catalogo-vivo` con lag que no baja, o la API sin conexion a Kafka | `GET /api/admin/grupos/catalogo-vivo` en `admin-kafka`, o Kafka UI → Consumers |
| Los brokers no arrancan: `InconsistentClusterIdException` | Se borraron los volumenes de Zookeeper y no los de Kafka (o al reves) | Borrar los nueve volumenes `zookeeper-*`/`kafka-*` juntos (se pierden los topicos; la API los vuelve a crear) |

## 7. Si algo falla en RabbitMQ

| Sintoma | Causa | Solucion |
|---|---|---|
| La app no arranca: `PRECONDITION_FAILED - inequivalent arg 'x-message-ttl'` | Cambiaste `biblioteca.correo.espera-reintento` (u otro argumento de cola) con la cola ya creada | Borra esa cola desde el panel y reinicia la app: la vuelve a declarar |
| Los correos no llegan y `correo.fallidos` crece | El SMTP rechaza (credenciales, remitente no verificado) | Lee el header `x-motivo-fallo` en el panel; corrige y mueve los mensajes de vuelta a `correo.transaccional` (*Move messages*) |
| Una `*.dlq` de eventos tiene mensajes | Un evento que ESE consumidor no pudo procesar (invalido, o fallo 6 veces) | Inspeccionalo en el panel y busca el `log.warn`/`log.error` en el stream `app`; si era un fallo del entorno, muevelo de vuelta a su cola (*Move messages*) |
| La app no arranca: `PRECONDITION_FAILED - inequivalent arg 'x-dead-letter-routing-key'` | Un broker que tenia las colas de eventos de antes (DLQ comun `eventos.fallidos`) | Borra `notif.prestamos`, `notif.disponibilidad` y `bitacora` en el panel (o los volumenes del cluster). En AWS no pasa: el cluster nuevo nace vacio |
| Una solicitud creada no genera correo | Avisos no activados, o la bandeja de salida no se vacia (broker caido) | `GET /api/avisos/correo`; mira los logs de `RelayEventos` |
