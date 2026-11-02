package cl.duoc.biblioteca.admin;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.rabbitmq.RabbitMQContainer;

/**
 * El administrador contra un RabbitMQ REAL (Docker): lo que se crea por REST
 * se comprueba en el broker, no en un mock.
 *
 * Cubre los tres indicadores de la pauta: rutas REST (201/200/204), la logica
 * en RabbitAdminService (404/409 de dominio) y la validacion de entrada (400
 * con el campo que fallo). Y la seguridad: 401 sin token, 403 estudiante.
 *
 * Los tokens se simulan con jwt() y el MISMO convertidor de autoridades de la
 * aplicacion (ValidacionJwt, modulo comun): se prueba la regla real de quien
 * es empleado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminRabbitTest.Broker.class)
class AdminRabbitTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Broker {

        /** La misma imagen que el cluster de aws/backend/compose.yaml. */
        @Bean
        RabbitMQContainer rabbit() {
            return new RabbitMQContainer("rabbitmq:4.1-management-alpine");
        }

        @Bean
        DynamicPropertyRegistrar conexion(RabbitMQContainer rabbit) {
            return registro -> {
                registro.add("spring.rabbitmq.host", rabbit::getHost);
                registro.add("spring.rabbitmq.port", rabbit::getAmqpPort);
                registro.add("spring.rabbitmq.username", rabbit::getAdminUsername);
                registro.add("spring.rabbitmq.password", rabbit::getAdminPassword);
            };
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    RabbitAdmin rabbit;

    @Autowired
    Converter<Jwt, Collection<GrantedAuthority>> autoridades;

    // -------------------------------------------------------------------------
    //  Colas
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /colas crea una cola quorum en el broker con sus argumentos; GET la describe")
    void crearYVerCola() throws Exception {
        String nombre = unico("pruebas.quorum");
        crearExchange("pruebas.dlx-" + nombre, "DIRECT");

        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "%s", "tipo": "QUORUM", "ttlMs": 60000,
                                 "deadLetterExchange": "pruebas.dlx-%s", "limiteEntregas": 3}
                                """.formatted(nombre, nombre)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/colas/" + nombre))
                .andExpect(jsonPath("$.nombre").value(nombre))
                .andExpect(jsonPath("$.mensajes").value(0));

        assertThat(rabbit.getQueueProperties(nombre)).isNotNull();

        mvc.perform(get("/v1/api/admin/colas/{nombre}", nombre).with(backoffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumidores").value(0));
    }

    @Test
    @DisplayName("DELETE /colas/{nombre}: 409 si tiene mensajes; con forzar=true la borra (204)")
    void eliminarCola() throws Exception {
        String nombre = unico("pruebas.borrar");
        crearCola(nombre);
        rabbit.getRabbitTemplate().send("", nombre, MessageBuilder.withBody("hola".getBytes()).build());

        mvc.perform(delete("/api/admin/colas/{nombre}", nombre).with(backoffice()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("forzar=true")));

        mvc.perform(delete("/api/admin/colas/{nombre}", nombre).param("forzar", "true").with(backoffice()))
                .andExpect(status().isNoContent());
        assertThat(rabbit.getQueueProperties(nombre)).isNull();

        mvc.perform(delete("/api/admin/colas/{nombre}", nombre).with(backoffice()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("crear una cola que ya existe es 409, no una redeclaracion silenciosa")
    void colaRepetida() throws Exception {
        String nombre = unico("pruebas.repetida");
        crearCola(nombre);

        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"" + nombre + "\", \"tipo\": \"QUORUM\"}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("una cola de la topologia de la biblioteca no se puede borrar (409)")
    void colaProtegida() throws Exception {
        mvc.perform(delete("/api/admin/colas/notif.prestamos").with(backoffice()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(org.hamcrest.Matchers.containsString("topologia")));
    }

    @Test
    @DisplayName("un DLX inexistente es 404: RabbitMQ lo aceptaria y perderia los mensajes en silencio")
    void dlxInexistente() throws Exception {
        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"" + unico("pruebas.sin-dlx") + "\", \"deadLetterExchange\": \"no-existe\"}"))
                .andExpect(status().isNotFound());
    }

    // -------------------------------------------------------------------------
    //  Validacion (400 con el campo que fallo)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("400: nombre vacio, nombre reservado amq.*, TTL negativo y tipo desconocido")
    void validacionDeColas() throws Exception {
        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.nombre").exists());

        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"amq.mia\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.nombre").value(org.hamcrest.Matchers.containsString("amq.")));

        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"pruebas.ttl\", \"ttlMs\": -5}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.ttlMs").exists());

        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"pruebas.tipo\", \"tipo\": \"STREAMING\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("400: reglas cruzadas (limiteEntregas solo en QUORUM; QUORUM siempre durable)")
    void validacionCruzada() throws Exception {
        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"pruebas.limite\", \"limiteEntregas\": 3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.limiteSoloEnQuorum").exists());

        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"pruebas.volatil\", \"tipo\": \"QUORUM\", \"durable\": false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.quorumDurable").exists());
    }

    @Test
    @DisplayName("400 tambien en la ruta: DELETE /colas/{nombre} con un nombre invalido")
    void validacionDeRuta() throws Exception {
        mvc.perform(delete("/api/admin/colas/{nombre}", "Mayusculas!").with(backoffice()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.nombre").exists());
    }

    // -------------------------------------------------------------------------
    //  Exchanges y bindings
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("exchange topic + binding: lo publicado con la routing key llega a la cola")
    void exchangeYBinding() throws Exception {
        String exchange = unico("pruebas.topic");
        String cola = unico("pruebas.destino");
        crearExchange(exchange, "TOPIC");
        crearCola(cola);

        mvc.perform(post("/api/admin/bindings").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\": \"%s\", \"cola\": \"%s\", \"routingKey\": \"libro.*\"}"
                                .formatted(exchange, cola)))
                .andExpect(status().isCreated());

        rabbit.getRabbitTemplate().send(exchange, "libro.prestado", MessageBuilder.withBody("x".getBytes()).build());
        assertThat(rabbit.getRabbitTemplate().receive(cola, 5000)).isNotNull();

        mvc.perform(delete("/api/admin/bindings").with(backoffice())
                        .param("exchange", exchange).param("cola", cola).param("routingKey", "libro.*"))
                .andExpect(status().isNoContent());

        rabbit.getRabbitTemplate().send(exchange, "libro.prestado", MessageBuilder.withBody("y".getBytes()).build());
        assertThat(rabbit.getRabbitTemplate().receive(cola, 500)).isNull();

        mvc.perform(delete("/api/admin/exchanges/{nombre}", exchange).with(backoffice()))
                .andExpect(status().isNoContent());
        mvc.perform(delete("/api/admin/exchanges/{nombre}", exchange).with(backoffice()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("exchange: 409 si ya existe o es protegido; 400 sin tipo")
    void reglasDeExchanges() throws Exception {
        String exchange = unico("pruebas.direct");
        crearExchange(exchange, "DIRECT");

        mvc.perform(post("/api/admin/exchanges").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"" + exchange + "\", \"tipo\": \"FANOUT\"}"))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/admin/exchanges/biblioteca.eventos").with(backoffice()))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/admin/exchanges").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"pruebas.sin-tipo\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.tipo").exists());
    }

    @Test
    @DisplayName("binding: 404 si falta el exchange; 409 si la cola es protegida; 400 sin routingKey")
    void reglasDeBindings() throws Exception {
        String cola = unico("pruebas.huerfana");
        crearCola(cola);

        mvc.perform(post("/api/admin/bindings").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\": \"no-existe\", \"cola\": \"" + cola + "\", \"routingKey\": \"\"}"))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/admin/bindings").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"exchange\": \"x\", \"cola\": \"notif.prestamos\", \"routingKey\": \"#\"}"))
                .andExpect(status().isConflict());

        mvc.perform(delete("/api/admin/bindings").with(backoffice()).param("exchange", "x").param("cola", cola))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.routingKey").exists());
    }

    // -------------------------------------------------------------------------
    //  Seguridad
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("401 sin token; 403 estudiante; 201 empleado del grupo (no solo el backoffice)")
    void seguridad() throws Exception {
        String cuerpo = "{\"nombre\": \"" + unico("pruebas.seguridad") + "\"}";

        mvc.perform(post("/api/admin/colas").contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isUnauthorized());

        mvc.perform(post("/api/admin/colas").with(token("alumno", "biblioteca-api/acceso", List.of()))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/colas").with(token("empleada", "biblioteca-api/acceso", List.of("empleados")))
                        .contentType(MediaType.APPLICATION_JSON).content(cuerpo))
                .andExpect(status().isCreated());
    }

    @Test
    @DisplayName("/health responde sin token; cualquier otra ruta, denegada")
    void rutasFueraDeLaApi() throws Exception {
        mvc.perform(get("/health")).andExpect(status().isOk());
        mvc.perform(get("/api/libros").with(backoffice())).andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    //  Ayudas
    // -------------------------------------------------------------------------

    private static String unico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private void crearCola(String nombre) throws Exception {
        mvc.perform(post("/api/admin/colas").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"" + nombre + "\"}"))
                .andExpect(status().isCreated());
    }

    private void crearExchange(String nombre, String tipo) throws Exception {
        mvc.perform(post("/api/admin/exchanges").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"" + nombre + "\", \"tipo\": \"" + tipo + "\"}"))
                .andExpect(status().isCreated());
    }

    /** El cliente M2M backoffice: client_credentials con el scope de gestion. */
    private JwtRequestPostProcessor backoffice() {
        return token("m2m-backoffice", "biblioteca-api/gestion", null);
    }

    private JwtRequestPostProcessor token(String sub, String scope, List<String> grupos) {
        return jwt().jwt(b -> {
            b.subject(sub).claim("scope", scope).claim("token_use", "access");
            if (grupos != null) {
                b.claim("cognito:groups", grupos);
            }
        }).authorities(autoridades);
    }
}
