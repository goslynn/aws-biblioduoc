package cl.duoc.biblioteca.adminkafka.dto;

import java.util.Map;
import java.util.Set;

import jakarta.validation.constraints.AssertTrue;

/**
 * Cuerpo de PATCH /api/admin/topicos/{nombre}/configuracion. Cambio
 * INCREMENTAL: lo que no se nombra queda como estaba.
 *
 * @param establecer  clave -> valor nuevo ({"retention.ms": "86400000"})
 * @param restablecer claves que vuelven al valor por defecto del broker
 */
public record CambioConfiguracion(Map<String, String> establecer, Set<String> restablecer) {

    public CambioConfiguracion {
        establecer = establecer == null ? Map.of() : Map.copyOf(establecer);
        restablecer = restablecer == null ? Set.of() : Set.copyOf(restablecer);
    }

    @AssertTrue(message = "hay que establecer o restablecer al menos una clave")
    public boolean isAlgoQueCambiar() {
        return !establecer.isEmpty() || !restablecer.isEmpty();
    }

    @AssertTrue(message = "una misma clave no puede establecerse y restablecerse a la vez")
    public boolean isSinClavesRepetidas() {
        return restablecer.stream().noneMatch(establecer::containsKey);
    }
}
