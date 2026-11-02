# Tareas: entrega 2 (EP3 encargo + EP4 presentacion)

Trazabilidad entre las pautas de evaluacion de DSY1107 y lo que hay en el
repositorio. Se actualiza al cerrar cada tarea.

Leyenda: `[x]` hecho · `[~]` parcial · `[ ]` pendiente.
"Fase" remite al plan de trabajo (seccion final).

---

## 1. Cumplimiento de la pauta del encargo (EP3, codigo)

| % | Indicador | Estado | Evidencia |
|---|---|---|---|
| 12 | Nombres de colas, exchanges y bindings centralizados | [x] | `back/.../eventos/TopologiaRabbit.java` (constantes en `@Configuration`, DLQ incluidas); en el administrador, `admin.rabbit.protegidos` en `application.properties` y la regla de nombres en `@NombreAmqp` |
| 13 | Beans `Queue`/`Exchange`/`Binding` por caso de uso | [x] | `Declarables` `topologiaEventos` y `topologiaCorreo`, con diagrama en el javadoc |
| 10 | Configuracion de mensajeria separada del negocio | [x] | Los consumidores reciben `PublicadorCorreo` / `LectorEventos` ya configurados |
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
