# Tareas: entrega 2 (EP3 + EP4) y entrega 3 (EP5 + EP6, Kafka)

Trazabilidad entre las pautas de evaluacion de DSY1107 y lo que hay en el
repositorio. Se actualiza al cerrar cada tarea. La entrega 3 (Kafka) esta en
la seccion 0; las secciones 1 a 4 son la entrega 2 (RabbitMQ).

Leyenda: `[x]` hecho · `[~]` parcial · `[ ]` pendiente.
"Fase" remite al plan de trabajo (seccion final).

---

## 0. Entrega 3: Kafka (EP5 encargo, EP6 presentacion)

Opcion elegida: **inventario en vivo (H8) y bandeja de gestion en vivo (H9)**,
con el administrador de Kafka (H10). Kafka complementa a RabbitMQ sin tocar su
topologia, con su propia bandeja de salida.

### EP5: encargo (codigo)

| % | Indicador | Estado | Evidencia |
|---|---|---|---|
| 12 | Cliente Kafka completo: bootstrap, serializers, consumer groups, propiedades avanzadas | [x] | `back/.../application.properties` (seccion Kafka): `acks=all`, idempotencia, `lz4`, timeouts, `ErrorHandlingDeserializer` + `JacksonJsonDeserializer` con type mapping, `CooperativeStickyAssignor`, `ack-mode=record`; topicos 3x3 desde `biblioteca.kafka.*` |
| 12 | Productores y consumidores con DTO sobre los dos topicos | [x] | `CambioStock` (`biblioteca.inventario`), `HechoSolicitud` (`biblioteca.solicitudes`); `StreamingIntegracionTest` |
| 6 | Microservicio administrador: crear, listar, modificar y eliminar topicos y configuraciones | [x] | `admin-kafka/`: `TopicoController` (POST, GET, PATCH configuracion, POST particiones, DELETE), `KafkaAdminService`; `AdminKafkaTest` (10 tests) |
| 14 | Productores con DTO y serializers adecuados | [x] | `BandejaKafka` (outbox) + `RelayKafka` (`KafkaTemplate`, `JacksonJsonSerializer`, clave por entidad, espera de confirmacion) |
| 14 | Consumidores `@KafkaListener` separados del dominio | [x] | `ConsumidorCatalogoVivo`, `ConsumidorBandejaGestion`, `ConsumidorAuditoria` (infra) → `ProyeccionStock`, `ProyeccionBandeja` (dominio, sin tipos de Kafka) |
| 12 | Manejo de errores: validacion, logging, reintentos | [x] | `ConfigKafka`: `@Valid` en listeners, `DefaultErrorHandler` (3 reintentos exponenciales), `DeadLetterPublishingRecoverer` a `<topico>.dlt`, `log.error` con particion/offset; tests `registroIlegibleALaDlt`, `dtoInvalidoALaDlt` |
| 12 | Retencion, cleanup, particiones y replicacion por flujo | [x] | inventario `compact` (estado), solicitudes `delete` 30 dias (historia), DLT 14 dias; todos 3x3 y `min.insync.replicas=2` (`TopologiaKafka`, `application.properties`) |
| 12 | Consumer groups para balancear o dividir responsabilidades | [x] | `catalogo-vivo` y `bandeja-gestion` (concurrencia 3 = una particion por hilo), `auditoria-kafka` lee los dos topicos con sus propios offsets |
| 6 | Administrador o scripts para inspeccionar topicos y consumer groups | [x] | `GrupoController`: lag por particion, reinicio de offsets (INICIO/FIN/FECHA), borrado; Kafka UI; comandos CLI en `admin-kafka/README.md` |

