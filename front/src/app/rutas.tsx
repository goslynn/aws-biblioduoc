import { createBrowserRouter, redirect, type RouteObject } from 'react-router';

import { App, CargaInicial } from './app';
import { requiereEmpleado, requiereEstudiante, requiereSesion } from './auth/guardas';
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
 * requiereEmpleado: sin ser empleado, solo verian errores 403. Las de pedir
 * libros (/solicitud, /mis-solicitudes) por requiereEstudiante: un empleado
 * no crea solicitudes (Spring le responde 403) y lo manda a su bandeja.
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
    loader: requiereEstudiante,
    handle: { titulo: 'Nueva solicitud · Biblioteca Duoc' },
    lazy: () => import('./solicitudes/formulario').then((m) => ({ Component: m.FormularioSolicitud })),
  },
  {
    path: 'mis-solicitudes',
    loader: requiereEstudiante,
    handle: { titulo: 'Mis solicitudes · Biblioteca Duoc' },
    lazy: () => import('./solicitudes/mis-solicitudes').then((m) => ({ Component: m.MisSolicitudes })),
  },
  {
    path: 'gestion/solicitudes',
    loader: requiereEmpleado,
    handle: { titulo: 'Bandeja · Biblioteca Duoc' },
    lazy: () => import('./gestion/bandeja').then((m) => ({ Component: m.Bandeja })),
  },
  // La vista de stock ya no existe: el empleado carga stock en el catalogo.
  // Un enlace viejo a /gestion/stock lleva ahi en vez de a la portada.
  { path: 'gestion/stock', loader: () => redirect('/catalogo') },
  ...rutasDiagnostico,

  { path: '*', loader: () => redirect('/inicio') },
];

/**
 * createBrowserRouter: rutas limpias (/catalogo, sin "#").
 *
 * Funciona porque Caddy (front/Caddyfile) devuelve index.html para cualquier
 * ruta que no sea un archivo: recargar en /gestion/solicitudes no da 404, llega
 * index.html y React Router pinta la vista. Cuando el sitio estaba en el
 * endpoint REST de S3 eso no era posible, y por eso las rutas llevaban "#".
 */
export const router = createBrowserRouter([{ path: '/', Component: App, HydrateFallback: CargaInicial, children: hijas }]);
