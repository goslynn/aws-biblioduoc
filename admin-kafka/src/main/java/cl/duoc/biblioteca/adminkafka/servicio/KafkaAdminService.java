package cl.duoc.biblioteca.adminkafka.servicio;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AlterConfigOp;
import org.apache.kafka.clients.admin.Config;
import org.apache.kafka.clients.admin.ConfigEntry;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.admin.DescribeClusterResult;
import org.apache.kafka.clients.admin.GroupListing;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.NewPartitions;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.GroupState;
import org.apache.kafka.common.GroupType;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.Node;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.TopicPartitionInfo;
import org.apache.kafka.common.config.ConfigResource;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.InvalidConfigurationException;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import cl.duoc.biblioteca.adminkafka.config.PropiedadesAdmin;
import cl.duoc.biblioteca.adminkafka.dto.AumentoParticiones;
import cl.duoc.biblioteca.adminkafka.dto.CambioConfiguracion;
import cl.duoc.biblioteca.adminkafka.dto.NuevoTopico;
import cl.duoc.biblioteca.adminkafka.dto.ReinicioOffsets;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.Broker;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.DetalleGrupo;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.DetalleTopico;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.InfoCluster;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.Miembro;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.OffsetParticion;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.Particion;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.ResumenGrupo;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.ResumenTopico;

/**
 * TODA la administracion de Kafka pasa por aqui: es la unica clase que habla
 * con el {@link Admin} de Kafka (el espejo de RabbitAdminService).
 *
 * Los controladores llaman a metodos de alto nivel con DTO ya validados y
 * reciben vistas (Vistas.*) o un error de dominio: {@link NoEncontrado} (404),
 * {@link Conflicto} (409), {@link PeticionInvalida} (400) o
 * {@link KafkaNoDisponible} (503). Ningun KafkaFuture ni excepcion del cliente
 * sale de esta clase.
 *
 * REGLAS QUE APLICA ANTES DE TOCAR EL CLUSTER
 *   * Lo protegido (topicos de la biblioteca e internos "__*") no se borra, no
 *     cambia de particiones ni de cleanup.policy. Las particiones, porque la
 *     particion de cada registro sale de hash(clave) % particiones: cambiarlas
 *     manda los nuevos registros de L-005 a otra particion y se pierde el
 *     orden por libro. El cleanup.policy, porque cambia lo que el topico ES
 *     (compactado = estado; delete = historia).
 *   * Solo se aceptan las claves de configuracion permitidas (PropiedadesAdmin).
 *   * Los offsets de un grupo solo se mueven con el grupo VACIO (sin
 *     consumidores): Kafka lo exige, y es lo sensato; un consumidor activo
 *     confirmaria encima.
 */
@Service
public class KafkaAdminService {

    private static final Logger log = LoggerFactory.getLogger(KafkaAdminService.class);

    private static final String CLEANUP_POLICY = "cleanup.policy";

    private final Admin admin;
    private final PropiedadesAdmin propiedades;

    public KafkaAdminService(Admin admin, PropiedadesAdmin propiedades) {
        this.admin = admin;
        this.propiedades = propiedades;
    }

    // -------------------------------------------------------------------------
    //  Cluster
    // -------------------------------------------------------------------------

    public InfoCluster cluster() {
        DescribeClusterResult cluster = admin.describeCluster();
        Node controlador = esperar(cluster.controller());
        List<Broker> brokers = esperar(cluster.nodes()).stream()
                .sorted(Comparator.comparingInt(Node::id))
                .map(n -> new Broker(n.id(), n.host(), n.port(), n.rack()))
                .toList();
        return new InfoCluster(esperar(cluster.clusterId()), controlador == null ? null : controlador.id(), brokers);
    }

    // -------------------------------------------------------------------------
    //  Topicos
    // -------------------------------------------------------------------------

    public List<ResumenTopico> listarTopicos(boolean conInternos) {
        Set<String> nombres = esperar(admin.listTopics(new ListTopicsOptions().listInternal(conInternos)).names());
        if (nombres.isEmpty()) {
            return List.of();
        }
        return esperar(admin.describeTopics(nombres).allTopicNames()).values().stream()
                .sorted(Comparator.comparing(TopicDescription::name))
                .map(t -> new ResumenTopico(t.name(), t.partitions().size(),
                        t.partitions().isEmpty() ? 0 : t.partitions().getFirst().replicas().size(),
                        t.isInternal(), propiedades.esProtegido(t.name())))
                .toList();
    }

