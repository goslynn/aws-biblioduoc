package cl.duoc.biblioteca.eventos;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/** La bandeja de salida. biblioteca_app tiene SELECT, INSERT y DELETE: sin UPDATE. */
public interface RepositorioEventos extends Repository<EventoPendiente, UUID> {

    /** Siempre un INSERT (ver EventoPendiente#isNew). */
    EventoPendiente save(EventoPendiente evento);

    /**
     * Saca de la tabla hasta {@code limite} eventos, los mas antiguos primero.
     *
     * DELETE ... RETURNING en vez de SELECT ... FOR UPDATE: biblioteca_app no
     * tiene UPDATE sobre esta tabla, y no le hace falta. Las filas borradas
     * quedan bloqueadas hasta el commit; si la publicacion falla, el rollback
     * las devuelve intactas.
     *
     * OJO: RETURNING no respeta el ORDER BY de la subconsulta. Quien llama
     * tiene que volver a ordenar por creadoEn (ver RelayEventos).
     */
    @Query("""
            DELETE FROM evento_pendiente
            WHERE id IN (SELECT id FROM evento_pendiente ORDER BY creado_en LIMIT :limite)
            RETURNING id, tipo, cuerpo, creado_en
            """)
    List<EventoPendiente> extraerLote(int limite);
}
