# Frontend · biblioteca-front

Aplicacion Angular 22 (standalone, signals) con **AWS Amplify Auth 6**,
publicada como sitio estatico en S3.

---

## 1. Compilar y publicar

El pipeline lo hace todo:

```sh
./aws/pipeline/publish-web.sh      # genera la config, compila y sube a S3
```

A mano:

```sh
cd front
npm ci
npm run build       # deja dist/biblioteca-front/browser/
```

### Antes de compilar hacen falta dos archivos generados

Los **genera** el pipeline y **no se versionan**: uno depende de la cuenta AWS
y el otro de como se quiera publicar el sitio.

| Archivo | De donde sale | Plantilla |
|---|---|---|
| `src/app/aws-config.ts` | Outputs de CloudFormation | `aws-config.example.ts` |
| `src/app/diagnostico/rutas.ts` | variable de entorno `DEBUG` | `rutas.example.ts` (apagada) · `rutas.example-debug.ts` (encendida) |

Para trabajar sin desplegar nada:

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
src/
├── main.ts                     Amplify.configure() ANTES de bootstrapApplication
├── index.html  styles.css
└── app/
    ├── app.config.ts           provideHttpClient(withInterceptors([...])) + withHashLocation()
    ├── app.routes.ts           /inicio publica; el resto tras authGuard (y /gestion tras empleadoGuard), lazy
    ├── app.ts / app.html       marco: cabecera, navegacion, estado de sesion
    ├── inicio.ts / .html       portada publica: botones de login y de registro
    ├── auth/
    │   ├── sesion.service.ts   estado de la sesion: entrar, registrarse, salir, ¿empleado?
    │   ├── auth.interceptor.ts anade el Bearer a toda peticion de HttpClient
    │   └── auth.guard.ts       authGuard y empleadoGuard: bloquean la NAVEGACION
    ├── catalogo/               buscador, filtros, seleccion y "avisame cuando vuelva"
    ├── solicitudes/            formulario de prestamo y "mis solicitudes" (con los avisos por correo)
    ├── avisos/                 activar / desactivar los avisos (el consentimiento)
    ├── gestion/                SOLO EMPLEADOS: bandeja (aprobar, rechazar, devolver) y stock
    ├── diagnostico/            OPT-IN: token, payload y fetch manual
    │   ├── diagnostico.ts/.html  solo se compila si la ruta existe
    │   └── rutas.ts              GENERADO segun DEBUG (vacio = no se compila)
    └── aws-config.ts           GENERADO (aws-config.example.ts es la plantilla)
```

---

## 3. Siete cosas que hay que mirar dos veces

**`provideHttpClient(withInterceptors([...]))` va DENTRO del array
`providers`.** Si se cierra el objeto antes, el archivo compila, la aplicacion
arranca y el interceptor **no queda registrado**: las peticiones salen sin
`Bearer` y todo responde 401. Es el error que la guia del curso marca dos veces.

**Se manda el ACCESS token, no el id token.** El id token dice quien eres; el
access token dice a que tienes derecho, y es el unico que lleva el claim
`scope`. Con un id token, API Gateway responde 401.

**El token no esta en `EstadoSesion`.** La senal de sesion solo lleva
`autenticado`, `usuario` y `empleado`. Guardarlo ahi no hace falta -- el interceptor se lo
pide a Amplify en cada peticion -- y solo abre la puerta a que acabe pintado en
una plantilla. Lo piden dos archivos, y los dos se ven venir:
`auth.interceptor.ts` y la vista de diagnostico, que es **opt-in** y con
`DEBUG=false` ni se compila (README raiz §5).

**El Guard no protege la API.** Controla la navegacion, y corre en el navegador
del usuario. Quien protege son API Gateway y Spring Security. El Guard esta
porque hay rutas privadas de verdad y sin el se verian pantallas vacias llenas
de 401.

**`empleado` es solo para pintar.** Sale del claim `cognito:groups` del access
token y decide si se ven *Bandeja* y *Stock*. Quien decide si se PUEDE es
Spring, que lee el mismo claim del token ya validado: un estudiante que forzara
`#/gestion/solicitudes` veria la pantalla y un 403 en cada accion. Tras meter a
alguien en el grupo, tiene que volver a iniciar sesion: el claim va en el token.

**El registro no lo hace Amplify.** No existe un `signUpWithRedirect`:
`signInWithRedirect()` siempre aterriza en `/login`. Pero `/signup` es un
endpoint mas de la Hosted UI y acepta los mismos parametros que
`/oauth2/authorize`, asi que `sesion.service.ts` arma esa URL con los valores de
`aws-config.ts`. **Tiene que pedir los mismos scopes que el login**, custom
scope incluido: si se olvida, el usuario se registra bien y la API le responde
401 a la primera peticion.

**Las rutas llevan `#` (`withHashLocation`).** El sitio se sirve por el endpoint
REST de S3, que devuelve los objetos que existen y nada mas: con rutas limpias,
recargar en `/catalogo` daria 404. El *website endpoint* si redirige a
`index.html`, pero es HTTP puro y Cognito rechaza callbacks sin TLS.

---

## 4. Recorrido de comprobacion en el navegador

Con DevTools abierto en la pestana **Network**:

Hace falta **una casilla de correo real**: las cuentas de demostracion tienen
direcciones inventadas. Para el estudiante, registrate con la tuya; el
empleado puede ser `empleado@duoc.cl` (su contrasena esta en
`aws/.local/usuario-demo.env`), en otra ventana privada.

1. **Crear cuenta** (estudiante) → `/signup` → codigo de 6 digitos al correo →
   vuelve ya autenticado. (O **Iniciar sesion** con una cuenta existente.)
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
9. Con el estudiante, forzar `#/gestion/solicitudes`: redirige al catalogo (el
   guard); y aunque se lo saltara, Spring responderia 403.
10. En Network: **ni un solo error de CORS** (incluidos `PUT` y `DELETE`), y el
    `Authorization: Bearer` en cada peticion **sin que ningun componente lo
    haya puesto**.

Para ver el recordatorio sin esperar dias, despliega con
`REMINDER_LEAD=PT23H58M ./aws/pipeline/build-backend.sh`, pide un prestamo de
**1 dia** y apruebalo: a los ~2 minutos llega "vence pronto".

Ese ultimo punto es la evidencia de que el interceptor funciona, y el silencio
en CORS es la evidencia de que el preflight y los `GatewayResponses` estan bien.

### El paso que no esta, salvo que se pida

Si el sitio se publico con `DEBUG=true` hay ademas un enlace **Diagnostico** en
la cabecera: el access token, su payload (`token_use`, `client_id`, `scope`) y
dos llamadas manuales con `fetch` (con cabecera a mano → 200, sin cabecera →
401). Es para explicar la arquitectura, no para entregar: ensena una credencial
valida en claro.

Con `DEBUG=false` -- el valor por defecto -- ese enlace no existe, la ruta
tampoco, el componente ni siquiera se compila y pedir `#/diagnostico` a mano
redirige al inicio.
