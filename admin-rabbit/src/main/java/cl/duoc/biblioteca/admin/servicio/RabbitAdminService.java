package cl.duoc.biblioteca.admin.servicio;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpIOException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

import cl.duoc.biblioteca.admin.config.PropiedadesAdmin;
import cl.duoc.biblioteca.admin.dto.InfoCola;
import cl.duoc.biblioteca.admin.dto.NuevaCola;
import cl.duoc.biblioteca.admin.dto.NuevoBinding;
import cl.duoc.biblioteca.admin.dto.NuevoExchange;
import cl.duoc.biblioteca.admin.dto.TipoCola;

/**
 * TODA la administracion de RabbitMQ pasa por aqui: es la unica clase que
 * habla con {@link RabbitAdmin}.
 *
 * Los controladores llaman a metodos de alto nivel (crearCola,
 * eliminarExchange...) con DTO ya validados, y no ven ni canales, ni
 * argumentos x-*, ni excepciones de AMQP. Lo que sale de aqui es una
 * respuesta o un error de dominio: {@link NoEncontrado} (404) o
 * {@link Conflicto} (409). Un broker caido sale como la excepcion de conexion
 * de Spring AMQP (503, ver ErroresAdmin).
 *
 * REGLAS QUE APLICA ANTES DE TOCAR EL BROKER
 *   * Lo protegido (PropiedadesAdmin) no se borra ni se le cambian bindings.
 *   * Crear algo que ya existe es 409, no "idempotente": RabbitMQ aceptaria
 *     redeclararlo con los mismos argumentos y fallaria con otros
 *     (PRECONDITION_FAILED). Un 409 claro es mejor que cualquiera de los dos.
 *   * Lo que se referencia (DLX, origen y destino de un binding) tiene que
 *     existir: RabbitMQ aceptaria un DLX inexistente y perderia los mensajes
 *     en silencio.
 *   * Una cola con mensajes no se borra salvo que se pida (forzar=true).
 */
@Service
public class RabbitAdminService {

    private static final Logger log = LoggerFactory.getLogger(RabbitAdminService.class);

    private final RabbitAdmin admin;
    private final PropiedadesAdmin propiedades;

    public RabbitAdminService(RabbitAdmin admin, PropiedadesAdmin propiedades) {
        this.admin = admin;
        this.propiedades = propiedades;
    }

    // -------------------------------------------------------------------------
    //  Colas
    // -------------------------------------------------------------------------

    public InfoCola crearCola(NuevaCola pedida) {
        if (admin.getQueueInfo(pedida.nombre()) != null) {
            throw new Conflicto("la cola " + pedida.nombre() + " ya existe");
        }
        if (pedida.deadLetterExchange() != null && !existeExchange(pedida.deadLetterExchange())) {
            throw new NoEncontrado("el deadLetterExchange " + pedida.deadLetterExchange() + " no existe");
        }

        QueueBuilder cola = pedida.durable()
                ? QueueBuilder.durable(pedida.nombre())
                : QueueBuilder.nonDurable(pedida.nombre());
        if (pedida.tipo() == TipoCola.QUORUM) {
            cola.quorum();
        }
        if (pedida.ttlMs() != null) {
            cola.ttl(pedida.ttlMs().intValue());
        }
        if (pedida.deadLetterExchange() != null) {
            cola.deadLetterExchange(pedida.deadLetterExchange());
        }
        if (pedida.limiteEntregas() != null) {
            cola.deliveryLimit(pedida.limiteEntregas());
        }
        Queue declarada = cola.build();
        admin.declareQueue(declarada);
        log.info("cola creada: {} {}", declarada.getName(), declarada.getArguments());
        return verCola(pedida.nombre());
    }

    public InfoCola verCola(String nombre) {
        QueueInformation info = admin.getQueueInfo(nombre);
        if (info == null) {
            throw new NoEncontrado("la cola " + nombre + " no existe");
        }
        return new InfoCola(info.getName(), info.getMessageCount(), info.getConsumerCount());
    }

