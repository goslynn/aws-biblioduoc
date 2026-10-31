import { fetchAuthSession } from 'aws-amplify/auth';

/**
 * Una respuesta que no es 2xx, o ninguna respuesta (status 0: red o CORS).
 * "cuerpo" es el JSON de error del backend o del gateway, si lo habia.
 */
export class ErrorHttp extends Error {
  constructor(
    readonly status: number,
    readonly cuerpo?: unknown,
  ) {
    super(`HTTP ${status}`);
  }
}

type Parametros = Record<string, string | undefined>;

/**
 * El UNICO sitio que llama a la API. Anade "Authorization: Bearer
 * <access_token>" a TODA peticion: hace el papel que en Angular hacia el
 * interceptor de HttpClient.
 *
 * Por que un solo cliente y no poner la cabecera en cada servicio:
 *   * un solo sitio decide como se autentica la aplicacion;
 *   * los servicios de negocio no saben nada de tokens;
 *   * si manana cambia el esquema de autenticacion, cambia este archivo y ya.
 *
 * Ojo con la diferencia respecto de una guarda: la guarda controla la
 * NAVEGACION (que rutas puede abrir el usuario) y esto controla las LLAMADAS
 * HTTP. Son cosas distintas, y ninguna de las dos protege la API: eso lo hacen
 * API Gateway y Spring Security, en el servidor.
 *
 * Si no hay sesion, la peticion sale SIN cabecera a proposito: asi el 401 lo
 * da el servidor y se ve en la pestana Network, en vez de que el frontend
 * disimule el problema.
 *
 * Que el token se pida a Amplify en CADA peticion no cuesta nada: Amplify lo
 * tiene en memoria y solo vuelve a Cognito cuando hay que refrescarlo.
 *
 * Las rutas son relativas al propio sitio (/api/libros): Caddy las reenvia a
 * API Gateway (front/Caddyfile). Para el navegador es el MISMO origen, asi
 * que no hay preflight ni CORS de por medio.
 */
async function peticion<T>(metodo: string, ruta: string, opciones: { params?: Parametros; cuerpo?: unknown } = {}): Promise<T> {
  const url = new URL(ruta, window.location.origin);
  for (const [clave, valor] of Object.entries(opciones.params ?? {})) {
    if (valor !== undefined) {
      url.searchParams.set(clave, valor);
    }
  }

  const cabeceras: Record<string, string> = {};
  const token = await tokenDeAcceso();
  if (token) {
    cabeceras['Authorization'] = `Bearer ${token}`;
  }
  if (opciones.cuerpo !== undefined) {
    cabeceras['Content-Type'] = 'application/json';
  }

  let respuesta: Response;
  try {
    respuesta = await fetch(url, {
      method: metodo,
      headers: cabeceras,
      body: opciones.cuerpo === undefined ? undefined : JSON.stringify(opciones.cuerpo),
    });
  } catch {
    // fetch solo se rechaza si NO hubo respuesta: red caida, o una peticion
    // que el navegador corto (CORS, o la CSP del Caddyfile).
    throw new ErrorHttp(0);
  }

  const texto = await respuesta.text();
  const cuerpo: unknown = texto ? leerJson(texto) : undefined;
  if (!respuesta.ok) {
    throw new ErrorHttp(respuesta.status, cuerpo);
  }
  return cuerpo as T;
}

async function tokenDeAcceso(): Promise<string | undefined> {
  try {
    const sesion = await fetchAuthSession();
    return sesion.tokens?.accessToken?.toString();
  } catch {
    return undefined;
  }
}

function leerJson(texto: string): unknown {
  try {
    return JSON.parse(texto);
  } catch {
    return texto;
  }
}

export const http = {
  get: <T>(ruta: string, params?: Parametros) => peticion<T>('GET', ruta, { params }),
  post: <T>(ruta: string, cuerpo?: unknown) => peticion<T>('POST', ruta, { cuerpo }),
  put: <T>(ruta: string, cuerpo?: unknown) => peticion<T>('PUT', ruta, { cuerpo }),
  delete: <T>(ruta: string) => peticion<T>('DELETE', ruta),
};

/** El status de cualquier fallo: el de la respuesta, o 0 si no la hubo. */
export function statusDe(fallo: unknown): number {
  return fallo instanceof ErrorHttp ? fallo.status : 0;
}

/** El cuerpo JSON de un fallo, si lo trajo. */
export function cuerpoDe<T>(fallo: unknown): T | undefined {
  return fallo instanceof ErrorHttp && typeof fallo.cuerpo === 'object' ? (fallo.cuerpo as T) : undefined;
}
