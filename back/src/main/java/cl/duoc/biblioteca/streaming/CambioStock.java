package cl.duoc.biblioteca.streaming;

import java.time.Instant;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * El stock de un libro acaba de cambiar. Viaja en {@code biblioteca.inventario}
 * con el id del libro como clave.
 *
 * Por que cada campo:
 *
 * @param idEvento    unico por cambio: es tambien el id de la fila de la
 *                    bandeja de salida, para seguir un registro de punta a punta
 * @param idLibro     la clave de particion, y lo que actualiza el catalogo
 * @param disponibles el stock RESULTANTE, no la diferencia. Es lo que hace
 *                    compactable el topico: el ultimo registro de cada libro
 *                    basta para saber su estado, y aplicar dos veces el mismo
 *                    registro da el mismo resultado (idempotente)
 * @param delta       cuanto cambio (+n carga, -n aprobacion, +n devolucion):
 *                    para la auditoria; el estado no depende de el
 * @param motivo      que lo provoco
 * @param ocurridoEn  cuando, en UTC: si llegara un registro mas viejo que el
 *                    que ya se aplico, la proyeccion lo ignora
 */
public record CambioStock(
        @NotNull UUID idEvento,
        @NotBlank String idLibro,
        @PositiveOrZero int disponibles,
        int delta,
        @NotNull MotivoStock motivo,
        @NotNull Instant ocurridoEn) {

    /** Que provoco un cambio de stock. */
    public enum MotivoStock {
        /** Un empleado (o el backoffice) cargo ejemplares. */
        CARGA,
        /** Se aprobo un prestamo: los ejemplares salieron. */
        APROBACION,
        /** Se devolvio un prestamo: los ejemplares volvieron. */
        DEVOLUCION
    }
}
