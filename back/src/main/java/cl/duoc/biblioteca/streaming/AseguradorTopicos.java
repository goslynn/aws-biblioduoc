package cl.duoc.biblioteca.streaming;

import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Vuelve a intentar crear los topicos hasta que existan.
 *
 * El KafkaAdmin de Spring los crea UNA vez, al arrancar. En la EC2 la API y
 * los brokers arrancan a la vez (compose no hace esperar a la API por Kafka, a
 * proposito: un Kafka roto no debe dejar sin API ni sin correos), asi que lo
 * normal es que en ese primer intento los brokers todavia no respondan. Esta
 * clase lo reintenta cada 30 s hasta ver los cuatro topicos, y luego no hace
 * nada mas. Mientras tanto, los registros esperan en evento_kafka y los
 * consumidores reintentan solos su conexion.
 *
 * No cambia la configuracion de un topico que ya existe (el KafkaAdmin no la
 * modifica por defecto): lo que se ajuste con el administrador de Kafka no se
 * pisa al reiniciar la API.
 */
@Component
public class AseguradorTopicos {

    private static final Logger log = LoggerFactory.getLogger(AseguradorTopicos.class);

    private final KafkaAdmin admin;
    private final AtomicBoolean listos = new AtomicBoolean();

    public AseguradorTopicos(KafkaAdmin admin) {
        this.admin = admin;
    }

    @Scheduled(initialDelayString = "PT5S", fixedDelayString = "${biblioteca.kafka.revision-topicos:PT30S}")
    public void asegurar() {
        if (listos.get()) {
            return;
        }
        try {
            admin.initialize();
            admin.describeTopics(TopologiaKafka.TODOS);
            listos.set(true);
            log.info("topicos de Kafka listos: {}", String.join(", ", TopologiaKafka.TODOS));
        } catch (RuntimeException e) {
            log.warn("los topicos de Kafka aun no estan listos, se reintenta: {}", e.toString());
        }
    }

    /** Para los tests y el diagnostico. */
    public boolean listos() {
        return listos.get();
    }
}
