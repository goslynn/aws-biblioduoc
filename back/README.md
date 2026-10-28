# Backend · biblioteca-backend

API de la Biblioteca Duoc. **Spring Boot 4.1 sobre Java 21**, compilada con el
Maven Wrapper (no hace falta tener Maven instalado).

El artefacto es un **fat jar**, y es exactamente el mismo en los dos entornos:

```sh
java -jar target/biblioteca-backend-0.0.1-SNAPSHOT.jar   # en tu maquina
# y dentro de Lambda, el AWS Lambda Web Adapter arranca ese mismo jar
```

No hay codigo especifico de AWS en ninguna clase: si manana el backend se
mudara a EC2 o a un contenedor, no habria que cambiar nada.

---

## 1. Estructura

```
src/main/java/cl/duoc/biblioteca/
├── BibliotecaApplication.java
├── catalogo/
│   ├── Libro.java                  record inmutable; "disponible" es calculado
│   ├── CatalogoLibros.java         lee la tabla libro (JdbcClient); busqueda sin tildes en SQL
│   └── LibroController.java        GET /api/libros, /generos, /{id}
├── solicitudes/
│   ├── Sede.java  EstadoSolicitud.java
│   ├── ItemSolicitado.java  NuevaSolicitud.java    DTO de ENTRADA, con validaciones
│   ├── LineaSolicitud.java  Solicitud.java         DTO de SALIDA
│   ├── RepositorioSolicitudes.java solicitud + linea_solicitud en PostgreSQL (JdbcClient)
│   └── SolicitudController.java    POST y GET de solicitudes
├── web/
│   ├── SaludController.java        GET /health, la sonda del Web Adapter
│   └── ManejadorDeErrores.java     convierte los fallos de validacion en 400 utiles
└── config/
    └── SecurityConfig.java         segunda validacion del JWT (JWKS embebido) + CORS
src/main/resources/
├── application.properties
└── db/migration/                   las aplica Flyway al arrancar, como usuario maestro
    ├── V1__esquema.sql             tablas, CHECKs, secuencia y el rol biblioteca_app
    └── V2__catalogo_inicial.sql    SEED: los 20 libros del catalogo
src/test/java/cl/duoc/biblioteca/
└── PostgresDePrueba.java           PostgreSQL 16 en Docker (Testcontainers) para los tests
```

---

## 2. Compilar y probar

```sh
cd back
./mvnw package          # compila y pasa los 15 tests
./mvnw test
```

**Hace falta Docker**: los tests levantan un PostgreSQL 16 real con
Testcontainers y le aplican las mismas migraciones que en AWS. Ademas
reproducen la separacion de usuarios: Flyway entra como superusuario y la
aplicacion como `biblioteca_app`, asi que si una consulta necesitara un permiso
que ese rol no tiene, el test falla aqui y no en el despliegue.

Los tests usan `MockMvc` y **simulan** la autenticacion con el post-processor
`jwt()` de `spring-security-test`: se inyecta un token ya validado con las
authorities que interesen. No hay red, no hay Cognito, y lo que se prueba son
las **reglas** de `SecurityConfig` (401 sin token, 403 con token sin scope, 200
con scope), no la criptografia, que es responsabilidad de la libreria.

### Configuracion que recibe

Nada de esto esta escrito en el repositorio: en AWS lo inyecta
`aws/backend.yaml` como variables de entorno, desde los Outputs de
CloudFormation y los secretos de Secrets Manager.

| Variable | Que es |
|---|---|
| `BIBLIOTECA_ISSUER` | Emisor exigido en el claim `iss` (Cognito) |
| `BIBLIOTECA_JWKS` | Claves publicas de Cognito en JSON; las baja `build-backend.sh` |
| `BIBLIOTECA_SCOPE` | Scope exigido (`biblioteca-api/acceso`) |
| `BIBLIOTECA_ORIGEN` | Origen del frontend, para el CORS |
| `SPRING_DATASOURCE_URL` | JDBC a RDS con `sslmode=verify-full` |
| `SPRING_DATASOURCE_USERNAME` / `_PASSWORD` | Rol `biblioteca_app` |
| `SPRING_FLYWAY_USER` / `_PASSWORD` | Usuario maestro, solo para las migraciones |
| `BIBLIOTECA_APP_PASSWORD` | La de `biblioteca_app`, para que V1 cree el rol |

Con eso, `GET /health` responde 200 y todo `/api/**` responde 401 sin un token
valido de ese pool.

---

## 3. Decisiones que conviene entender

