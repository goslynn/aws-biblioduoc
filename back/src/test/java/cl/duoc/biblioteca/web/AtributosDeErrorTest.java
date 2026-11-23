package cl.duoc.biblioteca.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.ServletWebRequest;

import cl.duoc.biblioteca.comun.web.AtributosDeError;
import jakarta.servlet.RequestDispatcher;

/**
 * El cuerpo de /error. MockMvc no reenvia a /error (ver LibroControllerTest),
 * asi que se prueba el componente directamente con la peticion tal como la
 * deja el contenedor de servlets despues de un fallo.
 */
class AtributosDeErrorTest {

    private final AtributosDeError atributos = new AtributosDeError();

    private Map<String, Object> cuerpo(int estado, Exception causa) {
        MockHttpServletRequest peticion = new MockHttpServletRequest();
        peticion.setAttribute(RequestDispatcher.ERROR_STATUS_CODE, estado);
        peticion.setAttribute(RequestDispatcher.ERROR_EXCEPTION, causa);
        return atributos.getErrorAttributes(new ServletWebRequest(peticion),
                ErrorAttributeOptions.of(ErrorAttributeOptions.Include.MESSAGE));
    }

    @Test
    @DisplayName("un 500 NO devuelve el mensaje de la excepcion (SQL, ARN...)")
    void quinientosSinDetalle() {
        Map<String, Object> cuerpo = cuerpo(500, new IllegalStateException(
                "PreparedStatementCallback; bad SQL grammar [SELECT * FROM solicitud]"));
        assertThat(cuerpo.get("message")).asString().doesNotContain("SQL").contains("Error interno");
    }

    @Test
    @DisplayName("un 404 conserva su mensaje: lo leen el frontend y Bruno")
    void cuatrocientosConDetalle() {
        Map<String, Object> cuerpo = cuerpo(404, new IllegalArgumentException("No existe el libro L-999"));
        assertThat(cuerpo.get("message")).isEqualTo("No existe el libro L-999");
    }
}
