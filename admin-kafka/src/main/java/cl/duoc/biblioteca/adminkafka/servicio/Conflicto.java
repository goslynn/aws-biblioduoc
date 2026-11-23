package cl.duoc.biblioteca.adminkafka.servicio;

/**
 * La operacion choca con el estado del cluster: el topico ya existe o esta
 * protegido, se piden menos particiones de las que hay, o el grupo tiene
 * consumidores activos. La web lo traduce a 409.
 */
public class Conflicto extends RuntimeException {

    public Conflicto(String mensaje) {
        super(mensaje);
    }
}
