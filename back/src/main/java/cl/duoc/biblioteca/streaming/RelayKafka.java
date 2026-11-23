package cl.duoc.biblioteca.streaming;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.common.KafkaException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/**
 * Vacia la bandeja de salida de Kafka (tabla evento_kafka): el PRODUCTOR que
 * de verdad habla con los brokers.
 *
 * Cada segundo toma hasta {@link #LOTE} registros, los envia con su clave y
 * espera a que los brokers confirmen TODOS. Con acks=all (application.properties)
 * "confirmado" significa escrito en todas las replicas sincronizadas, y con
 * min.insync.replicas=2 eso son al menos dos brokers: se puede perder uno sin
 * perder el registro. Solo entonces se confirma el DELETE.
 *
 * Si Kafka no responde, el lote vuelve a la tabla (rollback) y se reintenta en
 * la siguiente vuelta. Lo peor que puede pasar es un registro DUPLICADO (Kafka
 * confirmo y la base cayo antes del commit), nunca uno perdido; por eso las
 * proyecciones que lo leen son idempotentes. Con enable.idempotence=true el
 * propio productor tampoco duplica sus reintentos internos.
 *
 * INDEPENDIENTE DE RABBITMQ
 *   Tabla propia, relay propio y un hilo propio del planificador
 *   (spring.task.scheduling.pool.size): un Kafka caido deja crecer evento_kafka
 *   y nada mas. Los correos siguen saliendo por RelayEventos.
 */
@Component
public class RelayKafka {

    private static final Logger log = LoggerFactory.getLogger(RelayKafka.class);

    /** Cuantos registros se publican por vuelta, como maximo. */
    static final int LOTE = 100;

    private final RepositorioRegistrosKafka registros;
    private final KafkaTemplate<String, Object> kafka;
    private final JsonMapper json;
    private final PropiedadesKafka propiedades;

    public RelayKafka(RepositorioRegistrosKafka registros, KafkaTemplate<String, Object> kafka, JsonMapper json,
            PropiedadesKafka propiedades) {
        this.registros = registros;
        this.kafka = kafka;
        this.json = json;
        this.propiedades = propiedades;
    }

    @Scheduled(fixedDelayString = "${biblioteca.kafka.relay:PT1S}")
    @Transactional
    public void publicarPendientes() {
        // RETURNING no garantiza el orden: se reordena para que el stock de un
        // libro llegue en el orden en que cambio.
        List<RegistroKafka> lote = registros.extraerLote(LOTE).stream()
                .sorted(Comparator.comparing(RegistroKafka::creadoEn))
                .toList();
        if (lote.isEmpty()) {
            return;
        }

        try {
            List<CompletableFuture<?>> envios = lote.stream()
                    .<CompletableFuture<?>>map(r -> kafka.send(r.topico(), r.clave(), dto(r)))
                    .toList();
            CompletableFuture.allOf(envios.toArray(CompletableFuture[]::new))
                    .get(propiedades.esperaEnvio().toMillis(), TimeUnit.MILLISECONDS);
            log.info("publicados {} registros en Kafka", lote.size());
        } catch (ExecutionException | TimeoutException | KafkaException e) {
            // Sin excepcion hacia el planificador (que la imprimiria con su
            // traza cada segundo mientras Kafka este caido): una linea y
            // rollback. Los registros vuelven a la tabla.
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            log.warn("Kafka no confirmo {} registros; quedan en evento_kafka: {}", lote.size(), e.toString());
        } catch (InterruptedException e) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            Thread.currentThread().interrupt();
        }
    }

    /**
     * El cuerpo vuelve a su DTO antes de salir: el serializador JSON pone el
     * tipo en la cabecera __TypeId__ (cambioStock, hechoSolicitud), y lo que
     * no se pueda leer como su tipo no llega a los brokers.
     */
    private Object dto(RegistroKafka registro) {
        try {
            return json.readValue(registro.cuerpo(), TopologiaKafka.tipoDe(registro.topico()));
        } catch (JacksonException e) {
            throw new IllegalStateException("registro " + registro.id() + " ilegible en evento_kafka", e);
        }
    }
}
