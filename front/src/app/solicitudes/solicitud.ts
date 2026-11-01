/** Sedes donde se puede retirar el prestamo (enum Sede del backend). */
export const SEDES = ['SAN_JOAQUIN', 'PLAZA_OESTE', 'PUENTE_ALTO', 'VINA_DEL_MAR', 'CONCEPCION'] as const;
export type Sede = (typeof SEDES)[number];

/** Como se lee cada sede en pantalla; la API sigue recibiendo el valor del enum. */
export const NOMBRE_SEDE: Record<Sede, string> = {
  SAN_JOAQUIN: 'San Joaquin',
  PLAZA_OESTE: 'Plaza Oeste',
  PUENTE_ALTO: 'Puente Alto',
  VINA_DEL_MAR: 'Vina del Mar',
  CONCEPCION: 'Concepcion',
};

/** Lo que se envia en POST /api/solicitudes (NuevaSolicitud.java). */
export interface NuevaSolicitud {
  items: { libroId: string; cantidad: number }[];
  dias: number;
  /** Opcional; sin el, el backend guarda "". */
  motivo?: string;
  sede: Sede;
}

/** Una linea ya resuelta por el backend, con el titulo copiado del catalogo. */
export interface LineaSolicitud {
  libroId: string;
  titulo: string;
  autor: string;
  cantidad: number;
}

/** Ciclo de vida (EstadoSolicitud.java): RECIBIDA -> APROBADA -> DEVUELTA, o RECIBIDA -> RECHAZADA. */
export const ESTADOS = ['RECIBIDA', 'APROBADA', 'RECHAZADA', 'DEVUELTA'] as const;
export type EstadoSolicitud = (typeof ESTADOS)[number];

/** Como lo lee el estudiante: RECIBIDA es, para el, una solicitud en revision. */
export const NOMBRE_ESTADO: Record<EstadoSolicitud, string> = {
  RECIBIDA: 'En revision',
  APROBADA: 'Aprobada',
  RECHAZADA: 'Rechazada',
  DEVUELTA: 'Devuelta',
};

/**
 * La clase de la etiqueta de estado. Cada estado tiene un color fijo en toda
 * la aplicacion: azul en revision, verde aprobada, rojo rechazada, gris devuelta.
 */
export function claseEstado(estado: EstadoSolicitud): string {
  switch (estado) {
    case 'RECIBIDA':  return 'etiqueta revision';
    case 'APROBADA':  return 'etiqueta si';
    case 'RECHAZADA': return 'etiqueta no';
    default:          return 'etiqueta';
  }
}

/** Lo que devuelve la API (Solicitud.java). */
export interface Solicitud {
  id: string;
  /** Sale del JWT (claim sub o client_id), no del formulario. */
  solicitante: string;
  estado: EstadoSolicitud;
  lineas: LineaSolicitud[];
  dias: number;
  motivo: string;
  sede: Sede;
  creadaEn: string;
  /** Al crear, una estimacion; al aprobar, la fecha real (desde venceEn). */
  fechaDevolucion: string;
  /** Lo escribe el empleado al rechazar. */
  motivoRechazo: string | null;
  resueltaPor: string | null;
  resueltaEn: string | null;
  /** Solo si se aprobo: hasta cuando se presta. */
  venceEn: string | null;
  devueltaEn: string | null;
}

/** Cuerpo de error que devuelve ManejadorDeErrores del backend. */
export interface ErrorApi {
  status: number;
  error: string;
  mensaje: string;
  detalles: Record<string, string>;
}
