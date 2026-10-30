package cl.duoc.biblioteca.avisos;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import cl.duoc.biblioteca.catalogo.SuscripcionesLibro;
import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.eventos.TopologiaRabbit;

/**
 * Cola notif.disponibilidad: un stock.repuesto se convierte en UN correo por
 * cada estudiante que esperaba ese libro, por el carril MASIVO.
 *
 * ORDEN DE LOS PASOS, Y POR QUE
 *   1. encolar los correos (con confirmacion del broker),
 *   2. borrar las suscripciones de quienes ya fueron avisados,
 *   3. ACK.
 *   Si la aplicacion muere entre 1 y 3, el mensaje se reentrega y los correos
 *   se encolan otra vez; EnviadorSmtp los reconoce por su mensajeId (tabla
 *   correo_enviado) y no los repite. Al reves —borrar primero— un fallo dejaria
 *   gente sin avisar y sin suscripcion: un aviso perdido sin rastro.
 */
@Component
public class ConsumidorDisponibilidad {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDisponibilidad.class);

    private final LectorEventos lector;
    private final ContactosAviso contactos;
    private final SuscripcionesLibro suscripciones;
    private final RedactorCorreos redactor;
    private final PublicadorCorreo publicador;

    public ConsumidorDisponibilidad(LectorEventos lector, ContactosAviso contactos, SuscripcionesLibro suscripciones,
            RedactorCorreos redactor, PublicadorCorreo publicador) {
        this.lector = lector;
        this.contactos = contactos;
        this.suscripciones = suscripciones;
        this.redactor = redactor;
        this.publicador = publicador;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_NOTIF_DISPONIBILIDAD,
            containerFactory = TopologiaRabbit.FABRICA_MANUAL)
    public void recibir(Message mensaje, Channel canal) throws IOException {
        long tag = mensaje.getMessageProperties().getDeliveryTag();
        try {
            Evento evento = lector.evento(mensaje);
            String libroId = lector.texto(evento, "libroId");
            String titulo = lector.texto(evento, "titulo");
            String disponibles = lector.texto(evento, "disponibles");

            List<ContactosAviso.Interesado> interesados = contactos.interesadosEn(libroId);
            publicador.encolar(interesados.stream()
                    .map(i -> redactor.deDisponibilidad(evento, i.solicitante(), i.email(), titulo, disponibles))
                    .toList(), TopologiaRabbit.RK_MASIVO);
            interesados.forEach(i -> suscripciones.cancelar(libroId, i.solicitante()));

            log.info("{} repuesto: {} avisos encolados", libroId, interesados.size());
            canal.basicAck(tag, false);
        } catch (MensajeInvalido e) {
            log.warn("mensaje invalido en {}, va a la DLQ: {}",
                    TopologiaRabbit.COLA_NOTIF_DISPONIBILIDAD, e.getMessage());
            canal.basicNack(tag, false, false);
        } catch (RuntimeException e) {
            log.warn("fallo transitorio en {}, se reintenta: {}",
                    TopologiaRabbit.COLA_NOTIF_DISPONIBILIDAD, e.toString());
            canal.basicNack(tag, false, true);
        }
    }
}
