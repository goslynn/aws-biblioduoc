package cl.duoc.biblioteca.eventos;

import org.springframework.amqp.core.Message;
import org.springframework.stereotype.Component;

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

    /** El sobre {@link Evento} de un mensaje del exchange biblioteca.eventos. */
    public Evento evento(Message mensaje) {
        Evento evento = leer(mensaje, Evento.class, "un Evento");
        if (evento.id() == null || evento.tipo() == null || evento.datos() == null) {
            throw new MensajeInvalido("evento sin id, tipo o datos");
        }
        return evento;
    }

    /** Cualquier cuerpo JSON como {@code tipo}; ilegible = MensajeInvalido. */
    public <T> T leer(Message mensaje, Class<T> tipo, String descripcion) {
        try {
            return json.readValue(mensaje.getBody(), tipo);
        } catch (JacksonException e) {
            throw new MensajeInvalido("el cuerpo no es " + descripcion + " valido", e);
        }
    }

    /** Un objeto obligatorio de evento.datos, por ejemplo la solicitud de un solicitud.*. */
    public <T> T dato(Evento evento, String campo, Class<T> tipo) {
        Object valor = obligatorio(evento, campo);
        try {
            return json.convertValue(valor, tipo);
        } catch (JacksonException | IllegalArgumentException e) {
            throw new MensajeInvalido("el campo " + campo + " del evento " + evento.tipo() + " no se puede leer", e);
        }
    }

    /** Un campo de texto obligatorio de evento.datos. */
    public String texto(Evento evento, String campo) {
        return obligatorio(evento, campo).toString();
    }

    private static Object obligatorio(Evento evento, String campo) {
        Object valor = evento.datos().get(campo);
        if (valor == null) {
            throw new MensajeInvalido("el evento " + evento.tipo() + " no trae " + campo);
        }
        return valor;
    }
}
