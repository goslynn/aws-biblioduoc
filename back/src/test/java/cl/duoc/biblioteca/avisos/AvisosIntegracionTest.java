package cl.duoc.biblioteca.avisos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.core.convert.converter.Converter;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.GreenMailUtil;

import cl.duoc.biblioteca.ServiciosDePrueba;
import cl.duoc.biblioteca.ServiciosDePrueba.BaseMaestra;
import cl.duoc.biblioteca.Tokens;
import cl.duoc.biblioteca.eventos.TopologiaRabbit;
import cl.duoc.biblioteca.solicitudes.ProgramadorRecordatorios;
import jakarta.mail.internet.MimeMessage;

/**
 * Las historias de usuario de punta a punta: peticion HTTP -> bandeja de
 * salida -> RabbitMQ -> consumidor -> correo en GreenMail.
 *
 * Todo es asincrono, asi que las comprobaciones esperan (await) en vez de
 * mirar una sola vez. Cada test usa estudiantes con un sub propio (UUID) para
 * no confundir sus correos con los de otro test.
 */
@SpringBootTest
@Import(ServiciosDePrueba.class)
@AutoConfigureMockMvc
class AvisosIntegracionTest {

    private static final Duration ESPERA = Duration.ofSeconds(20);

    @Autowired
    MockMvc mvc;

    @Autowired
    GreenMail smtp;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    BaseMaestra maestra;

    @Autowired
    ProgramadorRecordatorios recordatorios;

    @Autowired
    Converter<Jwt, Collection<GrantedAuthority>> autoridades;

    Tokens tokens;

    @BeforeEach
    void preparar() {
        tokens = new Tokens(autoridades);
    }

