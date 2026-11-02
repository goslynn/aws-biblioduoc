package cl.duoc.biblioteca.comun.web;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;

/**
 * Convierte los errores de validacion en respuestas JSON utiles, igual en
 * todos los microservicios (vive en el modulo comun).
 *
 * Sin esto, un cuerpo mal formado produce un 400 vacio (o peor, un 500) y
 * quien llama no sabe que campo corregir. Con esto, el 400 dice exactamente
 * cual fallo y por que, que es justo lo que hace falta para diagnosticar desde
 * Bruno o desde la pestana Network del navegador.
 *
 * Los 404, 409 y demas se lanzan como ResponseStatusException desde los
 * controladores y Spring ya los formatea solo: aqui solo se tratan los casos
 * que por defecto quedan mudos.
 */
@RestControllerAdvice
public class ManejadorDeErrores {

    /** Falla alguna anotacion de validacion (@NotEmpty, @Min, @Size...). */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validacion(MethodArgumentNotValidException ex) {
        Map<String, String> campos = new TreeMap<>();
        ex.getBindingResult().getFieldErrors()
                .forEach(error -> campos.put(error.getField(), error.getDefaultMessage()));
        ex.getBindingResult().getGlobalErrors()
                .forEach(error -> campos.put(error.getObjectName(), error.getDefaultMessage()));

        return respuesta(HttpStatus.BAD_REQUEST, "La solicitud no es valida", campos);
    }

    /**
     * Falla una anotacion de validacion puesta directamente en un parametro
     * del metodo (un @PathVariable o @RequestParam), no en un DTO.
     */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<Map<String, Object>> validacionDeParametros(HandlerMethodValidationException ex) {
        Map<String, String> parametros = new TreeMap<>();
        ex.getParameterValidationResults().forEach(resultado -> resultado.getResolvableErrors()
                .forEach(error -> parametros.merge(resultado.getMethodParameter().getParameterName(),
                        error.getDefaultMessage(), (a, b) -> a + "; " + b)));

        return respuesta(HttpStatus.BAD_REQUEST, "La solicitud no es valida", parametros);
    }

    /**
     * El JSON no se puede leer: sintaxis rota, o un valor que no encaja en su
     * tipo (por ejemplo una sede inexistente, o "dias": "muchos").
     */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> cuerpoIlegible(HttpMessageNotReadableException ex) {
        return respuesta(HttpStatus.BAD_REQUEST,
                "El cuerpo de la peticion no se puede interpretar",
                Map.of("detalle", causaLegible(ex)));
    }

    /**
     * El formato de TODOS los errores con cuerpo de los microservicios. Es
     * publico para que los errores propios de cada servicio (por ejemplo, los
     * 404 y 409 del administrador de RabbitMQ) salgan con la misma forma.
     */
    public static ResponseEntity<Map<String, Object>> respuesta(
            HttpStatus estado, String mensaje, Map<String, String> detalles) {
        Map<String, Object> cuerpo = new LinkedHashMap<>();
        cuerpo.put("instante", Instant.now().toString());
        cuerpo.put("status", estado.value());
        cuerpo.put("error", estado.getReasonPhrase());
        cuerpo.put("mensaje", mensaje);
        cuerpo.put("detalles", detalles);
        return ResponseEntity.status(estado).body(cuerpo);
    }

    /** El mensaje crudo de Jackson trae la clase y el offset: sobra ruido. */
    private static String causaLegible(HttpMessageNotReadableException ex) {
        Throwable causa = ex.getMostSpecificCause();
        String texto = causa.getMessage() == null ? ex.getMessage() : causa.getMessage();
        int corte = texto.indexOf(" at [Source");
        return corte > 0 ? texto.substring(0, corte) : texto;
    }
}
