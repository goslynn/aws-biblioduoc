package cl.duoc.biblioteca.adminkafka.servicio;

/** El cluster no respondio a tiempo. La web lo traduce a 503: reintentar mas tarde puede servir. */
public class KafkaNoDisponible extends RuntimeException {

    public KafkaNoDisponible(String mensaje, Throwable causa) {
        super(mensaje, causa);
    }
}
