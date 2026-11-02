# Administrador de RabbitMQ · biblioteca-admin-rabbit

El **segundo microservicio** de la Biblioteca Duoc. Spring Boot 4.1 sobre
Java 21, como la API, pero con un solo trabajo: **crear y eliminar colas,
exchanges y bindings** en el cluster RabbitMQ, por REST.

Corre en su propio contenedor, en la misma EC2 que la API y los dos nodos
(`aws/backend/compose.yaml`), en el puerto **8081**.

---

## 1. Por que solo es interno

Administrar el broker no es una funcion de la aplicacion: es operacion. Por eso
**no esta publicado en API Gateway** y desde fuera de la VPC solo se llega por
la VPN opcional (`./aws/scripts/vpn-up.sh`, que abre el 8081 al tunel).

La VPN da el camino, no el permiso: el servicio **exige un JWT de empleado**,
validado exactamente igual que en la API (firma con el JWKS de Cognito, emisor,
expiracion, `token_use=access`) porque usa la misma clase, `ValidacionJwt`, del
modulo `comun`. Empleado es una persona del grupo `empleados` con el scope de
acceso, o el cliente M2M backoffice (scope `gestion`).

| Quien llama | Resultado |
|---|---|
| sin token, token inventado o alterado | **401** |
| estudiante (token valido, no empleado) | **403** |
| empleado o backoffice | lo que diga la operacion |

---

## 2. La API

Cada controlador tiene dos prefijos, `/api/admin/...` y `/v1/api/admin/...`,
como los de la API.

| Metodo | Ruta | Cuerpo / parametros | Exito | Errores |
|---|---|---|---|---|
| `POST` | `/api/admin/colas` | `NuevaCola` | **201** + `Location` | 400 · 404 DLX inexistente · 409 ya existe |
| `GET` | `/api/admin/colas/{nombre}` | | **200** `{nombre, mensajes, consumidores}` | 400 · 404 |
| `DELETE` | `/api/admin/colas/{nombre}` | `?forzar=true` opcional | **204** | 400 · 404 · 409 protegida o con mensajes |
| `POST` | `/api/admin/exchanges` | `NuevoExchange` | **201** + `Location` | 400 · 409 ya existe |
| `DELETE` | `/api/admin/exchanges/{nombre}` | | **204** | 400 · 404 · 409 protegido |
| `POST` | `/api/admin/bindings` | `NuevoBinding` | **201** | 400 · 404 exchange o cola · 409 cola protegida |
| `DELETE` | `/api/admin/bindings` | `?exchange=&cola=&routingKey=` | **204** | 400 · 404 · 409 cola protegida |

Un **503** en cualquiera de ellas significa que el broker no responde.

Todos los errores tienen la misma forma que los de la API:

```json
{
  "instante": "2026-11-01T14:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "mensaje": "La solicitud no es valida",
  "detalles": { "ttlMs": "ttlMs tiene que ser mayor que 0" }
}
```

### Validacion de entrada

| Regla | Donde |
|---|---|
| Nombre no vacio, hasta 255, `[a-z0-9][a-z0-9._-]*`, sin prefijo `amq.` | `@NombreAmqp`: una anotacion compuesta, usada en los DTO **y** en los `{nombre}` de la ruta |
| `tipo` de cola: `CLASSIC` (por defecto) o `QUORUM` | enum `TipoCola`; otro valor es 400 |
| `tipo` de exchange obligatorio: `DIRECT`, `TOPIC`, `FANOUT`, `HEADERS` | `@NotNull` + enum `TipoExchange` |
| `ttlMs` > 0 y cabe en un int; `limiteEntregas` entre 1 y 1000 | `@Positive`, `@Max` |
| `limiteEntregas` solo en `QUORUM`; una `QUORUM` siempre es durable | `@AssertTrue` en el record (reglas cruzadas) |
| `routingKey` presente (puede ser `""`) | `@NotNull` |

Las reglas que dependen del **estado del broker** no son validacion de
entrada: las aplica el servicio (404 y 409).

---

## 3. Como esta hecho

