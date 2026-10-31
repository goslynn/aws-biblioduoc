# Frontend · biblioteca-front

Aplicacion **React 19** con **React Router 8** (rutas de datos), compilada con
**Vite** y con **AWS Amplify Auth 6**, servida por **Caddy** (en Docker, en la
EC2 de `aws/web.yaml`) con HTTPS de Let's Encrypt. Como la sirve Caddy segun
`Caddyfile`, las rutas son limpias (`/catalogo`) y la API se llama en el mismo
origen (`/api/...`).

Antes era Angular 22. La migracion conserva las mismas vistas, la misma
integracion con Cognito (Amplify, code + PKCE) y el mismo contrato con la API.
Cada pieza de Angular tiene su equivalente:

| Angular | React | Archivo |
|---|---|---|
| `bootstrapApplication` tras `Amplify.configure` | `createRoot` tras `Amplify.configure` | `src/main.tsx` |
| `SesionService` (signal, `providedIn: 'root'`) | almacen de modulo + hook `useSesion()` | `app/auth/sesion.ts` |
| `authInterceptor` de `HttpClient` | cliente `http` sobre `fetch` | `app/auth/http.ts` |
| `authGuard` / `empleadoGuard` (`canActivate`) | `requiereSesion` / `requiereEmpleado` (`loader`) | `app/auth/guardas.ts` |
| `provideRouter(..., withHashLocation())` | `createBrowserRouter` (rutas limpias: ya no hace falta el `#`) | `app/rutas.tsx` |
| `loadComponent: () => import(...)` | `lazy: () => import(...)` | `app/rutas.tsx` |
| `title` de la ruta | `handle.titulo`, que aplica `App` | `app/rutas.tsx`, `app/app.tsx` |
| `SeleccionService` | almacen de modulo + `useSeleccion()` | `app/catalogo/seleccion.ts` |
| servicios con `Observable` | objetos con funciones que devuelven `Promise` | `*.service.ts` |
| pipe `date: 'dd-MM-yyyy HH:mm'` | `fechaHora()` | `app/formato.ts` |

---

## 1. Compilar y publicar

El pipeline lo hace todo:

```sh
./aws/pipeline/publish-web.sh      # genera la config, compila, empaqueta y reinicia Caddy
```

A mano:

```sh
cd front
npm ci
npm run build       # tsc -b (solo tipos) + vite build; deja dist/
```

No hay `npm run dev` ni servidor de desarrollo, a proposito: `localhost` no esta
en las listas blancas de Cognito ni en el CORS. Se comprueba en el sitio
publicado (§4).

### Antes de compilar hacen falta dos archivos generados

Los **genera** el pipeline y **no se versionan**: uno depende de la cuenta AWS
y el otro de como se quiera publicar el sitio.

| Archivo | De donde sale | Plantilla |
|---|---|---|
| `src/app/aws-config.ts` | Outputs de CloudFormation | `aws-config.example.ts` |
| `src/app/diagnostico/rutas.ts` | variable de entorno `DEBUG` | `rutas.example.ts` (apagada) · `rutas.example-debug.ts` (encendida) |

Para compilar sin desplegar nada:

```sh
cp src/app/aws-config.example.ts           src/app/aws-config.ts
cp src/app/diagnostico/rutas.example.ts    src/app/diagnostico/rutas.ts
```

Para generarlos de verdad sin compilar ni subir:

```sh
SOLO_CONFIG=true ./aws/pipeline/publish-web.sh
```

Ninguno de esos valores es secreto: el App Client del SPA se crea **sin**
`client_secret` a proposito, porque todo lo que va en un bundle de JavaScript es
publico. Lo que protege el flujo es PKCE.

---

## 2. Estructura

