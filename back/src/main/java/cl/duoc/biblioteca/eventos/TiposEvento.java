package cl.duoc.biblioteca.eventos;

/**
 * Las routing keys del exchange topic {@code biblioteca.eventos}.
 *
 * El formato es siempre "entidad.hecho". Eso es lo que permite suscribirse por
 * patron: "solicitud.*" recibe todo lo que le pasa a una solicitud, y "#"
 * recibe absolutamente todo (la bitacora).
 */
public final class TiposEvento {

    public static final String SOLICITUD_CREADA = "solicitud.creada";
    public static final String SOLICITUD_APROBADA = "solicitud.aprobada";
    public static final String SOLICITUD_RECHAZADA = "solicitud.rechazada";
    public static final String SOLICITUD_DEVUELTA = "solicitud.devuelta";

    /** Lo publica el programador de recordatorios, no una persona. */
    public static final String RECORDATORIO_VENCE = "recordatorio.vence";

    /** Un empleado (o el backoffice) cargo ejemplares. */
    public static final String STOCK_CARGADO = "stock.cargado";
    /** Un libro paso de 0 ejemplares a tener alguno: dispara los "avisame". */
    public static final String STOCK_REPUESTO = "stock.repuesto";

    /** El estudiante activo los avisos por correo: correo de bienvenida. */
    public static final String AVISO_ACTIVADO = "aviso.activado";

    private TiposEvento() {
    }
}
