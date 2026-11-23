package cl.duoc.biblioteca.solicitudes;

import java.time.Instant;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import cl.duoc.biblioteca.streaming.HechoSolicitud;

/**
 * La bandeja del empleado "en vivo" (H9): las solicitudes que siguen
 * RECIBIDA, y cuantas hay por sede. La alimenta ConsumidorBandejaGestion; la
 * lee GET /api/gestion/bandeja-vivo, que la vista de gestion consulta cada
 * pocos segundos.
 *
 * Regla: un hecho con estado RECIBIDA agrega la solicitud; cualquier otro
 * (aprobada, rechazada, devuelta) la saca. Agregar o sacar dos veces da lo
 * mismo: los duplicados no descuadran el contador.
 *
 * Se reconstruye releyendo biblioteca.solicitudes desde el principio al
 * arrancar (ver ProyeccionStock). Una solicitud pendiente desde hace mas que
 * la retencion del topico (30 dias) no apareceria aqui; sigue en la bandeja
 * normal, que lee la base.
 */
@Component
public class ProyeccionBandeja {

    /** Cuantas de las ultimas pendientes se devuelven con detalle. */
    static final int ULTIMAS = 10;

    /**
     * @param pendientes    total de solicitudes RECIBIDA
     * @param porSede       pendientes de cada sede (todas las sedes, con 0 si no hay)
     * @param ultimas       las mas recientes primero, hasta {@link #ULTIMAS}
     * @param actualizadoEn cuando se aplico el ultimo hecho; null si aun no llega ninguno
     */
    public record BandejaVivo(int pendientes, Map<Sede, Long> porSede, List<Pendiente> ultimas,
            Instant actualizadoEn) {
    }

    public record Pendiente(String id, Sede sede, int ejemplares, List<String> titulos, Instant recibidaEn) {
    }

    private final Map<String, HechoSolicitud> pendientes = new ConcurrentHashMap<>();
    private volatile Instant actualizadoEn;

    public void aplicar(HechoSolicitud hecho) {
        if (hecho.estado() == EstadoSolicitud.RECIBIDA) {
            pendientes.putIfAbsent(hecho.idSolicitud(), hecho);
        } else {
            pendientes.remove(hecho.idSolicitud());
        }
        actualizadoEn = Instant.now();
    }

    public BandejaVivo instantanea() {
        List<HechoSolicitud> todas = List.copyOf(pendientes.values());

        Map<Sede, Long> porSede = new EnumMap<>(Sede.class);
        for (Sede sede : Sede.values()) {
            porSede.put(sede, 0L);
        }
        todas.forEach(h -> porSede.merge(h.sede(), 1L, Long::sum));

        List<Pendiente> ultimas = todas.stream()
                .sorted(Comparator.comparing(HechoSolicitud::ocurridoEn).reversed())
                .limit(ULTIMAS)
                .map(h -> new Pendiente(h.idSolicitud(), h.sede(), h.ejemplares(), h.titulos(), h.ocurridoEn()))
                .toList();

        return new BandejaVivo(todas.size(), porSede, ultimas, actualizadoEn);
    }
}
