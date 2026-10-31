package cl.duoc.biblioteca.solicitudes;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import cl.duoc.biblioteca.ServiciosDePrueba;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

/** Las reglas de negocio del formulario, que son las que producen 400, 404 y 409. */
@SpringBootTest
@Import(ServiciosDePrueba.class)
@AutoConfigureMockMvc
class SolicitudControllerTest {

    @Autowired
    MockMvc mvc;

    /** Token con el scope exigido, y con un "sub" conocido: ese sub acaba
     *  siendo el campo "solicitante" de la solicitud creada. */
    private static JwtRequestPostProcessor comoAlumno() {
        return jwt()
                .jwt(builder -> builder.claim("sub", "alumno-de-prueba"))
                .authorities(new SimpleGrantedAuthority("SCOPE_biblioteca-api/acceso"));
    }

    private static final String VALIDA = """
            {
              "items": [ { "libroId": "L-001", "cantidad": 1 },
                         { "libroId": "L-013", "cantidad": 2 } ],
              "dias": 14,
              "motivo": "Material de apoyo para el proyecto de titulo",
              "sede": "SAN_JOAQUIN"
            }
            """;

    @Test
    @DisplayName("una solicitud valida responde 201, resuelve los titulos y calcula la devolucion")
    void creaSolicitud() throws Exception {
        mvc.perform(post("/api/solicitudes").contentType(MediaType.APPLICATION_JSON).content(VALIDA).with(comoAlumno()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.estado").value("RECIBIDA"))
                .andExpect(jsonPath("$.lineas.length()").value(2))
                .andExpect(jsonPath("$.lineas[0].titulo").value("Clean Code"))
                .andExpect(jsonPath("$.fechaDevolucion").exists());
    }

    @Test
    @DisplayName("lo creado se puede recuperar despues: queda guardado en PostgreSQL")
    void listaYRecupera() throws Exception {
        String cuerpo = mvc.perform(post("/api/solicitudes")
                        .contentType(MediaType.APPLICATION_JSON).content(VALIDA).with(comoAlumno()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String id = cuerpo.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");

        mvc.perform(get("/api/solicitudes/" + id).with(comoAlumno()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));

        mvc.perform(get("/api/solicitudes").with(comoAlumno())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("el motivo es opcional: sin el responde 201 con motivo vacio")
    void sinMotivo() throws Exception {
        String sinMotivo = VALIDA.replace("\"motivo\": \"Material de apoyo para el proyecto de titulo\",", "");

        mvc.perform(post("/api/solicitudes").contentType(MediaType.APPLICATION_JSON).content(sinMotivo).with(comoAlumno()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.motivo").value(""));
    }

    @Test
    @DisplayName("un motivo de mas de 300 caracteres responde 400 diciendo que campo fallo")
    void motivoLargo() throws Exception {
        String invalida = VALIDA.replace("Material de apoyo para el proyecto de titulo", "x".repeat(301));

        mvc.perform(post("/api/solicitudes").contentType(MediaType.APPLICATION_JSON).content(invalida).with(comoAlumno()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.motivo").exists());
    }

    @Test
    @DisplayName("mas de 30 dias responde 400")
    void demasiadosDias() throws Exception {
        mvc.perform(post("/api/solicitudes").contentType(MediaType.APPLICATION_JSON)
                        .content(VALIDA.replace("\"dias\": 14", "\"dias\": 90")).with(comoAlumno()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.dias").exists());
    }

    @Test
    @DisplayName("una sede inexistente responde 400 y no un 500")
    void sedeInexistente() throws Exception {
        mvc.perform(post("/api/solicitudes").contentType(MediaType.APPLICATION_JSON)
                        .content(VALIDA.replace("SAN_JOAQUIN", "LA_LUNA")).with(comoAlumno()))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("pedir un libro que no existe responde 404")
    void libroInexistente() throws Exception {
        mvc.perform(post("/api/solicitudes").contentType(MediaType.APPLICATION_JSON)
                        .content(VALIDA.replace("L-001", "L-999")).with(comoAlumno()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("pedir un libro sin ejemplares responde 409")
    void sinEjemplares() throws Exception {
        // L-005 (Building Microservices) tiene ejemplares: 0 en el seed (V2__catalogo_inicial.sql)
        mvc.perform(post("/api/solicitudes").contentType(MediaType.APPLICATION_JSON)
                        .content(VALIDA.replace("L-001", "L-005")).with(comoAlumno()))
                .andExpect(status().isConflict());
    }
}
