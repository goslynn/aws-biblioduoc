import { redirect } from 'react-router';

import { refrescar, type EstadoSesion } from './sesion';

/** La vista con la que entra cada rol: el empleado trabaja en la bandeja. */
export function inicioDe(estado: EstadoSesion): string {
  return estado.empleado ? '/gestion/solicitudes' : '/catalogo';
}

/**
 * Impide abrir las rutas privadas sin sesion y redirige al inicio. Se usa
 * como "loader" de la ruta: React Router lo ejecuta ANTES de cargar y pintar
 * la vista, igual que hacia canActivate en Angular.
 *
 * >>> LO QUE UNA GUARDA NO ES <<<
 *   Una guarda es COMODIDAD DE INTERFAZ, no seguridad. Todo lo que hay aqui
 *   corre en el navegador del usuario, que puede saltarselo con la consola
 *   abierta. Lo unico que evita es que alguien sin sesion llegue a una vista
 *   vacia llena de errores 401.
 *
 *   La seguridad de verdad esta en el servidor, y son DOS capas:
 *     1. API Gateway     -> authorizer Cognito + scope exigido por metodo
 *     2. Spring Security -> vuelve a validar el mismo token
 *   Si esta guarda desapareciera, la API seguiria igual de protegida.
 */
export async function requiereSesion(): Promise<null> {
  const estado = await refrescar();
  if (!estado.autenticado) {
    throw redirect('/inicio');
  }
  return null;
}

/**
 * Las vistas de gestion: hace falta sesion Y ser del grupo de empleados. Igual
 * que requiereSesion, es comodidad: un estudiante que se la salte veria la
 * vista, pero cada llamada a /api/gestion le responderia 403 desde Spring.
 */
export async function requiereEmpleado(): Promise<null> {
  const estado = await refrescar();
  if (!estado.autenticado) {
    throw redirect('/inicio');
  }
  if (!estado.empleado) {
    throw redirect('/catalogo');
  }
  return null;
}

/**
 * Las vistas de pedir libros (Mi solicitud, Mis solicitudes): solo
 * estudiantes. Un empleado gestiona las solicitudes, no las crea; si llega
 * aqui, va a su bandeja. Comodidad otra vez: si se la saltara, Spring le
 * responderia 403 al enviar (SecurityConfig).
 */
export async function requiereEstudiante(): Promise<null> {
  const estado = await refrescar();
  if (!estado.autenticado) {
    throw redirect('/inicio');
  }
  if (estado.empleado) {
    throw redirect(inicioDe(estado));
  }
  return null;
}
