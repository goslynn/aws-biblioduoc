// La version ENCENDIDA de diagnostico/rutas.ts: la que genera
// aws/pipeline/publish-web.sh cuando se le pasa DEBUG=true.
//
// Esta aqui por dos motivos: para poder encender la vista al compilar sin
// desplegar (copiandola a diagnostico/rutas.ts) y para que el generador del
// pipeline tenga un original legible contra el que compararse.
//
// No la dejes copiada al publicar: esta pantalla ensena el access token en
// claro. Ver diagnostico/diagnostico.tsx.
import type { RouteObject } from 'react-router';

import { requiereSesion } from '../auth/guardas';

export const rutasDiagnostico: RouteObject[] = [
  {
    path: 'diagnostico',
    loader: requiereSesion,
    handle: { titulo: 'Diagnostico · Biblioteca Duoc' },
    lazy: () => import('./diagnostico').then((m) => ({ Component: m.Diagnostico })),
  },
];