```
src/main/java/cl/duoc/biblioteca/admin/
├── AdminRabbitApplication.java   escanea admin y comun
├── config/
│   ├── AdminRabbitConfig.java    el RabbitAdmin (no ignora errores, no declara nada propio)
│   ├── PropiedadesAdmin.java     admin.rabbit.protegidos: la topologia que no se toca
│   └── SecurityConfig.java       /api/admin/** solo EMPLEADO; /health publico; lo demas, denegado
├── servicio/
│   ├── RabbitAdminService.java   TODA la administracion: el unico que toca RabbitAdmin
│   ├── NoEncontrado.java         -> 404
│   └── Conflicto.java            -> 409
├── dto/
│   ├── NuevaCola.java  NuevoExchange.java  NuevoBinding.java  InfoCola.java
│   ├── TipoCola.java  TipoExchange.java
│   └── NombreAmqp.java           la regla de nombres, en un solo lugar
└── web/
    ├── ColaController.java  ExchangeController.java  BindingController.java
    ├── ErroresAdmin.java         dominio -> 404 / 409 / 503, con el formato comun
    └── SaludController.java      GET /health (healthcheck del contenedor)
src/main/resources/application.properties   puerto, broker y la lista de protegidos
src/test/java/.../AdminRabbitTest.java      13 tests contra un RabbitMQ real (Testcontainers)
```

**Separacion de responsabilidades.** Los controladores no ven ni canales, ni
argumentos `x-*`, ni excepciones de AMQP: reciben un DTO validado y llaman a un
metodo de alto nivel (`crearCola`, `eliminarExchange`...). `RabbitAdminService`
traduce el pedido a `QueueBuilder`/`ExchangeBuilder`/`Binding` y los errores del
broker a errores de dominio, que `ErroresAdmin` convierte en codigos HTTP.

**Decisiones que conviene conocer:**

* **Crear algo que ya existe es 409.** RabbitMQ aceptaria redeclararlo con los
  mismos argumentos y fallaria con otros (`PRECONDITION_FAILED`). Un 409 claro
  es mejor que cualquiera de las dos cosas.
* **Lo referenciado tiene que existir.** RabbitMQ acepta un
  `x-dead-letter-exchange` inexistente y pierde los mensajes en silencio; aqui
  es 404. Como `AmqpAdmin` no sabe consultar un exchange, se pregunta con una
  declaracion **pasiva**.
* **Topologia protegida.** Lo que declara la API (`TopologiaRabbit`) no se
  borra ni se re-enlaza: la lista esta en `application.properties`
  (`admin.rabbit.protegidos`). Si se agrega una cola alli, se agrega aqui.
  Enlazar una cola NUEVA a un exchange de la biblioteca si se puede.
* **Una cola con mensajes no se borra** salvo con `forzar=true`.
* **Quitar un binding que no existe es 204**: AMQP no permite consultar
  bindings y RabbitMQ no lo trata como error.

---

## 4. Compilar, probar, usar

```sh
# desde la raiz del repositorio
./mvnw -pl admin-rabbit -am package    # 13 tests; necesita Docker
```

En AWS lo despliega `aws/pipeline/build-backend.sh` junto con la API (el mismo
paquete, otro contenedor). Para probarlo desplegado:

```sh
./aws/scripts/vpn-up.sh
./aws/pipeline/bruno-env.sh
cd bruno
bru run admin --env aws-admin-vpn               # 201 / 200 / 204
bru run admin --env aws-admin-vpn-estudiante    # 403
```

| Variable | Que es |
|---|---|
| `BIBLIOTECA_ISSUER`, `BIBLIOTECA_JWKS`, `BIBLIOTECA_SCOPE`, `BIBLIOTECA_SCOPE_GESTION` | La validacion del JWT, igual que la API |
| `RABBITMQ_ADDRESSES` / `_USER` / `_PASSWORD` | Los dos nodos del cluster |

No recibe credenciales de la base ni del SMTP: su archivo de entorno
(`/run/biblioteca/admin-rabbit.env`) solo lleva lo de arriba.
