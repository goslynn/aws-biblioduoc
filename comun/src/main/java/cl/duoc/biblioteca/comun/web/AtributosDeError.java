package cl.duoc.biblioteca.comun.web;

import org.springframework.boot.webmvc.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.WebRequest;

import jakarta.servlet.RequestDispatcher;

/**
 * El cuerpo de los errores que pasan por /error, sin filtrar detalles internos.
 *
 * EL PROBLEMA
 *   server.error.include-message=always es lo que hace que un 404 o un 409
 *   expliquen que paso ("No existe el libro L-999"), y el frontend y Bruno lo
 *   leen. Pero la propiedad vale para TODOS los errores: una excepcion no
 *   controlada (un fallo de SQL, un SDK de AWS sin permisos) devolvia su
 *   mensaje tal cual en el 500, con el texto de la consulta, nombres de
 *   tablas o el ARN del rol del laboratorio.
 *
 * LA REGLA
 *   Los 4xx los provoca quien llama y su mensaje lo escribimos nosotros: se
 *   mantiene. En un 5xx el mensaje se reemplaza por uno generico; el detalle
 *   queda en el log del servidor (lo registra el contenedor de servlets al
 *   propagarse la excepcion).
 *
 * Sustituye al DefaultErrorAttributes de Spring Boot (que se retira solo, por
 * @ConditionalOnMissingBean) en los dos microservicios: vive en comun.
 */
@Component
public class AtributosDeError extends DefaultErrorAttributes {

    static final String MENSAJE_INTERNO = "Error interno del servidor; el detalle quedo en el log";

    /**
     * El gancho que Spring Boot deja para el texto del campo "message". Se
     * mira el codigo de estado de la peticion de error, no el mapa de
     * atributos: "status" puede no estar en el mapa segun las opciones.
     */
    @Override
    protected String getMessage(WebRequest peticion, Throwable error) {
        Object estado = peticion.getAttribute(RequestDispatcher.ERROR_STATUS_CODE, RequestAttributes.SCOPE_REQUEST);
        if (estado instanceof Integer codigo && codigo >= 500) {
            return MENSAJE_INTERNO;
        }
        return super.getMessage(peticion, error);
    }
}
