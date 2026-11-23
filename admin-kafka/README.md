# Administrador de Kafka · biblioteca-admin-kafka

El **tercer microservicio** de la Biblioteca Duoc. Spring Boot 4.1 sobre Java
21, como la API, con un solo trabajo: **administrar el cluster Kafka por REST**.
Puede crear, describir, reconfigurar y borrar topicos, y supervisar y
reposicionar consumer groups (lag por particion, reinicio de offsets).

Corre en su propio contenedor, en la misma EC2 que la API y los tres brokers
(`aws/backend/compose.yaml`), en el puerto **8082**. Kafka UI (`:8090`) muestra
lo mismo en un panel.

---

## 1. Por que solo es interno

Igual que `admin-rabbit`: administrar el broker es operacion, no una funcion
de la aplicacion. **No esta en API Gateway**; desde fuera de la VPC solo se
llega por la VPN opcional (`./aws/scripts/vpn-up.sh` abre el 8082 al tunel).
Y la VPN da el camino, no el permiso: exige un **JWT de empleado**, validado
por `ValidacionJwt` del modulo `comun`, la misma clase que la API.

| Quien llama | Resultado |
|---|---|
| sin token, token inventado o alterado | **401** |
| estudiante (token valido, no empleado) | **403** |
| empleado o backoffice | lo que diga la operacion |

---

## 2. La API

Cada controlador tiene dos prefijos, `/api/admin/...` y `/v1/api/admin/...`.

| Metodo | Ruta | Cuerpo / parametros | Exito | Errores |
|---|---|---|---|---|
| `GET` | `/api/admin/cluster` | | **200** brokers vivos y controlador | |
| `GET` | `/api/admin/topicos` | `?internos=true` opcional | **200** nombre, particiones, replicas, protegido | |
| `GET` | `/api/admin/topicos/{nombre}` | | **200** cada particion (lider, replicas, ISR, offsets), configuracion propia, mensajes | 400 · 404 |
| `POST` | `/api/admin/topicos` | `NuevoTopico` | **201** + `Location` | 400 · 409 ya existe |
| `PATCH` | `/api/admin/topicos/{nombre}/configuracion` | `CambioConfiguracion` | **200** | 400 clave no permitida o valor invalido · 404 · 409 `cleanup.policy` de un protegido |
| `POST` | `/api/admin/topicos/{nombre}/particiones` | `AumentoParticiones` | **200** | 400 · 404 · 409 protegido o no aumenta |
| `DELETE` | `/api/admin/topicos/{nombre}` | | **204** | 400 · 404 · 409 protegido |
| `GET` | `/api/admin/grupos` | | **200** id, estado, tipo | |
| `GET` | `/api/admin/grupos/{id}` | | **200** miembros y sus particiones, offset confirmado, fin y **lag** por particion, lag total | 400 · 404 |
| `POST` | `/api/admin/grupos/{id}/offsets` | `ReinicioOffsets` | **200** | 400 · 404 · 409 grupo con consumidores |
| `DELETE` | `/api/admin/grupos/{id}` | | **204** | 400 · 404 · 409 grupo con consumidores |

Un **503** en cualquiera de ellas significa que el cluster no respondio en 15 s.
Los errores tienen la misma forma JSON que los de la API (`instante`, `status`,
`error`, `mensaje`, `detalles`).

### Validacion de entrada

| Regla | Donde |
|---|---|
| Nombre de topico no vacio, hasta 249, `[a-z0-9][a-z0-9._-]*` (nada de `__*`, internos de Kafka) | `@NombreTopico`, en los DTO **y** en los `{nombre}` de la ruta |
| Id de grupo: letras, digitos, `.`, `-`, `_` | `@IdGrupo` |
| `particiones` 1 a 12; `replicas` 1 a 3 (hay tres brokers) | `@Min`, `@Max` en `NuevoTopico` |
| Cambio de configuracion: algo que cambiar, y ninguna clave a la vez en `establecer` y `restablecer` | `@AssertTrue` en `CambioConfiguracion` |
| `FECHA` exige `fecha` | `@AssertTrue` en `ReinicioOffsets` |

Las reglas que dependen del **estado del cluster** (existe, esta protegido,
tiene consumidores) las aplica el servicio (404 y 409), y lo que Kafka rechaza
por sus valores (un `retention.ms` que no es numero, mas replicas que brokers
vivos) sale como **400**.

---

## 3. Como esta hecho

