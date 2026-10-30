package cl.duoc.biblioteca.avisos;

/**
 * Un mensaje que NUNCA se va a poder procesar: JSON roto, campos que faltan,
 * un tipo de evento desconocido. Reintentarlo no sirve de nada, asi que los
 * consumidores lo rechazan SIN requeue y el broker lo manda directo a la DLQ.
 *
 * Es la diferencia que importa en un consumidor: el error del MENSAJE (va a
 * la DLQ ya) contra el error del ENTORNO (la base caida: se reintenta).
 */
public class MensajeInvalido extends RuntimeException {

    public MensajeInvalido(String motivo, Throwable causa) {
        super(motivo, causa);
    }

    public MensajeInvalido(String motivo) {
        super(motivo);
    }
}