| Requisito formal | Estado | Comentario |
|---|---|---|
| Productores y consumidores conectados a topicos Kafka | [x] | `back/.../streaming/` |
| Administrador de Kafka: crear, modificar, eliminar topicos y configuraciones | [x] | `admin-kafka/` |
| docker-compose con Kafka + Zookeeper + brokers | [x] | `aws/backend/kafka.compose.yaml` (3 + 3), verificado en local: quorum, replicacion 3, caida de un broker con escritura `acks=all` |
| docker-compose con Kafka UI | [x] | `aws/backend/kafka-ui.compose.yaml` (incluye el cluster), login por `KafkaUiSecret` |
| Sin afectar a RabbitMQ | [x] | Tabla y relay propios (`evento_kafka`, V4), hilo de planificador propio, la API no espera a Kafka; los 59 tests de `back` (incluidos los de RabbitMQ) pasan |
| Compila y responde a pruebas basicas | [x] | `./mvnw package`: 59 de `back`, 13 de `admin-rabbit`, 10 de `admin-kafka` |

### EP6: presentacion (que mostrar)

| % | Punto | Donde |
|---|---|---|
| 10 | Arquitectura: 3 Zookeeper, 3 brokers, tolerancia a fallos | `kafka.compose.yaml` (cabecera); demo: `docker stop biblioteca-kafka-3-1` y ver el ISR bajar a 2 en Kafka UI mientras el catalogo sigue actualizandose |
| 10 | Dos topicos 3 particiones x 3 replicas | Kafka UI → Topics; `bru run admin-kafka` (`list-topics`, `get-topic`) |
| 5 | Kafka UI: brokers, topicos, particiones, mensajes | `http://<ip>:8090` por la VPN |
| 12 | Flujo de produccion y consumo | README raiz, "El streaming va por Kafka" (diagrama) |
| 6 | Estructura de los eventos | Javadoc de `CambioStock` y `HechoSolicitud` (por que cada campo) |
| 12 | Reaccion en tiempo real | Dos navegadores: solicitud → aparece en la bandeja; aprobacion → el stock baja en el catalogo (`front/README.md` §4, paso 9) |
| 15 | Administracion: particiones, replicas, retention, consumer groups | `admin-kafka` (PATCH `retention.ms`, 409 al cambiar particiones de un protegido) |
| 15 | Monitoreo: offsets, lag, distribucion | Kafka UI → Consumers; `GET /api/admin/grupos/catalogo-vivo`; detener la app y ver crecer el lag |
| 15 | Casos de uso que requieren streaming | H8 (varios lectores del mismo hecho, compactado = estado) y H9 (historia releible) frente a RabbitMQ (tareas) |

### Pendiente (requiere el lab encendido)

- [ ] `awsacademy start` y `./aws/pipeline/build-backend.sh`: reemplaza la EC2 (`AppServer` → `BackendServer`, t3.large). Verificar que el Learner Lab permite t3.large
- [ ] `docker compose -p biblioteca ps`: 12 contenedores sanos; `aws logs tail` sin errores de Kafka
- [ ] `vpn-up.sh` (abre 8082 y 8090) + Kafka UI: 3 brokers, 4 topicos 3x3, 3 grupos
- [ ] `bruno-env.sh` + `bru run admin-kafka --env aws-admin-kafka-vpn` y `-estudiante`; matriz de la API con `stock-vivo` y `bandeja-vivo`
- [ ] Recorrido del navegador, paso 9 (en vivo)
- [ ] Guion de demo EP6 y presentacion

---

## 1. Cumplimiento de la pauta del encargo (EP3, codigo)

