package cl.duoc.biblioteca.avisos;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.eventos.TiposEvento;
import cl.duoc.biblioteca.solicitudes.LineaSolicitud;
import cl.duoc.biblioteca.solicitudes.Sede;
import cl.duoc.biblioteca.solicitudes.Solicitud;

/**
 * El texto de cada correo. Solo redacta: no consulta ni envia nada.
 *
 * Las fechas se muestran en hora de Chile; en la base y en los eventos van
 * siempre en UTC.
 */
@Component
public class RedactorCorreos {

    private static final ZoneId CHILE = ZoneId.of("America/Santiago");
    private static final DateTimeFormatter FECHA_HORA =
            DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm").withZone(CHILE);

    private static final String PIE = """

            --
            Biblioteca Duoc
            Recibes este correo porque activaste los avisos en "Mis solicitudes".
            Puedes desactivarlos desde ahi en cualquier momento.
            """;

    /** Correo al estudiante por algo que le paso a SU solicitud. */
    public Correo deSolicitud(Evento evento, Solicitud s, String para) {
        String asunto;
        String cuerpo;
        switch (evento.tipo()) {
            case TiposEvento.SOLICITUD_CREADA -> {
                asunto = "Recibimos tu solicitud " + s.id();
                cuerpo = """
                        Tu solicitud %s quedo registrada y esta en revision.

                        %s
                        Sede de retiro: %s
                        Dias pedidos: %d

                        Te escribiremos cuando el equipo de biblioteca la revise.
                        """.formatted(s.id(), libros(s), sede(s.sede()), s.dias());
            }
            case TiposEvento.SOLICITUD_APROBADA -> {
                asunto = "Solicitud " + s.id() + " aprobada";
                cuerpo = """
                        Tu solicitud %s fue aprobada. Los ejemplares estan reservados para ti.

                        %s
                        Retiralos en: %s
                        Devuelvelos antes del: %s

                        Te recordaremos antes del vencimiento.
                        """.formatted(s.id(), libros(s), sede(s.sede()), fechaHora(s.venceEn()));
            }
            case TiposEvento.SOLICITUD_RECHAZADA -> {
                asunto = "Solicitud " + s.id() + " rechazada";
                cuerpo = """
                        El equipo de biblioteca rechazo tu solicitud %s.

                        Motivo: %s

                        %s
                        Si tienes dudas, acercate al meson de %s.
                        """.formatted(s.id(), s.motivoRechazo(), libros(s), sede(s.sede()));
            }
            case TiposEvento.SOLICITUD_DEVUELTA -> {
                asunto = "Devolucion registrada: " + s.id();
                cuerpo = """
                        Registramos la devolucion de tu prestamo %s. Gracias.

                        %s
                        """.formatted(s.id(), libros(s));
            }
            case TiposEvento.RECORDATORIO_VENCE -> {
                asunto = "Tu prestamo " + s.id() + " vence pronto";
                cuerpo = """
                        Te recordamos que tu prestamo %s vence el %s.

                        %s
                        Devuelvelo en: %s
                        """.formatted(s.id(), fechaHora(s.venceEn()), libros(s), sede(s.sede()));
            }
            default -> throw new MensajeInvalido("no hay correo para el evento " + evento.tipo());
        }
        return new Correo(evento.id().toString(), para, asunto, cuerpo + PIE);
    }

    /** Bienvenida al activar los avisos: confirma que la direccion funciona. */
    public Correo deActivacion(Evento evento, String para) {
        return new Correo(evento.id().toString(), para, "Avisos de la Biblioteca Duoc activados", """
                Desde ahora te avisaremos por este medio cuando:

                  * registremos una solicitud tuya,
                  * la aprobemos o rechacemos,
                  * se acerque el vencimiento de un prestamo,
                  * vuelva a haber ejemplares de un libro que estes esperando.
                """ + PIE);
    }

    /** "Volvio el libro que esperabas". Un correo por interesado. */
    public Correo deDisponibilidad(Evento evento, String solicitante, String para, String titulo, String disponibles) {
        return new Correo(evento.id() + "/" + solicitante, para, "Volvio: " + titulo, """
                Hay ejemplares disponibles de "%s" (%s en este momento).

                Te avisamos porque pediste que te avisaramos cuando volviera. Este
                aviso no reserva el libro: pidelo desde el catalogo antes de que se
                agote de nuevo.

                Este fue el unico aviso por este libro; si vuelve a agotarse y lo
                sigues esperando, suscribete otra vez.
                """.formatted(titulo, disponibles) + PIE);
    }

    private static String libros(Solicitud s) {
        return s.lineas().stream()
                .map(RedactorCorreos::linea)
                .collect(Collectors.joining("\n", "Libros:\n", "\n"));
    }

    private static String linea(LineaSolicitud l) {
        return "  * " + l.titulo() + " (" + l.autor() + ") x" + l.cantidad();
    }

    private static String sede(Sede sede) {
        return switch (sede) {
            case SAN_JOAQUIN -> "San Joaquin";
            case PLAZA_OESTE -> "Plaza Oeste";
            case PUENTE_ALTO -> "Puente Alto";
            case VINA_DEL_MAR -> "Vina del Mar";
            case CONCEPCION -> "Concepcion";
        };
    }

    private static String fechaHora(Instant instante) {
        return instante == null ? "(sin fecha)" : FECHA_HORA.format(instante);
    }
}
