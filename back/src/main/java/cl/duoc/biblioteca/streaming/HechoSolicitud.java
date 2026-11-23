package cl.duoc.biblioteca.streaming;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import cl.duoc.biblioteca.solicitudes.EstadoSolicitud;
import cl.duoc.biblioteca.solicitudes.Sede;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Algo le paso a una solicitud. Viaja en {@code biblioteca.solicitudes} con el
 * id de la solicitud como clave.
 *
 * Lleva lo justo para que la bandeja del empleado se dibuje SIN volver a
 * consultar la base, y nada personal: ni el sub ni el email del estudiante
 * (el topico se retiene 30 dias y lo lee cualquier grupo).
 *
 * @param idEvento    unico por hecho; tambien el id de la fila de la bandeja de salida
 * @param idSolicitud la clave de particion: los hechos de una solicitud se
 *                    leen en orden (creada antes que aprobada)
 * @param estado      en que estado QUEDO la solicitud: RECIBIDA es "llego una
 *                    nueva"; cualquier otro, "ya se resolvio"
 * @param sede        para el contador de pendientes por sede
 * @param ejemplares  cuantos libros en total, para priorizar en la bandeja
 * @param titulos     que pidio, para mostrarlo sin consultar el catalogo
 * @param ocurridoEn  cuando, en UTC
 */
public record HechoSolicitud(
        @NotNull UUID idEvento,
        @NotBlank String idSolicitud,
        @NotNull EstadoSolicitud estado,
        @NotNull Sede sede,
        @Positive int ejemplares,
        @NotEmpty List<@NotBlank String> titulos,
        @NotNull Instant ocurridoEn) {

    public HechoSolicitud {
        titulos = titulos == null ? null : List.copyOf(titulos);
    }
}
