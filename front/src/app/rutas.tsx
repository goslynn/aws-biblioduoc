import { createHashRouter, redirect, type RouteObject } from 'react-router';

import { App } from './app';
import { requiereEmpleado, requiereSesion } from './auth/guardas';
import { rutasDiagnostico } from './diagnostico/rutas';
import { Inicio } from './inicio';

/** Lo que cada ruta declara de si misma; App lo usa para el titulo de la pestana. */
export interface DatosRuta {
  titulo: string;
}

/**
 * Rutas de la aplicacion.
 *
 * Solo /inicio es publica. Todo lo demas pasa por requiereSesion, porque todo
 * lo demas llama a la API y la API exige token: sin sesion, esas vistas solo
 * podrian mostrar errores 401. Las de /gestion pasan ademas por
 * requiereEmpleado: sin ser empleado, solo verian errores 403.
 *
 * Los componentes se cargan con import() (lazy): el bundle inicial no arrastra
 * el catalogo ni el formulario, que es lo que ve alguien que todavia no ha
 * entrado.
 *
 * La vista de diagnostico es la excepcion, y por eso su ruta no esta escrita
 * aqui: viene de diagnostico/rutas.ts, que GENERA el pipeline segun la
 * variable de entorno DEBUG. Con DEBUG=false esa lista llega vacia, nadie
 * importa el componente y Vite no lo empaqueta; pedir /diagnostico a mano cae
 * en el comodin "*" y redirige al inicio, igual que cualquier URL inventada.
 */
const hijas: RouteObject[] = [
  { index: true, loader: () => redirect('/inicio') },

  { path: 'inicio', Component: Inicio, handle: { titulo: 'Biblioteca Duoc' } },

  {
    path: 'catalogo',
    loader: requiereSesion,
    handle: { titulo: 'Catalogo · Biblioteca Duoc' },
    lazy: () => import('./catalogo/catalogo').then((m) => ({ Component: m.Catalogo })),
  },
  {
    path: 'solicitud',
    loader: requiereSesion,
    handle: { titulo: 'Nueva solicitud · Biblioteca Duoc' },
    lazy: () => import('./solicitudes/formulario').then((m) => ({ Component: m.FormularioSolicitud })),
  },
  {
    path: 'mis-solicitudes',
    loader: requiereSesion,
    handle: { titulo: 'Mis solicitudes · Biblioteca Duoc' },
    lazy: () => import('./solicitudes/mis-solicitudes').then((m) => ({ Component: m.MisSolicitudes })),
  },
  {
    path: 'gestion/solicitudes',
    loader: requiereEmpleado,
    handle: { titulo: 'Bandeja · Biblioteca Duoc' },
    lazy: () => import('./gestion/bandeja').then((m) => ({ Component: m.Bandeja })),
  },
  {
    path: 'gestion/stock',
    loader: requiereEmpleado,
    handle: { titulo: 'Stock · Biblioteca Duoc' },
    lazy: () => import('./gestion/stock').then((m) => ({ Component: m.Stock })),
  },
  ...rutasDiagnostico,

  { path: '*', loader: () => redirect('/inicio') },
];

/**
 * createHashRouter: las rutas viven detras de "#" (…/index.html#/catalogo).
 *
 * El sitio se sirve desde el endpoint REST de S3, que devuelve los objetos que
 * existen y nada mas: si la ruta fuera /catalogo, S3 respondaria 404 al
 * recargar la pagina. El "website endpoint" de S3 si sabe redirigir a
 * index.html, pero es HTTP puro y Cognito rechaza callbacks sin TLS. Con
 * CloudFront delante se podria volver a rutas limpias.
 */
export const router = createHashRouter([{ path: '/', Component: App, children: hijas }]);
