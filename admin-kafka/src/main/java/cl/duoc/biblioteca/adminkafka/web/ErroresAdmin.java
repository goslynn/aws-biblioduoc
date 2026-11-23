package cl.duoc.biblioteca.adminkafka.web;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import cl.duoc.biblioteca.adminkafka.servicio.Conflicto;
import cl.duoc.biblioteca.adminkafka.servicio.KafkaNoDisponible;
import cl.duoc.biblioteca.adminkafka.servicio.NoEncontrado;
import cl.duoc.biblioteca.adminkafka.servicio.PeticionInvalida;
import cl.duoc.biblioteca.comun.web.ManejadorDeErrores;

/**
 * De los errores del servicio a codigos HTTP, con el mismo formato JSON que
 * los 400 de validacion (ManejadorDeErrores, modulo comun).
 *
 *   PeticionInvalida   400  Kafka rechazo los valores (o la clave no esta permitida)
 *   NoEncontrado       404
 *   Conflicto          409
 *   KafkaNoDisponible  503  el cluster no respondio: reintentar puede servir
 */
@RestControllerAdvice
public class ErroresAdmin {

    @ExceptionHandler(PeticionInvalida.class)
    public ResponseEntity<Map<String, Object>> invalida(PeticionInvalida ex) {
        return ManejadorDeErrores.respuesta(HttpStatus.BAD_REQUEST, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(NoEncontrado.class)
    public ResponseEntity<Map<String, Object>> noEncontrado(NoEncontrado ex) {
        return ManejadorDeErrores.respuesta(HttpStatus.NOT_FOUND, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(Conflicto.class)
    public ResponseEntity<Map<String, Object>> conflicto(Conflicto ex) {
        return ManejadorDeErrores.respuesta(HttpStatus.CONFLICT, ex.getMessage(), Map.of());
    }

    @ExceptionHandler(KafkaNoDisponible.class)
    public ResponseEntity<Map<String, Object>> clusterCaido(KafkaNoDisponible ex) {
        return ManejadorDeErrores.respuesta(HttpStatus.SERVICE_UNAVAILABLE,
                "Kafka no responde; reintenta en unos segundos", Map.of());
    }
}
