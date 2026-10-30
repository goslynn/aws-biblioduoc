package cl.duoc.biblioteca.avisos;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * A quien se puede escribir (tabla contacto_aviso, ver V3). Que exista la
 * fila ES el consentimiento: sin fila, no sale ningun correo a esa persona.
 */
@Component
public class ContactosAviso {

    /** Un estudiante que espera un libro y tiene los avisos activos. */
    public record Interesado(String solicitante, String email) {
    }

    private final JdbcClient jdbc;

    public ContactosAviso(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<String> correoDe(String solicitante) {
        return jdbc.sql("SELECT email FROM contacto_aviso WHERE solicitante = :s")
                .param("s", solicitante)
                .query(String.class)
                .optional();
    }

    public void guardar(String solicitante, String email) {
        jdbc.sql("""
                INSERT INTO contacto_aviso (solicitante, email, activado_en) VALUES (:s, :email, :ahora)
                ON CONFLICT (solicitante) DO UPDATE SET email = EXCLUDED.email
                """)
                .param("s", solicitante)
                .param("email", email)
                .param("ahora", Timestamp.from(Instant.now()))
                .update();
    }

    public void borrar(String solicitante) {
        jdbc.sql("DELETE FROM contacto_aviso WHERE solicitante = :s").param("s", solicitante).update();
    }

    /**
     * Quienes esperan un libro Y tienen los avisos activos. Los que esperan
     * sin avisos activos no aparecen: su suscripcion sigue viva para la
     * proxima vez, por si los activan.
     */
    public List<Interesado> interesadosEn(String libroId) {
        return jdbc.sql("""
                SELECT s.solicitante, c.email
                FROM suscripcion_libro s JOIN contacto_aviso c ON c.solicitante = s.solicitante
                WHERE s.libro_id = :libro
                ORDER BY s.creada_en
                """)
                .param("libro", libroId)
                .query(Interesado.class)
                .list();
    }
}
