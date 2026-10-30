package cl.duoc.biblioteca.avisos;

import java.sql.Timestamp;
import java.time.Instant;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Registro de correos que ya salieron (tabla correo_enviado).
 *
 * RabbitMQ garantiza entrega "al menos una vez": un mensaje puede llegar dos
 * veces (por ejemplo, si la aplicacion muere despues de enviar el correo y
 * antes del ACK). Mirar aqui antes de enviar evita el correo repetido.
 */
@Component
public class CorreosEnviados {

    private final JdbcClient jdbc;

    public CorreosEnviados(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean yaSalio(String mensajeId) {
        return jdbc.sql("SELECT count(*) FROM correo_enviado WHERE mensaje_id = :id")
                .param("id", mensajeId)
                .query(Integer.class)
                .single() > 0;
    }

    public void registrar(Correo correo) {
        jdbc.sql("""
                INSERT INTO correo_enviado (mensaje_id, destinatario, asunto, enviado_en)
                VALUES (:id, :para, :asunto, :ahora)
                ON CONFLICT DO NOTHING
                """)
                .param("id", correo.mensajeId())
                .param("para", correo.para())
                .param("asunto", correo.asunto())
                .param("ahora", Timestamp.from(Instant.now()))
                .update();
    }
}
