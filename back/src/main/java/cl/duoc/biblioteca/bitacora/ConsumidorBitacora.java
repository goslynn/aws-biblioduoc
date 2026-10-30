package cl.duoc.biblioteca.bitacora;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import cl.duoc.biblioteca.avisos.LectorEventos;
import cl.duoc.biblioteca.avisos.MensajeInvalido;
import cl.duoc.biblioteca.eventos.TopologiaRabbit;

/**
 * Cola bitacora: binding "#" en el exchange topic, es decir, recibe una copia
 * de TODOS los eventos. Es el mismo efecto que un exchange fanout, pero sin
 * un segundo exchange: el topic ya reparte cada evento a todas las colas cuyo
 * patron encaja, y "#" encaja con todo.
 *
 * El consumidor no sabe nada de correos: si manana se agrega otro tipo de
 * evento, aparece en la bitacora sin tocar esta clase.
 */
@Component
public class ConsumidorBitacora {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorBitacora.class);

    private final LectorEventos lector;
    private final Bitacora bitacora;

    public ConsumidorBitacora(LectorEventos lector, Bitacora bitacora) {
        this.lector = lector;
        this.bitacora = bitacora;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_BITACORA, containerFactory = TopologiaRabbit.FABRICA_MANUAL)
    public void recibir(Message mensaje, Channel canal) throws IOException {
        long tag = mensaje.getMessageProperties().getDeliveryTag();
        try {
            bitacora.anotar(lector.evento(mensaje), new String(mensaje.getBody(), StandardCharsets.UTF_8));
            canal.basicAck(tag, false);
        } catch (MensajeInvalido e) {
            log.warn("mensaje invalido en {}, va a la DLQ: {}", TopologiaRabbit.COLA_BITACORA, e.getMessage());
            canal.basicNack(tag, false, false);
        } catch (RuntimeException e) {
            log.warn("fallo transitorio en {}, se reintenta: {}", TopologiaRabbit.COLA_BITACORA, e.toString());
            canal.basicNack(tag, false, true);
        }
    }
}
