package cl.duoc.biblioteca.admin.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Un binding: "lo que llegue a {exchange} con {routingKey}, a {cola}".
 *
 * Es el cuerpo de POST /api/admin/bindings y los parametros de DELETE
 * /api/admin/bindings (un binding no tiene nombre propio: lo identifican sus
 * tres partes).
 *
 * @param exchange   origen; tiene que existir
 * @param cola       destino; tiene que existir
 * @param routingKey clave o patron (topic: "solicitud.*", "#"). Puede ser
 *                   vacia, que es lo habitual en fanout y headers, pero no
 *                   puede faltar.
 */
public record NuevoBinding(

        @NombreAmqp
        String exchange,

        @NombreAmqp
        String cola,

        @NotNull(message = "hay que indicar la routingKey (puede ser vacia: \"\")")
        @Size(max = 255, message = "la routingKey no puede superar 255 caracteres")
        String routingKey) {
}
