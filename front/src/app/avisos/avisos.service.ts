import { http } from '../auth/http';

/** GET /api/avisos/correo (ContactoController.EstadoAvisos). */
export interface EstadoAvisos {
  activo: boolean;
  email: string | null;
}

/**
 * Los avisos por correo: el consentimiento.
 *
 * Fijate en que activar NO manda ningun correo en el cuerpo. El backend lo
 * lee de Cognito (email verificado) por el sub del token: si viniera de aqui,
 * cualquiera podria mandar avisos al correo de otra persona.
 */
const url = '/api/avisos/correo';

export const avisosService = {
  estado(): Promise<EstadoAvisos> {
    return http.get<EstadoAvisos>(url);
  },

  /** PUT -> 200, y un correo de bienvenida. 409 si la cuenta no tiene email verificado. */
  activar(): Promise<EstadoAvisos> {
    return http.put<EstadoAvisos>(url);
  },

  /** DELETE -> 204. */
  desactivar(): Promise<void> {
    return http.delete<void>(url);
  },
};
