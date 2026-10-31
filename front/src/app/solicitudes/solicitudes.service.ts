import { http } from '../auth/http';
import type { NuevaSolicitud, Solicitud } from './solicitud';

/**
 * Solicitudes de prestamo.
 *
 * Fijate en lo que NO se manda al crear: el solicitante. El backend lo saca
 * del JWT ya validado. Si viniera del formulario, cualquiera podria pedir
 * libros a nombre de otra persona.
 */
const base = '/api/solicitudes';

export const solicitudesService = {
  /** POST /api/solicitudes -> 201. Puede dar 400 (validacion), 404 o 409. */
  crear(solicitud: NuevaSolicitud): Promise<Solicitud> {
    return http.post<Solicitud>(base, solicitud);
  },

  /**
   * GET /api/solicitudes -> solo las del portador del token. El filtro lo
   * aplica el backend con el sub del JWT; la bandeja completa es de
   * empleados (gestionService).
   */
  mias(): Promise<Solicitud[]> {
    return http.get<Solicitud[]>(base);
  },
};
