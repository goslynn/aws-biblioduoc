package cl.duoc.biblioteca.streaming;

import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.listener.AbstractConsumerSeekAware;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

import cl.duoc.biblioteca.solicitudes.ProyeccionBandeja;
import jakarta.validation.Valid;

/**
 * Grupo {@code bandeja-gestion}: lee biblioteca.solicitudes y mantiene la
 * bandeja en vivo del empleado (H9): llega una solicitud y aparece sin
 * recargar; se resuelve y desaparece.
 *
 * Mismo esquema que ConsumidorCatalogoVivo: tres hilos (una particion cada
 * uno), proyeccion en memoria que se reconstruye desde el offset 0 al
 * arrancar, y los fallos en ConfigKafka. Que este grupo y catalogo-vivo
 * lean topicos distintos, y auditoria-kafka lea los dos, es el reparto de
 * responsabilidades entre consumer groups.
 */
@Component
public class ConsumidorBandejaGestion extends AbstractConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorBandejaGestion.class);

    private final ProyeccionBandeja proyeccion;

    public ConsumidorBandejaGestion(ProyeccionBandeja proyeccion) {
        this.proyeccion = proyeccion;
    }

    @KafkaListener(id = TopologiaKafka.GRUPO_BANDEJA, topics = TopologiaKafka.SOLICITUDES,
            concurrency = "${biblioteca.kafka.concurrencia:3}")
    public void recibir(@Payload @Valid HechoSolicitud hecho,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int particion,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.debug("solicitud {} {} ({}) desde particion {} offset {}", hecho.idSolicitud(), hecho.estado(),
                hecho.sede(), particion, offset);
        proyeccion.aplicar(hecho);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> asignadas, ConsumerSeekCallback posicionador) {
        super.onPartitionsAssigned(asignadas, posicionador);
        posicionador.seekToBeginning(asignadas.keySet());
        log.info("{} reconstruye la bandeja desde el inicio de {}", TopologiaKafka.GRUPO_BANDEJA, asignadas.keySet());
    }
}
