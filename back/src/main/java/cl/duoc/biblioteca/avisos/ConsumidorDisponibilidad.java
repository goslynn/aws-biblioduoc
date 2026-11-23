package cl.duoc.biblioteca.avisos;

import java.io.IOException;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import com.rabbitmq.client.Channel;

import cl.duoc.biblioteca.catalogo.ServicioAvisame;
import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.eventos.LectorEventos;
import cl.duoc.biblioteca.eventos.ProcesadorDeEventos;
import cl.duoc.biblioteca.eventos.TopologiaRabbit;

/**
 * Cola notif.disponibilidad: un stock.repuesto se convierte en UN correo por
 * cada estudiante que esperaba ese libro, por el carril MASIVO.
 *
 * ORDEN DE LOS PASOS, Y POR QUE
 *   1. encolar los correos (con confirmacion del broker),
 *   2. borrar las suscripciones de quienes ya fueron avisados,
 *   3. ACK (lo hace ProcesadorDeEventos al volver sin error).
 *   Si la aplicacion muere entre 1 y 3, el mensaje se reentrega y los correos
 *   se encolan otra vez; EnviadorSmtp los reconoce por su mensajeId (tabla
 *   correo_enviado) y no los repite. Al reves —borrar primero— un fallo dejaria
 *   gente sin avisar y sin suscripcion: un aviso perdido sin rastro.
 */
@Component
public class ConsumidorDisponibilidad {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorDisponibilidad.class);

    private final ProcesadorDeEventos procesador;
    private final LectorEventos lector;
    private final RepositorioContactos contactos;
    private final ServicioAvisame avisame;
    private final RedactorCorreos redactor;
    private final PublicadorCorreo publicador;

    public ConsumidorDisponibilidad(ProcesadorDeEventos procesador, LectorEventos lector,
            RepositorioContactos contactos, ServicioAvisame avisame, RedactorCorreos redactor,
            PublicadorCorreo publicador) {
        this.procesador = procesador;
        this.lector = lector;
        this.contactos = contactos;
        this.avisame = avisame;
        this.redactor = redactor;
        this.publicador = publicador;
    }

    @RabbitListener(queues = TopologiaRabbit.COLA_NOTIF_DISPONIBILIDAD,
            containerFactory = TopologiaRabbit.FABRICA_MANUAL)
    public void recibir(Message mensaje, Channel canal) throws IOException {
        procesador.procesar(mensaje, canal, TopologiaRabbit.COLA_NOTIF_DISPONIBILIDAD, (evento, m) -> avisar(evento));
    }

    private void avisar(Evento evento) {
        String libroId = lector.texto(evento, "libroId");
        String titulo = lector.texto(evento, "titulo");
        String disponibles = lector.texto(evento, "disponibles");

        List<Interesado> interesados = contactos.interesadosEn(libroId);
        publicador.encolar(interesados.stream()
                .map(i -> redactor.deDisponibilidad(evento, i.solicitante(), i.email(), titulo, disponibles))
                .toList(), TopologiaRabbit.RK_MASIVO);
        interesados.forEach(i -> avisame.cancelarSinValidar(libroId, i.solicitante()));

        log.info("{} repuesto: {} avisos encolados", libroId, interesados.size());
    }
}
