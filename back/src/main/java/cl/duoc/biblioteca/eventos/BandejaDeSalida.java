package cl.duoc.biblioteca.eventos;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import tools.jackson.databind.json.JsonMapper;

/**
 * La bandeja de salida (patron transactional outbox).
 *
 * EL PROBLEMA QUE RESUELVE
 *   Aprobar una solicitud son dos escrituras en dos sistemas: el UPDATE en
 *   PostgreSQL y el mensaje en RabbitMQ. Si se hacen por separado, tarde o
 *   temprano una sale bien y la otra no (el broker reiniciando justo en ese
 *   segundo), y el estudiante nunca recibe el correo de una solicitud que si
 *   se aprobo.
 *
 * COMO
 *   El evento se escribe en la tabla evento_pendiente DENTRO de la misma
 *   transaccion que el cambio de negocio: se guardan los dos o ninguno.
 *   {@link RelayEventos} los lee despues, los publica y recien entonces los
 *   borra. Si RabbitMQ esta caido, los eventos esperan en la tabla.
 *
 * {@code Propagation.MANDATORY}: llamar a esto fuera de una transaccion es un
 * error de programacion (el evento podria quedar sin su cambio), asi que falla.
 */
@Component
public class BandejaDeSalida {

    private final JdbcClient jdbc;
    private final JsonMapper json;

    public BandejaDeSalida(JdbcClient jdbc, JsonMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public Evento registrar(String tipo, String actor, String referencia, Map<String, Object> datos) {
        Evento evento = new Evento(UUID.randomUUID(), tipo,
                Instant.now().truncatedTo(ChronoUnit.MICROS), actor, referencia, datos);

        jdbc.sql("""
                INSERT INTO evento_pendiente (id, tipo, cuerpo, creado_en)
                VALUES (:id, :tipo, :cuerpo, :creadoEn)
                """)
                .param("id", evento.id())
                .param("tipo", evento.tipo())
                .param("cuerpo", json.writeValueAsString(evento))
                .param("creadoEn", Timestamp.from(evento.ocurridoEn()))
                .update();
        return evento;
    }
}
