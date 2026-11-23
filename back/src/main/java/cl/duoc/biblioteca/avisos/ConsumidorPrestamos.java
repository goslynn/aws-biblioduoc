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
import cl.duoc.biblioteca.eventos.LectorEventos;
import cl.duoc.biblioteca.eventos.ProcesadorDeEventos;
import cl.duoc.biblioteca.eventos.TiposEvento;
import cl.duoc.biblioteca.eventos.TopologiaRabbit;
import cl.duoc.biblioteca.solicitudes.EstadoSolicitud;
import cl.duoc.biblioteca.solicitudes.RepositorioSolicitudes;
import cl.duoc.biblioteca.solicitudes.Solicitud;

/**
 * Cola notif.prestamos: de un evento de prestamo a un correo para su dueno.
 *
 * Recibe solicitud.*, recordatorio.* y aviso.* (bindings del exchange topic).
 * No envia nada: redacta y deja el correo en el carril transaccional. Si no
 * hay nada que enviar (el estudiante no activo los avisos, o el recordatorio
 * llego tarde a un prestamo ya devuelto) termina igual con ACK.
 *
 * El ACK, el NACK y la DLQ los decide ProcesadorDeEventos.
 */
@Component
public class ConsumidorPrestamos {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorPrestamos.class);

    private final ProcesadorDeEventos procesador;
    private final LectorEventos lector;
    private final RepositorioContactos contactos;
    private final RepositorioSolicitudes solicitudes;
    private final RedactorCorreos redactor;
    private final PublicadorCorreo publicador;

    public ConsumidorPrestamos(ProcesadorDeEventos procesador, LectorEventos lector, RepositorioContactos contactos,
            RepositorioSolicitudes solicitudes, RedactorCorreos redactor, PublicadorCorreo publicador) {
        this.procesador = procesador;
        this.lector = lector;
        this.contactos = contactos;
        this.solicitudes = solicitudes;
        this.redactor = redactor;
        this.publicador = publicador;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_NOTIF_PRESTAMOS, containerFactory = TopologiaRabbit.FABRICA_MANUAL)
    public void recibir(Message mensaje, Channel canal) throws IOException {
        procesador.procesar(mensaje, canal, TopologiaRabbit.COLA_NOTIF_PRESTAMOS, (evento, m) -> redactar(evento)
                .ifPresent(correo -> publicador.encolar(List.of(correo), TopologiaRabbit.RK_TRANSACCIONAL)));
    }

    private Optional<Correo> redactar(Evento evento) {
        if (TiposEvento.AVISO_ACTIVADO.equals(evento.tipo())) {
            return correoDe(evento.actor()).map(para -> redactor.deActivacion(evento, para));
        }

        Solicitud solicitud = lector.dato(evento, "solicitud", Solicitud.class);
        if (TiposEvento.RECORDATORIO_VENCE.equals(evento.tipo()) && !sigueVigente(solicitud.id())) {
            log.info("recordatorio de {} descartado: ya no esta APROBADA", solicitud.id());
            return Optional.empty();
        }
        // Sin contacto no hay consentimiento: se descarta sin error.
        return correoDe(solicitud.solicitante()).map(para -> redactor.deSolicitud(evento, solicitud, para));
    }

    private Optional<String> correoDe(String solicitante) {
        return solicitante == null ? Optional.empty() : contactos.findById(solicitante).map(ContactoAviso::email);
    }

    /**
     * El recordatorio se programo hace un rato; el prestamo pudo devolverse
     * entretanto. Se vuelve a mirar el estado REAL antes de molestar a nadie.
     */
    private boolean sigueVigente(String id) {
        return solicitudes.findById(id).map(s -> s.estado() == EstadoSolicitud.APROBADA).orElse(false);
    }
}
