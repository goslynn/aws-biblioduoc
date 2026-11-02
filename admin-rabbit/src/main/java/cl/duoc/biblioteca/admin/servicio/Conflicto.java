package cl.duoc.biblioteca.admin.servicio;

/**
 * La operacion choca con el estado del broker: el recurso ya existe, es parte
 * de la topologia protegida, o la cola todavia tiene mensajes. La web lo
 * traduce a 409.
 */
public class Conflicto extends RuntimeException {

    public Conflicto(String mensaje) {
        super(mensaje);
    }
}
