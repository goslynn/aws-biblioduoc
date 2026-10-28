# Coleccion Bruno · biblioteca-api

Pruebas HTTP de la API, versionadas en el repositorio. Formato
**OpenCollection YAML** (Bruno ≥ 3.0): un `.yml` por peticion, nada en la nube.

La documentacion de que hace cada endpoint **esta dentro de la coleccion**
(pestana *Docs* de cada peticion y de cada carpeta). Este README solo explica
como abrirla, como generar los entornos y como leer la matriz.

---

## 1. Abrir y correr

**Aplicacion de escritorio:** abre la carpeta `bruno/` y elige el entorno arriba
a la derecha.

**Terminal:**

```sh
cd bruno
bru run --env aws
```

En NixOS, si OpenSSL se queja del directorio de certificados:

```sh
SSL_CERT_DIR=/etc/ssl/certs bru run --env aws
```

`bru run` sale con codigo distinto de cero si algun test falla, asi que sirve
tal cual en un pipeline.

---

## 2. Los entornos se generan, no se escriben

```sh
./aws/pipeline/bruno-env.sh
```

El script lee los Outputs de CloudFormation, obtiene **un token por cada caso**
y escribe los archivos de `environments/`. Esos archivos **llevan tokens
reales** y estan en `.gitignore`.

Los tokens duran **60 minutos**. Cuando empieces a ver 401 donde esperabas otra
cosa, vuelve a ejecutarlo: es lo primero que hay que descartar.

---

## 3. La matriz de seguridad vive en los entornos

Esta coleccion tiene **una peticion por endpoint**. Los 401, 403, 404 y 400 no
son peticiones aparte, porque no son endpoints distintos: son el resultado de
llamar **al mismo endpoint con otra credencial**. Y una credencial es
exactamente lo que define un entorno.

Cada entorno declara, ademas del token, el codigo que esa credencial **debe**
obtener (`ESPERADO_LECTURA` y `ESPERADO_CREACION`), y los tests lo leen:

```js
const esperado = Number(bru.getEnvVar("ESPERADO_LECTURA"));
test("responde lo que corresponde a esta credencial", function () {
  expect(res.getStatus()).to.equal(esperado);
});
```

Resultado: la matriz completa esta automatizada, `bru run` pasa en **verde en
los seis casos**, y no hay ni una peticion duplicada.

| Entorno | Credencial | Lecturas | Crear | Que demuestra |
|---|---|---|---|---|
| `aws` | cliente M2M **con** el custom scope | 200 | 201 | el camino completo funciona |
| `aws-usuario-sin-scope` | access token de usuario (`admin-initiate-auth`) | **401** | **401** | un token valido al que le falta el scope |
| `aws-id-token` | id token del mismo usuario | 401 | 401 | un id token no autoriza: no lleva claim `scope` |
| `aws-token-invalido` | `no-es-un-jwt-de-verdad` | 401 | 401 | el authorizer valida la **firma** |

**Los codigos estan medidos, no supuestos.** La fila del token sin scope decia
403 sobre el papel; medida contra el despliegue real, API Gateway responde
**401**, con el mismo cuerpo `{"message":"Unauthorized"}` que ante un token
inventado. El 403 por falta de autorizacion **si existe**, pero lo emite Spring
Security cuando se invoca la Lambda sin pasar por API Gateway.

### La segunda capa no se prueba con Bruno

Demostrar Defense in Depth exige llamar a la funcion **sin pasar por API
Gateway**, y eso Bruno no lo puede hacer:

* la via elegante, una Function URL con `AuthType: NONE`, esta **bloqueada por
  la cuenta de AWS Academy**: devuelve `403 AccessDeniedException` antes de
  llegar a la funcion, aunque el permiso este bien puesto;
* la via que si funciona es `aws lambda invoke`, que no es HTTP.

Los resultados medidos son **401 / 401 / 401 / 403 / 200**: el authorizer no
interviene, y aun asi ninguna peticion sin el scope entrega datos.

---

## 4. Por que hacen falta TRES formas de conseguir un token

Esta es la parte que no es obvia, y la razon de que el script sea como es.

