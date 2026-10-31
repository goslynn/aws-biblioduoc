import { fetchAuthSession } from 'aws-amplify/auth';
import { useEffect, useState } from 'react';

import { useSesion } from '../auth/sesion';
import { awsConfig } from '../aws-config';

/**
 * Vista de diagnostico. OPT-IN: solo existe si la lista de rutas.ts -- que
 * genera el pipeline desde la variable de entorno DEBUG, false por defecto --
 * viene con la ruta dentro. Con DEBUG=false no la importa nadie, Vite ni la
 * empaqueta, y en el sitio publicado no hay ruta, ni enlace, ni chunk.
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
 *      mano. Es redundante teniendo auth/http.ts, y es deliberado: hasta que
 *      no ves la cabecera escrita a mano, el cliente HTTP parece magia.
 *
 *   3. La misma llamada SIN cabecera: responde 401. Ese 401 lo produce API
 *      Gateway antes de llegar a la EC2.
 *
 * ES LA UNICA EXCEPCION a la regla de que solo sesion.ts y http.ts hablan con
 * Amplify, y lo es por diseno: el token crudo NO viaja en EstadoSesion,
 * justamente para que ningun componente de negocio lo tenga a mano. Quien lo
 * quiera, que lo pida aqui y que se vea en el codigo.
 */
export function Diagnostico() {
  // La sesion ya la refresco la guarda de la ruta (requiereSesion).
  const estado = useSesion();
  const [accessToken, setAccessToken] = useState('');
  const [resultadoManual, setResultadoManual] = useState('');
  const [resultadoSinToken, setResultadoSinToken] = useState('');

  useEffect(() => {
    void tokenDeAcceso().then(setAccessToken);
  }, []);

  const payload = payloadDelToken(accessToken);

  /** Consumo manual: la cabecera se escribe aqui, sin auth/http.ts de por medio. */
  const llamarConTokenAMano = async () => {
    setResultadoManual('llamando…');
    try {
      const respuesta = await fetch('/api/libros', {
        headers: { Authorization: `Bearer ${accessToken}` },
      });
      const cuerpo = await respuesta.text();
      setResultadoManual(`HTTP ${respuesta.status}\n${recortar(cuerpo)}`);
    } catch (e) {
      setResultadoManual(`Sin respuesta: ${(e as Error).message}\n(mira la consola: suele ser la CSP)`);
    }
  };

  /** La misma peticion sin credencial: el 401 lo da API Gateway. */
  const llamarSinToken = async () => {
    setResultadoSinToken('llamando…');
    try {
      const respuesta = await fetch('/api/libros');
      const cuerpo = await respuesta.text();
      setResultadoSinToken(`HTTP ${respuesta.status}\n${recortar(cuerpo)}`);
    } catch (e) {
      setResultadoSinToken(`Sin respuesta: ${(e as Error).message}`);
    }
  };

  return (
    <>
      <section className="panel">
        <h1>Diagnostico</h1>
        <p className="aviso">
          Pantalla de depuracion, visible solo porque el sitio se publico con{' '}
          <code>DEBUG=true</code>. Ensena el access token en claro: en una
          publicacion normal no existe ni la ruta ni este enlace.
        </p>
        <p className="tenue">
          Existe para ver lo que normalmente esta escondido: el token, lo que lleva
          dentro y como responde la API segun con que se la llame.
        </p>

        {!estado.autenticado ? (
          <p className="aviso">No hay sesion activa.</p>
        ) : (
          <>
            <dl className="datos">
              <dt>Usuario</dt><dd>{estado.usuario}</dd>
              <dt>API</dt><dd><code>{window.location.origin}/api</code> (Caddy la reenvia a API Gateway)</dd>
              <dt>Scope exigido</dt><dd><code>{awsConfig.scope}</code></dd>
            </dl>

            <h3>Access token</h3>
            <p className="tenue">
              Se manda el ACCESS token y no el id token: el id token dice quien eres,
              pero no lleva el claim <code>scope</code>, y el metodo de API Gateway lo
              exige. Con un id token la respuesta seria 401.
            </p>
            <textarea rows={4} readOnly value={accessToken} />

            <h3>Payload decodificado</h3>
            <p className="tenue">
              Fijate en <code>token_use</code>, <code>client_id</code> y sobre todo en{' '}
              <code>scope</code>. Se decodifica en el navegador solo para mirarlo: la
              firma la verifican API Gateway y Spring, cada uno por su cuenta.
            </p>
            <pre>{payload ? JSON.stringify(payload, null, 2) : ''}</pre>
          </>
        )}
      </section>

      <section className="panel">
        <h2>Consumo manual, sin el cliente HTTP</h2>
        <p className="tenue">
          Estas dos llamadas usan <code>fetch</code> directamente, no auth/http.ts,
          asi que nadie anade el token por ellas. La primera pone la cabecera a
          mano; la segunda no pone ninguna.
        </p>

        <div className="acciones">
          <button type="button" onClick={() => void llamarConTokenAMano()}>GET /api/libros con Bearer a mano</button>
          <button type="button" className="secundario" onClick={() => void llamarSinToken()}>GET /api/libros sin token</button>
        </div>

        {resultadoManual && (
          <>
            <h3>Con token (se espera 200)</h3>
            <pre>{resultadoManual}</pre>
          </>
        )}
        {resultadoSinToken && (
          <>
            <h3>Sin token (se espera 401, y lo da API Gateway)</h3>
            <pre>{resultadoSinToken}</pre>
          </>
        )}
      </section>
    </>
  );
}

/**
 * Pide a Amplify el access token crudo.
 *
 * SE PIDE EL ACCESS TOKEN, no el id token. El id token dice QUIEN eres; el
 * access token dice A QUE tienes derecho, y es el unico que lleva el claim
 * "scope" que exigen API Gateway y Spring. Mandar el id token es el error
 * numero uno con esta arquitectura, y aqui se responde con un 401.
 */
async function tokenDeAcceso(): Promise<string> {
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
function payloadDelToken(token: string): Record<string, unknown> | null {
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

function recortar(texto: string): string {
  return texto.length > 400 ? `${texto.slice(0, 400)}…` : texto;
}
