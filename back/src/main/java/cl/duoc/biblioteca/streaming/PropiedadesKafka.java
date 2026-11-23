package cl.duoc.biblioteca.streaming;

import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Como se crea cada topico, leido de application.properties (biblioteca.kafka.*).
 *
 * Los NOMBRES no estan aqui sino en {@link TopologiaKafka}: los necesitan las
 * anotaciones @KafkaListener, que solo aceptan constantes. Aqui va lo que se
 * AJUSTA segun el entorno: en AWS hay tres brokers (3 replicas,
 * min.insync.replicas=2); en los tests, uno solo.
 *
 * @param inventario  biblioteca.inventario: ultimo stock de cada libro (compactado)
 * @param solicitudes biblioteca.solicitudes: historial de hechos de las solicitudes
 * @param dlt         los dos topicos de mensajes muertos (*.dlt)
 * @param esperaEnvio cuanto espera RelayKafka la confirmacion de un lote antes
 *                    de devolverlo a la tabla
 */
@ConfigurationProperties("biblioteca.kafka")
public record PropiedadesKafka(Topico inventario, Topico solicitudes, Topico dlt, Duration esperaEnvio) {

    public PropiedadesKafka {
        esperaEnvio = esperaEnvio == null ? Duration.ofSeconds(10) : esperaEnvio;
    }

    /**
     * @param particiones   cuantas particiones: el maximo de consumidores de un
     *                      mismo grupo que trabajan en paralelo
     * @param replicas      copias de cada particion, en brokers distintos
     * @param configuracion configuracion del topico tal cual la entiende Kafka
     *                      (cleanup.policy, retention.ms, min.insync.replicas...)
     */
    public record Topico(int particiones, short replicas, Map<String, String> configuracion) {

        public Topico {
            configuracion = configuracion == null ? Map.of() : Map.copyOf(configuracion);
        }
    }
}