| % | Indicador | Estado | Evidencia |
|---|---|---|---|
| 12 | Nombres de colas, exchanges y bindings centralizados | [x] | `back/.../eventos/TopologiaRabbit.java` (constantes en `@Configuration`, DLQ incluidas); en el administrador, `admin.rabbit.protegidos` en `application.properties` y la regla de nombres en `@NombreAmqp` |
| 13 | Beans `Queue`/`Exchange`/`Binding` por caso de uso | [x] | `Declarables` `topologiaEventos` y `topologiaCorreo`, con diagrama en el javadoc |
| 10 | Configuracion de mensajeria separada del negocio | [x] | Los consumidores reciben `PublicadorCorreo` / `ProcesadorDeEventos` ya configurados; la politica ACK/NACK vive en un solo lugar |
| 15 | `@RabbitListener` agrupados por dominio | [x] | Paquetes `avisos/` y `bitacora/` |
| 20 | ACK manual, NACK, reintento y DLQ diferenciados | [x] | `AcknowledgeMode.MANUAL`; invalido -> NACK sin requeue (DLQ); transitorio -> NACK con requeue + `x-delivery-limit`; SMTP -> espera por TTL + `correo.fallidos` |
| 13 | Microservicio administrador con API REST (`POST /queues`, `DELETE /queues/{name}`) | [x] | `admin-rabbit/`: `POST/GET/DELETE /api/admin/colas`, `POST/DELETE /api/admin/exchanges`, `POST/DELETE /api/admin/bindings` (201/200/204, 404, 409, 503) |
| 10 | Logica de administracion en un `RabbitAdminService` | [x] | `admin-rabbit/.../servicio/RabbitAdminService.java`: unico que toca `RabbitAdmin`; errores de dominio `NoEncontrado`/`Conflicto` |
| 7 | Validacion de entrada con DTO y errores claros | [x] | `NuevaCola`/`NuevoExchange`/`NuevoBinding` + `@NombreAmqp` (tambien en la ruta) + reglas cruzadas `@AssertTrue`; 400 con el campo que fallo |

### Requisitos formales del encargo

| Requisito | Estado | Comentario |
|---|---|---|
| Backend en varios microservicios Java / Spring Boot | [x] | Dos: `back/` (API) y `admin-rabbit/` (administrador), mas la libreria `comun/`, en un Maven multi-modulo |
| Productores y consumidores RabbitMQ | [x] | `RelayEventos`, `PublicadorCorreo`; cuatro consumidores |
| Mensajes no entregados a DLQ y registrados en logs | [x] | NACK a DLQ con `log.warn`; el ultimo intento antes de `x-delivery-limit` con `log.error` (`Entregas.esUltima`) |
| El administrador crea y elimina colas, exchanges y bindings | [x] | `admin-rabbit/` (13 tests contra RabbitMQ real; Bruno `admin/` verificado de punta a punta en local) |
| Backend con filtros que validan el JWT del IDaaS | [x] | `SecurityConfig` (JWKS fijo, RS256, `token_use`) |
| Frontend con login IDaaS y JWT en las llamadas | [x] | Managed Login + PKCE; `auth/http.ts` |
| Integracion con la base cloud (entidades, repos, propiedades) | [x] | RDS PostgreSQL, Flyway, `spring.datasource.*` |
| `.gitignore` por tecnologia | [x] | Raiz, `back/`, `bruno/` |
| Compila y responde a pruebas basicas | [x] | 49 tests (`./mvnw package` en la raiz: 36 de `back`, 13 de `admin-rabbit`) |
| Frontend **Angular** | [~] | Es React (README §7). **Consultar al docente** |
| Entrega como enlaces a GitHub | [~] | Monorepo. **Consultar al docente** si basta un enlace |

## 2. Cumplimiento de la pauta de la presentacion (EP4, demostracion)

| Punto | Estado | Comentario |
|---|---|---|
| Instancia de API Manager en funcionamiento | [x] | API Gateway REST `biblioteca-api` |
| Configuracion del API Manager hacia el backend | [x] | VPC Link -> NLB -> EC2 |
| El frontend consume via API Manager | [x] | Caddy reenvia `/api` al gateway; explicarlo con el diagrama |
| El API Manager valida JWT (rechaza / acepta) | [x] | Matriz de Bruno (200 / 401 / 403) |
| Tenant IDaaS con usuarios | [x] | User Pool de Cognito, `alumno` y `empleado` |
| Login OAuth 2.0 / OIDC con JWT valido | [x] | Managed Login, code + PKCE |
| Backend y frontend desplegados e integrados | [x] | |
| Dos nodos RabbitMQ en cluster | [~] | `aws/backend/compose.yaml` + `rabbitmq.conf`: cluster verificado en local (2 nodos, quorum con lider y seguidor). **Falta desplegar y mostrarlo en AWS** |
| Tres colas con sus respectivas DLQ | [x] | `notif.prestamos.dlq`, `notif.disponibilidad.dlq`, `bitacora.dlq` (test `eventoEnvenenado`). Falta verlas en el panel desplegado |
| Exchanges direct y topic | [x] | `correo` (direct), `biblioteca.eventos` (topic) |
| docker-compose levantando RabbitMQ en la nube | [~] | El servicio `biblioteca` de la EC2 corre `docker compose up` (UserData de `backend.yaml`). **Falta desplegar** |

