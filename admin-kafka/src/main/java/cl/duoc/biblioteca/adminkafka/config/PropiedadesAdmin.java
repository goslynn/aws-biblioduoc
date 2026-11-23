package cl.duoc.biblioteca.adminkafka.config;

import java.time.Duration;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Las reglas del administrador, leidas de application.properties (admin.kafka.*).
 * Es el unico lugar con nombres de topicos en este microservicio.
 *
 * @param protegidos                los topicos de la biblioteca (los declara la API en
 *                                  TopologiaKafka). No se borran, no se les cambian las
 *                                  particiones ni el cleanup.policy. Los que empiezan por
 *                                  "__" son internos de Kafka y estan protegidos siempre.
 * @param configuracionesPermitidas las claves de configuracion de topico que se pueden
 *                                  fijar por REST. Lo demas (por ejemplo
 *                                  message.timestamp.type) es 400: este servicio administra
 *                                  lo que la biblioteca necesita ajustar, no todo Kafka.
 * @param espera                    cuanto se espera una respuesta del cluster antes del 503
 */
@ConfigurationProperties("admin.kafka")
public record PropiedadesAdmin(Set<String> protegidos, Set<String> configuracionesPermitidas, Duration espera) {

    public PropiedadesAdmin {
        protegidos = protegidos == null ? Set.of() : Set.copyOf(protegidos);
        configuracionesPermitidas = configuracionesPermitidas == null ? Set.of() : Set.copyOf(configuracionesPermitidas);
        espera = espera == null ? Duration.ofSeconds(15) : espera;
    }

    public boolean esProtegido(String topico) {
        return topico.startsWith("__") || protegidos.contains(topico);
    }
}