    public DetalleTopico verTopico(String nombre) {
        TopicDescription descripcion = describir(nombre);

        List<TopicPartition> particiones = descripcion.partitions().stream()
                .map(p -> new TopicPartition(nombre, p.partition())).toList();
        Map<TopicPartition, ListOffsetsResultInfo> inicio = offsets(particiones, OffsetSpec.earliest());
        Map<TopicPartition, ListOffsetsResultInfo> fin = offsets(particiones, OffsetSpec.latest());

        List<Particion> vista = new ArrayList<>();
        long mensajes = 0;
        for (TopicPartitionInfo p : descripcion.partitions()) {
            TopicPartition tp = new TopicPartition(nombre, p.partition());
            long desde = inicio.get(tp).offset();
            long hasta = fin.get(tp).offset();
            mensajes += hasta - desde;
            vista.add(new Particion(p.partition(), p.leader() == null ? null : p.leader().id(),
                    ids(p.replicas()), ids(p.isr()), desde, hasta));
        }
        return new DetalleTopico(nombre, descripcion.isInternal(), propiedades.esProtegido(nombre), vista,
                configuracionPropia(nombre), mensajes);
    }

    public DetalleTopico crearTopico(NuevoTopico pedido) {
        exigirPermitidas(pedido.configuracion().keySet());
        NewTopic topico = new NewTopic(pedido.nombre(), pedido.particiones(), pedido.replicas())
                .configs(pedido.configuracion());
        esperar(admin.createTopics(List.of(topico)).all());
        log.info("topico creado: {} ({} particiones x {} replicas) {}", pedido.nombre(), pedido.particiones(),
                pedido.replicas(), pedido.configuracion());
        return verTopico(pedido.nombre());
    }

    /**
     * incrementalAlterConfigs: SET de lo que se establece y DELETE (vuelve al
     * valor por defecto) de lo que se restablece. Kafka valida los valores y
     * aplica todo o nada.
     */
    public DetalleTopico cambiarConfiguracion(String nombre, CambioConfiguracion cambio) {
        describir(nombre);
        Set<String> claves = new HashSet<>(cambio.establecer().keySet());
        claves.addAll(cambio.restablecer());
        exigirPermitidas(claves);
        if (propiedades.esProtegido(nombre) && claves.contains(CLEANUP_POLICY)) {
            throw new Conflicto(nombre + " es parte de la topologia de la biblioteca: su " + CLEANUP_POLICY
                    + " define lo que guarda y no se cambia por REST");
        }

        List<AlterConfigOp> operaciones = new ArrayList<>();
        cambio.establecer().forEach((clave, valor) ->
                operaciones.add(new AlterConfigOp(new ConfigEntry(clave, valor), AlterConfigOp.OpType.SET)));
        cambio.restablecer().forEach(clave ->
                operaciones.add(new AlterConfigOp(new ConfigEntry(clave, null), AlterConfigOp.OpType.DELETE)));

        esperar(admin.incrementalAlterConfigs(Map.of(recurso(nombre), operaciones)).all());
        log.info("configuracion de {}: establecer {} restablecer {}", nombre, cambio.establecer(),
                cambio.restablecer());
        return verTopico(nombre);
    }

    public DetalleTopico aumentarParticiones(String nombre, AumentoParticiones pedido) {
        exigirNoProtegido(nombre, "cambiarle las particiones moveria cada clave a otra particion y romperia "
                + "el orden de sus registros");
        int actuales = describir(nombre).partitions().size();
        if (pedido.total() <= actuales) {
            throw new Conflicto(nombre + " ya tiene " + actuales
                    + " particiones; Kafka solo permite aumentarlas");
        }
        esperar(admin.createPartitions(Map.of(nombre, NewPartitions.increaseTo(pedido.total()))).all());
        log.info("particiones de {}: {} -> {}", nombre, actuales, pedido.total());
        return verTopico(nombre);
    }

    public void eliminarTopico(String nombre) {
        exigirNoProtegido(nombre, "la API dejaria de publicar o de consumir");
        describir(nombre);
        esperar(admin.deleteTopics(List.of(nombre)).all());
        log.info("topico eliminado: {}", nombre);
    }

    // -------------------------------------------------------------------------
    //  Consumer groups
    // -------------------------------------------------------------------------

    /**
     * listGroups() sin filtros de tipo (y luego se filtra aqui): los brokers
     * 3.9 de cp-kafka no aceptan el filtro por tipo de ListGroups v5.
     */
    public List<ResumenGrupo> listarGrupos() {
        return esperar(admin.listGroups().all()).stream()
                .filter(g -> "consumer".equals(g.protocol())
                        || g.type().map(GroupType.CONSUMER::equals).orElse(false)
                        || g.type().map(GroupType.CLASSIC::equals).orElse(false))
                .sorted(Comparator.comparing(GroupListing::groupId))
                .map(g -> new ResumenGrupo(g.groupId(), g.groupState().map(Enum::name).orElse("DESCONOCIDO"),
                        g.type().map(Enum::name).orElse("CLASSIC")))
                .toList();
    }

