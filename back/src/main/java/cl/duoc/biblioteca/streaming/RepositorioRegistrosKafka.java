package cl.duoc.biblioteca.streaming;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/** La bandeja de salida de Kafka. biblioteca_app tiene SELECT, INSERT y DELETE (V4). */
public interface RepositorioRegistrosKafka extends Repository<RegistroKafka, UUID> {

    /** Siempre un INSERT (ver RegistroKafka#isNew). */
    RegistroKafka save(RegistroKafka registro);

    /**
     * Saca hasta {@code limite} registros, los mas antiguos primero. Mismo
     * mecanismo que RepositorioEventos#extraerLote: DELETE ... RETURNING deja
     * las filas bloqueadas hasta el commit, y un rollback las devuelve.
     * RETURNING no respeta el orden: quien llama reordena por creadoEn.
     */
    @Query("""
            DELETE FROM evento_kafka
            WHERE id IN (SELECT id FROM evento_kafka ORDER BY creado_en LIMIT :limite)
            RETURNING id, topico, clave, cuerpo, creado_en
            """)
    List<RegistroKafka> extraerLote(int limite);
}
