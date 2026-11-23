package cl.duoc.biblioteca.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

import cl.duoc.biblioteca.ServiciosDePrueba;
import cl.duoc.biblioteca.ServiciosDePrueba.BaseMaestra;
import cl.duoc.biblioteca.Tokens;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * H8 y H9 de punta a punta: peticion HTTP -> bandeja de salida de Kafka ->
 * RelayKafka -> broker -> @KafkaListener -> proyeccion -> GET de la vista en
 * vivo. Y el camino triste: un registro ilegible o invalido termina en su DLT
 * sin detener al consumidor.
 *
 * Usa libros que no tocan los otros tests (L-006, L-009, L-020) y compara con
 * la base, no con numeros fijos: el contexto (y la base) es compartido.
 */
@SpringBootTest
@Import(ServiciosDePrueba.class)
@AutoConfigureMockMvc
class StreamingIntegracionTest {

    private static final Duration ESPERA = Duration.ofSeconds(30);

    @Autowired
    MockMvc mvc;

    @Autowired
    BaseMaestra maestra;

    @Autowired
    KafkaContainer kafka;

    @Autowired
    AseguradorTopicos topicos;

    @Autowired
    JsonMapper json;

    @Autowired
    Converter<Jwt, Collection<GrantedAuthority>> autoridades;

    Tokens tokens;

    @BeforeEach
    void preparar() {
        tokens = new Tokens(autoridades);
        await().atMost(ESPERA).until(topicos::listos);
    }

    // -------------------------------------------------------------------------
    //  H8: catalogo en vivo
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("H8: aprobar y cargar stock se ven en /api/libros/stock-vivo sin consultar la base")
    void stockEnVivo() throws Exception {
        String id = pedir(UUID.randomUUID().toString(), "L-006", "SAN_JOAQUIN");
        mvc.perform(post("/api/gestion/solicitudes/" + id + "/aprobacion").with(tokens.backoffice()))
                .andExpect(status().isOk());
        await().atMost(ESPERA).untilAsserted(() ->
                assertThat(stockVivo("L-006")).isEqualTo(ejemplaresEnBase("L-006")));

        mvc.perform(post("/api/gestion/libros/L-006/stock").with(tokens.backoffice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\": 3}"))
                .andExpect(status().isOk());
        int esperado = ejemplaresEnBase("L-006");
        await().atMost(ESPERA).untilAsserted(() -> assertThat(stockVivo("L-006")).isEqualTo(esperado));
    }

