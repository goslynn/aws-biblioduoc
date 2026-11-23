package cl.duoc.biblioteca.adminkafka.dto;

import java.time.Instant;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Cuerpo de POST /api/admin/grupos/{id}/offsets: a donde mover el grupo para
 * que vuelva a leer (o se salte) registros.
 *
 * @param posicion INICIO (reprocesar todo lo retenido), FIN (saltarse lo
 *                 pendiente) o FECHA (el primer registro desde ese instante)
 * @param fecha    obligatoria con FECHA; ISO-8601 ("2026-11-23T12:00:00Z")
 * @param topico   solo ese topico; si falta, todos los que el grupo tiene
 *                 offsets confirmados
 */
public record ReinicioOffsets(
        @NotNull(message = "posicion es obligatoria: INICIO, FIN o FECHA")
        Posicion posicion,
        Instant fecha,
        @Size(max = 249, message = "topico no puede superar 249 caracteres")
        @Pattern(regexp = NombreTopico.PATRON, message = "topico no es un nombre de topico valido")
        String topico) {

    public enum Posicion {
        INICIO, FIN, FECHA
    }

    @AssertTrue(message = "con posicion FECHA hay que indicar la fecha")
    public boolean isFechaSiCorresponde() {
        return posicion != Posicion.FECHA || fecha != null;
    }
}