    public DetalleGrupo verGrupo(String id) {
        ConsumerGroupDescription grupo = describirGrupo(id);

        List<Miembro> miembros = grupo.members().stream()
                .map(m -> new Miembro(m.clientId(), m.host(), m.assignment().topicPartitions().stream()
                        .map(TopicPartition::toString).sorted().toList()))
                .sorted(Comparator.comparing(Miembro::cliente))
                .toList();

        Map<TopicPartition, OffsetAndMetadata> confirmados = confirmados(id);
        Map<TopicPartition, ListOffsetsResultInfo> fin = offsets(confirmados.keySet(), OffsetSpec.latest());
        List<OffsetParticion> offsets = confirmados.entrySet().stream()
                .map(e -> {
                    long hasta = fin.get(e.getKey()).offset();
                    long confirmado = e.getValue().offset();
                    return new OffsetParticion(e.getKey().topic(), e.getKey().partition(), confirmado, hasta,
                            Math.max(0, hasta - confirmado));
                })
                .sorted(Comparator.comparing(OffsetParticion::topico).thenComparingInt(OffsetParticion::particion))
                .toList();

        return new DetalleGrupo(id, grupo.groupState().name(),
                grupo.coordinator() == null ? null : grupo.coordinator().id(), grupo.partitionAssignor(),
                miembros, offsets, offsets.stream().mapToLong(OffsetParticion::lag).sum());
    }

    /**
     * Mueve los offsets confirmados del grupo. Despues, cuando sus consumidores
     * vuelvan, empiezan desde ahi: es como se REPROCESA (INICIO) o se descarta
     * un atasco (FIN) sin tocar el codigo.
     */
    public DetalleGrupo reiniciarOffsets(String id, ReinicioOffsets pedido) {
        ConsumerGroupDescription grupo = describirGrupo(id);
        if (grupo.groupState() != GroupState.EMPTY) {
            throw new Conflicto("el grupo " + id + " esta " + grupo.groupState() + " con " + grupo.members().size()
                    + " consumidor(es): Kafka solo mueve los offsets de un grupo sin consumidores activos. "
                    + "Deten la aplicacion que lo consume y vuelve a intentarlo");
        }

        Collection<TopicPartition> particiones = pedido.topico() != null
                ? describir(pedido.topico()).partitions().stream()
                        .map(p -> new TopicPartition(pedido.topico(), p.partition())).toList()
                : confirmados(id).keySet();
        if (particiones.isEmpty()) {
            throw new Conflicto("el grupo " + id + " no tiene offsets confirmados; indica el topico");
        }

        OffsetSpec destino = switch (pedido.posicion()) {
            case INICIO -> OffsetSpec.earliest();
            case FIN -> OffsetSpec.latest();
            case FECHA -> OffsetSpec.forTimestamp(pedido.fecha().toEpochMilli());
        };
        Map<TopicPartition, ListOffsetsResultInfo> elegidos = offsets(particiones, destino);
        // Una fecha posterior al ultimo registro no encuentra offset (-1): va al final.
        Map<TopicPartition, ListOffsetsResultInfo> finales = offsets(particiones, OffsetSpec.latest());

        Map<TopicPartition, OffsetAndMetadata> nuevos = new HashMap<>();
        elegidos.forEach((tp, info) -> nuevos.put(tp,
                new OffsetAndMetadata(info.offset() >= 0 ? info.offset() : finales.get(tp).offset())));
        esperar(admin.alterConsumerGroupOffsets(id, nuevos).all());
        log.info("offsets de {} movidos a {}{}: {}", id, pedido.posicion(),
                pedido.fecha() == null ? "" : " " + pedido.fecha(), new TreeMap<>(nuevos.entrySet().stream()
                        .collect(Collectors.toMap(e -> e.getKey().toString(), e -> e.getValue().offset()))));
        return verGrupo(id);
    }

    /** Borra el grupo y sus offsets confirmados. Kafka responde GroupNotEmpty (409) si tiene consumidores. */
    public void eliminarGrupo(String id) {
        describirGrupo(id);
        esperar(admin.deleteConsumerGroups(List.of(id)).all());
        log.info("grupo eliminado: {}", id);
    }

    // -------------------------------------------------------------------------
    //  Ayudas
    // -------------------------------------------------------------------------

