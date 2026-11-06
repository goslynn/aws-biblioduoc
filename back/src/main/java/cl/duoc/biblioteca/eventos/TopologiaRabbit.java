package cl.duoc.biblioteca.eventos;

import java.time.Duration;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * TODA la topologia de RabbitMQ, declarada en codigo.
 *
 * Al arrancar, Spring (RabbitAdmin) declara estos exchanges, colas y bindings
 * en el broker. Declarar es idempotente: si ya existen con los MISMOS
 * argumentos, no pasa nada. Si existen con argumentos DISTINTOS (por ejemplo,
 * cambiaste el TTL de correo.espera), el broker responde PRECONDITION_FAILED y
 * la aplicacion no arranca: hay que borrar esa cola desde el panel. Ver
 * aws/README.md §8.
 *
 * <pre>
 *  topic  biblioteca.eventos
 *     solicitud.* / recordatorio.* / aviso.*  -> notif.prestamos      (quorum)
 *     stock.repuesto                          -> notif.disponibilidad (quorum)
 *     #                                       -> bitacora             (quorum)
 *       las tres: x-delivery-limit=5 y DLX eventos.dlx, cada una con
 *       su routing key de muerte = su nombre, asi que cada una tiene SU DLQ:
 *
 *  direct eventos.dlx
 *     notif.prestamos      -> notif.prestamos.dlq
 *     notif.disponibilidad -> notif.disponibilidad.dlq
 *     bitacora             -> bitacora.dlq
 *
 *  direct correo
 *     transaccional -> correo.transaccional   (classic)
 *     masivo        -> correo.masivo          (classic)
 *       las dos: DLX correo.reintento -> correo.espera (TTL, sin consumidor)
 *                correo.espera: DLX correo -> vuelve a su cola de origen
 *     correo.fallidos: la DLQ final, la llena EnviadorSmtp
 * </pre>
 *
 * POR QUE DOS ESTRATEGIAS DE REINTENTO
 *   * Los consumidores de EVENTOS fallan por causas internas y breves (la base
 *     reiniciando). Reintentan al instante (NACK con requeue) y la cola quorum
 *     cuenta las devoluciones: tras la quinta, si la sexta entrega tambien
 *     falla, el broker lo aparta solo en la DLQ.
 *   * El envio de CORREO falla por el proveedor SMTP, que suele necesitar
 *     minutos para recuperarse. Reintentar al instante seria martillarlo.
 *     El NACK sin requeue manda el mensaje a correo.espera, donde nadie lo
 *     consume: al vencer su TTL, el broker lo devuelve a su cola original.
 */
@Configuration
public class TopologiaRabbit {

    public static final String EXCHANGE_EVENTOS = "biblioteca.eventos";
    public static final String EXCHANGE_EVENTOS_DLX = "eventos.dlx";
    public static final String COLA_NOTIF_PRESTAMOS = "notif.prestamos";
    public static final String COLA_NOTIF_DISPONIBILIDAD = "notif.disponibilidad";
    public static final String COLA_BITACORA = "bitacora";
    /** Cada cola de eventos tiene SU DLQ: el mismo nombre con este sufijo. */
    public static final String SUFIJO_DLQ = ".dlq";
    public static final String COLA_NOTIF_PRESTAMOS_DLQ = COLA_NOTIF_PRESTAMOS + SUFIJO_DLQ;
    public static final String COLA_NOTIF_DISPONIBILIDAD_DLQ = COLA_NOTIF_DISPONIBILIDAD + SUFIJO_DLQ;
    public static final String COLA_BITACORA_DLQ = COLA_BITACORA + SUFIJO_DLQ;

    public static final String EXCHANGE_CORREO = "correo";
    public static final String EXCHANGE_CORREO_REINTENTO = "correo.reintento";
    public static final String RK_TRANSACCIONAL = "transaccional";
    public static final String RK_MASIVO = "masivo";
    public static final String COLA_CORREO_TRANSACCIONAL = "correo.transaccional";
    public static final String COLA_CORREO_MASIVO = "correo.masivo";
    public static final String COLA_CORREO_ESPERA = "correo.espera";
    public static final String COLA_CORREO_FALLIDOS = "correo.fallidos";

    /** Fabrica de listeners con ACK manual y prefetch moderado. */
    public static final String FABRICA_MANUAL = "fabricaManual";
    /** Igual, pero de a un mensaje: para el correo masivo, que no debe acaparar el SMTP. */
    public static final String FABRICA_MASIVA = "fabricaMasiva";

    /** Cuantas devoluciones (NACK con requeue) admite un evento antes de que el broker lo aparte en su DLQ. */
    public static final int LIMITE_ENTREGAS = 5;

