package cl.duoc.biblioteca.solicitudes;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * Las solicitudes como AGREGADO (tablas solicitud y linea_solicitud): altas,
 * lectura de una por id y transiciones de estado. Spring Data JDBC genera la
 * implementacion. Los listados son de {@link ListadoSolicitudes}.
 *
 * QUE SE PUEDE HACER Y QUE NO
 *   El rol biblioteca_app puede crear solicitudes y cambiar SOLO las columnas
 *   de su ciclo de vida (estado, resolucion, vencimiento). Lo que el
 *   estudiante pidio —lineas, dias, motivo, sede— no se puede reescribir ni
 *   borrar. Por eso este repositorio NO hereda de CrudRepository:
 *     * save() existe solo para ALTAS (id null). Un save() de una solicitud
 *       existente haria UPDATE de todas las columnas y borraria y reinsertaria
 *       las lineas; la base lo rechazaria por permisos, pero ni se ofrece.
 *     * Los cambios de estado son @Query explicitas, una por transicion.
 *
 * TRANSICIONES CONDICIONALES
 *   Cada cambio de estado es un UPDATE ... WHERE estado = 'el de origen'. Si
 *   dos empleados aprueban la misma solicitud a la vez, Postgres serializa los
 *   dos UPDATE sobre la fila: el primero cambia 1 fila, el segundo 0. Quien
 *   llama interpreta el false como un 409, sin necesidad de bloqueos.
 */
public interface RepositorioSolicitudes extends Repository<Solicitud, String> {

    /** SOLO para altas: la solicitud llega con id null (ver GeneradorIdSolicitud). */
    Solicitud save(Solicitud nueva);

    Optional<Solicitud> findById(String id);

    // Los LISTADOS no estan aqui: un listado de agregados cuesta 1 + N
    // sentencias. Ver ListadoSolicitudes, el modelo de lectura.

    /**
     * RECIBIDA -> APROBADA. El plazo empieza a contar AHORA, no cuando se
     * envio el formulario: vence_en y fecha_devolucion se recalculan.
     *
     * @return false si la solicitud ya no estaba RECIBIDA
     */
    @Modifying
    @Query("""
            UPDATE solicitud
            SET estado = 'APROBADA', resuelta_por = :empleado, resuelta_en = :ahora,
                vence_en = CAST(:ahora AS timestamptz) + make_interval(days => dias),
                fecha_devolucion = CAST((CAST(:ahora AS timestamptz) + make_interval(days => dias))
                                        AT TIME ZONE 'UTC' AS date)
            WHERE id = :id AND estado = 'RECIBIDA'
            """)
    boolean aprobar(String id, String empleado, Instant ahora);

    /** RECIBIDA -> RECHAZADA. @return false si ya no estaba RECIBIDA */
    @Modifying
    @Query("""
            UPDATE solicitud
            SET estado = 'RECHAZADA', resuelta_por = :empleado, resuelta_en = :ahora, motivo_rechazo = :motivo
            WHERE id = :id AND estado = 'RECIBIDA'
            """)
    boolean rechazar(String id, String empleado, String motivo, Instant ahora);

    /** APROBADA -> DEVUELTA. @return false si no estaba APROBADA */
    @Modifying
    @Query("""
            UPDATE solicitud SET estado = 'DEVUELTA', devuelta_en = :ahora
            WHERE id = :id AND estado = 'APROBADA'
            """)
    boolean devolver(String id, Instant ahora);

    /**
     * Marca como recordados los prestamos que vencen dentro de la antelacion
     * y devuelve sus ids, en UNA sentencia: dos revisiones seguidas nunca
     * recuerdan el mismo prestamo dos veces.
     *
     * Se excluyen los prestamos mas cortos que la antelacion (uno de 1 dia
     * con 24 h de antelacion se "recordaria" en el mismo instante de
     * aprobarlo, junto con el correo de aprobacion, y no aporta nada).
     */
    @Query("""
            UPDATE solicitud SET recordatorio_enviado = TRUE
            WHERE estado = 'APROBADA' AND NOT recordatorio_enviado
              AND vence_en - make_interval(secs => :segundos) <= CAST(:ahora AS timestamptz)
              AND vence_en - make_interval(secs => :segundos) > resuelta_en
            RETURNING id
            """)
    List<String> marcarParaRecordar(long segundos, Instant ahora);
}