---

## 3. Lista priorizada

### P0: sin esto no hay nota maxima

- [x] **Microservicio `admin-rabbit`** (Spring Boot): `RabbitAdminService`, REST de colas, exchanges y bindings, DTO validados, tests. Fases 2 y 3
- [~] **Cluster RabbitMQ de 2 nodos con docker-compose** en la EC2 de la app (todos los contenedores en el compose). Fase 4: codigo listo y cluster probado en local; **falta desplegar** (`awsacademy start` + `./aws/pipeline/build-backend.sh`)

### P1: brechas literales de la pauta

- [x] Una DLQ por cola de eventos (`notif.prestamos.dlq`, `notif.disponibilidad.dlq`, `bitacora.dlq`). Fase 1
- [x] Log cuando el broker aparta un mensaje por `x-delivery-limit`. Fase 1
- [x] Bruno: carpeta `admin/` y entornos `aws-admin-vpn` / `aws-admin-vpn-estudiante` (administrador solo por VPN). Fase 5

### P1 bis: lo que queda para cerrar (requiere el lab encendido)

- [ ] `awsacademy start` y `aws cloudformation validate-template` de `backend.yaml` y `vpn.yaml` (en local paso `cfn-lint`)
- [ ] `./aws/pipeline/build-backend.sh`: crea la EC2 nueva con compose y el cluster
- [ ] Medir: `docker compose ps`, `rabbitmqctl cluster_status`, `rabbitmq-queues quorum_status notif.prestamos`
- [ ] `vpn-up.sh` + panel: 2 nodos, 3 quorum con 2 miembros, 3 `.dlq`, exchanges topic y direct
- [ ] Mensaje envenenado desde el panel a `biblioteca.eventos` (`solicitud.x`): llega a `notif.prestamos.dlq` y `bitacora.dlq`, y aparece en el log
- [ ] `bruno-env.sh` y matriz completa: los 5 entornos de la API sin cambios + `bru run admin` con los 2 del administrador
- [ ] Recorrido del navegador (`front/README.md` §4)

### P2: documentacion y presentacion

- [x] README, `back/README.md`, `aws/README.md`, `admin-rabbit/README.md`, `bruno/README.md`, `CLAUDE.md` al dia (Fase 6)
- [ ] Guion de demo de 5 a 10 minutos en el orden de los 11 puntos de EP4
- [ ] Informe `.docx` y presentacion `.pptx`

### Fuera de este plan (decisiones externas)

- [ ] Confirmar con el docente: React en vez de Angular
- [ ] Confirmar con el docente: un solo repositorio (monorepo) o un enlace por componente

---

## 4. Plan de trabajo

| Fase | Contenido | Estado |
|---|---|---|
| 0 | Este archivo | [x] |
| 1 | DLQ por cola + log de lo que el broker aparta (`back/`) | [x] |
| 2 | Maven multi-modulo (`comun`, `back`, `admin-rabbit`), `mvnw` en la raiz | [x] |
| 3 | Microservicio `admin-rabbit` + tests | [x] |
| 4 | `aws/backend/compose.yaml`, cluster de 2 nodos, `backend.yaml`, `build-backend.sh`, VPN | [x] (cfn-lint OK; sin desplegar) |
| 5 | Bruno `admin/` + entorno generado | [x] |
| 6 | Documentacion | [x] |
| — | Despliegue y medicion: `build-backend.sh` (crea la EC2 nueva `AppServer`), `vpn-up.sh`, panel (2 nodos, quorum, 3 DLQ), Bruno (API y `admin`), navegador | [ ] |

Decisiones tomadas: multi-modulo Maven; administrador solo interno por la VPN
(no por API Gateway); cluster de 2 nodos en la misma EC2; un solo compose para
todos los contenedores; DLQ por cola solo en eventos (el correo conserva su
reintento + `correo.fallidos`); sin vista de administracion en el frontend.
