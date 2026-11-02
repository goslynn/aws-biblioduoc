package cl.duoc.biblioteca.admin.servicio;

/** La cola o el exchange no existe en el broker. La web lo traduce a 404. */
public class NoEncontrado extends RuntimeException {

    public NoEncontrado(String mensaje) {
        super(mensaje);
    }
}
