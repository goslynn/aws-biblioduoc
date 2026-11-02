package cl.duoc.biblioteca.admin.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * La configuracion de mensajeria del administrador, aislada aqui.
 *
 * El RabbitAdmin se declara a mano (Spring Boot crearia uno igual) para que
 * quede explicito como se comporta:
 *
 *   * NO ignora errores de declaracion: si el broker rechaza algo, el servicio
 *     tiene que enterarse para responder con el codigo correcto.
 *   * No declara nada al arrancar: este servicio no tiene topologia propia.
 *     Toda la topologia de la biblioteca la declara la API (TopologiaRabbit),
 *     y aqui solo se protege por nombre (PropiedadesAdmin).
 *
 * Los controladores y el servicio no construyen conexiones ni canales: reciben
 * este bean ya configurado.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesAdmin.class)
public class AdminRabbitConfig {

    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory conexiones) {
        RabbitAdmin admin = new RabbitAdmin(conexiones);
        admin.setIgnoreDeclarationExceptions(false);
        admin.setAutoStartup(false);
        return admin;
    }
}
