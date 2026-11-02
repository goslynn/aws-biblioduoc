package cl.duoc.biblioteca.eventos;

import org.springframework.amqp.core.Message;

/**
 * Cuantas veces se ha entregado ya un mensaje de una cola quorum.
 *
 * Cuando un consumidor hace NACK con requeue, la cola quorum devuelve el
 * mensaje y anota la devolucion en la cabecera x-delivery-count. Si las
 * devoluciones superan x-delivery-limit, el BROKER lo aparta solo en la DLQ:
 * el consumidor no se entera, y sin esta clase eso no dejaria rastro en los
 * logs de la aplicacion. Con ella, el consumidor reconoce el ultimo intento y
 * lo registra antes de soltar el mensaje.
 */
public final class Entregas {

    private Entregas() {
    }

    /** Devoluciones anteriores a esta entrega (0 en la primera). */
    public static long previas(Message mensaje) {
        Object valor = mensaje.getMessageProperties().getHeader("x-delivery-count");
        return valor instanceof Number n ? n.longValue() : 0;
    }

    /**
     * true si un NACK con requeue ahora ya no reintenta: el broker lo apartara
     * en la DLQ, porque las devoluciones pasarian de {@link TopologiaRabbit#LIMITE_ENTREGAS}.
     */
    public static boolean esUltima(Message mensaje) {
        return previas(mensaje) >= TopologiaRabbit.LIMITE_ENTREGAS;
    }
}
