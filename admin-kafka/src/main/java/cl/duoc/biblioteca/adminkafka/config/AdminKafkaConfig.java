package cl.duoc.biblioteca.adminkafka.config;

import org.apache.kafka.clients.admin.Admin;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * El cliente de administracion de Kafka, aislado aqui.
 *
 * Spring Boot arma el KafkaAdmin con spring.kafka.* (brokers, client-id); de
 * el sale la configuracion del {@link Admin} de Kafka, que es el que tiene las
 * operaciones de topicos, configuraciones, offsets y grupos. Un solo Admin para
 * todo el servicio: es seguro entre hilos y mantiene sus conexiones abiertas.
 *
 * Este servicio no declara topicos (no hay beans NewTopic): toda la topologia
 * la declara la API (TopologiaKafka) y aqui solo se protege por nombre.
 */
@Configuration
@EnableConfigurationProperties(PropiedadesAdmin.class)
public class AdminKafkaConfig {

    @Bean(destroyMethod = "close")
    Admin adminKafka(KafkaAdmin kafkaAdmin) {
        return Admin.create(kafkaAdmin.getConfigurationProperties());
    }
}
