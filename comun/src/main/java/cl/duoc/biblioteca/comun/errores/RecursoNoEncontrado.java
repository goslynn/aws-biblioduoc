package cl.duoc.biblioteca.comun.errores;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Lo que se pide no existe (un libro, una solicitud). Sale como 404.
 *
 * POR QUE UNA EXCEPCION PROPIA Y NO ResponseStatusException
 *   Los servicios de dominio no deberian saber de HTTP. Esta clase solo dice
 *   QUE paso; el {@code @ResponseStatus} es una anotacion, no una dependencia
 *   del codigo, y Spring la traduce al codigo HTTP al final del camino. El
 *   cuerpo de la respuesta es el de siempre ({timestamp, status, error,
 *   message, path}): el frontend y Bruno leen "message" de ahi.
 */
@ResponseStatus(HttpStatus.NOT_FOUND)
public class RecursoNoEncontrado extends RuntimeException {

    public RecursoNoEncontrado(String mensaje) {
        super(mensaje);
    }
}
