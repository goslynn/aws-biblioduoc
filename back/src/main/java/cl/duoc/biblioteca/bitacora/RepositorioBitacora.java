package cl.duoc.biblioteca.bitacora;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Limit;
import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/** La tabla bitacora: solo SELECT e INSERT para biblioteca_app. */
public interface RepositorioBitacora extends Repository<RegistroBitacora, Long> {

    /** Idempotente por evento_id: una reentrega no duplica la fila. */
    @Modifying
    @Query("""
            INSERT INTO bitacora (evento_id, tipo, actor, referencia, detalle, ocurrido_en)
            VALUES (:eventoId, :tipo, :actor, :referencia, :detalle, :ocurridoEn)
            ON CONFLICT (evento_id) DO NOTHING
            """)
    void anotar(UUID eventoId, String tipo, String actor, String referencia, String detalle, Instant ocurridoEn);

    /**
     * Los ultimos {@code limite} registros, del mas nuevo al mas viejo,
     * proyectados a lo que ve el empleado. Consulta derivada: sin SQL.
     */
    List<Bitacora.Entrada> findByOrderByOcurridoEnDescIdDesc(Limit limite);
}