**1. Cliente M2M, `grant_type=client_credentials`.**
Emite un access token **con** el custom scope en una sola llamada HTTP, sin
usuario y sin navegador. Es el unico camino automatizable para el caso bueno.

**2. `admin-initiate-auth` (usuario y contrasena, por AWS CLI).**
Parece la via headless obvia… pero **no emite custom scopes**: sus tokens
llevan `scope: "aws.cognito.signin.user.admin"` y nada mas. Los custom scopes
solo salen por los endpoints OAuth2. Eso, que parece una limitacion, es justo
lo que hace falta: da gratis el token "valido pero sin autorizacion" —que a
traves del gateway produce **401**, y **403** solo al invocar la Lambda
directamente— y de paso el **id token**, que tambien produce **401**.

**3. Ninguna, o una cadena inventada.**
El 401 mas simple.

¿Y el flujo del navegador (Authorization Code + PKCE)? Tambien emite el token
bueno, pero **exige una pantalla de login**: hay un ser humano de por medio por
definicion. Por eso no sirve para automatizar, y por eso existe el cliente M2M.

---

## 5. El caso "sin cabecera Authorization"

No tiene entorno propio. Con el token vacio, Bruno manda igualmente
`Authorization: Bearer` y API Gateway responde el mismo 401. Para reproducir la
**ausencia total** de la cabecera, pon la pestana *Auth* de la peticion en
*No Auth*: esta documentado asi en cada peticion.

---

## 6. Estructura

```
bruno/
├── opencollection.yml      auth bearer declarada UNA vez; docs generales
├── environments/           GENERADOS por bruno-env.sh, en .gitignore
├── libros/
│   ├── folder.yml          modelo del catalogo y reglas
│   ├── list.yml            GET /api/libros      (q, genero, soloDisponibles)
│   └── get.yml             GET /api/libros/:id  (200 · 404)
└── solicitudes/
    ├── folder.yml          reglas de negocio y persistencia
    ├── create.yml          POST /api/solicitudes  (4 variantes de cuerpo)
    ├── list.yml            GET /api/solicitudes   (mias, estado)
    └── get.yml             GET /api/solicitudes/:id
```

Detalles que conviene conocer al tocarla:

* La autenticacion se declara **una sola vez** en `opencollection.yml`; las
  peticiones usan `auth: inherit` y ninguna repite la cabecera.
* Los parametros son **nativos**: `:id` como path param, y `q`/`genero`/`mias`
  como query params desactivados que se activan en la pestana *Params*. No se
  usan variables de entorno para decir *que* se pide: las variables dicen
  *donde* y *con que credencial*.
* El POST lleva **un solo cuerpo**, el valido. Los que producen 400, 404 y 409
  estan en la documentacion de la peticion, listos para pegar. Se intento con
  la forma `variants` que permite OpenCollection y el runner de terminal **no
  la envia**: la peticion sale sin cuerpo y responde 400 por el motivo
  equivocado.
* `create.yml` deja el id creado en una variable de runtime
  (`bru.setVar("solicitudCreada", …)`) que `get.yml` usa como path param. Por
  eso `bru run` funciona de corrido: las `seq` de la carpeta ordenan create → get.

---

## 7. Si algo falla

| Sintoma | Causa mas probable | Solucion |
|---|---|---|
| Todo responde 401 en el entorno `aws` | Los tokens caducaron (60 min) | `./aws/pipeline/bruno-env.sh` |
| 401 donde esperabas 200, con tokens recien generados | El token no trae el custom scope: el authorizer responde igual que ante un token invalido | Comprueba el claim `scope`; el caso vive en el entorno `aws-usuario-sin-scope` |
| `solicitudes/get` da 404 | El id no existe en la base (por ejemplo, tras un `teardown.sh`) | Ejecuta `create` otra vez |
| Todo responde 500 tras reabrir el lab | RDS todavia esta arrancando | Espera a que la instancia este `available` y repite |
| Error de certificados de OpenSSL | NixOS | `SSL_CERT_DIR=/etc/ssl/certs bru run …` |
| Falla `responde lo que corresponde a esta credencial` | Es la señal util: el codigo real no es el que esa credencial deberia obtener | Mira el codigo devuelto y compara con la tabla de la §3 |
