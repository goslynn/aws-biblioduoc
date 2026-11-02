package cl.duoc.biblioteca.admin.dto;

/** Los tipos de exchange de AMQP 0-9-1. Uno desconocido en el JSON da 400. */
public enum TipoExchange {
    DIRECT, TOPIC, FANOUT, HEADERS
}
