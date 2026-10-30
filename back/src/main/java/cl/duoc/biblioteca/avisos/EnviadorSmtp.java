package cl.duoc.biblioteca.avisos;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.MailException;
import org.springframework.mail.MailParseException;
import org.springframework.mail.MailPreparationException;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import cl.duoc.biblioteca.eventos.TopologiaRabbit;
import jakarta.mail.SendFailedException;
import jakarta.mail.internet.AddressException;

/**
 * El UNICO lugar que habla con el proveedor SMTP. Consume las dos colas de
 * correo, cada una con su ritmo:
 *
 *   correo.transaccional  2 consumidores, prefetch 5: sale ya.
 *   correo.masivo         1 consumidor,   prefetch 1: de a uno, sin acaparar el SMTP.
 *
 * QUE PASA CUANDO EL ENVIO FALLA
 *   * Fallo PERMANENTE (direccion invalida, mensaje mal formado): reintentar
 *     no lo arregla. Se aparta en correo.fallidos con el motivo y ACK.
 *   * Fallo TRANSITORIO (SMTP caido, timeout, limite de envios): NACK SIN
 *     requeue. La cola tiene DLX = correo.reintento, asi que el broker lo
 *     manda a correo.espera; ahi nadie lo consume y, al vencer el TTL de la
 *     cola, vuelve a su cola de origen. Es un reintento CON ESPERA, sin una
 *     sola linea de temporizador en Java.
 *   * Cada vuelta deja una marca en el header x-death del mensaje. Con
 *     biblioteca.correo.max-intentos ya gastados, se aparta en correo.fallidos.
 *
 * correo.fallidos no tiene consumidor: es la DLQ que se revisa en el panel de
 * RabbitMQ. Desde ahi se puede reenviar un mensaje a mano (Move messages)
 * cuando se arregla la causa.
 */
@Component
public class EnviadorSmtp {

    private static final Logger log = LoggerFactory.getLogger(EnviadorSmtp.class);

    /** Header que explica en el panel por que un correo termino en la DLQ. */
    public static final String HEADER_MOTIVO = "x-motivo-fallo";

    private final LectorEventos lector;
    private final CorreosEnviados enviados;
    private final JavaMailSender smtp;
    private final RabbitTemplate rabbit;
    private final String remitente;
    private final int maxIntentos;

    public EnviadorSmtp(LectorEventos lector, CorreosEnviados enviados, JavaMailSender smtp, RabbitTemplate rabbit,
            @Value("${biblioteca.correo.remitente}") String remitente,
            @Value("${biblioteca.correo.max-intentos:3}") int maxIntentos) {
        this.lector = lector;
        this.enviados = enviados;
        this.smtp = smtp;
        this.rabbit = rabbit;
        this.remitente = remitente;
        this.maxIntentos = maxIntentos;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_CORREO_TRANSACCIONAL,
            containerFactory = TopologiaRabbit.FABRICA_MANUAL, concurrency = "2")
    public void transaccional(Message mensaje, Channel canal) throws IOException {
        enviar(mensaje, canal);
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_CORREO_MASIVO,
            containerFactory = TopologiaRabbit.FABRICA_MASIVA, concurrency = "1")
    public void masivo(Message mensaje, Channel canal) throws IOException {
        enviar(mensaje, canal);
    }

    private void enviar(Message mensaje, Channel canal) throws IOException {
        long tag = mensaje.getMessageProperties().getDeliveryTag();

        Correo correo;
        try {
            correo = lector.correo(mensaje);
        } catch (MensajeInvalido e) {
            apartar(mensaje, e.getMessage());
            canal.basicAck(tag, false);
            return;
        }

        try {
            // Reentrega de un correo que YA salio: no se repite.
            if (enviados.yaSalio(correo.mensajeId())) {
                canal.basicAck(tag, false);
                return;
            }

            SimpleMailMessage email = new SimpleMailMessage();
            email.setFrom(remitente);
            email.setTo(correo.para());
            email.setSubject(correo.asunto());
            email.setText(correo.cuerpo());
            smtp.send(email);

            enviados.registrar(correo);
            log.info("correo {} enviado: {}", correo.mensajeId(), correo.asunto());
            canal.basicAck(tag, false);

        } catch (MailException e) {
            int intento = intentosPrevios(mensaje) + 1;
            if (esPermanente(e)) {
                apartar(mensaje, "fallo permanente: " + e.getMessage());
                canal.basicAck(tag, false);
            } else if (intento >= maxIntentos) {
                apartar(mensaje, "agoto " + maxIntentos + " intentos: " + e.getMessage());
                canal.basicAck(tag, false);
            } else {
                log.warn("correo {} fallo (intento {} de {}), a correo.espera: {}",
                        correo.mensajeId(), intento, maxIntentos, e.getMessage());
                canal.basicNack(tag, false, false);
            }
        } catch (RuntimeException e) {
            // La base (correo_enviado) no responde: tambien es transitorio.
            log.warn("correo {}: fallo del entorno, a correo.espera: {}", correo.mensajeId(), e.toString());
            canal.basicNack(tag, false, false);
        }
    }

    /**
     * Cuantas veces lo rechazo YA esta misma cola. El broker lleva la cuenta
     * en x-death: una entrada por (cola, motivo), con su "count".
     */
    private static int intentosPrevios(Message mensaje) {
        String cola = mensaje.getMessageProperties().getConsumerQueue();
        List<Map<String, ?>> muertes = mensaje.getMessageProperties().getXDeathHeader();
        if (muertes == null) {
            return 0;
        }
        return muertes.stream()
                .filter(m -> cola.equals(m.get("queue")) && "rejected".equals(String.valueOf(m.get("reason"))))
                .mapToInt(m -> ((Number) m.get("count")).intValue())
                .sum();
    }

    private static boolean esPermanente(MailException e) {
        if (e instanceof MailParseException || e instanceof MailPreparationException) {
            return true;
        }
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof AddressException || t instanceof SendFailedException) {
                return true;
            }
        }
        return false;
    }

    /** A la DLQ final, por el exchange por defecto (routing key = nombre de la cola). */
    private void apartar(Message mensaje, String motivo) {
        log.error("correo apartado en {}: {}", TopologiaRabbit.COLA_CORREO_FALLIDOS, motivo);
        rabbit.send("", TopologiaRabbit.COLA_CORREO_FALLIDOS,
                MessageBuilder.fromMessage(mensaje).setHeader(HEADER_MOTIVO, motivo).build());
    }
}
