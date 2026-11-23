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

import cl.duoc.biblioteca.catalogo.ProyeccionStock;
import jakarta.validation.Valid;

/**
 * Grupo {@code catalogo-vivo}: lee biblioteca.inventario y mantiene el stock
 * en vivo del catalogo (H8).
 *
 * TRES HILOS, UNA PARTICION CADA UNO
 *   concurrency = 3 crea tres consumidores del MISMO grupo; Kafka le reparte
 *   a cada uno una de las tres particiones. Es el balanceo de carga dentro de
 *   un grupo: con 4 hilos, uno quedaria ocioso (no hay cuarta particion).
 *
 * RELEE DESDE EL PRINCIPIO AL ARRANCAR
 *   La proyeccion vive en memoria. Al recibir sus particiones, el consumidor
 *   se posiciona en el offset 0 (seekToBeginning): el topico es compactado,
 *   asi que releerlo es leer el ultimo stock de cada libro. Los offsets que
 *   el grupo confirma siguen sirviendo para ver el lag en Kafka UI.
 *
 * Aqui solo hay mensajeria: que se hace con el cambio lo decide
 * ProyeccionStock. Los fallos los resuelve ConfigKafka (reintentos y DLT).
 */
@Component
public class ConsumidorCatalogoVivo extends AbstractConsumerSeekAware {

    private static final Logger log = LoggerFactory.getLogger(ConsumidorCatalogoVivo.class);

    private final ProyeccionStock proyeccion;

    public ConsumidorCatalogoVivo(ProyeccionStock proyeccion) {
        this.proyeccion = proyeccion;
    }

    @KafkaListener(id = TopologiaKafka.GRUPO_CATALOGO, topics = TopologiaKafka.INVENTARIO,
            concurrency = "${biblioteca.kafka.concurrencia:3}")
    public void recibir(@Payload @Valid CambioStock cambio,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int particion,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.debug("stock {} -> {} ({}) desde particion {} offset {}", cambio.idLibro(), cambio.disponibles(),
                cambio.motivo(), particion, offset);
        proyeccion.aplicar(cambio);
    }

    @Override
    public void onPartitionsAssigned(Map<TopicPartition, Long> asignadas, ConsumerSeekCallback posicionador) {
        super.onPartitionsAssigned(asignadas, posicionador);
        posicionador.seekToBeginning(asignadas.keySet());
        log.info("{} reconstruye el stock desde el inicio de {}", TopologiaKafka.GRUPO_CATALOGO, asignadas.keySet());
    }
}
