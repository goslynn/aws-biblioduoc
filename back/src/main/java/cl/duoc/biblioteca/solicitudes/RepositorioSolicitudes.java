package cl.duoc.biblioteca.solicitudes;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
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
 * ver db/migration/V1__esquema.sql y V3__prestamos_y_avisos.sql).
 *
 * El rol con el que se conecta (biblioteca_app) puede crear solicitudes y
 * cambiar SOLO las columnas de su ciclo de vida (estado, resolucion,
 * vencimiento). Lo que el estudiante pidio —lineas, dias, motivo, sede— no se
 * puede reescribir ni borrar desde la aplicacion.
 *
 * TRANSICIONES CONDICIONALES
 *   Cada cambio de estado es un UPDATE ... WHERE estado = 'el de origen'. Si
 *   dos empleados aprueban la misma solicitud a la vez, Postgres serializa los
 *   dos UPDATE sobre la fila: el primero cambia 1 fila, el segundo 0. Quien
 *   llama interpreta el 0 como un 409, sin necesidad de bloqueos explicitos.
 */
@Component
public class RepositorioSolicitudes {

    private static final String CABECERAS = """
            SELECT id, solicitante, estado, dias, motivo, sede, creada_en, fecha_devolucion,
                   motivo_rechazo, resuelta_por, resuelta_en, vence_en, devuelta_en
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
     * aunque haya varias peticiones escribiendo a la vez.
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
                formulario.dias(), formulario.motivo(), formulario.sede(), ahora, devolucion,
                null, null, null, null, null);
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
     * RECIBIDA -> APROBADA. El plazo empieza a contar AHORA, no cuando se
     * envio el formulario: vence_en y fecha_devolucion se recalculan.
     *
     * @return false si la solicitud ya no estaba RECIBIDA
     */
    public boolean aprobar(String id, String empleado, Instant ahora) {
        return jdbc.sql("""
                UPDATE solicitud
                SET estado = 'APROBADA', resuelta_por = :empleado, resuelta_en = :ahora,
                    vence_en = CAST(:ahora AS timestamptz) + make_interval(days => dias),
                    fecha_devolucion = CAST((CAST(:ahora AS timestamptz) + make_interval(days => dias))
                                            AT TIME ZONE 'UTC' AS date)
                WHERE id = :id AND estado = 'RECIBIDA'
                """)
                .param("id", id)
                .param("empleado", empleado)
                .param("ahora", Timestamp.from(ahora))
                .update() == 1;
    }

    /** RECIBIDA -> RECHAZADA. @return false si ya no estaba RECIBIDA */
    public boolean rechazar(String id, String empleado, String motivo, Instant ahora) {
        return jdbc.sql("""
                UPDATE solicitud
                SET estado = 'RECHAZADA', resuelta_por = :empleado, resuelta_en = :ahora,
                    motivo_rechazo = :motivo
                WHERE id = :id AND estado = 'RECIBIDA'
                """)
                .param("id", id)
                .param("empleado", empleado)
                .param("motivo", motivo)
                .param("ahora", Timestamp.from(ahora))
                .update() == 1;
    }

    /** APROBADA -> DEVUELTA. @return false si no estaba APROBADA */
    public boolean devolver(String id, Instant ahora) {
        return jdbc.sql("""
                UPDATE solicitud SET estado = 'DEVUELTA', devuelta_en = :ahora
                WHERE id = :id AND estado = 'APROBADA'
                """)
                .param("id", id)
                .param("ahora", Timestamp.from(ahora))
                .update() == 1;
    }

    /**
     * Marca como recordados los prestamos que vencen dentro de la antelacion
     * y devuelve sus ids, en UNA sentencia: dos revisiones seguidas nunca
     * recuerdan el mismo prestamo dos veces.
     *
     * Se excluyen los prestamos mas cortos que la antelacion (uno de 1 dia
     * con 24 h de antelacion se "recordaria" en el mismo instante de
     * aprobarlo, junto con el correo de aprobacion, y no aporta nada).
     */
    public List<String> marcarParaRecordar(Duration antelacion, Instant ahora) {
        return jdbc.sql("""
                UPDATE solicitud SET recordatorio_enviado = TRUE
                WHERE estado = 'APROBADA' AND NOT recordatorio_enviado
                  AND vence_en - make_interval(secs => :segundos) <= CAST(:ahora AS timestamptz)
                  AND vence_en - make_interval(secs => :segundos) > resuelta_en
                RETURNING id
                """)
                .param("segundos", antelacion.toSeconds())
                .param("ahora", Timestamp.from(ahora))
                .query(String.class)
                .list();
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
                .map(s -> s.conLineas(lineasPorId.get(s.id())))
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
                rs.getObject("fecha_devolucion", LocalDate.class),
                rs.getString("motivo_rechazo"),
                rs.getString("resuelta_por"),
                instante(rs, "resuelta_en"),
                instante(rs, "vence_en"),
                instante(rs, "devuelta_en"));
    }

    private static Instant instante(ResultSet rs, String columna) throws SQLException {
        Timestamp t = rs.getTimestamp(columna);
        return t == null ? null : t.toInstant();
    }
}
