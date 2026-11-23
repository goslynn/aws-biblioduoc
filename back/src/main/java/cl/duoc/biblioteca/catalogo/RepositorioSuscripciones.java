package cl.duoc.biblioteca.catalogo;

import java.util.List;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * La tabla suscripcion_libro. biblioteca_app tiene SELECT, INSERT y DELETE:
 * no hay UPDATE, y por eso tampoco hay save() (que con una clave ya asignada
 * intentaria un UPDATE).
 */
public interface RepositorioSuscripciones extends Repository<SuscripcionLibro, SuscripcionLibro.Clave> {

    /** Idempotente: suscribirse dos veces deja una sola fila, sin error. */
    @Modifying
    @Query("""
            INSERT INTO suscripcion_libro (libro_id, solicitante, creada_en)
            VALUES (:libroId, :solicitante, now())
            ON CONFLICT DO NOTHING
            """)
    void suscribir(String libroId, String solicitante);

    /** Darse de baja; no es error si no estaba suscrito. */
    void deleteById(SuscripcionLibro.Clave id);

    /** Los libros que espera un estudiante: el frontend pinta "Dejar de avisarme". */
    @Query("SELECT libro_id FROM suscripcion_libro WHERE solicitante = :solicitante ORDER BY libro_id")
    List<String> librosDe(String solicitante);
}
