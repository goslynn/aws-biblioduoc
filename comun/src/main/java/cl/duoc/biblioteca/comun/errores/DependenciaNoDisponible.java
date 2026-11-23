package cl.duoc.biblioteca.comun.errores;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

/**
 * Un servicio externo (Cognito, por ejemplo) no respondio o respondio con
 * error. Sale como 503: no es culpa de quien llama y reintentar puede servir.
 *
 * El mensaje es para el CLIENTE, asi que no lleva el detalle del proveedor:
 * un error de AWS incluye el ARN del rol y el id de la cuenta. Ese detalle se
 * registra en el log de quien lanza la excepcion, no aqui.
 */
@ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
public class DependenciaNoDisponible extends RuntimeException {

    public DependenciaNoDisponible(String mensaje) {
        super(mensaje);
    }
}