    /**
     * @param forzar false: si la cola tiene mensajes, 409 y no se borra nada.
     *               true: se borra con lo que tenga.
     */
    public void eliminarCola(String nombre, boolean forzar) {
        exigirNoProtegido(nombre);
        InfoCola cola = verCola(nombre);
        if (!forzar && cola.mensajes() > 0) {
            throw new Conflicto("la cola " + nombre + " tiene " + cola.mensajes()
                    + " mensajes; para borrarla igual, forzar=true");
        }
        admin.deleteQueue(nombre);
        log.info("cola eliminada: {} ({} mensajes descartados)", nombre, cola.mensajes());
    }

    // -------------------------------------------------------------------------
    //  Exchanges
    // -------------------------------------------------------------------------

    public void crearExchange(NuevoExchange pedido) {
        if (existeExchange(pedido.nombre())) {
            throw new Conflicto("el exchange " + pedido.nombre() + " ya existe");
        }
        ExchangeBuilder constructor = switch (pedido.tipo()) {
            case DIRECT -> ExchangeBuilder.directExchange(pedido.nombre());
            case TOPIC -> ExchangeBuilder.topicExchange(pedido.nombre());
            case FANOUT -> ExchangeBuilder.fanoutExchange(pedido.nombre());
            case HEADERS -> ExchangeBuilder.headersExchange(pedido.nombre());
        };
        Exchange exchange = constructor.durable(pedido.durable()).build();
        admin.declareExchange(exchange);
        log.info("exchange creado: {} ({})", exchange.getName(), exchange.getType());
    }

    public void eliminarExchange(String nombre) {
        exigirNoProtegido(nombre);
        if (!existeExchange(nombre)) {
            throw new NoEncontrado("el exchange " + nombre + " no existe");
        }
        admin.deleteExchange(nombre);
        log.info("exchange eliminado: {}", nombre);
    }

    // -------------------------------------------------------------------------
    //  Bindings
    // -------------------------------------------------------------------------

    /**
     * Se puede enlazar una cola NUEVA a un exchange protegido (por ejemplo,
     * otra cola que escuche biblioteca.eventos): eso no le quita nada a la API.
     * Lo que no se puede es cambiar los bindings de una cola protegida.
     */
    public void crearBinding(NuevoBinding pedido) {
        admin.declareBinding(binding(pedido));
        log.info("binding creado: {} -[{}]-> {}", pedido.exchange(), pedido.routingKey(), pedido.cola());
    }

    /**
     * AMQP no permite consultar si un binding existe, y quitar uno que no
     * existe no es un error para RabbitMQ. Por eso responde igual (204) en los
     * dos casos, una vez comprobado que existen la cola y el exchange.
     */
    public void eliminarBinding(NuevoBinding pedido) {
        admin.removeBinding(binding(pedido));
        log.info("binding eliminado: {} -[{}]-> {}", pedido.exchange(), pedido.routingKey(), pedido.cola());
    }

    private Binding binding(NuevoBinding pedido) {
        exigirNoProtegido(pedido.cola());
        if (!existeExchange(pedido.exchange())) {
            throw new NoEncontrado("el exchange " + pedido.exchange() + " no existe");
        }
        verCola(pedido.cola());
        // Sin BindingBuilder: exige un Exchange del tipo correcto, y para
        // enlazar (o desenlazar) basta con los nombres y la routing key.
        return new Binding(pedido.cola(), Binding.DestinationType.QUEUE, pedido.exchange(),
                pedido.routingKey(), null);
    }

    // -------------------------------------------------------------------------
    //  Ayudas
    // -------------------------------------------------------------------------

    private void exigirNoProtegido(String nombre) {
        if (propiedades.esProtegido(nombre)) {
            throw new Conflicto(nombre + " es parte de la topologia de la biblioteca y no se puede modificar");
        }
    }

    /**
     * AmqpAdmin no tiene "getExchangeInfo". Se pregunta con una declaracion
     * PASIVA: el broker responde OK si existe y cierra el canal con 404 si no.
     * Un broker caido no es un 404: esa excepcion (de conexion) sube tal cual.
     */
    private boolean existeExchange(String nombre) {
        try {
            return Boolean.TRUE.equals(admin.getRabbitTemplate().execute(canal -> {
                canal.exchangeDeclarePassive(nombre);
                return true;
            }));
        } catch (AmqpIOException e) {
            return false;
        }
    }
}
