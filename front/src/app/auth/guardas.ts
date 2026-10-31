import { redirect } from 'react-router';

import { refrescar } from './sesion';

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
