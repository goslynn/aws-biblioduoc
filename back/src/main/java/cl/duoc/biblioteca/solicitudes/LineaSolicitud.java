package cl.duoc.biblioteca.solicitudes;

import org.springframework.data.relational.core.mapping.Table;

/**
 * Una linea YA RESUELTA de la solicitud: al crearla, el servidor busca cada
 * libroId en el catalogo y copia el titulo y el autor.
 *
 * Se guarda una copia en vez de una referencia para que la solicitud siga
 * siendo legible aunque el catalogo cambie en un despliegue posterior: la
 * solicitud es un documento historico de lo que se pidio ese dia.
 *
 * No tiene id propio: es parte del agregado {@link Solicitud}, y Spring Data
 * la identifica por (solicitud_id, orden).
 */
@Table("linea_solicitud")
public record LineaSolicitud(
        String libroId,
        String titulo,
        String autor,
        int cantidad) {
}
