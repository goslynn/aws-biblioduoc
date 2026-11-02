package cl.duoc.biblioteca.admin.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de POST /api/admin/colas.
 *
 * @param nombre             ver {@link NombreAmqp}
 * @param tipo               CLASSIC (por defecto) o QUORUM
 * @param durable            sobrevive a un reinicio del broker; por defecto true
 * @param ttlMs              x-message-ttl: cuanto vive un mensaje en la cola; opcional
 * @param deadLetterExchange x-dead-letter-exchange: adonde va lo rechazado o
 *                           vencido; opcional, y tiene que existir
 * @param limiteEntregas     x-delivery-limit: reentregas antes de ir al DLX;
 *                           opcional y solo para QUORUM
 */
public record NuevaCola(

        @NombreAmqp
        String nombre,

        TipoCola tipo,

        Boolean durable,

        @Positive(message = "ttlMs tiene que ser mayor que 0")
        @Max(value = Integer.MAX_VALUE, message = "ttlMs no puede superar 2147483647 ms (~24 dias)")
        Long ttlMs,

        // Opcional: null es "sin DLX". Por eso no lleva @NombreAmqp (que
        // incluye @NotBlank), sino solo sus reglas de forma.
        @Size(max = 255, message = "deadLetterExchange no puede superar 255 caracteres")
        @Pattern(regexp = NombreAmqp.PATRON, message = "deadLetterExchange no es un nombre de exchange valido")
        String deadLetterExchange,

        @Positive(message = "limiteEntregas tiene que ser mayor que 0")
        @Max(value = 1000, message = "limiteEntregas no puede superar 1000")
        Integer limiteEntregas) {

    public NuevaCola {
        tipo = tipo == null ? TipoCola.CLASSIC : tipo;
        durable = durable == null || durable;
    }

    @AssertTrue(message = "una cola QUORUM es siempre durable: durable no puede ser false")
    public boolean isQuorumDurable() {
        return tipo != TipoCola.QUORUM || durable;
    }

    @AssertTrue(message = "limiteEntregas solo existe en colas QUORUM")
    public boolean isLimiteSoloEnQuorum() {
        return limiteEntregas == null || tipo == TipoCola.QUORUM;
    }
}
