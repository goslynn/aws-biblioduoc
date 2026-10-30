package cl.duoc.biblioteca.avisos;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import cl.duoc.biblioteca.eventos.TopologiaRabbit;
import tools.jackson.databind.json.JsonMapper;

/**
 * Deja correos redactados en el exchange direct "correo".
 *
 * La routing key elige el CARRIL:
 *   transaccional  lo que le pasa a UNA persona por algo que hizo (comprobante,
 *                  aprobacion, recordatorio). Tiene que salir ya.
 *   masivo         lo que le llega a MUCHOS a la vez (un libro repuesto con 200
 *                  interesados). Va por otra cola con su propio consumidor
 *                  lento, asi esos 200 no retrasan el comprobante de nadie.
 *
 * Espera la confirmacion del broker antes de volver: quien llama hace ACK de
 * su propio mensaje recien entonces, y si el broker no confirma, el evento
 * original se reintenta en vez de perderse a medio camino.
 */
@Component
public class PublicadorCorreo {

    private final RabbitTemplate rabbit;
    private final JsonMapper json;

    public PublicadorCorreo(RabbitTemplate rabbit, JsonMapper json) {
        this.rabbit = rabbit;
        this.json = json;
    }

    public void encolar(List<Correo> correos, String carril) {
        if (correos.isEmpty()) {
            return;
        }
        rabbit.invoke(canal -> {
            for (Correo correo : correos) {
                canal.send(TopologiaRabbit.EXCHANGE_CORREO, carril, mensaje(correo));
            }
            canal.waitForConfirmsOrDie(5_000);
            return null;
        });
    }

    private Message mensaje(Correo correo) {
        return MessageBuilder.withBody(json.writeValueAsBytes(correo))
                .setContentType(MessageProperties.CONTENT_TYPE_JSON)
                .setContentEncoding(StandardCharsets.UTF_8.name())
                .setMessageId(correo.mensajeId())
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .build();
    }
}