```
Caddyfile                       como se sirve: HTTPS, CSP/HSTS, /api -> API Gateway, rutas limpias
index.html                      punto de entrada de Vite (<div id="raiz">)
vite.config.ts                  salida a dist/, base '/' (raiz del sitio)
tsconfig.json                   comprueba TODO src/, diagnostico incluido
src/
├── main.tsx                    Amplify.configure() ANTES de montar React
├── styles.css
└── app/
    ├── rutas.tsx               createBrowserRouter; /inicio publica, el resto tras una guarda, lazy
    ├── app.tsx                 marco: cabecera, navegacion, estado de sesion, titulo
    ├── inicio.tsx              portada publica
    ├── almacen.ts              estado compartido fuera de React (useSyncExternalStore)
    ├── formato.ts              fechas como dd-MM-yyyy HH:mm
    ├── auth/
    │   ├── sesion.ts           entrar, registrarse, salir, ¿empleado? (+ hook useSesion)
    │   ├── http.ts             UNICO cliente de la API (/api, mismo origen): anade el Bearer
    │   └── guardas.ts          requiereSesion y requiereEmpleado: bloquean la NAVEGACION
    ├── catalogo/               buscador, filtros, seleccion y "avisame cuando vuelva"
    ├── solicitudes/            formulario de prestamo y "mis solicitudes" (con los avisos por correo)
    ├── avisos/                 activar / desactivar los avisos (el consentimiento)
    ├── gestion/                SOLO EMPLEADOS: bandeja (aprobar, rechazar, devolver) y stock
    ├── diagnostico/            OPT-IN: token, payload y fetch manual
    │   ├── diagnostico.tsx       solo se empaqueta si la ruta existe
    │   └── rutas.ts              GENERADO segun DEBUG (vacio = no se empaqueta)
    └── aws-config.ts           GENERADO (aws-config.example.ts es la plantilla)
```

---

## 3. Ocho cosas que hay que mirar dos veces

**Toda llamada a la API pasa por `auth/http.ts`.** Es el papel que en Angular
hacia el interceptor: se pide el token a Amplify justo antes de cada peticion y
se anade `Authorization: Bearer`. Un `fetch` suelto en un componente saldria sin
token y recibiria 401. La unica excepcion, y declarada, es la vista de
diagnostico.

**Se manda el ACCESS token, no el id token.** El id token dice quien eres; el
access token dice a que tienes derecho, y es el unico que lleva el claim
`scope`. Con un id token, API Gateway responde 401.

**El token no esta en `EstadoSesion`.** El estado de sesion solo lleva
`autenticado`, `usuario` y `empleado`. Guardarlo ahi no hace falta --
`http.ts` se lo pide a Amplify en cada peticion -- y solo abre la puerta a que
acabe pintado en pantalla. Lo piden dos archivos, y los dos se ven venir:
`auth/http.ts` y la vista de diagnostico, que es **opt-in** y con
`DEBUG=false` ni se empaqueta (README raiz §5).

**La sesion y la seleccion son almacenes de modulo, no Context.** Las guardas
son `loader` de React Router: corren antes de pintar y fuera de cualquier
componente, asi que un Context no les llegaria. `almacen.ts` guarda el valor en
el modulo y lo expone con `useSyncExternalStore`: guardas y componentes leen el
MISMO estado, como hacian los servicios singleton de Angular.

**La guarda no protege la API.** Controla la navegacion, y corre en el
navegador del usuario. Quien protege son API Gateway y Spring Security. La
guarda esta porque hay rutas privadas de verdad y sin ella se verian pantallas
vacias llenas de 401.

**`empleado` es solo para pintar.** Sale del claim `cognito:groups` del access
token y decide si se ven *Bandeja* y *Stock*. Quien decide si se PUEDE es
Spring, que lee el mismo claim del token ya validado: un estudiante que forzara
`/gestion/solicitudes` veria la pantalla y un 403 en cada accion. Tras meter a
alguien en el grupo, tiene que volver a iniciar sesion: el claim va en el token.

**Las pantallas de login y registro son de Cognito, en castellano.** Son
Managed Login (`aws/cognito.yaml`), con la marca de la Biblioteca. El idioma lo
pide `sesion.ts` con `lang=es`, en `signInWithRedirect` y en la URL de
`/signup`. El registro no lo hace Amplify: no existe un `signUpWithRedirect`,
asi que `sesion.ts` arma la URL de `/signup` con los valores de `aws-config.ts`.
**Tiene que pedir los mismos scopes que el login**, custom scope incluido: si
se olvida, el usuario se registra bien y la API le responde 401 a la primera
peticion.

