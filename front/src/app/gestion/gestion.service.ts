import { http } from '../auth/http';
import type { Libro } from '../catalogo/libro';
import type { EstadoSolicitud, Solicitud } from '../solicitudes/solicitud';

/**
 * Las rutas de EMPLEADO (/api/gestion/**).
 *
 * Que solo las use un empleado lo decide SPRING: un estudiante que llamara a
 * estas funciones recibiria 403. Las vistas que las usan estan detras de
 * requiereEmpleado solo para no ensenarle botones que no le van a funcionar.
 *
 * Cada accion responde con la solicitud ya cambiada; el correo al estudiante
 * sale despues, por RabbitMQ, sin que esta peticion lo espere.
 */
const base = '/api/gestion';

export const gestionService = {
  /** GET /api/gestion/solicitudes?estado= -> la bandeja completa, o filtrada. */
  solicitudes(estado: EstadoSolicitud | ''): Promise<Solicitud[]> {
    return http.get<Solicitud[]>(`${base}/solicitudes`, { estado: estado || undefined });
  },

  /** 200; 409 si ya no esta RECIBIDA o ya no alcanza el stock. */
  aprobar(id: string): Promise<Solicitud> {
    return http.post<Solicitud>(`${base}/solicitudes/${id}/aprobacion`);
  },

  /** 200; 400 sin motivo; 409 si ya no esta RECIBIDA. */
  rechazar(id: string, motivo: string): Promise<Solicitud> {
    return http.post<Solicitud>(`${base}/solicitudes/${id}/rechazo`, { motivo });
  },

  /** 200; 409 si no estaba APROBADA. Repone el stock (y dispara los "avisame"). */
  devolver(id: string): Promise<Solicitud> {
    return http.post<Solicitud>(`${base}/solicitudes/${id}/devolucion`);
  },

  /** 200 con el libro actualizado; 400 si la cantidad no esta entre 1 y 100. */
  cargarStock(id: string, cantidad: number): Promise<Libro> {
    return http.post<Libro>(`${base}/libros/${id}/stock`, { cantidad });
  },
};
