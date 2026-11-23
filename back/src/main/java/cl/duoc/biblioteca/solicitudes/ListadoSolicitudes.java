package cl.duoc.biblioteca.solicitudes;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Sort;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.relational.core.mapping.Table;
import org.springframework.data.repository.Repository;

/**
 * El MODELO DE LECTURA de los listados de solicitudes (CQRS ligero): bandeja
 * del empleado, "mis solicitudes" y los recordatorios de una revision.
 *
 * POR QUE NO SE LISTA CON RepositorioSolicitudes
 *   Leer N agregados Solicitud cuesta 1 + N sentencias: Spring Data JDBC carga
 *   las lineas de cada uno por separado, y una proyeccion derivada tampoco lo
 *   evita (lee la entidad entera y despues proyecta). Su "single query
 *   loading" si lo evitaria, pero en 4.1.1 arma mal los agregados cuando
 *   Postgres elige otro plan de ejecucion (el SQL generado no ordena las
 *   filas y el lector supone que llegan juntas). Medido, y descartado.
 *
 * COMO
 *   {@link Cabecera} es OTRA entidad sobre la misma tabla solicitud, de solo
 *   lectura y sin lineas: sus consultas derivadas no tocan linea_solicitud.
 *   Un listado son dos sentencias, sea cual sea su tamano
 *   (RepositorioSolicitudesTest):
 *     1. las cabeceras, filtradas y ordenadas: consulta derivada, sin SQL;
 *     2. las lineas de todas ellas a la vez: la unica @Query.
 *   El resultado son Solicitud completas, identicas a las del agregado.
 */
public interface ListadoSolicitudes extends Repository<ListadoSolicitudes.Cabecera, String> {

    /** De la mas reciente a la mas antigua; el id desempata dentro del mismo microsegundo. */
    Sort RECIENTES_PRIMERO = Sort.by(Sort.Order.desc("creadaEn"), Sort.Order.desc("id"));

    /** La fila de la tabla solicitud, sin lineas. Solo se lee. */
    @Table("solicitud")
    record Cabecera(@Id String id, String solicitante, EstadoSolicitud estado, int dias, String motivo, Sede sede,
            Instant creadaEn, LocalDate fechaDevolucion, String motivoRechazo, String resueltaPor,
            Instant resueltaEn, Instant venceEn, Instant devueltaEn) {

        Solicitud con(List<LineaSolicitud> lineas) {
            return new Solicitud(id, solicitante, estado, lineas, dias, motivo, sede, creadaEn, fechaDevolucion,
                    motivoRechazo, resueltaPor, resueltaEn, venceEn, devueltaEn);
        }
    }

    /** Una linea con la solicitud a la que pertenece. */
    record LineaDe(String solicitudId, String libroId, String titulo, String autor, int cantidad) {

        LineaSolicitud linea() {
            return new LineaSolicitud(libroId, titulo, autor, cantidad);
        }
    }

    List<Cabecera> findAll(Sort orden);

    List<Cabecera> findByEstado(EstadoSolicitud estado, Sort orden);

    List<Cabecera> findBySolicitante(String solicitante, Sort orden);

    List<Cabecera> findByIdIn(Collection<String> ids, Sort orden);

    @Query("""
            SELECT solicitud_id, libro_id, titulo, autor, cantidad FROM linea_solicitud
            WHERE solicitud_id IN (:ids) ORDER BY solicitud_id, orden
            """)
    List<LineaDe> lineasDe(Collection<String> ids);

    /** Todas, de la mas reciente a la mas antigua. */
    default List<Solicitud> todas() {
        return conLineas(findAll(RECIENTES_PRIMERO));
    }

    /** Las de un estado, mismo orden. */
    default List<Solicitud> porEstado(EstadoSolicitud estado) {
        return conLineas(findByEstado(estado, RECIENTES_PRIMERO));
    }

    /** Las de un estudiante: lo que alimenta la vista "mis solicitudes". */
    default List<Solicitud> deSolicitante(String solicitante) {
        return conLineas(findBySolicitante(solicitante, RECIENTES_PRIMERO));
    }

    /** Varias por id (los recordatorios de una revision), mismo orden. */
    default List<Solicitud> porIds(Collection<String> ids) {
        return ids.isEmpty() ? List.of() : conLineas(findByIdIn(ids, RECIENTES_PRIMERO));
    }

    private List<Solicitud> conLineas(List<Cabecera> cabeceras) {
        if (cabeceras.isEmpty()) {
            return List.of();
        }
        // lineasDe ya viene ordenada por (solicitud, orden), y groupingBy
        // conserva ese orden dentro de cada grupo.
        Map<String, List<LineaSolicitud>> lineas = lineasDe(cabeceras.stream().map(Cabecera::id).toList()).stream()
                .collect(Collectors.groupingBy(LineaDe::solicitudId,
                        Collectors.mapping(LineaDe::linea, Collectors.toList())));
        return cabeceras.stream().map(c -> c.con(lineas.getOrDefault(c.id(), List.of()))).toList();
    }
}
