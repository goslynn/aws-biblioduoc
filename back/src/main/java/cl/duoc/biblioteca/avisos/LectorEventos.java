package cl.duoc.biblioteca.avisos;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.solicitudes.Solicitud;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Del cuerpo de un mensaje a objetos. Todo lo que no se pueda leer se
 * convierte en {@link MensajeInvalido}: ese mensaje no tiene arreglo y debe
 * ir a la DLQ, no reintentarse.
 */
@Component
public class LectorEventos {

    private final JsonMapper json;

    public LectorEventos(JsonMapper json) {
        this.json = json;
    }

    public Evento evento(Message mensaje) {
        try {
            Evento evento = json.readValue(mensaje.getBody(), Evento.class);
            if (evento.id() == null || evento.tipo() == null || evento.datos() == null) {
                throw new MensajeInvalido("evento sin id, tipo o datos");
            }
            return evento;
        } catch (JacksonException e) {
            throw new MensajeInvalido("el cuerpo no es un Evento valido", e);
        }
    }

    public Correo correo(Message mensaje) {
        try {
            Correo correo = json.readValue(mensaje.getBody(), Correo.class);
            if (correo.mensajeId() == null || correo.para() == null || correo.asunto() == null) {
                throw new MensajeInvalido("correo sin mensajeId, destinatario o asunto");
            }
            return correo;
        } catch (JacksonException e) {
            throw new MensajeInvalido("el cuerpo no es un Correo valido", e);
        }
    }

    /** La solicitud que viaja en datos.solicitud de los eventos solicitud.* y recordatorio.*. */
    public Solicitud solicitud(Evento evento) {
        Object datos = evento.datos().get("solicitud");
        if (datos == null) {
            throw new MensajeInvalido("el evento " + evento.tipo() + " no trae la solicitud");
        }
        try {
            return json.convertValue(datos, Solicitud.class);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MensajeInvalido("la solicitud del evento no se puede leer", e);
        }
    }

    /** Un campo de texto obligatorio de evento.datos. */
    public String texto(Evento evento, String campo) {
        Object valor = evento.datos().get(campo);
        if (valor == null) {
            throw new MensajeInvalido("el evento " + evento.tipo() + " no trae " + campo);
        }
        return valor.toString();
    }
}
