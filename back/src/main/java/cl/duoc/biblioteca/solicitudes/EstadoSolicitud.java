package cl.duoc.biblioteca.solicitudes;

/**
 * Ciclo de vida de una solicitud de prestamo.
 *
 * <pre>
 *   RECIBIDA --aprobar--> APROBADA --devolver--> DEVUELTA
 *       \
 *        `--rechazar--> RECHAZADA
 * </pre>
 *
 * Las transiciones las hace un EMPLEADO (ver GestionSolicitudController). No
 * hay vuelta atras: una solicitud rechazada o devuelta queda cerrada.
 */
public enum EstadoSolicitud {
    /** Recien creada; el equipo de biblioteca todavia no la revisa. */
    RECIBIDA,
    /** Aprobada: los ejemplares se descontaron del stock y el plazo corre. */
    APROBADA,
    /** Rechazada por el equipo de biblioteca, con motivo. */
    RECHAZADA,
    /** Los libros volvieron: el stock se repuso. */
    DEVUELTA
}
