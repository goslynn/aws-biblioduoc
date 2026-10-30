package cl.duoc.biblioteca.catalogo;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import cl.duoc.biblioteca.ServiciosDePrueba;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/**
 * Catalogo: 200 con datos, 404 propio de Spring, y las dos caras de la
 * seguridad (401 sin token, 403 con token pero sin el scope).
 *
 * La autenticacion se SIMULA con el post-processor jwt(): se inyecta un token
 * ya validado con las authorities que se quieran, sin red y sin Cognito. Lo
 * que se prueba aqui son las REGLAS de SecurityConfig, no la criptografia.
 */
@SpringBootTest
@Import(ServiciosDePrueba.class)
@AutoConfigureMockMvc
class LibroControllerTest {

    @Autowired
    MockMvc mvc;

    /** Token con el scope que exige la API: el caso feliz. */
    private static JwtRequestPostProcessor conScope() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_biblioteca-api/acceso"));
    }

    /** Token bien firmado pero SIN el scope: autenticado y no autorizado. */
    private static JwtRequestPostProcessor sinScope() {
        return jwt().authorities(new SimpleGrantedAuthority("SCOPE_aws.cognito.signin.user.admin"));
    }

    @Test
    @DisplayName("el catalogo completo responde 200 con los 20 libros")
    void listaCompleta() throws Exception {
        mvc.perform(get("/api/libros").with(conScope()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(20))
                .andExpect(jsonPath("$[0].titulo").exists())
                .andExpect(jsonPath("$[0].disponible").exists());
    }

    @Test
    @DisplayName("la busqueda por texto ignora mayusculas y tildes")
    void busquedaSinTildes() throws Exception {
        mvc.perform(get("/api/libros").param("q", "GARCÍA").with(conScope()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value("L-017"));
    }

    @Test
    @DisplayName("los filtros de genero y disponibilidad se combinan")
    void filtroCombinado() throws Exception {
        mvc.perform(get("/api/libros")
                        .param("genero", "cloud")
                        .param("soloDisponibles", "true")
                        .with(conScope()))
                .andExpect(status().isOk())
                // De los tres libros "Cloud", Terraform tiene 0 ejemplares.
                .andExpect(jsonPath("$.length()").value(2));
    }

    /**
     * OJO CON EL ALCANCE DE ESTE TEST.
     *
     * MockMvc no reproduce el reenvio interno a /error que hace un contenedor
     * de verdad cuando un controlador lanza una excepcion. Por eso este test
     * pasaba en verde mientras la aplicacion desplegada devolvia 403 en vez de
     * 404: el reenvio volvia a pasar por las reglas de seguridad y caia en el
     * denyAll(). Se arreglo permitiendo el dispatcher ERROR en SecurityConfig.
     *
     * Moraleja: este test comprueba que el CONTROLADOR responde 404; que ese
     * 404 llegue al cliente solo lo demuestra una peticion real contra la
     * aplicacion desplegada.
     */
    @Test
    @DisplayName("un id inexistente responde 404, y lo genera Spring, no la infraestructura")
    void idInexistente() throws Exception {
        mvc.perform(get("/api/libros/L-999").with(conScope())).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("la ruta versionada /v1 sirve exactamente lo mismo")
    void rutaVersionada() throws Exception {
        mvc.perform(get("/v1/api/libros/L-001").with(conScope()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.titulo").value("Clean Code"));
    }

    @Test
    @DisplayName("sin token responde 401: no hay identidad")
    void sinToken() throws Exception {
        mvc.perform(get("/api/libros")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("con token valido pero SIN el scope responde 403: autenticado, no autorizado")
    void tokenSinScope() throws Exception {
        mvc.perform(get("/api/libros").with(sinScope())).andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("/health sigue siendo publico: es la sonda del Lambda Web Adapter")
    void healthPublico() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
    }
}
