import { fetchAuthSession, signInWithRedirect, signOut } from 'aws-amplify/auth';

import { crearAlmacen } from '../almacen';
import { awsConfig } from '../aws-config';

/**
 * Lo que la aplicacion necesita saber de la sesion actual.
 *
 * EL TOKEN NO ESTA AQUI, Y ES A PROPOSITO. Ningun componente de negocio tiene
 * que tocarlo: el cliente HTTP (http.ts) se lo pide a Amplify justo antes de
 * cada peticion. Tenerlo en un estado compartido solo abre la puerta a que
 * acabe pintado en pantalla, que es exactamente lo que se quiere evitar. Quien
 * de verdad lo necesita -- la vista de diagnostico, que es opt-in -- se lo
 * pide a Amplify y se le ve hacerlo.
 */
export interface EstadoSesion {
  autenticado: boolean;
  usuario: string;
  /**
   * Si la persona esta en el grupo de empleados de Cognito. SOLO decide que
   * se muestra (los enlaces de gestion); quien decide que se PUEDE hacer es
   * Spring, que lee el mismo claim del token ya validado.
   */
  empleado: boolean;
}

const SIN_SESION: EstadoSesion = { autenticado: false, usuario: '', empleado: false };

/** Grupo de Cognito de los empleados (cognito.yaml, EmployeesGroupName). */
const GRUPO_EMPLEADOS = 'empleados';

/**
 * Idioma de las pantallas de Cognito. Managed Login (cognito.yaml,
 * ManagedLoginVersion: 2) las traduce si se le pide con el parametro "lang";
 * sin el, salen en ingles.
 */
const IDIOMA_LOGIN = 'es';

/*
 * Unico punto de la aplicacion que habla con Amplify Auth para gestionar la
 * SESION: entrar, registrarse, salir y saber quien esta dentro.
 *
 * El resto de componentes leen useSesion() y no saben nada de Cognito: si
 * manana se cambiara de proveedor de identidad, solo cambiaria este archivo.
 *
 * Hay dos sitios mas que llaman a Amplify, y los dos piden el TOKEN, no la
 * sesion: http.ts, que lo necesita en cada peticion, y la vista de
 * diagnostico, que es opt-in y no se publica salvo con DEBUG=true.
 */
const estado = crearAlmacen<EstadoSesion>(SIN_SESION);

/** Hook con la sesion vigente. Repinta el componente cuando cambia. */
export const useSesion = estado.usar;

/**
 * Si la pagina se abrio de vuelta de Cognito ("?code=..." en la URL).
 *
 * Se mira AL CARGAR EL MODULO, antes de que exista el router: la ruta "/" a la
 * que vuelve Cognito redirige a /inicio y esa redireccion se lleva la query.
 * Mientras Amplify canjea el code por los tokens (una peticion a /oauth2/token
 * que tarda lo suyo), App pinta una pantalla de carga en vez de la portada
 * sin sesion.
 */
const canjeando = crearAlmacen<boolean>(new URLSearchParams(window.location.search).has('code'));

/** Hook: true mientras se procesa la vuelta del login. */
export const useCanjeando = canjeando.usar;

/** Lo llama App cuando ya sabe adonde ir tras el login (o que fallo). */
export function terminarCanje(): void {
  canjeando.fijar(false);
}

/**
 * Manda al usuario a la pantalla de login de Cognito (Managed Login).
 *
 * A partir de aqui el navegador sale de la aplicacion: se autentica en
 * Cognito y vuelve al redirectUrl con "?code=...". Amplify detecta ese code
 * al arrancar, lo canjea por los tokens (PKCE) y los guarda. Por eso la
 * aplicacion, al volver, ya tiene sesion sin haber hecho nada mas.
 */
export async function iniciarSesion(): Promise<void> {
  await signInWithRedirect({ options: { lang: IDIOMA_LOGIN } });
}

