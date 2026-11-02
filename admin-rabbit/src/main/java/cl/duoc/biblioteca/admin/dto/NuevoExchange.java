package cl.duoc.biblioteca.admin.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de POST /api/admin/exchanges.
 *
 * @param nombre  ver {@link NombreAmqp}
 * @param tipo    DIRECT, TOPIC, FANOUT o HEADERS; obligatorio
 * @param durable sobrevive a un reinicio del broker; por defecto true
 */
public record NuevoExchange(

        @NombreAmqp
        String nombre,

        @NotNull(message = "hay que indicar el tipo: DIRECT, TOPIC, FANOUT o HEADERS")
        TipoExchange tipo,

        Boolean durable) {

    public NuevoExchange {
        durable = durable == null || durable;
    }
}
