package cl.duoc.biblioteca.streaming;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Grupo {@code auditoria-kafka}: lee LOS DOS topicos y deja cada registro en
 * el log (stream "app" de CloudWatch), con su particion y offset.
 *
 * Esta para demostrar lo que una cola no hace: un tercer grupo lee
 * exactamente los mismos registros que catalogo-vivo y bandeja-gestion, con
 * SUS propios offsets, sin quitarselos a nadie ni retrasarlos. Si se detiene,
 * su lag crece en Kafka UI y los otros dos ni se enteran.
 *
 * A diferencia de los otros dos, no relee al arrancar: sigue desde el ultimo
 * offset que confirmo (auto-offset-reset=earliest solo aplica la primera vez).
 */
@Component
public class ConsumidorAuditoria {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorAuditoria.class);

    @KafkaListener(id = TopologiaKafka.GRUPO_AUDITORIA,
            topics = { TopologiaKafka.INVENTARIO, TopologiaKafka.SOLICITUDES })
    public void recibir(ConsumerRecord<String, Object> registro) {
        log.info("[auditoria] {}-{}@{} clave={} {}", registro.topic(), registro.partition(), registro.offset(),
                registro.key(), registro.value());
    }
}