**La API va por el mismo origen, y las rutas no llevan `#`. Las dos cosas
las hace el `Caddyfile`.** `/api/*` se reenvia a API Gateway (con el `Host`
del gateway, o responde 403), asi que `http.ts` pide `/api/libros` al propio
sitio: ni preflight ni CORS. Cualquier otra ruta que no sea un archivo devuelve
`index.html`, y por eso `createBrowserRouter` funciona al recargar en
`/catalogo`. Con el sitio en S3 no se podia: de ahi venia el `#`. La CSP del
`Caddyfile` solo deja al JavaScript hablar con el sitio y con Cognito; si
algun dia se llama a otro origen, hay que anadirlo a `connect-src`.

---

## 4. Recorrido de comprobacion en el navegador

Con DevTools abierto en la pestana **Network**:

Hace falta **una casilla de correo real**: las cuentas de demostracion tienen
direcciones inventadas. Para el estudiante, registrate con la tuya; el
empleado puede ser `empleado@duoc.cl` (su contrasena esta en
`aws/.local/usuario-demo.env`), en otra ventana privada.

1. **Crear cuenta** (estudiante) → `/signup` **en castellano y con el logo de
   la Biblioteca** → codigo de 6 digitos al correo → vuelve ya autenticado. (O
   **Iniciar sesion** con una cuenta existente: la misma marca.)
2. **Mis solicitudes → Activar avisos por correo** → PUT 200 con tu email, y
   llega el correo de bienvenida.
3. **Catalogo**: buscar y filtrar. Cada busqueda es una peticion real.
4. **Agregar** libros y **Completar la solicitud** → 201 al instante; el
   comprobante llega por correo segundos despues.
5. En un libro agotado (*Building Microservices*, *Terraform*): **Avisame
   cuando vuelva** → 201; el boton pasa a *Dejar de avisarme*.
6. **Empleado → Bandeja**: la solicitud esta RECIBIDA. **Aprobar** → 200 → el
   estudiante recibe "aprobada" con la fecha de devolucion, y en *Mis
   solicitudes* la ve *Aprobada*. (O **Rechazar** con motivo → correo con el
   motivo.)
7. **Empleado → Stock**: cargar el libro agotado del paso 5 → el estudiante
   recibe "Volvio: ..." y su suscripcion se cierra.
8. **Empleado → Bandeja → Registrar devolucion** → correo de devolucion.
9. Con el estudiante, forzar `/gestion/solicitudes`: redirige al catalogo (la
   guarda); y aunque se la saltara, Spring responderia 403.
10. Recargar (F5) en `/mis-solicitudes`: sigue en la misma vista, sin 404.
11. En Network: todas las llamadas van a `/api/...` **del mismo origen**, sin
    ningun `OPTIONS`; el `Authorization: Bearer` en cada peticion **sin que
    ningun componente lo haya puesto**. En la consola, **ni un aviso de la
    CSP**. En la respuesta de `/`, las cabeceras `Strict-Transport-Security` y
    `Content-Security-Policy`, y el candado con un certificado de Let's
    Encrypt.

Para ver el recordatorio sin esperar dias, despliega con
`REMINDER_LEAD=PT23H58M ./aws/pipeline/build-backend.sh`, pide un prestamo de
**1 dia** y apruebalo: a los ~2 minutos llega "vence pronto".

Ese ultimo punto es la evidencia de que `auth/http.ts` y el proxy del
`Caddyfile` funcionan. El CORS del gateway sigue configurado (con el origen del
sitio) para quien llame a API Gateway directamente; desde el sitio ya no
interviene.

### El paso que no esta, salvo que se pida

Si el sitio se publico con `DEBUG=true` hay ademas un enlace **Diagnostico** en
la cabecera: el access token, su payload (`token_use`, `client_id`, `scope`) y
dos llamadas manuales con `fetch` (con cabecera a mano → 200, sin cabecera →
401). Es para explicar la arquitectura, no para entregar: ensena una credencial
valida en claro.

Con `DEBUG=false` -- el valor por defecto -- ese enlace no existe, la ruta
tampoco, el componente ni siquiera se empaqueta y pedir `/diagnostico` a mano
redirige al inicio.
