import { http } from '../auth/http';
import type { Libro } from '../catalogo/libro';
import type { EstadoSolicitud, Sede, Solicitud } from '../solicitudes/solicitud';

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

/**
 * GET /api/gestion/bandeja-vivo (ProyeccionBandeja.java): las solicitudes
 * RECIBIDA segun el consumidor de Kafka, sin consultar la base (H9).
 */
export interface BandejaVivo {
  pendientes: number;
  porSede: Record<Sede, number>;
  /** Las mas recientes primero, hasta 10. */
  ultimas: { id: string; sede: Sede; ejemplares: number; titulos: string[]; recibidaEn: string }[];
  actualizadoEn: string | null;
}

export const gestionService = {
  /** GET /api/gestion/bandeja-vivo: lo pendiente, en vivo, desde Kafka. */
  bandejaVivo(): Promise<BandejaVivo> {
    return http.get<BandejaVivo>(`${base}/bandeja-vivo`);
  },

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
