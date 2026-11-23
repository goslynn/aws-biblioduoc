package cl.duoc.biblioteca.solicitudes;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Una solicitud de prestamo: es lo que devuelve la API y, a la vez, el
 * AGREGADO de Spring Data JDBC (la cabecera en la tabla solicitud y sus
 * lineas en linea_solicitud, que se guardan y se leen juntas).
 *
 * @param id               correlativo generado por el servidor, formato "SOL-0001"
 *                         (lo asigna GeneradorIdSolicitud al guardar; null antes)
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
@Table("solicitud")
public record Solicitud(
        @Id String id,
        String solicitante,
        EstadoSolicitud estado,
        // Las lineas viven en linea_solicitud: solicitud_id apunta a la
        // cabecera y "orden" es la posicion en la lista (el orden del formulario).
        @MappedCollection(idColumn = "solicitud_id", keyColumn = "orden")
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

    public Solicitud {
        lineas = lineas == null ? List.of() : List.copyOf(lineas);
    }

    /**
     * Una solicitud recien enviada, todavia sin id: el id null es lo que le
     * dice a Spring Data que es NUEVA (INSERT y no UPDATE).
     *
     * @param ahora truncado al microsegundo, la precision de TIMESTAMPTZ: lo que
     *              se devuelve ahora es identico a lo que se leera despues
     */
    static Solicitud recibida(String solicitante, List<LineaSolicitud> lineas, NuevaSolicitud formulario,
            Instant ahora) {
        LocalDate devolucion = LocalDate.ofInstant(ahora, ZoneOffset.UTC).plusDays(formulario.dias());
        return new Solicitud(null, solicitante, EstadoSolicitud.RECIBIDA, lineas, formulario.dias(),
                formulario.motivo(), formulario.sede(), ahora, devolucion, null, null, null, null, null);
    }

    /** La misma solicitud con su id ya asignado. */
    Solicitud conId(String nuevoId) {
        return new Solicitud(nuevoId, solicitante, estado, lineas, dias, motivo, sede, creadaEn,
                fechaDevolucion, motivoRechazo, resueltaPor, resueltaEn, venceEn, devueltaEn);
    }
}
