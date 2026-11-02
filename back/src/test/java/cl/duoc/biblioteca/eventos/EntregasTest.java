package cl.duoc.biblioteca.eventos;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;

/**
 * El ultimo intento se reconoce por la cabecera x-delivery-count que pone la
 * cola quorum. Probarlo contra el broker exigiria provocar seis fallos
 * transitorios seguidos; la regla es aritmetica y se prueba aqui.
 */
class EntregasTest {

    @Test
    @DisplayName("primera entrega: sin cabecera, 0 devoluciones y no es la ultima")
    void primeraEntrega() {
        Message mensaje = MessageBuilder.withBody(new byte[0]).build();

        assertThat(Entregas.previas(mensaje)).isZero();
        assertThat(Entregas.esUltima(mensaje)).isFalse();
    }

    @Test
    @DisplayName("con LIMITE_ENTREGAS devoluciones, el proximo NACK lo aparta el broker en la DLQ")
    void ultimaEntrega() {
        Message penultima = conDevoluciones(TopologiaRabbit.LIMITE_ENTREGAS - 1);
        Message ultima = conDevoluciones(TopologiaRabbit.LIMITE_ENTREGAS);

        assertThat(Entregas.esUltima(penultima)).isFalse();
        assertThat(Entregas.esUltima(ultima)).isTrue();
    }

    private static Message conDevoluciones(long cuantas) {
        return MessageBuilder.withBody(new byte[0]).setHeader("x-delivery-count", cuantas).build();
    }
}
