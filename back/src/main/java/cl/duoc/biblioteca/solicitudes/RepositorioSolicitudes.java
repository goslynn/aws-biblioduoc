package cl.duoc.biblioteca.solicitudes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Las solicitudes, guardadas en PostgreSQL (tablas solicitud y linea_solicitud,
 * ver db/migration/V1__esquema.sql).
 *
 * Sobreviven a los arranques en frio de la Lambda y se comparten entre todas
 * sus instancias: es la persistencia que antes faltaba.
 *
 * El rol con el que se conecta (biblioteca_app) solo puede hacer SELECT e
 * INSERT aqui. Una solicitud, una vez registrada, no se puede reescribir ni
 * borrar desde la aplicacion.
 */
@Component
public class RepositorioSolicitudes {

    private static final String CABECERAS = """
            SELECT id, solicitante, estado, dias, motivo, sede, creada_en, fecha_devolucion
            FROM solicitud
            """;

    private final JdbcClient jdbc;

    public RepositorioSolicitudes(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Crea y guarda la solicitud. El id, el estado, la fecha de creacion y la
     * de devolucion los pone el servidor; el cliente no los puede falsear.
     *
     * Transaccional: la cabecera y sus lineas se guardan juntas o no se guarda
     * nada. El correlativo sale de una secuencia de Postgres, que es segura
     * aunque haya varias instancias de la Lambda escribiendo a la vez.
     */
    @Transactional
    public Solicitud crear(String solicitante, NuevaSolicitud formulario, List<LineaSolicitud> lineas) {
        String id = jdbc.sql("SELECT 'SOL-' || lpad(nextval('solicitud_seq')::text, 4, '0')")
                .query(String.class)
                .single();

        // Al microsegundo, que es la precision de TIMESTAMPTZ: asi lo que se
        // devuelve ahora es identico a lo que se leera despues.
        Instant ahora = Instant.now().truncatedTo(ChronoUnit.MICROS);
        LocalDate devolucion = LocalDate.ofInstant(ahora, ZoneOffset.UTC).plusDays(formulario.dias());

        jdbc.sql("""
                INSERT INTO solicitud (id, solicitante, estado, dias, motivo, sede, creada_en, fecha_devolucion)
                VALUES (:id, :solicitante, :estado, :dias, :motivo, :sede, :creadaEn, :devolucion)
                """)
                .param("id", id)
                .param("solicitante", solicitante)
                .param("estado", EstadoSolicitud.RECIBIDA.name())
                .param("dias", formulario.dias())
                .param("motivo", formulario.motivo())
                .param("sede", formulario.sede().name())
                .param("creadaEn", Timestamp.from(ahora))
                .param("devolucion", devolucion)
                .update();

        for (int orden = 0; orden < lineas.size(); orden++) {
            LineaSolicitud linea = lineas.get(orden);
            jdbc.sql("""
                    INSERT INTO linea_solicitud (solicitud_id, orden, libro_id, titulo, autor, cantidad)
                    VALUES (:solicitud, :orden, :libro, :titulo, :autor, :cantidad)
                    """)
                    .param("solicitud", id)
                    .param("orden", orden)
                    .param("libro", linea.libroId())
                    .param("titulo", linea.titulo())
                    .param("autor", linea.autor())
                    .param("cantidad", linea.cantidad())
                    .update();
        }

        return new Solicitud(id, solicitante, EstadoSolicitud.RECIBIDA, List.copyOf(lineas),
                formulario.dias(), formulario.motivo(), formulario.sede(), ahora, devolucion);
    }

    /** Todas las solicitudes, de la mas reciente a la mas antigua. */
    public List<Solicitud> todas() {
        return conLineas(jdbc.sql(CABECERAS + "ORDER BY creada_en DESC, id DESC")
                .query(RepositorioSolicitudes::cabecera).list());
    }

    /** Las de un estado concreto, mismo orden. */
    public List<Solicitud> porEstado(EstadoSolicitud estado) {
        return conLineas(jdbc.sql(CABECERAS + "WHERE estado = :estado ORDER BY creada_en DESC, id DESC")
                .param("estado", estado.name())
                .query(RepositorioSolicitudes::cabecera).list());
    }

    /** Las de un solicitante concreto: es lo que alimenta la vista "mis solicitudes". */
    public List<Solicitud> deSolicitante(String solicitante) {
        return conLineas(jdbc.sql(CABECERAS + "WHERE solicitante = :solicitante ORDER BY creada_en DESC, id DESC")
                .param("solicitante", solicitante)
                .query(RepositorioSolicitudes::cabecera).list());
    }

    public Optional<Solicitud> buscar(String id) {
        return conLineas(jdbc.sql(CABECERAS + "WHERE id = :id")
                .param("id", id)
                .query(RepositorioSolicitudes::cabecera).list())
                .stream().findFirst();
    }

    /**
     * Rellena las lineas de varias solicitudes con UNA sola consulta, en vez de
     * una por solicitud (el clasico problema N+1).
     */
    private List<Solicitud> conLineas(List<Solicitud> cabeceras) {
        if (cabeceras.isEmpty()) {
            return cabeceras;
        }
        Map<String, List<LineaSolicitud>> lineasPorId = new LinkedHashMap<>();
        cabeceras.forEach(s -> lineasPorId.put(s.id(), new ArrayList<>()));

        jdbc.sql("""
                SELECT solicitud_id, libro_id, titulo, autor, cantidad
                FROM linea_solicitud
                WHERE solicitud_id IN (:ids)
                ORDER BY solicitud_id, orden
                """)
                .param("ids", List.copyOf(lineasPorId.keySet()))
                .query((rs, n) -> {
                    lineasPorId.get(rs.getString("solicitud_id")).add(new LineaSolicitud(
                            rs.getString("libro_id"), rs.getString("titulo"),
                            rs.getString("autor"), rs.getInt("cantidad")));
                    return null;
                })
                .list();

        return cabeceras.stream()
                .map(s -> new Solicitud(s.id(), s.solicitante(), s.estado(), List.copyOf(lineasPorId.get(s.id())),
                        s.dias(), s.motivo(), s.sede(), s.creadaEn(), s.fechaDevolucion()))
                .toList();
    }

    /** Una fila de la tabla solicitud, todavia sin lineas. */
    private static Solicitud cabecera(ResultSet rs, int fila) throws SQLException {
        return new Solicitud(
                rs.getString("id"),
                rs.getString("solicitante"),
                EstadoSolicitud.valueOf(rs.getString("estado")),
                List.of(),
                rs.getInt("dias"),
                rs.getString("motivo"),
                Sede.valueOf(rs.getString("sede")),
                rs.getTimestamp("creada_en").toInstant(),
                rs.getObject("fecha_devolucion", LocalDate.class));
    }
}
