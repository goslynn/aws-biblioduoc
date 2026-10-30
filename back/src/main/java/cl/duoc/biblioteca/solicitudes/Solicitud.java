package cl.duoc.biblioteca.solicitudes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Una solicitud de prestamo ya creada. Es lo que devuelve la API.
 *
 * @param id               correlativo generado por el servidor, formato "SOL-0001"
 * @param solicitante      sacado del JWT (claim "sub" o "client_id"), NO del formulario
 * @param estado           ver {@link EstadoSolicitud}
 * @param lineas           libros pedidos, con su titulo resuelto desde el catalogo
 * @param dias             dias de prestamo solicitados
 * @param motivo           texto libre del formulario
 * @param sede             donde se retira
 * @param creadaEn         instante de creacion, en UTC
 * @param fechaDevolucion  hasta que dia se presta. Al crear es una estimacion
 *                         (creadaEn + dias); al aprobar se recalcula desde venceEn
 * @param motivoRechazo    lo escribe el empleado al rechazar; null en otro caso
 * @param resueltaPor      sub o client_id del empleado que aprobo o rechazo
 * @param resueltaEn       cuando se aprobo o rechazo
 * @param venceEn          resueltaEn + dias, solo si se aprobo: es lo que mira el recordatorio
 * @param devueltaEn       cuando se registro la devolucion
 */
public record Solicitud(
        String id,
        String solicitante,
        EstadoSolicitud estado,
        List<LineaSolicitud> lineas,
        int dias,
        String motivo,
        Sede sede,
        Instant creadaEn,
        LocalDate fechaDevolucion,
        String motivoRechazo,
        String resueltaPor,
        Instant resueltaEn,
        Instant venceEn,
        Instant devueltaEn) {

    /** La misma solicitud con sus lineas (el repositorio las carga aparte). */
    Solicitud conLineas(List<LineaSolicitud> nuevas) {
        return new Solicitud(id, solicitante, estado, List.copyOf(nuevas), dias, motivo, sede, creadaEn,
                fechaDevolucion, motivoRechazo, resueltaPor, resueltaEn, venceEn, devueltaEn);
    }
}
