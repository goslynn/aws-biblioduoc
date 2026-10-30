import { Component, OnInit, inject, signal } from '@angular/core';
import { fetchAuthSession } from 'aws-amplify/auth';

import { awsConfig } from '../aws-config';
import { SesionService } from '../auth/sesion.service';

/**
 * Vista de diagnostico. OPT-IN: solo existe si la lista de rutas.ts -- que
 * genera el pipeline desde la variable de entorno DEBUG, false por defecto --
 * viene con la ruta dentro. Con DEBUG=false no la importa nadie, Angular ni la
 * compila, y en el sitio publicado no hay ruta, ni enlace, ni chunk.
 *
 * POR QUE ESTA ESCONDIDA
 *   Ensena el access token en claro. Es material didactico, no una pantalla de
 *   negocio: quien mire por encima del hombro se lleva una credencial valida
 *   durante una hora. Para explicar la arquitectura se enciende a proposito;
 *   para entregar la aplicacion se deja apagada.
 *
 * Muestra tres cosas, y cada una prueba algo distinto:
 *
 *   1. El access token y su payload decodificado -> se ve el claim "scope" con
 *      el custom scope, el "token_use": "access" y el "client_id". Es la
 *      diferencia entre id token y access token, mirandola de frente.
 *
 *   2. Una llamada MANUAL con fetch, poniendo la cabecera Authorization a
 *      mano. Es redundante teniendo el interceptor, y es deliberado: hasta que
 *      no ves la cabecera escrita a mano, el interceptor parece magia.
 *
 *   3. La misma llamada SIN cabecera: responde 401. Ese 401 lo produce API
 *      Gateway antes de llegar a la EC2.
 *
 * ES LA UNICA EXCEPCION a la regla de que solo sesion.service.ts habla con
 * Amplify, y lo es por diseno: el token crudo ya NO viaja en EstadoSesion,
 * justamente para que ningun componente de negocio lo tenga a mano. Quien lo
 * quiera, que lo pida aqui y que se vea en el codigo.
 */
@Component({
  selector: 'app-diagnostico',
  templateUrl: './diagnostico.html',
})
export class Diagnostico implements OnInit {

  private readonly sesionService = inject(SesionService);

  readonly estado = this.sesionService.estado;
  readonly accessToken = signal<string>('');
  readonly payload = signal<string>('');
  readonly resultadoManual = signal<string>('');
  readonly resultadoSinToken = signal<string>('');
  readonly config = awsConfig;

  async ngOnInit(): Promise<void> {
    await this.sesionService.refrescar();
    const token = await this.tokenDeAcceso();
    this.accessToken.set(token);
    const payload = this.payloadDelToken(token);
    this.payload.set(payload ? JSON.stringify(payload, null, 2) : '');
  }

  /** Consumo manual: la cabecera se escribe aqui, sin interceptor de por medio. */
  async llamarConTokenAMano(): Promise<void> {
    this.resultadoManual.set('llamando…');
    try {
      const respuesta = await fetch(`${awsConfig.apiBaseUrl}/api/libros`, {
        headers: { Authorization: `Bearer ${this.accessToken()}` },
      });
      const cuerpo = await respuesta.text();
      this.resultadoManual.set(`HTTP ${respuesta.status}\n${this.recortar(cuerpo)}`);
    } catch (e) {
      this.resultadoManual.set(`Sin respuesta: ${(e as Error).message}\n(mira la pestana Network: suele ser CORS)`);
    }
  }

  /** La misma peticion sin credencial: el 401 lo da API Gateway. */
  async llamarSinToken(): Promise<void> {
    this.resultadoSinToken.set('llamando…');
    try {
      const respuesta = await fetch(`${awsConfig.apiBaseUrl}/api/libros`);
      const cuerpo = await respuesta.text();
      this.resultadoSinToken.set(`HTTP ${respuesta.status}\n${this.recortar(cuerpo)}`);
    } catch (e) {
      this.resultadoSinToken.set(`Sin respuesta: ${(e as Error).message}`);
    }
  }

  /**
   * Pide a Amplify el access token crudo.
   *
   * SE PIDE EL ACCESS TOKEN, no el id token. El id token dice QUIEN eres; el
   * access token dice A QUE tienes derecho, y es el unico que lleva el claim
   * "scope" que exigen API Gateway y Spring. Mandar el id token es el error
   * numero uno con esta arquitectura, y aqui se responde con un 401.
   */
  private async tokenDeAcceso(): Promise<string> {
    try {
      const sesion = await fetchAuthSession();
      return sesion.tokens?.accessToken?.toString() ?? '';
    } catch {
      return '';
    }
  }

  /**
   * Decodifica el payload del JWT para mostrarlo en pantalla.
   *
   * Es un fin PEDAGOGICO: sirve para VER que trae el token (token_use,
   * client_id, scope, exp). No valida nada, y no debe usarse para decidir
   * permisos: un JWT se lee sin la clave, pero solo se puede CONFIAR en el
   * despues de verificar su firma, y eso ocurre en API Gateway y en Spring.
   */
  private payloadDelToken(token: string): Record<string, unknown> | null {
    const partes = token.split('.');
    if (partes.length !== 3) {
      return null;
    }
    try {
      const base64 = partes[1].replace(/-/g, '+').replace(/_/g, '/');
      return JSON.parse(atob(base64)) as Record<string, unknown>;
    } catch {
      return null;
    }
  }

  private recortar(texto: string): string {
    return texto.length > 400 ? `${texto.slice(0, 400)}…` : texto;
  }
}
