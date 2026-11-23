package cl.duoc.biblioteca.streaming;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/**
 * Los topicos de Kafka de la biblioteca, declarados en codigo (el espejo de
 * TopologiaRabbit). Al arrancar, el KafkaAdmin de Spring crea los que falten
 * con estas particiones, replicas y configuracion. Los brokers tienen
 * auto.create.topics.enable=false: un topico mal escrito no aparece solo.
 *
 * <pre>
 *   biblioteca.inventario    clave = id del libro      cleanup.policy=compact
 *       el ULTIMO stock de cada libro. Compactar deja, con el tiempo, un solo
 *       registro por clave: un lector nuevo arranca con el estado completo
 *       sin leer la historia entera.
 *       lectores: catalogo-vivo (H8), auditoria-kafka
 *
 *   biblioteca.solicitudes   clave = id de la solicitud cleanup.policy=delete, 30 dias
 *       cada HECHO de una solicitud (creada, aprobada, rechazada, devuelta).
 *       Aqui importa la historia, no solo el ultimo valor.
 *       lectores: bandeja-gestion (H9), auditoria-kafka
 *
 *   *.dlt                    lo que un consumidor no pudo procesar
 * </pre>
 *
 * POR QUE LA CLAVE ES EL ID DE LA ENTIDAD
 *   Kafka solo garantiza el orden DENTRO de una particion, y la particion sale
 *   del hash de la clave. Con el id como clave, todos los registros de L-005
 *   caen en la misma particion y se leen en el orden en que pasaron: el stock
 *   nunca "retrocede" aunque haya tres consumidores en paralelo.
 *
 * Si se agrega un topico aqui, hay que agregarlo tambien a
 * admin.kafka.protegidos del administrador (admin-kafka/application.properties).
 */
@Configuration
@EnableConfigurationProperties(PropiedadesKafka.class)
public class TopologiaKafka {

    public static final String INVENTARIO = "biblioteca.inventario";
    public static final String SOLICITUDES = "biblioteca.solicitudes";

    /** Sufijo de los topicos de mensajes muertos: biblioteca.inventario.dlt. */
    public static final String SUFIJO_DLT = ".dlt";

    /** Mantiene el stock vivo del catalogo (H8). */
    public static final String GRUPO_CATALOGO = "catalogo-vivo";
    /** Mantiene la bandeja en vivo del empleado (H9). */
    public static final String GRUPO_BANDEJA = "bandeja-gestion";
    /** Lee TODO para dejarlo en el log: un tercer lector que no molesta a los otros dos. */
    public static final String GRUPO_AUDITORIA = "auditoria-kafka";

    /** Todos los topicos de la aplicacion, para comprobar que existen. */
    static final String[] TODOS = {
        INVENTARIO, SOLICITUDES, INVENTARIO + SUFIJO_DLT, SOLICITUDES + SUFIJO_DLT };

    @Bean
    KafkaAdmin.NewTopics topicosBiblioteca(PropiedadesKafka propiedades) {
        return new KafkaAdmin.NewTopics(
                topico(INVENTARIO, propiedades.inventario()),
                topico(SOLICITUDES, propiedades.solicitudes()),
                // Cada DLT con las MISMAS particiones que su origen: el
                // recuperador deja el mensaje en la misma particion que tenia.
                topico(INVENTARIO + SUFIJO_DLT, propiedades.dlt()),
                topico(SOLICITUDES + SUFIJO_DLT, propiedades.dlt()));
    }

    /** La clase del DTO que viaja en cada topico (la usa RelayKafka para validar antes de enviar). */
    public static Class<?> tipoDe(String topico) {
        return switch (topico) {
            case INVENTARIO -> CambioStock.class;
            case SOLICITUDES -> HechoSolicitud.class;
            default -> throw new IllegalArgumentException("topico desconocido: " + topico);
        };
    }

    private static NewTopic topico(String nombre, PropiedadesKafka.Topico t) {
        return TopicBuilder.name(nombre)
                .partitions(t.particiones())
                .replicas(t.replicas())
                .configs(t.configuracion())
                .build();
    }
}