    private TopicDescription describir(String nombre) {
        return esperar(admin.describeTopics(List.of(nombre)).allTopicNames()).get(nombre);
    }

    /** Un grupo que nunca existio no es un error para Kafka: lo describe como DEAD. Aqui es 404. */
    private ConsumerGroupDescription describirGrupo(String id) {
        ConsumerGroupDescription grupo = esperar(admin.describeConsumerGroups(List.of(id)).all()).get(id);
        if (grupo == null || grupo.groupState() == GroupState.DEAD) {
            throw new NoEncontrado("el grupo " + id + " no existe");
        }
        return grupo;
    }

    private Map<TopicPartition, OffsetAndMetadata> confirmados(String id) {
        Map<TopicPartition, OffsetAndMetadata> todos =
                esperar(admin.listConsumerGroupOffsets(id).partitionsToOffsetAndMetadata());
        Map<TopicPartition, OffsetAndMetadata> validos = new HashMap<>();
        todos.forEach((tp, om) -> {
            if (om != null) {
                validos.put(tp, om);
            }
        });
        return validos;
    }

    private Map<TopicPartition, ListOffsetsResultInfo> offsets(Collection<TopicPartition> particiones,
            OffsetSpec spec) {
        if (particiones.isEmpty()) {
            return Map.of();
        }
        return esperar(admin.listOffsets(particiones.stream()
                .collect(Collectors.toMap(tp -> tp, tp -> spec))).all());
    }

    /** Solo lo que el topico fija por su cuenta: el resto es el valor por defecto del broker. */
    private Map<String, String> configuracionPropia(String nombre) {
        Config config = esperar(admin.describeConfigs(List.of(recurso(nombre))).all()).get(recurso(nombre));
        Map<String, String> propia = new TreeMap<>();
        config.entries().stream()
                .filter(e -> e.source() == ConfigEntry.ConfigSource.DYNAMIC_TOPIC_CONFIG)
                .forEach(e -> propia.put(e.name(), e.value()));
        return propia;
    }

    private static ConfigResource recurso(String topico) {
        return new ConfigResource(ConfigResource.Type.TOPIC, topico);
    }

    private static List<Integer> ids(List<Node> nodos) {
        return nodos.stream().map(Node::id).sorted().toList();
    }

    private void exigirNoProtegido(String nombre, String porque) {
        if (propiedades.esProtegido(nombre)) {
            throw new Conflicto(nombre + " es parte de la topologia de la biblioteca (o interno de Kafka) y no "
                    + "se puede modificar asi: " + porque);
        }
    }

    private void exigirPermitidas(Collection<String> claves) {
        List<String> rechazadas = claves.stream()
                .filter(c -> !propiedades.configuracionesPermitidas().contains(c)).sorted().toList();
        if (!rechazadas.isEmpty()) {
            throw new PeticionInvalida("configuracion no permitida: " + rechazadas + ". Permitidas: "
                    + propiedades.configuracionesPermitidas().stream().sorted().toList());
        }
    }

    /**
     * Espera un resultado del cluster y traduce sus errores a los del dominio.
     * Toda llamada al Admin pasa por aqui.
     */
    private <T> T esperar(KafkaFuture<T> futuro) {
        try {
            return futuro.get(propiedades.espera().toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new KafkaNoDisponible("Kafka no respondio en " + propiedades.espera().toSeconds() + " s", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new KafkaNoDisponible("interrumpido esperando a Kafka", e);
        } catch (ExecutionException e) {
            throw traducir(e.getCause());
        }
    }

    private static RuntimeException traducir(Throwable causa) {
        return switch (causa) {
            case TopicExistsException e -> new Conflicto(e.getMessage());
            case GroupNotEmptyException e -> new Conflicto("el grupo tiene consumidores activos: " + e.getMessage());
            case UnknownTopicOrPartitionException e -> new NoEncontrado("el topico no existe");
            case GroupIdNotFoundException e -> new NoEncontrado("el grupo no existe");
            // InvalidReplicationFactorException e InvalidTopicException son
            // subclases de InvalidConfigurationException: caen aqui.
            case InvalidConfigurationException e -> new PeticionInvalida(e.getMessage());
            case InvalidPartitionsException e -> new PeticionInvalida(e.getMessage());
            case PolicyViolationException e -> new PeticionInvalida(e.getMessage());
            case InvalidRequestException e -> new PeticionInvalida(e.getMessage());
            case org.apache.kafka.common.errors.TimeoutException e ->
                    new KafkaNoDisponible("Kafka no respondio a tiempo", e);
            case RuntimeException e -> e;
            default -> new IllegalStateException(causa);
        };
    }
}
