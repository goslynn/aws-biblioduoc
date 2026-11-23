package cl.duoc.biblioteca.adminkafka.dto;

import java.util.List;
import java.util.Map;

/**
 * Lo que DEVUELVE el administrador: vistas de solo lectura del cluster, sin
 * tipos del cliente de Kafka (Node, TopicPartitionInfo...) en el JSON.
 */
public final class Vistas {

    private Vistas() {
    }

    /** GET /cluster. El controlador es el broker que coordina el cluster (elegido via Zookeeper). */
    public record InfoCluster(String id, Integer controlador, List<Broker> brokers) {
    }

    public record Broker(int id, String host, int puerto, String rack) {
    }

    /** Una fila de GET /topicos. */
    public record ResumenTopico(String nombre, int particiones, int replicas, boolean interno, boolean protegido) {
    }

    /**
     * GET /topicos/{nombre}.
     *
     * @param configuracion solo lo que el TOPICO fija por su cuenta (lo demas es
     *                      el valor por defecto del broker)
     * @param mensajes      registros retenidos (suma de fin - inicio); en un
     *                      topico compactado es una cota superior
     */
    public record DetalleTopico(String nombre, boolean interno, boolean protegido, List<Particion> particiones,
            Map<String, String> configuracion, long mensajes) {
    }

    /**
     * @param lider        broker que atiende lecturas y escrituras de la particion
     * @param replicas     brokers con una copia
     * @param sincronizadas ISR: las replicas al dia. Con min.insync.replicas=2,
     *                     si quedan menos de 2 la particion deja de aceptar
     *                     escrituras con acks=all
     */
    public record Particion(int numero, Integer lider, List<Integer> replicas, List<Integer> sincronizadas,
            long offsetInicio, long offsetFin) {
    }

    /** Una fila de GET /grupos. */
    public record ResumenGrupo(String id, String estado, String tipo) {
    }

    /**
     * GET /grupos/{id}: quien consume que, y cuanto le falta.
     *
     * @param lagTotal registros pendientes del grupo, sumando todas sus particiones
     */
    public record DetalleGrupo(String id, String estado, Integer coordinador, String asignador,
            List<Miembro> miembros, List<OffsetParticion> offsets, long lagTotal) {
    }

    public record Miembro(String cliente, String host, List<String> particiones) {
    }

    /**
     * @param confirmado el siguiente offset que el grupo leera (lo ultimo confirmado)
     * @param fin        el offset del proximo registro que se escriba
     * @param lag        fin - confirmado: cuanto le falta al grupo en esta particion
     */
    public record OffsetParticion(String topico, int particion, long confirmado, long fin, long lag) {
    }
}
