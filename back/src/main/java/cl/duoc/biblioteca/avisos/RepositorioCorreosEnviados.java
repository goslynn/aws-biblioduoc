package cl.duoc.biblioteca.avisos;

import org.springframework.data.jdbc.repository.query.Modifying;
import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * Registro de correos que ya salieron (tabla correo_enviado, solo SELECT e
 * INSERT).
 *
 * RabbitMQ garantiza entrega "al menos una vez": un mensaje puede llegar dos
 * veces (por ejemplo, si la aplicacion muere despues de registrar el correo y
 * antes del ACK). Mirar aqui antes de enviar evita repetirlo. Si muere entre el
 * envio y el registro no hay como saberlo, y el correo puede salir dos veces.
 */
public interface RepositorioCorreosEnviados extends Repository<CorreoEnviado, String> {

    boolean existsById(String mensajeId);

    /** Idempotente: registrar dos veces el mismo mensajeId no es error. */
    @Modifying
    @Query("""
            INSERT INTO correo_enviado (mensaje_id, destinatario, asunto, enviado_en)
            VALUES (:mensajeId, :destinatario, :asunto, now())
            ON CONFLICT DO NOTHING
            """)
    void registrar(String mensajeId, String destinatario, String asunto);
}
