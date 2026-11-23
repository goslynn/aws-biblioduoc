package cl.duoc.biblioteca.bitacora;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import cl.duoc.biblioteca.eventos.ProcesadorDeEventos;
import cl.duoc.biblioteca.eventos.TopologiaRabbit;

/**
 * Cola bitacora: binding "#" en el exchange topic, es decir, recibe una copia
 * de TODOS los eventos. Es el mismo efecto que un exchange fanout, pero sin
 * un segundo exchange: el topic ya reparte cada evento a todas las colas cuyo
 * patron encaja, y "#" encaja con todo.
 *
 * El consumidor no sabe nada de correos: si manana se agrega otro tipo de
 * evento, aparece en la bitacora sin tocar esta clase. El ACK/NACK lo decide
 * ProcesadorDeEventos.
 */
@Component
public class ConsumidorBitacora {

    private final ProcesadorDeEventos procesador;
    private final Bitacora bitacora;

    public ConsumidorBitacora(ProcesadorDeEventos procesador, Bitacora bitacora) {
        this.procesador = procesador;
        this.bitacora = bitacora;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_BITACORA, containerFactory = TopologiaRabbit.FABRICA_MANUAL)
    public void recibir(Message mensaje, Channel canal) throws IOException {
        procesador.procesar(mensaje, canal, TopologiaRabbit.COLA_BITACORA, (evento, m) ->
                bitacora.anotar(evento, new String(m.getBody(), StandardCharsets.UTF_8)));
    }
}
