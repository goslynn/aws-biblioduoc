import { Injectable, signal } from '@angular/core';
import {
  fetchAuthSession,
  fetchUserAttributes,
  getCurrentUser,
  signInWithRedirect,
  signOut,
} from 'aws-amplify/auth';

import { awsConfig } from '../aws-config';

/**
 * Lo que la aplicacion necesita saber de la sesion actual.
 *
 * EL TOKEN NO ESTA AQUI, Y ES A PROPOSITO. Ningun componente de negocio tiene
 * que tocarlo: el interceptor se lo pide a Amplify justo antes de cada
 * peticion. Tenerlo en una senal compartida solo abre la puerta a que acabe
 * pintado en una plantilla, que es exactamente lo que se quiere evitar. Quien
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
 * Unico punto de la aplicacion que habla con Amplify Auth para gestionar la
 * SESION: entrar, registrarse, salir y saber quien esta dentro.
 *
 * El resto de componentes leen la senal {@link estado} y no saben nada de
 * Cognito: si manana se cambiara de proveedor de identidad, solo cambiaria
 * este archivo.
 *
 * Hay dos sitios mas que llaman a Amplify, y los dos piden el TOKEN, no la
 * sesion: auth.interceptor.ts, que lo necesita en cada peticion, y la vista
 * de diagnostico, que es opt-in y no se publica salvo con DEBUG=true.
 */
@Injectable({ providedIn: 'root' })
export class SesionService {

  /** Senal de solo lectura con la sesion vigente. */
  readonly estado = signal<EstadoSesion>(SIN_SESION);

  /**
   * Manda al usuario a la Hosted UI de Cognito.
   *
   * A partir de aqui el navegador sale de la aplicacion: se autentica en
   * Cognito y vuelve al redirectUrl con "?code=...". Amplify detecta ese code
   * al arrancar, lo canjea por los tokens (PKCE) y los guarda. Por eso la
   * aplicacion, al volver, ya tiene sesion sin haber hecho nada mas.
   */
  async iniciarSesion(): Promise<void> {
    await signInWithRedirect();
  }

  /**
   * Manda al usuario a la pantalla de REGISTRO de la Hosted UI.
   *
   * Amplify no expone un "signUpWithRedirect": signInWithRedirect siempre
   * aterriza en /login. Pero /signup es un endpoint mas de la Hosted UI y
   * acepta exactamente los mismos parametros que /oauth2/authorize, asi que la
   * URL se arma aqui con los mismos valores que ya usa main.ts para configurar
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
  async registrarse(): Promise<void> {
    const scopes = ['openid', 'email', 'profile', awsConfig.scope].join(' ');
    const parametros = new URLSearchParams({
      client_id: awsConfig.userPoolClientId,
      response_type: 'code',
      scope: scopes,
      redirect_uri: awsConfig.redirectUrl,
    });
    window.location.assign(`https://${awsConfig.hostedUiDomain}/signup?${parametros}`);
  }

  async cerrarSesion(): Promise<void> {
    this.estado.set(SIN_SESION);
    await signOut();
  }

  /**
   * Refresca la senal leyendo la sesion de Amplify.
   *
   * SE PREGUNTA POR EL ACCESS TOKEN, no por el id token: es el unico que lleva
   * el claim "scope" que exigen API Gateway y Spring, y por tanto el unico
   * cuya presencia significa "esta sesion sirve para llamar a la API". Mandar
   * el id token es el error numero uno con esta arquitectura, y se responde
   * con un 401.
   *
   * El token se mira y se tira: solo hace de testigo de que hay sesion. Quien
   * lo manda de verdad es el interceptor, que se lo pide a Amplify en cada
   * peticion y nunca lo lee de aqui.
   *
   * "usuario" es SIEMPRE algo legible por humanos (correo o loginId), nunca
   * el "username" que devuelve getCurrentUser(): con UsernameAttributes:
   * email en el User Pool, ese campo es el sub en formato UUID, no el correo.
   * Si no se puede resolver ninguno de los dos, queda vacio en vez de mostrar
   * el UUID.
   *
   * "empleado" sale del claim "cognito:groups" del MISMO access token (su
   * contenido ya decodificado; el token en si no se guarda).
   */
  async refrescar(): Promise<EstadoSesion> {
    try {
      const sesion = await fetchAuthSession();
      const accessToken = sesion.tokens?.accessToken;
      const token = accessToken?.toString() ?? '';
      if (!token) {
        this.estado.set(SIN_SESION);
        return SIN_SESION;
      }
      const usuarioActual = await getCurrentUser();
      let usuario = usuarioActual.signInDetails?.loginId ?? '';
      if (!usuario) {
        try {
          const atributos = await fetchUserAttributes();
          usuario = atributos.email ?? '';
        } catch {
          usuario = '';
        }
      }
      const grupos = accessToken?.payload['cognito:groups'];
      const empleado = Array.isArray(grupos) && grupos.includes(GRUPO_EMPLEADOS);
      const nuevo: EstadoSesion = { autenticado: true, usuario, empleado };
      this.estado.set(nuevo);
      return nuevo;
    } catch {
      // No hay sesion: no es un error, es el estado normal antes del login.
      this.estado.set(SIN_SESION);
      return SIN_SESION;
    }
  }
}
