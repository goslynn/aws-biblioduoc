package cl.duoc.biblioteca.eventos;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Vacia la bandeja de salida hacia RabbitMQ (la otra mitad del outbox).
 *
 * Cada segundo toma hasta 50 eventos pendientes y los publica en el exchange
 * topic {@code biblioteca.eventos}, con su tipo como routing key.
 *
 * PUBLISHER CONFIRMS
 *   Que basicPublish no lance excepcion no significa que el broker tenga el
 *   mensaje. Con publisher-confirm-type=simple, waitForConfirmsOrDie espera
 *   el ACK del BROKER (el mensaje ya esta en disco, en la cola). Solo entonces
 *   se confirma el DELETE de la tabla. Si el broker no confirma, se lanza una
 *   excepcion, la transaccion hace rollback y los eventos siguen en la tabla
 *   para el siguiente intento.
 *
 * El caso limite —el broker confirma y la base cae antes del commit— produce
 * un evento DUPLICADO, nunca uno perdido. Por eso todos los consumidores son
 * idempotentes (ver correo_enviado y bitacora.evento_id).
 */
@Component
public class RelayEventos {

    private static final Logger log = LoggerFactory.getLogger(RelayEventos.class);

    private final JdbcClient jdbc;
    private final RabbitTemplate rabbit;

    public RelayEventos(JdbcClient jdbc, RabbitTemplate rabbit) {
        this.jdbc = jdbc;
        this.rabbit = rabbit;
    }

    private record Pendiente(String id, String tipo, String cuerpo) {
    }

    @Scheduled(fixedDelayString = "${biblioteca.eventos.relay:PT1S}")
    @Transactional
    public void publicarPendientes() {
        // DELETE ... RETURNING en vez de SELECT ... FOR UPDATE: biblioteca_app
        // no tiene UPDATE sobre esta tabla, y no le hace falta. Las filas
        // borradas quedan bloqueadas hasta el commit; si la publicacion falla,
        // el rollback las devuelve intactas.
        List<Pendiente> lote = jdbc.sql("""
                DELETE FROM evento_pendiente
                WHERE id IN (SELECT id FROM evento_pendiente ORDER BY creado_en LIMIT 50)
                RETURNING id, tipo, cuerpo
                """)
                .query((rs, n) -> new Pendiente(rs.getString("id"), rs.getString("tipo"), rs.getString("cuerpo")))
                .list();

        if (lote.isEmpty()) {
            return;
        }

        rabbit.invoke(canal -> {
            for (Pendiente p : lote) {
                canal.send(TopologiaRabbit.EXCHANGE_EVENTOS, p.tipo(), mensaje(p));
            }
            canal.waitForConfirmsOrDie(5_000);
            return null;
        });
        log.info("publicados {} eventos en {}", lote.size(), TopologiaRabbit.EXCHANGE_EVENTOS);
    }

    private static Message mensaje(Pendiente p) {
        return MessageBuilder.withBody(p.cuerpo().getBytes(StandardCharsets.UTF_8))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(p.id())
                .setType(p.tipo())
                // PERSISTENT: sobrevive a un reinicio del broker (las colas
                // tambien son durables). Sin las dos cosas, se pierde.
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