    @Test
    @DisplayName("H8: el stock en vivo lo puede leer un estudiante")
    void stockVivoEstudiante() throws Exception {
        mvc.perform(get("/api/libros/stock-vivo").with(tokens.alumno(UUID.randomUUID().toString())))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    //  H9: bandeja de gestion en vivo
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("H9: una solicitud nueva aparece en la bandeja en vivo y sale al resolverse")
    void bandejaEnVivo() throws Exception {
        String id = pedir(UUID.randomUUID().toString(), "L-020", "PLAZA_OESTE");
        await().atMost(ESPERA).untilAsserted(() -> {
            JsonNode bandeja = bandejaVivo();
            assertThat(idsPendientes(bandeja)).contains(id);
            assertThat(bandeja.get("porSede").get("PLAZA_OESTE").asLong()).isPositive();
        });

        mvc.perform(post("/api/gestion/solicitudes/" + id + "/rechazo").with(tokens.empleado("empleado-1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"prueba\"}"))
                .andExpect(status().isOk());
        await().atMost(ESPERA).untilAsserted(() -> assertThat(idsPendientes(bandejaVivo())).doesNotContain(id));
    }

    @Test
    @DisplayName("H9: la bandeja en vivo es solo de empleados: estudiante 403")
    void bandejaSoloEmpleados() throws Exception {
        mvc.perform(get("/api/gestion/bandeja-vivo").with(tokens.alumno(UUID.randomUUID().toString())))
                .andExpect(status().isForbidden());
    }

    // -------------------------------------------------------------------------
    //  Manejo de errores: DLT
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Un registro que no es JSON va a biblioteca.inventario.dlt y el consumidor sigue")
    void registroIlegibleALaDlt() throws Exception {
        String clave = "veneno-" + UUID.randomUUID();
        enviar(TopologiaKafka.INVENTARIO, clave, "esto no es json", null);

        assertThat(esperarEnDlt(TopologiaKafka.INVENTARIO + TopologiaKafka.SUFIJO_DLT, clave)).isNotEmpty();
        // El consumidor no se quedo pegado en el registro envenenado.
        stockEnVivo();
    }

    @Test
    @DisplayName("Un CambioStock que no pasa @Valid (stock negativo) va a la DLT sin reintentos")
    void dtoInvalidoALaDlt() throws Exception {
        String clave = "L-INVALIDO-" + UUID.randomUUID();
        String cuerpo = """
                {"idEvento": "%s", "idLibro": "%s", "disponibles": -4, "delta": -1,
                 "motivo": "APROBACION", "ocurridoEn": "2026-11-23T12:00:00Z"}
                """.formatted(UUID.randomUUID(), clave);
        enviar(TopologiaKafka.INVENTARIO, clave, cuerpo, "cambioStock");

        List<ConsumerRecord<String, byte[]>> muertos =
                esperarEnDlt(TopologiaKafka.INVENTARIO + TopologiaKafka.SUFIJO_DLT, clave);
        assertThat(muertos).anySatisfy(r -> assertThat(new String(
                r.headers().lastHeader("kafka_dlt-original-consumer-group").value(), StandardCharsets.UTF_8))
                .isEqualTo(TopologiaKafka.GRUPO_CATALOGO));
        mvc.perform(get("/api/libros/stock-vivo").with(tokens.backoffice()))
                .andExpect(status().isOk());
        assertThat(stockVivoCrudo().get("disponibles").has(clave)).isFalse();
    }

    // -------------------------------------------------------------------------
    //  Ayudas
    // -------------------------------------------------------------------------

    private String pedir(String sub, String libroId, String sede) throws Exception {
        String cuerpo = mvc.perform(post("/api/solicitudes").with(tokens.alumno(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "libroId": "%s", "cantidad": 1 } ],
                                  "dias": 7, "motivo": "Material para el ramo de prueba", "sede": "%s" }
                                """.formatted(libroId, sede)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(cuerpo).get("id").asString();
    }

    private int ejemplaresEnBase(String libroId) {
        return maestra.jdbc().sql("SELECT ejemplares FROM libro WHERE id = ?").param(libroId)
                .query(Integer.class).single();
    }

    private JsonNode stockVivoCrudo() throws Exception {
        return json.readTree(mvc.perform(get("/api/libros/stock-vivo").with(tokens.backoffice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private Integer stockVivo(String libroId) throws Exception {
        JsonNode valor = stockVivoCrudo().get("disponibles").get(libroId);
        return valor == null ? null : valor.asInt();
    }

    private JsonNode bandejaVivo() throws Exception {
        return json.readTree(mvc.perform(get("/api/gestion/bandeja-vivo").with(tokens.backoffice()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private static List<String> idsPendientes(JsonNode bandeja) {
        List<String> ids = new ArrayList<>();
        bandeja.get("ultimas").forEach(p -> ids.add(p.get("id").asString()));
        return ids;
    }

    /** Como lo haria un productor ajeno (kafka-console-producer): bytes crudos, con o sin cabecera de tipo. */
    private void enviar(String topico, String clave, String cuerpo, String tipo) throws Exception {
        try (KafkaProducer<String, byte[]> productor = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers()),
                new StringSerializer(), new ByteArraySerializer())) {
            ProducerRecord<String, byte[]> registro =
                    new ProducerRecord<>(topico, clave, cuerpo.getBytes(StandardCharsets.UTF_8));
            if (tipo != null) {
                registro.headers().add(new RecordHeader("__TypeId__", tipo.getBytes(StandardCharsets.UTF_8)));
            }
            productor.send(registro).get();
        }
    }

    private List<ConsumerRecord<String, byte[]>> esperarEnDlt(String dlt, String clave) {
        List<ConsumerRecord<String, byte[]>> encontrados = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumidor = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "pruebas-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new ByteArrayDeserializer())) {
            consumidor.subscribe(List.of(dlt));
            await().atMost(ESPERA).until(() -> {
                consumidor.poll(Duration.ofMillis(500)).forEach(r -> {
                    if (clave.equals(r.key())) {
                        encontrados.add(r);
                    }
                });
                return !encontrados.isEmpty();
            });
        }
        return encontrados;
    }
}
