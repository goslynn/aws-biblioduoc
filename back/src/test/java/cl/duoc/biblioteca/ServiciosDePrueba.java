package cl.duoc.biblioteca;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.Optional;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.rabbitmq.RabbitMQContainer;

import com.icegreen.greenmail.util.GreenMail;
import com.icegreen.greenmail.util.ServerSetup;

import cl.duoc.biblioteca.avisos.DirectorioUsuarios;

/**
 * Los servicios de verdad que necesita la aplicacion, para los tests.
 *
 *   * PostgreSQL en Docker, con la MISMA separacion de usuarios que en AWS:
 *     Flyway entra con el superusuario del contenedor (en AWS, el maestro de
 *     RDS) y la aplicacion como biblioteca_app, con los permisos de V1 y V3.
 *     Si una consulta necesitara un permiso que biblioteca_app no tiene, el
 *     test falla aqui y no en el despliegue.
 *   * RabbitMQ en Docker, la misma version mayor que en la EC2. La topologia
 *     (exchanges, colas, DLX, TTL) la declara la propia aplicacion al arrancar.
 *   * GreenMail: un servidor SMTP en memoria que guarda lo que recibe.
 *   * Un directorio de usuarios falso en lugar de Cognito.
 *
 * Spring arranca y para todo solo, porque son beans. Todas las clases de
 * test comparten contexto, asi que se levanta un unico juego de servicios.
 */
@TestConfiguration(proxyBeanMethods = false)
public class ServiciosDePrueba {

    /** La version mayor coincide con EngineVersion de aws/database.yaml. */
    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }

    /** La misma imagen que levanta el UserData de aws/backend.yaml. */
    @Bean
    RabbitMQContainer rabbit() {
        return new RabbitMQContainer("rabbitmq:4.1-management-alpine");
    }

    /**
     * Puerto FIJO (elegido libre al arrancar), no dinamico: los tests de
     * fallos paran y vuelven a arrancar el servidor, y tiene que volver en el
     * mismo puerto que conoce JavaMailSender.
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    GreenMail smtp() throws IOException {
        int puerto;
        try (ServerSocket libre = new ServerSocket(0)) {
            puerto = libre.getLocalPort();
        }
        return new GreenMail(new ServerSetup(puerto, "127.0.0.1", ServerSetup.PROTOCOL_SMTP));
    }

    @Bean
    DynamicPropertyRegistrar conexiones(PostgreSQLContainer postgres, RabbitMQContainer rabbit, GreenMail smtp) {
        return registro -> {
            registro.add("spring.datasource.url", postgres::getJdbcUrl);
            registro.add("spring.datasource.username", () -> "biblioteca_app");
            registro.add("spring.datasource.password", () -> "clave-de-pruebas");

            registro.add("spring.flyway.user", postgres::getUsername);
            registro.add("spring.flyway.password", postgres::getPassword);

            registro.add("spring.rabbitmq.host", rabbit::getHost);
            registro.add("spring.rabbitmq.port", rabbit::getAmqpPort);
            registro.add("spring.rabbitmq.username", rabbit::getAdminUsername);
            registro.add("spring.rabbitmq.password", rabbit::getAdminPassword);

            registro.add("spring.mail.port", () -> smtp.getSmtp().getPort());
            // GreenMail no habla TLS en este puerto ni pide usuario.
            registro.add("spring.mail.properties.mail.smtp.auth", () -> "false");
            registro.add("spring.mail.properties.mail.smtp.starttls.enable", () -> "false");
        };
    }

    /**
     * En lugar de Cognito. Convenciones de los tests:
     *   * un sub que empieza por "m2m-" es un cliente maquina: sin email;
     *   * uno que empieza por "invalido-" tiene un email que el SMTP rechaza;
     *   * cualquier otro tiene &lt;sub&gt;@prueba.cl, verificado.
     */
    @Bean
    DirectorioUsuarios directorioDePrueba() {
        return sub -> {
            if (sub.startsWith("m2m-")) {
                return Optional.empty();
            }
            if (sub.startsWith("invalido-")) {
                return Optional.of("esto no es un correo");
            }
            return Optional.of(sub + "@prueba.cl");
        };
    }

    /**
     * El superusuario, para PREPARAR escenarios que la aplicacion no puede
     * producir por si misma (adelantar un vencimiento, dejar un libro en 0).
     * No es un JdbcClient registrado como bean a proposito: la aplicacion lo
     * tomaria en lugar del suyo y los tests dejarian de probar los permisos.
     */
    @Bean
    BaseMaestra baseMaestra(PostgreSQLContainer postgres) {
        return new BaseMaestra(JdbcClient.create(new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())));
    }

    public record BaseMaestra(JdbcClient jdbc) {
    }
}
