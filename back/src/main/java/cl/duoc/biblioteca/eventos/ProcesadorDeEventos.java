package cl.duoc.biblioteca.eventos;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

/**
 * La politica de ACK de TODOS los consumidores de eventos, en un solo lugar.
 *
 * Antes cada consumidor (prestamos, disponibilidad, bitacora) repetia el mismo
 * try/catch de veinte lineas. Ahora cada uno solo dice QUE hace con el evento
 * (una {@link Reaccion}) y este componente decide COMO termina el mensaje:
 *
 *   ACK                    la reaccion termino sin error: procesado.
 *   NACK sin requeue       MensajeInvalido: el mensaje no tiene arreglo. El
 *                          broker lo manda al DLX y termina en la DLQ de su
 *                          cola para revisarlo a mano.
 *   NACK con requeue       cualquier otro fallo (base caida, broker sin
 *                          confirmar): vuelve a la cola al instante. La cola
 *                          quorum cuenta las devoluciones y, pasado
 *                          x-delivery-limit, lo aparta sola en la DLQ.
 *
 * Es el patron Estrategia: la politica es fija y la reaccion es intercambiable.
 * Un consumidor nuevo no puede olvidarse del ACK ni equivocarse de rama.
 */
@Component
public class ProcesadorDeEventos {

    private static final Logger log = LoggerFactory.getLogger(ProcesadorDeEventos.class);

    /** Lo que un consumidor hace con un evento ya leido. */
    @FunctionalInterface
    public interface Reaccion {
        void reaccionar(Evento evento, Message mensaje);
    }

    private final LectorEventos lector;

    public ProcesadorDeEventos(LectorEventos lector) {
        this.lector = lector;
    }

    /**
     * @param cola el nombre de la cola, para los logs; su DLQ es cola + {@link TopologiaRabbit#SUFIJO_DLQ}
     */
    public void procesar(Message mensaje, Channel canal, String cola, Reaccion reaccion) throws IOException {
        long tag = mensaje.getMessageProperties().getDeliveryTag();
        String dlq = cola + TopologiaRabbit.SUFIJO_DLQ;
        try {
            reaccion.reaccionar(lector.evento(mensaje), mensaje);
            canal.basicAck(tag, false);
        } catch (MensajeInvalido e) {
            log.warn("mensaje invalido en {}, va a {}: {}", cola, dlq, e.getMessage());
            canal.basicNack(tag, false, false);
        } catch (RuntimeException e) {
            if (Entregas.esUltima(mensaje)) {
                // El requeue ya no reintenta: el broker lo aparta en la DLQ
                // por x-delivery-limit. Se registra aqui, que es el ultimo
                // momento en que la aplicacion lo ve.
                log.error("fallo en {} tras {} reintentos, el broker lo aparta en {}: {}",
                        cola, Entregas.previas(mensaje), dlq, e.toString());
            } else {
                log.warn("fallo transitorio en {}, se reintenta: {}", cola, e.toString());
            }
            canal.basicNack(tag, false, true);
        }
    }
}