```
src/main/java/cl/duoc/biblioteca/adminkafka/
├── AdminKafkaApplication.java    escanea adminkafka y comun
├── config/
│   ├── AdminKafkaConfig.java     el Admin de Kafka, desde el KafkaAdmin de Spring (spring.kafka.*)
│   ├── PropiedadesAdmin.java     admin.kafka.*: topicos protegidos, claves permitidas, espera
│   └── SecurityConfig.java       /api/admin/** solo EMPLEADO; /health publico; lo demas, denegado
├── servicio/
│   ├── KafkaAdminService.java    TODA la administracion: el unico que toca el Admin de Kafka
│   └── NoEncontrado  Conflicto  PeticionInvalida  KafkaNoDisponible   -> 404 / 409 / 400 / 503
├── dto/
│   ├── NuevoTopico  CambioConfiguracion  AumentoParticiones  ReinicioOffsets   lo que se RECIBE
│   ├── Vistas.java               lo que se DEVUELVE (cluster, topico, particion, grupo, offsets)
│   └── NombreTopico  IdGrupo     las reglas de nombres, en un solo lugar
└── web/
    ├── ClusterController  TopicoController  GrupoController
    ├── ErroresAdmin.java         dominio -> 400 / 404 / 409 / 503, con el formato comun
    └── SaludController.java      GET /health (healthcheck del contenedor)
src/test/java/.../AdminKafkaTest.java   10 tests contra un Kafka real (Testcontainers)
```

**Separacion de responsabilidades.** Ningun `KafkaFuture`, `TopicDescription`
ni excepcion del cliente sale de `KafkaAdminService`: los controladores reciben
vistas (`Vistas.*`) y errores de dominio. Toda llamada al cluster pasa por
`esperar(...)`, que pone el limite de tiempo y traduce los errores de Kafka
(`TopicExistsException` → 409, `UnknownTopicOrPartitionException` → 404,
`InvalidConfigurationException` → 400, timeout → 503).

**Decisiones que conviene conocer:**

* **Topicos protegidos** (`admin.kafka.protegidos`, los que declara la API en
  `TopologiaKafka`, mas los internos `__*`): no se borran, no se les cambian
  las particiones ni el `cleanup.policy`. Su `retention.ms`, `segment.ms` o
  `min.insync.replicas` **si** se ajustan: es la administracion que la pauta
  pide demostrar.
* **Por que no cambiar las particiones de un topico de la biblioteca.** La
  particion de un registro es `hash(clave) % particiones`. Con mas
  particiones, los registros nuevos de `L-005` caen en otra, y un consumidor
  podria leerlos antes que los viejos: el stock "retrocederia".
* **Solo claves de configuracion permitidas** (`admin.kafka.configuraciones-permitidas`):
  retencion, limpieza, segmentos, replicas minimas, tamano maximo y
  compresion. Este servicio administra lo que la biblioteca necesita, no todo
  Kafka.
* **Cambio incremental.** `PATCH .../configuracion` usa
  `incrementalAlterConfigs`: lo que no se nombra queda igual, y `restablecer`
  devuelve una clave al valor por defecto del broker.
* **Offsets solo con el grupo vacio.** Kafka no deja mover los offsets de un
  grupo con consumidores (confirmarian encima). Para reprocesar: detener la API,
  mover el grupo a `INICIO`, arrancarla. `GET /grupos/{id}` muestra el lag
  subir y luego bajar a 0.
* **Un grupo que nunca existio**: Kafka lo describe como `DEAD`, sin error;
  aqui es **404**.
* **`listGroups()` sin filtro de tipo**, filtrando en Java: los brokers 3.9 de
  `cp-kafka` no entienden el filtro por tipo de `ListGroups` v5 de Kafka 4.

---

## 4. Compilar, probar, usar

```sh
# desde la raiz del repositorio
./mvnw -pl admin-kafka -am package    # 10 tests; necesita Docker
```

En AWS lo despliega `aws/pipeline/build-backend.sh` junto con la API (el mismo
paquete, otro contenedor). Para probarlo desplegado:

```sh
./aws/scripts/vpn-up.sh
./aws/pipeline/bruno-env.sh
cd bruno
bru run admin-kafka --env aws-admin-kafka-vpn               # ciclo completo
bru run admin-kafka --env aws-admin-kafka-vpn-estudiante    # 403
```

| Variable | Que es |
|---|---|
| `BIBLIOTECA_ISSUER`, `BIBLIOTECA_JWKS`, `BIBLIOTECA_SCOPE`, `BIBLIOTECA_SCOPE_GESTION` | La validacion del JWT, igual que la API |
| `KAFKA_BROKERS` | Los tres brokers (`kafka-1:9092,kafka-2:9092,kafka-3:9092`) |

No recibe credenciales de la base, del SMTP ni de RabbitMQ: su archivo de
entorno (`/run/biblioteca/admin-kafka.env`) solo lleva lo de arriba.

### Lo mismo por CLI, dentro de la EC2 (por SSM)

```sh
docker exec biblioteca-kafka-1-1 kafka-topics --bootstrap-server kafka-1:9092 --describe --topic biblioteca.inventario
docker exec biblioteca-kafka-1-1 kafka-consumer-groups --bootstrap-server kafka-1:9092 --describe --group catalogo-vivo
docker exec biblioteca-kafka-1-1 kafka-consumer-groups --bootstrap-server kafka-1:9092 --describe --all-groups --state
```
