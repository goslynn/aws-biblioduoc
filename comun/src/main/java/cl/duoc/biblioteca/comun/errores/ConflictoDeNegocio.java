package cl.duoc.biblioteca.comun.errores;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * La peticion esta bien formada pero choca con el estado actual: la solicitud
 * ya se resolvio, no quedan ejemplares, el libro tiene stock y no hay nada que
 * esperar. Sale como 409. Ver {@link RecursoNoEncontrado} para el por que de
 * una excepcion propia.
 */
@ResponseStatus(HttpStatus.CONFLICT)
public class ConflictoDeNegocio extends RuntimeException {

    public ConflictoDeNegocio(String mensaje) {
        super(mensaje);
    }
}
