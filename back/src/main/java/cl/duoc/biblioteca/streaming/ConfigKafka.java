package cl.duoc.biblioteca.streaming;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.ConsumerRecordRecoverer;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.kafka.support.serializer.DelegatingByTypeSerializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;
import org.springframework.validation.Validator;

import cl.duoc.biblioteca.eventos.MensajeInvalido;

/**
 * Lo que comparten TODOS los consumidores de Kafka: la validacion del DTO y
 * que hacer cuando un registro falla. Es el equivalente de
 * ProcesadorDeEventos para RabbitMQ: cada consumidor dice QUE hace con el
 * registro; como termina un fallo se decide aqui, una vez.
 *
 * <pre>
 *   el registro no es JSON, o no trae
 *   cabecera de tipo                    -> ErrorHandlingDeserializer lo marca:
 *                                          va DIRECTO a &lt;topico&gt;.dlt
 *   el DTO no pasa @Valid               -> directo a &lt;topico&gt;.dlt
 *   MensajeInvalido (regla de negocio)  -> directo a &lt;topico&gt;.dlt
 *   cualquier otra excepcion            -> 3 reintentos con espera creciente
 *                                          (0,5 s, 1 s, 2 s) y luego a .dlt
 * </pre>
 *
 * En todos los casos el registro queda en el log (log.error con topico,
 * particion, offset y motivo) y el consumidor SIGUE con el siguiente: un
 * registro envenenado no bloquea la particion.
 */
@Configuration
public class ConfigKafka implements KafkaListenerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(ConfigKafka.class);

    private final Validator validador;

    public ConfigKafka(Validator validador) {
        this.validador = validador;
    }

    /** {@code @Valid} en un @Payload de @KafkaListener usa el mismo Bean Validation que los controladores. */
    @Override
    public void configureKafkaListeners(KafkaListenerEndpointRegistrar registrar) {
        registrar.setValidator(validador);
    }

    /**
     * Spring Boot lo conecta solo a la fabrica de contenedores de los
     * @KafkaListener (hay un unico CommonErrorHandler en el contexto).
     */
    @Bean
    CommonErrorHandler manejadorDeErroresKafka(ProducerFactory<?, ?> fabrica) {
        DeadLetterPublishingRecoverer aLaDlt = new DeadLetterPublishingRecoverer(plantillaDlt(fabrica),
                // Misma particion que el original: biblioteca.inventario-2 -> biblioteca.inventario.dlt-2.
                (registro, error) -> new TopicPartition(registro.topic() + TopologiaKafka.SUFIJO_DLT,
                        registro.partition()));

        ConsumerRecordRecoverer recuperador = (registro, error) -> {
            log.error("registro descartado {}-{}@{} (clave {}), va a {}: {}", registro.topic(),
                    registro.partition(), registro.offset(), registro.key(),
                    registro.topic() + TopologiaKafka.SUFIJO_DLT, motivo(error));
            aLaDlt.accept(registro, error);
        };

        ExponentialBackOffWithMaxRetries espera = new ExponentialBackOffWithMaxRetries(3);
        espera.setInitialInterval(500);
        espera.setMultiplier(2);
        espera.setMaxInterval(5_000);

        DefaultErrorHandler manejador = new DefaultErrorHandler(recuperador, espera);
        // Ya trae como no reintentables los errores de deserializacion, de
        // conversion y de validacion: no tienen arreglo. Se suma el nuestro.
        manejador.addNotRetryableExceptions(MensajeInvalido.class);
        manejador.setRetryListeners((ConsumerRecord<?, ?> registro, Exception error, int intento) ->
                log.warn("fallo procesando {}-{}@{}, intento {}: {}", registro.topic(), registro.partition(),
                        registro.offset(), intento, motivo(error)));
        return manejador;
    }

    /**
     * El productor de la DLT. Distinto del de la aplicacion en una cosa: si el
     * registro original no se pudo deserializar, el recuperador solo tiene sus
     * BYTES y tiene que reenviarlos tal cual. Por tipo: byte[] sale crudo,
     * cualquier otra cosa como JSON. El resto (brokers, acks=all, idempotencia)
     * es la configuracion de spring.kafka.producer.*.
     *
     * No es un bean a proposito: si lo fuera, Spring Boot dejaria de crear el
     * KafkaTemplate de la aplicacion.
     */
    private static KafkaTemplate<String, Object> plantillaDlt(ProducerFactory<?, ?> fabrica) {
        Map<Class<?>, Serializer<?>> porTipo = new LinkedHashMap<>();
        porTipo.put(byte[].class, new ByteArraySerializer());
        porTipo.put(Object.class, new JacksonJsonSerializer<>());
        Map<String, Object> config = new HashMap<>(fabrica.getConfigurationProperties());
        return new KafkaTemplate<>(new DefaultKafkaProducerFactory<>(config,
                new StringSerializer(), new DelegatingByTypeSerializer(porTipo, true)));
    }

    /** La causa mas interna: la que explica el fallo, no el envoltorio del listener. */
    private static String motivo(Throwable error) {
        Throwable causa = error;
        while (causa.getCause() != null && causa.getCause() != causa) {
            causa = causa.getCause();
        }
        return causa.getClass().getSimpleName() + ": " + causa.getMessage();
    }
}
