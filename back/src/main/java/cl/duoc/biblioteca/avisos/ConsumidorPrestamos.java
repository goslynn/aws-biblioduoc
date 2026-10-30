package cl.duoc.biblioteca.avisos;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.eventos.TiposEvento;
import cl.duoc.biblioteca.eventos.TopologiaRabbit;
import cl.duoc.biblioteca.solicitudes.EstadoSolicitud;
import cl.duoc.biblioteca.solicitudes.RepositorioSolicitudes;
import cl.duoc.biblioteca.solicitudes.Solicitud;

/**
 * Cola notif.prestamos: de un evento de prestamo a un correo para su dueno.
 *
 * Recibe solicitud.*, recordatorio.* y aviso.* (bindings del exchange topic).
 * No envia nada: redacta y deja el correo en el carril transaccional.
 *
 * LAS TRES SALIDAS DE UN MENSAJE
 *   ACK                    procesado: con correo encolado, o sin nada que
 *                          enviar (el estudiante no activo los avisos, o el
 *                          recordatorio llego tarde a un prestamo ya devuelto).
 *   NACK sin requeue       el mensaje es invalido: el broker lo manda al DLX y
 *                          termina en eventos.fallidos para revisarlo a mano.
 *   NACK con requeue       fallo del entorno (base caida, broker sin confirmar):
 *                          vuelve a la cola al instante. La cola quorum cuenta
 *                          las entregas y a la quinta lo manda sola a la DLQ.
 */
@Component
public class ConsumidorPrestamos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorPrestamos.class);

    private final LectorEventos lector;
    private final ContactosAviso contactos;
    private final RepositorioSolicitudes solicitudes;
    private final RedactorCorreos redactor;
    private final PublicadorCorreo publicador;

    public ConsumidorPrestamos(LectorEventos lector, ContactosAviso contactos, RepositorioSolicitudes solicitudes,
            RedactorCorreos redactor, PublicadorCorreo publicador) {
        this.lector = lector;
        this.contactos = contactos;
        this.solicitudes = solicitudes;
        this.redactor = redactor;
        this.publicador = publicador;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_NOTIF_PRESTAMOS, containerFactory = TopologiaRabbit.FABRICA_MANUAL)
    public void recibir(Message mensaje, Channel canal) throws IOException {
        long tag = mensaje.getMessageProperties().getDeliveryTag();
        try {
            Optional<Correo> correo = redactar(lector.evento(mensaje));
            correo.ifPresent(c -> publicador.encolar(List.of(c), TopologiaRabbit.RK_TRANSACCIONAL));
            canal.basicAck(tag, false);
        } catch (MensajeInvalido e) {
            log.warn("mensaje invalido en {}, va a la DLQ: {}", TopologiaRabbit.COLA_NOTIF_PRESTAMOS, e.getMessage());
            canal.basicNack(tag, false, false);
        } catch (RuntimeException e) {
            log.warn("fallo transitorio en {}, se reintenta: {}", TopologiaRabbit.COLA_NOTIF_PRESTAMOS, e.toString());
            canal.basicNack(tag, false, true);
        }
    }

    private Optional<Correo> redactar(Evento evento) {
        if (TiposEvento.AVISO_ACTIVADO.equals(evento.tipo())) {
            return contactos.correoDe(evento.actor()).map(para -> redactor.deActivacion(evento, para));
        }

        Solicitud solicitud = lector.solicitud(evento);
        if (TiposEvento.RECORDATORIO_VENCE.equals(evento.tipo()) && !sigueVigente(solicitud.id())) {
            log.info("recordatorio de {} descartado: ya no esta APROBADA", solicitud.id());
            return Optional.empty();
        }
        // Sin contacto no hay consentimiento: se descarta sin error.
        return contactos.correoDe(solicitud.solicitante())
                .map(para -> redactor.deSolicitud(evento, solicitud, para));
    }

    /**
     * El recordatorio se programo hace un rato; el prestamo pudo devolverse
     * entretanto. Se vuelve a mirar el estado REAL antes de molestar a nadie.
     */
    private boolean sigueVigente(String id) {
        return solicitudes.buscar(id).map(s -> s.estado() == EstadoSolicitud.APROBADA).orElse(false);
    }
}
