package cl.duoc.biblioteca.admin.web;

import java.util.Map;

import org.springframework.amqp.AmqpConnectException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cl.duoc.biblioteca.admin.servicio.Conflicto;
import cl.duoc.biblioteca.admin.servicio.NoEncontrado;
import cl.duoc.biblioteca.comun.web.ManejadorDeErrores;

/**
 * De los errores del servicio a codigos HTTP, con el mismo formato JSON que
 * los 400 de validacion (ManejadorDeErrores, modulo comun).
 *
 *   NoEncontrado           404
 *   Conflicto              409
 *   AmqpConnectException   503  el broker no responde: no es culpa de quien
 *                              llama, y reintentar mas tarde puede servir
 */
@RestControllerAdvice
public class ErroresAdmin {

    @ExceptionHandler(NoEncontrado.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(NoEncontrado ex) {
        return ManejadorDeErrores.respuesta(HttpStatus.NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(Conflicto.class)
    public ResponseEntity<Map<String, Object>> conflicto(Conflicto ex) {
        return ManejadorDeErrores.respuesta(HttpStatus.CONFLICT, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(AmqpConnectException.class)
    public ResponseEntity<Map<String, Object>> brokerCaido(AmqpConnectException ex) {
        return ManejadorDeErrores.respuesta(HttpStatus.SERVICE_UNAVAILABLE,
                "RabbitMQ no responde; reintenta en unos segundos", Map.of());
    }
}
