package cl.duoc.biblioteca.catalogo;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Quien espera cada libro agotado ("avisame cuando vuelva"). Tabla
 * suscripcion_libro, ver V3__prestamos_y_avisos.sql.
 */
@Component
public class SuscripcionesLibro {

    private final JdbcClient jdbc;

    public SuscripcionesLibro(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotente: suscribirse dos veces deja una sola fila. */
    public void suscribir(String libroId, String solicitante) {
        jdbc.sql("""
                INSERT INTO suscripcion_libro (libro_id, solicitante, creada_en)
                VALUES (:libro, :solicitante, :ahora)
                ON CONFLICT DO NOTHING
                """)
                .param("libro", libroId)
                .param("solicitante", solicitante)
                .param("ahora", Timestamp.from(Instant.now()))
                .update();
    }

    public void cancelar(String libroId, String solicitante) {
        jdbc.sql("DELETE FROM suscripcion_libro WHERE libro_id = :libro AND solicitante = :solicitante")
                .param("libro", libroId)
                .param("solicitante", solicitante)
                .update();
    }

    /** Los libros que espera un estudiante: el frontend pinta "Dejar de avisarme". */
    public List<String> deSolicitante(String solicitante) {
        return jdbc.sql("SELECT libro_id FROM suscripcion_libro WHERE solicitante = :solicitante ORDER BY libro_id")
                .param("solicitante", solicitante)
                .query(String.class)
                .list();
    }
}