    @Bean
    Declarables topologiaEventos() {
        TopicExchange eventos = new TopicExchange(EXCHANGE_EVENTOS, true, false);
        DirectExchange dlx = new DirectExchange(EXCHANGE_EVENTOS_DLX, true, false);

        Queue prestamos = colaDeEventos(COLA_NOTIF_PRESTAMOS);
        Queue disponibilidad = colaDeEventos(COLA_NOTIF_DISPONIBILIDAD);
        Queue bitacora = colaDeEventos(COLA_BITACORA);
        Queue prestamosDlq = QueueBuilder.durable(COLA_NOTIF_PRESTAMOS_DLQ).build();
        Queue disponibilidadDlq = QueueBuilder.durable(COLA_NOTIF_DISPONIBILIDAD_DLQ).build();
        Queue bitacoraDlq = QueueBuilder.durable(COLA_BITACORA_DLQ).build();

        return new Declarables(eventos, dlx, prestamos, disponibilidad, bitacora,
                prestamosDlq, disponibilidadDlq, bitacoraDlq,
                BindingBuilder.bind(prestamos).to(eventos).with("solicitud.*"),
                BindingBuilder.bind(prestamos).to(eventos).with("recordatorio.*"),
                BindingBuilder.bind(prestamos).to(eventos).with("aviso.*"),
                BindingBuilder.bind(disponibilidad).to(eventos).with(TiposEvento.STOCK_REPUESTO),
                // "#" = cero o mas palabras: TODO lo que se publique.
                BindingBuilder.bind(bitacora).to(eventos).with("#"),
                // Lo que muere en una cola llega al DLX con el NOMBRE de esa
                // cola como routing key (x-dead-letter-routing-key), y el
                // exchange direct lo deja en la DLQ que le corresponde.
                BindingBuilder.bind(prestamosDlq).to(dlx).with(COLA_NOTIF_PRESTAMOS),
                BindingBuilder.bind(disponibilidadDlq).to(dlx).with(COLA_NOTIF_DISPONIBILIDAD),
                BindingBuilder.bind(bitacoraDlq).to(dlx).with(COLA_BITACORA));
    }

    @Bean
    Declarables topologiaCorreo(@Value("${biblioteca.correo.espera-reintento:PT60S}") Duration retencion) {
        DirectExchange correo = new DirectExchange(EXCHANGE_CORREO, true, false);
        // Fanout: lo que llega aqui va a correo.espera sea cual sea su routing
        // key, y la conserva. Es lo que permite devolverlo a SU cola despues.
        FanoutExchange reintento = new FanoutExchange(EXCHANGE_CORREO_REINTENTO, true, false);

        Queue transaccional = colaDeCorreo(COLA_CORREO_TRANSACCIONAL);
        Queue masivo = colaDeCorreo(COLA_CORREO_MASIVO);
        Queue espera = QueueBuilder.durable(COLA_CORREO_ESPERA)
                // Todos los mensajes esperan lo mismo: un TTL de COLA, no por
                // mensaje. Con TTL por mensaje, uno largo en la cabeza de la
                // cola bloquearia a los cortos que vienen detras.
                .ttl((int) retencion.toMillis())
                // Sin x-dead-letter-routing-key: al expirar, el mensaje vuelve
                // al exchange "correo" con su routing key ORIGINAL.
                .deadLetterExchange(EXCHANGE_CORREO)
                .build();
        Queue fallidos = QueueBuilder.durable(COLA_CORREO_FALLIDOS).build();

        return new Declarables(correo, reintento, transaccional, masivo, espera, fallidos,
                BindingBuilder.bind(transaccional).to(correo).with(RK_TRANSACCIONAL),
                BindingBuilder.bind(masivo).to(correo).with(RK_MASIVO),
                bindingFanout(espera, reintento));
    }

    @Bean(FABRICA_MANUAL)
    SimpleRabbitListenerContainerFactory fabricaManual(ConnectionFactory conexiones) {
        return fabrica(conexiones, 5);
    }

    @Bean(FABRICA_MASIVA)
    SimpleRabbitListenerContainerFactory fabricaMasiva(ConnectionFactory conexiones) {
        return fabrica(conexiones, 1);
    }

    /**
     * Cola QUORUM (replicada con Raft en los nodos del cluster) con limite de
     * entregas. Es el tipo recomendado para datos que
     * no se pueden perder, y el unico que cuenta las reentregas por si solo.
     */
    private static Queue colaDeEventos(String nombre) {
        return QueueBuilder.durable(nombre)
                .quorum()
                .deliveryLimit(LIMITE_ENTREGAS)
                .deadLetterExchange(EXCHANGE_EVENTOS_DLX)
                .deadLetterRoutingKey(nombre)
                .build();
    }

    private static Queue colaDeCorreo(String nombre) {
        return QueueBuilder.durable(nombre)
                .deadLetterExchange(EXCHANGE_CORREO_REINTENTO)
                .build();
    }

    private static Binding bindingFanout(Queue cola, FanoutExchange exchange) {
        return BindingBuilder.bind(cola).to(exchange);
    }

    /**
     * ACK MANUAL: el broker no da un mensaje por procesado hasta que el codigo
     * llama a basicAck. Si la aplicacion muere a mitad, el mensaje vuelve a la
     * cola. Con el modo AUTO de Spring, el ACK lo decide el framework segun si
     * el metodo lanzo excepcion; aqui se decide explicitamente en cada rama.
     */
    private static SimpleRabbitListenerContainerFactory fabrica(ConnectionFactory conexiones, int prefetch) {
        SimpleRabbitListenerContainerFactory fabrica = new SimpleRabbitListenerContainerFactory();
        fabrica.setConnectionFactory(conexiones);
        fabrica.setAcknowledgeMode(AcknowledgeMode.MANUAL);
        fabrica.setPrefetchCount(prefetch);
        return fabrica;
    }
}
