package cl.duoc.biblioteca.adminkafka.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * Cuerpo de POST /api/admin/topicos/{nombre}/particiones.
 *
 * Kafka solo AUMENTA particiones, nunca las reduce: total tiene que ser mayor
 * que el actual (si no, 409).
 *
 * @param total cuantas particiones debe tener el topico despues
 */
public record AumentoParticiones(
        @NotNull(message = "total es obligatorio")
        @Min(value = 2, message = "total tiene que ser al menos 2")
        @Max(value = 12, message = "total no puede superar 12")
        Integer total) {
}
