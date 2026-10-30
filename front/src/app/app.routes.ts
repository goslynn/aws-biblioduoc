import { Routes } from '@angular/router';

import { authGuard, empleadoGuard } from './auth/auth.guard';
import { rutasDiagnostico } from './diagnostico/rutas';
import { Inicio } from './inicio';

/**
 * Rutas de la aplicacion.
 *
 * Solo /inicio es publica. Todo lo demas pasa por authGuard, porque todo lo
 * demas llama a la API y la API exige token: sin sesion, esas vistas solo
 * podrian mostrar errores 401. Las de /gestion pasan ademas por
 * empleadoGuard: sin ser empleado, solo verian errores 403.
 *
 * Los componentes se cargan con import() (lazy loading): el bundle inicial no
 * arrastra el catalogo ni el formulario, que es lo que ve alguien que todavia
 * no ha entrado.
 *
 * La vista de diagnostico es la excepcion, y por eso su ruta no esta escrita
 * aqui: viene de diagnostico/rutas.ts, que GENERA el pipeline segun la
 * variable de entorno DEBUG. Con DEBUG=false esa lista llega vacia, nadie
 * importa el componente y no se compila; pedir /diagnostico a mano cae en el
 * comodin "**" y redirige al inicio, igual que cualquier URL inventada.
 */
export const routes: Routes = [
  { path: '', pathMatch: 'full', redirectTo: 'inicio' },

  { path: 'inicio', component: Inicio, title: 'Biblioteca Duoc' },

  {
    path: 'catalogo',
    canActivate: [authGuard],
    title: 'Catalogo · Biblioteca Duoc',
    loadComponent: () => import('./catalogo/catalogo').then((m) => m.Catalogo),
  },
  {
    path: 'solicitud',
    canActivate: [authGuard],
    title: 'Nueva solicitud · Biblioteca Duoc',
    loadComponent: () => import('./solicitudes/formulario').then((m) => m.FormularioSolicitud),
  },
  {
    path: 'mis-solicitudes',
    canActivate: [authGuard],
    title: 'Mis solicitudes · Biblioteca Duoc',
    loadComponent: () => import('./solicitudes/mis-solicitudes').then((m) => m.MisSolicitudes),
  },
  {
    path: 'gestion/solicitudes',
    canActivate: [empleadoGuard],
    title: 'Bandeja · Biblioteca Duoc',
    loadComponent: () => import('./gestion/bandeja').then((m) => m.Bandeja),
  },
  {
    path: 'gestion/stock',
    canActivate: [empleadoGuard],
    title: 'Stock · Biblioteca Duoc',
    loadComponent: () => import('./gestion/stock').then((m) => m.Stock),
  },
  ...rutasDiagnostico,

  { path: '**', redirectTo: 'inicio' },
];