    // -------------------------------------------------------------------------
    //  H0 + H1: activar avisos y comprobante
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("H0/H1: al activar los avisos llega la bienvenida, y al pedir, el comprobante")
    void activarYComprobante() throws Exception {
        String sub = nuevoAlumno();

        mvc.perform(get("/api/avisos/correo").with(tokens.alumno(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activo").value(false));

        mvc.perform(put("/api/avisos/correo").with(tokens.alumno(sub)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(sub + "@prueba.cl"));
        esperarCorreo(sub, "Avisos de la Biblioteca Duoc activados");

        String id = pedir(sub, "L-001", 1);
        MimeMessage comprobante = esperarCorreo(sub, "Recibimos tu solicitud " + id);
        assertThat(GreenMailUtil.getBody(comprobante)).contains("Clean Code");
    }

    @Test
    @DisplayName("H0: sin avisos activos no sale ningun correo, y la solicitud se crea igual")
    void sinConsentimientoNoHayCorreo() throws Exception {
        String sub = nuevoAlumno();
        pedir(sub, "L-001", 1);
        Thread.sleep(2_000);
        assertThat(correosDe(sub)).isEmpty();
    }

    @Test
    @DisplayName("H0: un cliente M2M no tiene email en Cognito: 409")
    void m2mSinCorreo() throws Exception {
        mvc.perform(put("/api/avisos/correo").with(tokens.alumno("m2m-cliente")))
                .andExpect(status().isConflict());
    }

    // -------------------------------------------------------------------------
    //  H2: bandeja del empleado
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("H2: aprobar descuenta stock y avisa; aprobar dos veces es 409")
    void aprobarDescuentaYAvisa() throws Exception {
        String sub = alumnoConAvisos();
        int antes = ejemplares("L-006");
        String id = pedir(sub, "L-006", 2);

        mvc.perform(post("/api/gestion/solicitudes/" + id + "/aprobacion").with(tokens.empleado("empleado-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("APROBADA"))
                .andExpect(jsonPath("$.resueltaPor").value("empleado-1"))
                .andExpect(jsonPath("$.venceEn").exists());
        assertThat(ejemplares("L-006")).isEqualTo(antes - 2);

        mvc.perform(post("/api/gestion/solicitudes/" + id + "/aprobacion").with(tokens.empleado("empleado-2")))
                .andExpect(status().isConflict());
        assertThat(ejemplares("L-006")).isEqualTo(antes - 2);

        assertThat(GreenMailUtil.getBody(esperarCorreo(sub, "Solicitud " + id + " aprobada")))
                .contains("Devuelvelos antes del");

        // Se deja el stock como estaba para los demas tests.
        mvc.perform(post("/api/gestion/solicitudes/" + id + "/devolucion").with(tokens.backoffice()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("H2: sin stock al aprobar es 409 y la solicitud sigue RECIBIDA")
    void aprobarSinStock() throws Exception {
        // L-008 tiene 1 ejemplar. Dos estudiantes lo piden; los dos ven stock.
        String id1 = pedir(nuevoAlumno(), "L-008", 1);
        String id2 = pedir(nuevoAlumno(), "L-008", 1);

        mvc.perform(post("/api/gestion/solicitudes/" + id1 + "/aprobacion").with(tokens.backoffice()))
                .andExpect(status().isOk());
        mvc.perform(post("/api/gestion/solicitudes/" + id2 + "/aprobacion").with(tokens.backoffice()))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/solicitudes/" + id2).with(tokens.backoffice()))
                .andExpect(jsonPath("$.estado").value("RECIBIDA"));

        mvc.perform(post("/api/gestion/solicitudes/" + id1 + "/devolucion").with(tokens.backoffice()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("H2: rechazar exige motivo (400) y el estudiante lo recibe por correo")
    void rechazarConMotivo() throws Exception {
        String sub = alumnoConAvisos();
        String id = pedir(sub, "L-014", 1);

        mvc.perform(post("/api/gestion/solicitudes/" + id + "/rechazo").with(tokens.empleado("empleado-1"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"motivo\": \"  \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detalles.motivo").exists());

        mvc.perform(post("/api/gestion/solicitudes/" + id + "/rechazo").with(tokens.empleado("empleado-1"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"motivo\": \"Tienes un prestamo atrasado\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("RECHAZADA"));

        assertThat(GreenMailUtil.getBody(esperarCorreo(sub, "Solicitud " + id + " rechazada")))
                .contains("Tienes un prestamo atrasado");
    }

    @Test
    @DisplayName("RBAC: un estudiante no gestiona (403); el backoffice y el empleado si")
    void rbac() throws Exception {
        mvc.perform(get("/api/gestion/solicitudes").with(tokens.alumno(nuevoAlumno())))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/gestion/libros/L-001/stock").with(tokens.alumno(nuevoAlumno()))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\": 1}"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/gestion/solicitudes").with(tokens.empleadoSinScope("empleado-1")))
                .andExpect(status().isForbidden());

        mvc.perform(get("/api/gestion/solicitudes").with(tokens.empleado("empleado-1")))
                .andExpect(status().isOk());
        mvc.perform(get("/api/gestion/solicitudes").param("estado", "RECIBIDA").with(tokens.backoffice()))
                .andExpect(status().isOk());
        // El backoffice tambien lee el catalogo que gestiona.
        mvc.perform(get("/api/libros/L-001").with(tokens.backoffice())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("RBAC: la solicitud de otro estudiante responde 404, no 403")
    void solicitudAjena() throws Exception {
        String id = pedir(nuevoAlumno(), "L-001", 1);
        mvc.perform(get("/api/solicitudes/" + id).with(tokens.alumno(nuevoAlumno())))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/solicitudes/" + id).with(tokens.empleado("empleado-1")))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    //  H3: recordatorio
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("H3: un prestamo que vence pronto recibe UN recordatorio; uno devuelto, ninguno")
    void recordatorio() throws Exception {
        String sub = alumnoConAvisos();
        String vigente = pedirYAprobar(sub, "L-013");
        String devuelto = pedirYAprobar(sub, "L-013");
        mvc.perform(post("/api/gestion/solicitudes/" + devuelto + "/devolucion").with(tokens.backoffice()))
                .andExpect(status().isOk());

        // Los dos "se aprobaron hace 2 dias y vencen en 1 hora".
        maestra.jdbc().sql("""
                UPDATE solicitud SET resuelta_en = now() - interval '2 days', vence_en = now() + interval '1 hour'
                WHERE id IN (:ids)
                """).param("ids", List.of(vigente, devuelto)).update();

        assertThat(recordatorios.revisar()).isGreaterThanOrEqualTo(1);
        assertThat(recordatorios.revisar()).isZero();

        esperarCorreo(sub, "Tu prestamo " + vigente + " vence pronto");
        Thread.sleep(1_000);
        assertThat(asuntos(sub)).noneMatch(a -> a.contains(devuelto + " vence pronto"));

        mvc.perform(post("/api/gestion/solicitudes/" + vigente + "/devolucion").with(tokens.backoffice()))
                .andExpect(status().isOk());
    }

    // -------------------------------------------------------------------------
    //  H4 + H5 + H6: devolucion, avisame y carga de stock
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("H5/H6: avisame en un libro agotado; al cargar stock llega el aviso y la suscripcion se cierra")
    void avisameYCargaDeStock() throws Exception {
        String sub = alumnoConAvisos();
        try {
            mvc.perform(post("/api/libros/L-011/avisame").with(tokens.alumno(sub))).andExpect(status().isCreated());
            mvc.perform(post("/api/libros/L-011/avisame").with(tokens.alumno(sub))).andExpect(status().isCreated());
            mvc.perform(post("/api/libros/L-001/avisame").with(tokens.alumno(sub))).andExpect(status().isConflict());
            mvc.perform(post("/api/libros/L-999/avisame").with(tokens.alumno(sub))).andExpect(status().isNotFound());
            mvc.perform(get("/api/libros/avisame").with(tokens.alumno(sub)))
                    .andExpect(jsonPath("$[0]").value("L-011"));

            mvc.perform(post("/api/gestion/libros/L-011/stock").with(tokens.empleado("empleado-1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\": 0}"))
                    .andExpect(status().isBadRequest());
            mvc.perform(post("/api/gestion/libros/L-011/stock").with(tokens.empleado("empleado-1"))
                            .contentType(MediaType.APPLICATION_JSON).content("{\"cantidad\": 2}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.ejemplares").value(2));

            esperarCorreo(sub, "Volvio: Terraform Up and Running");
            await().atMost(ESPERA).untilAsserted(() -> mvc.perform(get("/api/libros/avisame").with(tokens.alumno(sub)))
                    .andExpect(jsonPath("$.length()").value(0)));
        } finally {
            // LibroControllerTest cuenta con que Terraform tenga 0 ejemplares.
            maestra.jdbc().sql("UPDATE libro SET ejemplares = 0 WHERE id = 'L-011'").update();
        }
    }

    @Test
    @DisplayName("H4: devolver el ultimo ejemplar repone el stock y avisa a quien lo esperaba")
    void devolucionDisparaAvisame() throws Exception {
        String quienPide = alumnoConAvisos();
        String quienEspera = alumnoConAvisos();

        // L-003 tiene 2 ejemplares: esta solicitud los agota.
        String id = pedirYAprobar(quienPide, "L-003", 2);
        assertThat(ejemplares("L-003")).isZero();
        mvc.perform(post("/api/libros/L-003/avisame").with(tokens.alumno(quienEspera))).andExpect(status().isCreated());

        mvc.perform(post("/api/gestion/solicitudes/" + id + "/devolucion").with(tokens.empleado("empleado-1")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.estado").value("DEVUELTA"));
        mvc.perform(post("/api/gestion/solicitudes/" + id + "/devolucion").with(tokens.empleado("empleado-1")))
                .andExpect(status().isConflict());
        assertThat(ejemplares("L-003")).isEqualTo(2);

        esperarCorreo(quienPide, "Devolucion registrada: " + id);
        esperarCorreo(quienEspera, "Volvio: Refactoring");
    }

    @Test
    @DisplayName("H5: darse de baja del avisame")
    void bajaDeAvisame() throws Exception {
        String sub = nuevoAlumno();
        mvc.perform(post("/api/libros/L-005/avisame").with(tokens.alumno(sub))).andExpect(status().isCreated());
        mvc.perform(delete("/api/libros/L-005/avisame").with(tokens.alumno(sub))).andExpect(status().isNoContent());
        mvc.perform(get("/api/libros/avisame").with(tokens.alumno(sub))).andExpect(jsonPath("$.length()").value(0));
    }

    // -------------------------------------------------------------------------
    //  H7: bitacora
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("H7: la bitacora registra quien aprobo")
    void bitacora() throws Exception {
        String id = pedirYAprobar(nuevoAlumno(), "L-014");
        await().atMost(ESPERA).untilAsserted(() -> mvc.perform(get("/api/gestion/bitacora").with(tokens.backoffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.referencia == '" + id + "' && @.tipo == 'solicitud.aprobada')].actor")
                        .value("m2m-backoffice")));
        mvc.perform(post("/api/gestion/solicitudes/" + id + "/devolucion").with(tokens.backoffice()));
    }

    // -------------------------------------------------------------------------
    //  Fallos: reintento con espera, DLQ y mensajes envenenados
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("SMTP caido: el correo da 3 vueltas por correo.espera y termina en correo.fallidos")
    void smtpCaidoAgotaReintentos() throws Exception {
        String sub = alumnoConAvisos();
        smtp.stop();
        try {
            pedir(sub, "L-001", 1);
            Message apartado = esperarFallido(TopologiaRabbit.COLA_CORREO_FALLIDOS, sub + "@prueba.cl");
            assertThat(motivo(apartado)).startsWith("agoto 3 intentos");
            // Dos vueltas por la espera: la tercera falla y ya no vuelve.
            assertThat(apartado.getMessageProperties().getXDeathHeader())
                    .anyMatch(m -> TopologiaRabbit.COLA_CORREO_ESPERA.equals(m.get("queue"))
                            && ((Number) m.get("count")).intValue() == 2);
        } finally {
            smtp.start();
        }
    }

    @Test
    @DisplayName("SMTP vuelve a tiempo: el correo sale en el segundo intento")
    void smtpVuelveATiempo() throws Exception {
        String sub = alumnoConAvisos();
        smtp.stop();
        String id;
        try {
            id = pedir(sub, "L-001", 1);
            Thread.sleep(1_500);
        } finally {
            smtp.start();
        }
        esperarCorreo(sub, "Recibimos tu solicitud " + id);
    }

    @Test
    @DisplayName("direccion invalida: fallo permanente, a correo.fallidos SIN reintentar")
    void direccionInvalida() throws Exception {
        String sub = "invalido-" + UUID.randomUUID();
        mvc.perform(put("/api/avisos/correo").with(tokens.alumno(sub))).andExpect(status().isOk());

        Message apartado = esperarFallido(TopologiaRabbit.COLA_CORREO_FALLIDOS, "esto no es un correo");
        assertThat(motivo(apartado)).startsWith("fallo permanente");
        assertThat(apartado.getMessageProperties().getXDeathHeader()).isNullOrEmpty();
    }

    @Test
    @DisplayName("evento envenenado: NACK sin requeue y directo a eventos.fallidos")
    void eventoEnvenenado() {
        String marca = "envenenado-" + UUID.randomUUID();
        rabbit.send(TopologiaRabbit.EXCHANGE_EVENTOS, "solicitud.creada",
                MessageBuilder.withBody(("{ esto no es json " + marca).getBytes(StandardCharsets.UTF_8)).build());

        // Llega a eventos.fallidos dos veces: lo rechazan notif.prestamos y bitacora (las dos lo reciben).
        Message apartado = esperarFallido(TopologiaRabbit.COLA_EVENTOS_FALLIDOS, marca);
        assertThat(apartado.getMessageProperties().getXDeathHeader())
                .anyMatch(m -> "rejected".equals(String.valueOf(m.get("reason"))));
    }

    @Test
    @DisplayName("correo ilegible en la cola de correo: apartado con su motivo")
    void correoIlegible() {
        String marca = "ilegible-" + UUID.randomUUID();
        rabbit.send(TopologiaRabbit.EXCHANGE_CORREO, TopologiaRabbit.RK_TRANSACCIONAL,
                MessageBuilder.withBody(("{\"asunto\": \"" + marca + "\"}").getBytes(StandardCharsets.UTF_8)).build());

        Message apartado = esperarFallido(TopologiaRabbit.COLA_CORREO_FALLIDOS, marca);
        assertThat(motivo(apartado)).contains("sin mensajeId");
    }

    // -------------------------------------------------------------------------
    //  Ayudas
    // -------------------------------------------------------------------------

    private static String nuevoAlumno() {
        return "alumno-" + UUID.randomUUID();
    }

    private String alumnoConAvisos() throws Exception {
        String sub = nuevoAlumno();
        mvc.perform(put("/api/avisos/correo").with(tokens.alumno(sub))).andExpect(status().isOk());
        esperarCorreo(sub, "Avisos de la Biblioteca Duoc activados");
        return sub;
    }

    private String pedir(String sub, String libroId, int cantidad) throws Exception {
        String cuerpo = mvc.perform(post("/api/solicitudes").with(tokens.alumno(sub))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "items": [ { "libroId": "%s", "cantidad": %d } ],
                                  "dias": 7, "motivo": "Material para el ramo de prueba", "sede": "SAN_JOAQUIN" }
                                """.formatted(libroId, cantidad)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return cuerpo.replaceAll(".*\"id\"\\s*:\\s*\"([^\"]+)\".*", "$1");
    }

    private String pedirYAprobar(String sub, String libroId) throws Exception {
        return pedirYAprobar(sub, libroId, 1);
    }

    private String pedirYAprobar(String sub, String libroId, int cantidad) throws Exception {
        String id = pedir(sub, libroId, cantidad);
        mvc.perform(post("/api/gestion/solicitudes/" + id + "/aprobacion").with(tokens.backoffice()))
                .andExpect(status().isOk());
        return id;
    }

    private int ejemplares(String libroId) {
        return maestra.jdbc().sql("SELECT ejemplares FROM libro WHERE id = :id")
                .param("id", libroId).query(Integer.class).single();
    }

    private List<MimeMessage> correosDe(String sub) {
        String para = sub + "@prueba.cl";
        return Arrays.stream(smtp.getReceivedMessages())
                .filter(m -> {
                    try {
                        return Arrays.stream(m.getAllRecipients()).anyMatch(r -> r.toString().equals(para));
                    } catch (Exception e) {
                        return false;
                    }
                })
                .toList();
    }

    private List<String> asuntos(String sub) {
        List<String> asuntos = new ArrayList<>();
        for (MimeMessage m : correosDe(sub)) {
            try {
                asuntos.add(m.getSubject());
            } catch (Exception e) {
                asuntos.add("");
            }
        }
        return asuntos;
    }

    private MimeMessage esperarCorreo(String sub, String asunto) {
        await().atMost(ESPERA).until(() -> asuntos(sub).contains(asunto));
        List<MimeMessage> correos = correosDe(sub);
        List<String> lista = asuntos(sub);
        return correos.get(lista.indexOf(asunto));
    }

    /**
     * Saca mensajes de una DLQ hasta encontrar el que contiene la marca. Los
     * demas se descartan: son restos de otros tests.
     */
    private Message esperarFallido(String cola, String marca) {
        Message[] encontrado = new Message[1];
        await().atMost(ESPERA).until(() -> {
            Message m;
            while ((m = rabbit.receive(cola)) != null) {
                if (new String(m.getBody(), StandardCharsets.UTF_8).contains(marca)) {
                    encontrado[0] = m;
                    return true;
                }
            }
            return false;
        });
        return encontrado[0];
    }

    private static String motivo(Message m) {
        return String.valueOf(m.getMessageProperties().getHeaders().get(EnviadorSmtp.HEADER_MOTIVO));
    }
}
