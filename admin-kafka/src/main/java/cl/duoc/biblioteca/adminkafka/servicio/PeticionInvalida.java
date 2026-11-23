package cl.duoc.biblioteca.adminkafka.servicio;

/**
 * Kafka (o una regla de este servicio) rechazo los VALORES pedidos: una clave
 * de configuracion no permitida, un retention.ms que no es un numero, mas
 * replicas que brokers. La web lo traduce a 400.
 */
public class PeticionInvalida extends RuntimeException {

    public PeticionInvalida(String mensaje) {
        super(mensaje);
    }
}