**PostgreSQL con JDBC, sin JPA.** `JdbcClient` sobre records inmutables:
Hibernate anadiria segundos al arranque en frio de la Lambda sin aportar nada a
cuatro consultas. Todos los filtros van como parametros ligados, nunca
concatenados: la inyeccion SQL no tiene por donde entrar.

**El seed es una migracion.** `V2__catalogo_inicial.sql` contiene los 20 libros
que antes vivian en `libros.json` (convertido una vez con `jq` y borrado: una
sola fuente de verdad). Lo aplica Flyway al arrancar, desde dentro de la VPC,
porque la base es privada y no hay bastion. Las migraciones aplicadas **no se
editan**: un cambio al catalogo es una `V3__...sql`.

**Dos usuarios de base de datos.** Flyway usa el maestro, solo al arrancar. La
aplicacion atiende las peticiones como `biblioteca_app`, que puede leer el
catalogo y registrar solicitudes, y nada mas: ni DDL, ni `UPDATE`, ni
`DELETE`. Una inyeccion o una credencial filtrada de la aplicacion no alcanzan
para borrar tablas ni reescribir solicitudes.

**El JWKS llega embebido.** La Lambda corre en una VPC sin salida a Internet
(asi la base queda fuera del alcance publico) y no puede descargar las claves
de Cognito. Las baja el pipeline y entran en `BIBLIOTECA_JWKS`. Si Cognito las
rotara, todo token daria 401 hasta volver a correr `build-backend.sh`.

**El solicitante sale del token.** `Principal.getName()`, que con Spring
Security es el claim `sub` (una persona) o el `client_id` (un cliente M2M).
Nunca del formulario: si viniera del cuerpo, cualquiera podria pedir libros a
nombre de otro.

**Dos prefijos por controlador.** `@RequestMapping({"/api/libros",
"/v1/api/libros"})`: en API Gateway `/v1` es un unico recurso proxy, asi que el
enrutado fino lo hace Spring. Cuando exista un `/v2` incompatible, sera otra
clase y las dos convivieran.

**`/health` es la unica ruta publica.** El Lambda Web Adapter no da por
arrancada la aplicacion hasta que una ruta le responde 2xx; con Spring Security
activo, cualquier ruta de `/api` le devolveria 401 y el arranque se daria por
fallido. No se publica en API Gateway, asi que desde internet no existe, y no
revela nada.

**El JwtDecoder apunta al JWKS, no al issuer.** `withIssuerLocation()` descarga
el documento de descubrimiento OIDC **al arrancar el contexto**: una llamada de
red en el arranque en frio, y la aplicacion no levanta si Cognito tarda. Con
`withJwkSetUri()` la descarga es perezosa y se cachea.

**El dispatcher `ERROR` esta permitido, y hace falta.** Cuando un controlador
lanza una excepcion, el contenedor reenvia internamente a `/error`, y ese
reenvio vuelve a pasar por las reglas de seguridad. Sin
`.dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()`, `/error` cae en el
`denyAll()` final y **todo 404 y 409 sale convertido en un 403 con cuerpo
vacio**. Paso de verdad en el despliegue, y los tests de MockMvc no lo
detectaron porque MockMvc no reproduce ese reenvio.

**El CORS lo pone Spring en las respuestas reales.** Con integracion
`AWS_PROXY`, API Gateway devuelve lo que diga la Lambda y no puede anadir
cabeceras. Asi que el reparto es: el preflight `OPTIONS` lo responde API
Gateway (integracion MOCK), la cabecera del 200 la pone Spring, y los errores
del propio gateway los cubren los `GatewayResponses`.

---

## 4. Codigos de respuesta, y quien los produce

| Codigo | Cuando | Quien |
|---|---|---|
| 200 / 201 | todo correcto | el controlador |
| 400 | el formulario no valida, o el JSON no se puede leer | Bean Validation → `ManejadorDeErrores` |
| 401 | sin token, token invalido, caducado, un id token, o un token valido **sin** el scope | API Gateway **y** Spring Security |
| 403 | token valido sin el scope, invocando la Lambda **sin** pasar por el gateway | **Spring Security** (`hasAuthority`); por el gateway ese caso es 401 |
| 404 | el id no existe | **Spring** (`ResponseStatusException`) |
| 409 | el libro existe pero no tiene ejemplares | el controlador |

El 404 es util como diagnostico: API Gateway no puede producirlo (una ruta
desconocida la contesta con 403), asi que recibirlo demuestra que la peticion
atraveso el authorizer, arranco Spring y llego al controlador.
