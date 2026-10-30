import { inject } from '@angular/core';
import { CanActivateFn, Router } from '@angular/router';

import { SesionService } from './sesion.service';

/**
 * Impide abrir las rutas privadas sin sesion y redirige al inicio.
 *
 * >>> LO QUE UN GUARD NO ES <<<
 *   Un Guard es COMODIDAD DE INTERFAZ, no seguridad. Todo lo que hay aqui
 *   corre en el navegador del usuario, que puede saltarselo con la consola
 *   abierta. Lo unico que evita es que alguien sin sesion llegue a una vista
 *   vacia llena de errores 401.
 *
 *   La seguridad de verdad esta en el servidor, y son DOS capas:
 *     1. API Gateway   -> authorizer Cognito + scope exigido por metodo
 *     2. Spring Security -> vuelve a validar el mismo token
 *   Si este Guard desapareciera, la API seguiria igual de protegida.
 */
export const authGuard: CanActivateFn = async () => {
  const sesion = inject(SesionService);
  const router = inject(Router);

  const estado = await sesion.refrescar();
  return estado.autenticado ? true : router.createUrlTree(['/inicio']);
};

/**
 * Las vistas de gestion: hace falta sesion Y ser del grupo de empleados. Igual
 * que authGuard, es comodidad: un estudiante que se lo salte veria la vista,
 * pero cada llamada a /api/gestion le responderia 403 desde Spring.
 */
export const empleadoGuard: CanActivateFn = async () => {
  const sesion = inject(SesionService);
  const router = inject(Router);

  const estado = await sesion.refrescar();
  if (!estado.autenticado) {
    return router.createUrlTree(['/inicio']);
  }
  return estado.empleado ? true : router.createUrlTree(['/catalogo']);
};