/**
 * Manda al usuario a la pantalla de REGISTRO de Cognito.
 *
 * Amplify no expone un "signUpWithRedirect": signInWithRedirect siempre
 * aterriza en /login. Pero /signup es un endpoint mas de Managed Login y
 * acepta exactamente los mismos parametros que /oauth2/authorize, asi que la
 * URL se arma aqui con los mismos valores que ya usa main.tsx para configurar
 * Amplify. No hay ningun identificador escrito a mano: todos salen de
 * aws-config.ts, que genera el pipeline.
 *
 * QUE PASA DESPUES, Y POR QUE NO HAY QUE PROGRAMAR NADA MAS
 *   Cognito pide correo y contrasena, crea el usuario UNCONFIRMED y le manda
 *   un codigo de seis digitos. En cuanto lo teclea, la MISMA pantalla lo
 *   confirma e inicia sesion, y el navegador vuelve al redirect_uri con
 *   "?code=...". A partir de ahi es el flujo de siempre: Amplify canjea el
 *   code por los tokens (PKCE) y la aplicacion arranca con sesion.
 *
 *   Por eso se piden aqui los mismos scopes que en el login: el access token
 *   que sale de este camino tiene que traer el custom scope, o la API
 *   respondera 401 a un usuario recien registrado.
 */
export function registrarse(): void {
  const scopes = ['openid', 'email', 'profile', awsConfig.scope].join(' ');
  const parametros = new URLSearchParams({
    client_id: awsConfig.userPoolClientId,
    response_type: 'code',
    scope: scopes,
    redirect_uri: awsConfig.redirectUrl,
    lang: IDIOMA_LOGIN,
  });
  window.location.assign(`https://${awsConfig.hostedUiDomain}/signup?${parametros}`);
}

export async function cerrarSesion(): Promise<void> {
  estado.fijar(SIN_SESION);
  await signOut();
}

/**
 * Refresca el estado leyendo la sesion de Amplify.
 *
 * SE PREGUNTA POR EL ACCESS TOKEN, no por el id token: es el unico que lleva
 * el claim "scope" que exigen API Gateway y Spring, y por tanto el unico
 * cuya presencia significa "esta sesion sirve para llamar a la API". Mandar
 * el id token es el error numero uno con esta arquitectura, y se responde
 * con un 401.
 *
 * El token se mira y se tira: solo hace de testigo de que hay sesion. Quien
 * lo manda de verdad es http.ts, que se lo pide a Amplify en cada peticion y
 * nunca lo lee de aqui.
 *
 * "usuario" es el correo, y sale del claim "email" del ID TOKEN. No se le
 * pregunta a Cognito con fetchUserAttributes(): eso es la API GetUser, que
 * exige el scope "aws.cognito.signin.user.admin" en el access token, y el
 * SpaClient no lo pide (ni debe: abriria la puerta a que el navegador edite
 * los atributos del usuario). Tampoco sirve signInDetails.loginId de
 * getCurrentUser(): solo existe tras un login con usuario y contrasena en la
 * propia aplicacion, y aqui se entra por redireccion. El id token si lo trae,
 * porque se pide el scope "email".
 *
 * "empleado" sale del claim "cognito:groups" del MISMO access token (su
 * contenido ya decodificado; el token en si no se guarda).
 */
export async function refrescar(): Promise<EstadoSesion> {
  try {
    const sesion = await fetchAuthSession();
    const accessToken = sesion.tokens?.accessToken;
    const token = accessToken?.toString() ?? '';
    if (!token) {
      estado.fijar(SIN_SESION);
      return SIN_SESION;
    }
    const correo = sesion.tokens?.idToken?.payload['email'];
    const usuario = typeof correo === 'string' ? correo : '';
    const grupos = accessToken?.payload['cognito:groups'];
    const empleado = Array.isArray(grupos) && grupos.includes(GRUPO_EMPLEADOS);
    const nuevo: EstadoSesion = { autenticado: true, usuario, empleado };
    estado.fijar(nuevo);
    return nuevo;
  } catch {
    // No hay sesion: no es un error, es el estado normal antes del login.
    estado.fijar(SIN_SESION);
    return SIN_SESION;
  }
}
