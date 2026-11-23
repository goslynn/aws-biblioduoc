package cl.duoc.biblioteca.adminkafka.servicio;

/** El topico o el grupo no existe en el cluster. La web lo traduce a 404. */
public class NoEncontrado extends RuntimeException {

    public NoEncontrado(String mensaje) {
        super(mensaje);
    }
}
