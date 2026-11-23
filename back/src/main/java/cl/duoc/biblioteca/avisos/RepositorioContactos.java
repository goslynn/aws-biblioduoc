package cl.duoc.biblioteca.avisos;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/** La tabla contacto_aviso. Spring Data genera la implementacion. */
public interface RepositorioContactos extends Repository<ContactoAviso, String> {

    Optional<ContactoAviso> findById(String solicitante);

    /** Desactivar los avisos; no es error si no estaban activos. */
    void deleteById(String solicitante);

    /**
     * Alta del consentimiento, SOLO si no existia.
     *
     * @return true si la fila es nueva. Con dos activaciones simultaneas, la
     *         segunda espera a que la primera confirme y devuelve false: el
     *         correo de bienvenida sale una sola vez.
     */
    @Modifying
    @Query("""
            INSERT INTO contacto_aviso (solicitante, email, activado_en) VALUES (:solicitante, :email, now())
            ON CONFLICT (solicitante) DO NOTHING
            """)
    boolean crearSiNoExiste(String solicitante, String email);

    /** Si el email cambio en Cognito, se actualiza. */
    @Modifying
    @Query("UPDATE contacto_aviso SET email = :email WHERE solicitante = :solicitante")
    void actualizarEmail(String solicitante, String email);

    /**
     * Quienes esperan un libro Y tienen los avisos activos. Los que esperan
     * sin avisos activos no aparecen: su suscripcion sigue viva para la
     * proxima vez, por si los activan.
     */
    @Query("""
            SELECT s.solicitante, c.email
            FROM suscripcion_libro s JOIN contacto_aviso c ON c.solicitante = s.solicitante
            WHERE s.libro_id = :libroId
            ORDER BY s.creada_en
            """)
    List<Interesado> interesadosEn(String libroId);
}
