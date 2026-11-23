package cl.duoc.biblioteca.bitacora;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Una fila de la tabla bitacora (V3): un registro por evento publicado, de
 * solo INSERT. Lo que ve el empleado es la proyeccion {@link Bitacora.Entrada}.
 */
@Table("bitacora")
public record RegistroBitacora(
        @Id Long id,
        UUID eventoId,
        String tipo,
        String actor,
        String referencia,
        String detalle,
        Instant ocurridoEn) {
}
