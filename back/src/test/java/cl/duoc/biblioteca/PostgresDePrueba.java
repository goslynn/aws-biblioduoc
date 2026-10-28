package cl.duoc.biblioteca;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistrar;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Un PostgreSQL de verdad, en Docker, para los tests.
 *
 * Reproduce la MISMA separacion de usuarios que en AWS:
 *
 *   * Flyway se conecta con el superusuario del contenedor (en AWS, el
 *     maestro de RDS) y aplica V1 y V2: esquema, rol y seed.
 *   * La aplicacion se conecta como biblioteca_app, con los permisos minimos
 *     que le da V1.
 *
 * Asi, si una consulta necesitara un permiso que biblioteca_app no tiene, el
 * test falla aqui y no en el despliegue.
 *
 * Spring arranca y para el contenedor solo, porque es un bean. Las dos clases
 * de test comparten contexto, asi que se levanta un unico contenedor.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresDePrueba {

    /** La version mayor coincide con EngineVersion de aws/database.yaml. */
    @Bean
    PostgreSQLContainer postgres() {
        return new PostgreSQLContainer("postgres:16-alpine");
    }

    @Bean
    DynamicPropertyRegistrar conexiones(PostgreSQLContainer postgres) {
        return registro -> {
            registro.add("spring.datasource.url", postgres::getJdbcUrl);
            registro.add("spring.datasource.username", () -> "biblioteca_app");
            registro.add("spring.datasource.password", () -> "clave-de-pruebas");

            registro.add("spring.flyway.user", postgres::getUsername);
            registro.add("spring.flyway.password", postgres::getPassword);
        };
    }
}
