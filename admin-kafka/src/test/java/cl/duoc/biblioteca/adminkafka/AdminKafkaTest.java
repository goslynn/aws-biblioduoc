package cl.duoc.biblioteca.adminkafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import org.testcontainers.kafka.KafkaContainer;

/**
 * El administrador contra un Kafka REAL (Docker): lo que se crea o cambia por
 * REST se comprueba en el cluster con el Admin de Kafka, no en un mock.
 *
 * Cubre lo que pide la pauta: crear, listar, modificar y eliminar topicos y su
 * configuracion; inspeccionar consumer groups (lag) y reposicionarlos; la
 * validacion (400), las reglas de dominio (404/409) y la seguridad (401/403).
 *
 * Un solo broker: los topicos de prueba van con 1 replica.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(AdminKafkaTest.Broker.class)
class AdminKafkaTest {

    @TestConfiguration(proxyBeanMethods = false)
    static class Broker {

        /** La misma version de Kafka que los brokers cp-kafka 7.9 de aws/backend/kafka.compose.yaml. */
        @Bean
        KafkaContainer kafka() {
            return new KafkaContainer("apache/kafka-native:3.9.1");
        }

        @Bean
        DynamicPropertyRegistrar conexion(KafkaContainer kafka) {
            return registro -> registro.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);
        }
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    Admin kafka;

    @Autowired
    KafkaContainer contenedor;

    @Autowired
    Converter<Jwt, Collection<GrantedAuthority>> autoridades;

    // -------------------------------------------------------------------------
    //  Topicos
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("POST /topicos crea el topico con sus particiones y configuracion; GET lo describe")
    void crearYVerTopico() throws Exception {
        String nombre = unico("pruebas.crear");

        mvc.perform(post("/api/admin/topicos").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nombre": "%s", "particiones": 3, "replicas": 1,
                                 "configuracion": {"retention.ms": "3600000", "cleanup.policy": "delete"}}
                                """.formatted(nombre)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/admin/topicos/" + nombre))
                .andExpect(jsonPath("$.particiones.length()").value(3))
                .andExpect(jsonPath("$.particiones[0].lider").isNumber())
                .andExpect(jsonPath("$.configuracion['retention.ms']").value("3600000"));

        assertThat(kafka.describeTopics(List.of(nombre)).allTopicNames().get().get(nombre).partitions())
                .hasSize(3);

        mvc.perform(get("/v1/api/admin/topicos").with(backoffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].nombre", hasItem(nombre)));
    }

    @Test
    @DisplayName("PATCH /configuracion cambia retention.ms y restablece lo que se pide")
    void cambiarConfiguracion() throws Exception {
        String nombre = crearTopico(unico("pruebas.config"), 1);

        mvc.perform(patch("/api/admin/topicos/{nombre}/configuracion", nombre).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\": {\"retention.ms\": \"86400000\", \"segment.ms\": \"600000\"}}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuracion['retention.ms']").value("86400000"))
                .andExpect(jsonPath("$.configuracion['segment.ms']").value("600000"));

        mvc.perform(patch("/api/admin/topicos/{nombre}/configuracion", nombre).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"restablecer\": [\"segment.ms\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.configuracion['retention.ms']").value("86400000"))
                .andExpect(jsonPath("$.configuracion['segment.ms']").doesNotExist());
    }

    @Test
    @DisplayName("PATCH /configuracion: 400 clave no permitida, 400 valor invalido, 409 cleanup.policy protegido")
    void configuracionInvalida() throws Exception {
        String nombre = crearTopico(unico("pruebas.config-mala"), 1);

        mvc.perform(patch("/api/admin/topicos/{nombre}/configuracion", nombre).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\": {\"message.timestamp.type\": \"LogAppendTime\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.mensaje").value(containsString("no permitida")));

        mvc.perform(patch("/api/admin/topicos/{nombre}/configuracion", nombre).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\": {\"retention.ms\": \"mucho\"}}"))
                .andExpect(status().isBadRequest());

        mvc.perform(patch("/api/admin/topicos/{nombre}/configuracion", nombre).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());

        crearTopico("biblioteca.inventario", 1);
        mvc.perform(patch("/api/admin/topicos/biblioteca.inventario/configuracion").with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\": {\"cleanup.policy\": \"delete\"}}"))
                .andExpect(status().isConflict());
        // retention.ms de un topico protegido si se puede ajustar.
        mvc.perform(patch("/api/admin/topicos/biblioteca.inventario/configuracion").with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"establecer\": {\"retention.ms\": \"604800000\"}}"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("POST /particiones aumenta; 409 si no aumenta o si el topico esta protegido")
    void particiones() throws Exception {
        String nombre = crearTopico(unico("pruebas.particiones"), 1);

        mvc.perform(post("/api/admin/topicos/{nombre}/particiones", nombre).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"total\": 4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.particiones.length()").value(4));

        mvc.perform(post("/api/admin/topicos/{nombre}/particiones", nombre).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"total\": 2}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.mensaje").value(containsString("solo permite aumentarlas")));

        crearTopico("biblioteca.inventario", 1);
        mvc.perform(post("/api/admin/topicos/biblioteca.inventario/particiones").with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"total\": 6}"))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("DELETE /topicos: 204, despues 404; 409 si esta protegido o es interno")
    void eliminarTopico() throws Exception {
        String nombre = crearTopico(unico("pruebas.borrar"), 1);

        mvc.perform(delete("/api/admin/topicos/{nombre}", nombre).with(backoffice()))
                .andExpect(status().isNoContent());
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                mvc.perform(get("/api/admin/topicos/{nombre}", nombre).with(backoffice()))
                        .andExpect(status().isNotFound()));

        crearTopico("biblioteca.inventario", 1);
        mvc.perform(delete("/api/admin/topicos/biblioteca.inventario").with(backoffice()))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("POST /topicos: 400 nombre invalido o demasiadas particiones; 409 si ya existe")
    void validacionDeTopicos() throws Exception {
        mvc.perform(post("/api/admin/topicos").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"__interno\", \"replicas\": 1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/admin/topicos").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"pruebas.muchas\", \"particiones\": 50, \"replicas\": 1}"))
                .andExpect(status().isBadRequest());
        // Un broker: 3 replicas no se pueden colocar. Lo rechaza Kafka, no la validacion.
        mvc.perform(post("/api/admin/topicos").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"%s\", \"replicas\": 3}".formatted(unico("pruebas.replicas"))))
                .andExpect(status().isBadRequest());

        String nombre = crearTopico(unico("pruebas.duplicado"), 1);
        mvc.perform(post("/api/admin/topicos").with(backoffice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nombre\": \"%s\", \"replicas\": 1}".formatted(nombre)))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    //  Consumer groups
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("GET /grupos/{id} muestra el lag; con el grupo vacio, POST /offsets lo rebobina a INICIO")
    void lagYReinicioDeOffsets() throws Exception {
        String topico = crearTopico(unico("pruebas.lag"), 1);
        String grupo = unico("grupo-lag");
        producir(topico, 5);
        consumirYConfirmar(topico, grupo, 2);
        producir(topico, 3);

        mvc.perform(get("/api/admin/grupos/{id}", grupo).with(backoffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("EMPTY"))
                .andExpect(jsonPath("$.offsets[0].confirmado").value(2))
                .andExpect(jsonPath("$.offsets[0].fin").value(8))
                .andExpect(jsonPath("$.lagTotal").value(6));

        mvc.perform(get("/api/admin/grupos").with(backoffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id", hasItem(grupo)));

        mvc.perform(post("/api/admin/grupos/{id}/offsets", grupo).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posicion\": \"INICIO\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.offsets[0].confirmado").value(0))
                .andExpect(jsonPath("$.lagTotal").value(8));

        mvc.perform(post("/api/admin/grupos/{id}/offsets", grupo).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"posicion\": \"FIN\", \"topico\": \"%s\"}".formatted(topico)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lagTotal").value(0));
    }

    @Test
    @DisplayName("POST /offsets: 409 con consumidores activos; 400 FECHA sin fecha; 404 grupo inexistente")
    void reinicioRechazado() throws Exception {
        String topico = crearTopico(unico("pruebas.activo"), 1);
        String grupo = unico("grupo-activo");
        producir(topico, 1);

        try (KafkaConsumer<String, String> activo = consumidor(grupo)) {
            activo.subscribe(List.of(topico));
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                activo.poll(Duration.ofMillis(200));
                return !activo.assignment().isEmpty();
            });
            mvc.perform(post("/api/admin/grupos/{id}/offsets", grupo).with(backoffice())
                            .contentType(MediaType.APPLICATION_JSON).content("{\"posicion\": \"INICIO\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.mensaje").value(containsString("consumidores activos")));
            mvc.perform(get("/api/admin/grupos/{id}", grupo).with(backoffice()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.miembros.length()").value(1));
        }

        mvc.perform(post("/api/admin/grupos/{id}/offsets", grupo).with(backoffice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"posicion\": \"FECHA\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/grupos/{id}", unico("no-existe")).with(backoffice()))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET /cluster lista el broker y el controlador")
    void cluster() throws Exception {
        mvc.perform(get("/api/admin/cluster").with(backoffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.brokers.length()").value(1))
                .andExpect(jsonPath("$.controlador").isNumber());
    }

    // -------------------------------------------------------------------------
    //  Seguridad
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Seguridad: 401 sin token, 403 estudiante, 200 empleado del grupo")
    void seguridad() throws Exception {
        mvc.perform(get("/api/admin/topicos")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/topicos").with(token("alumno", "biblioteca-api/acceso", List.of())))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/topicos").with(token("empleado", "biblioteca-api/acceso",
                        List.of("empleados"))))
                .andExpect(status().isOk());
        mvc.perform(get("/health")).andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    //  Ayudas
    // -------------------------------------------------------------------------

    private static String unico(String prefijo) {
        return prefijo + "-" + UUID.randomUUID().toString().substring(0, 8);
    }

    /** Crea el topico directo en Kafka (si no existe) y devuelve su nombre. */
    private String crearTopico(String nombre, int particiones) throws Exception {
        if (!kafka.listTopics().names().get().contains(nombre)) {
            kafka.createTopics(List.of(new NewTopic(nombre, particiones, (short) 1))).all().get();
        }
        return nombre;
    }

    private void producir(String topico, int cuantos) throws Exception {
        try (KafkaProducer<String, String> productor = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, contenedor.getBootstrapServers()),
                new StringSerializer(), new StringSerializer())) {
            for (int i = 0; i < cuantos; i++) {
                productor.send(new ProducerRecord<>(topico, "clave", "registro " + i)).get();
            }
        }
    }

    /** Lee {@code cuantos} registros, confirma y CIERRA: el grupo queda vacio, con lag. */
    private void consumirYConfirmar(String topico, String grupo, int cuantos) {
        try (KafkaConsumer<String, String> consumidor = consumidor(grupo)) {
            consumidor.subscribe(List.of(topico));
            int[] leidos = { 0 };
            await().atMost(Duration.ofSeconds(20)).until(() -> {
                consumidor.poll(Duration.ofMillis(200)).forEach(r -> leidos[0]++);
                return leidos[0] >= cuantos;
            });
            consumidor.commitSync(Map.of(new org.apache.kafka.common.TopicPartition(topico, 0),
                    new org.apache.kafka.clients.consumer.OffsetAndMetadata(cuantos)));
        }
    }

    private KafkaConsumer<String, String> consumidor(String grupo) {
        return new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, contenedor.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, grupo,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false"),
                new StringDeserializer(), new StringDeserializer());
    }

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
