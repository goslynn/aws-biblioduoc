package cl.duoc.biblioteca.solicitudes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import cl.duoc.biblioteca.catalogo.CatalogoLibros;
import cl.duoc.biblioteca.catalogo.ServicioInventario;
import cl.duoc.biblioteca.eventos.BandejaDeSalida;
import cl.duoc.biblioteca.eventos.TiposEvento;

/**
 * El ciclo de vida de un prestamo, con su evento.
 *
 * Cada metodo es UNA transaccion que hace el cambio de negocio y deja el
 * evento en la bandeja de salida (ver BandejaDeSalida). Si algo falla a mitad
 * —por ejemplo, el segundo libro de la solicitud ya no tiene stock— el
 * rollback deshace tambien el descuento del primero y el evento: no queda un
 * correo de "aprobada" de una solicitud que no se aprobo.
 */
@Service
public class ServicioPrestamos {

    private final RepositorioSolicitudes solicitudes;
    private final CatalogoLibros catalogo;
    private final ServicioInventario inventario;
    private final BandejaDeSalida bandeja;

    public ServicioPrestamos(RepositorioSolicitudes solicitudes, CatalogoLibros catalogo,
            ServicioInventario inventario, BandejaDeSalida bandeja) {
        this.solicitudes = solicitudes;
        this.catalogo = catalogo;
        this.inventario = inventario;
        this.bandeja = bandeja;
    }

    @Transactional
    public Solicitud crear(String solicitante, NuevaSolicitud formulario, List<LineaSolicitud> lineas) {
        Solicitud creada = solicitudes.crear(solicitante, formulario, lineas);
        return publicar(TiposEvento.SOLICITUD_CREADA, solicitante, creada);
    }

    /**
     * RECIBIDA -> APROBADA, descontando el stock de cada libro.
     *
     * 404 si no existe; 409 si ya no esta RECIBIDA (otro empleado se adelanto)
     * o si algun libro ya no tiene los ejemplares pedidos.
     */
    @Transactional
    public Solicitud aprobar(String id, String empleado) {
        Solicitud solicitud = existente(id);
        if (!solicitudes.aprobar(id, empleado, ahora())) {
            throw yaResuelta(solicitud);
        }
        porLibro(solicitud).forEach((libroId, cantidad) -> {
            if (!catalogo.descontar(libroId, cantidad)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "No quedan " + cantidad + " ejemplares de " + libroId + " para aprobar " + id);
            }
        });
        return publicar(TiposEvento.SOLICITUD_APROBADA, empleado, existente(id));
    }

    /** RECIBIDA -> RECHAZADA. 404 si no existe; 409 si ya no esta RECIBIDA. */
    @Transactional
    public Solicitud rechazar(String id, String empleado, String motivo) {
        Solicitud solicitud = existente(id);
        if (!solicitudes.rechazar(id, empleado, motivo.trim(), ahora())) {
            throw yaResuelta(solicitud);
        }
        return publicar(TiposEvento.SOLICITUD_RECHAZADA, empleado, existente(id));
    }

    /**
     * APROBADA -> DEVUELTA, reponiendo el stock. Si un libro estaba agotado,
     * la reposicion dispara los "avisame" (ServicioInventario).
     *
     * 404 si no existe; 409 si no estaba APROBADA.
     */
    @Transactional
    public Solicitud devolver(String id, String empleado) {
        Solicitud solicitud = existente(id);
        if (!solicitudes.devolver(id, ahora())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se devuelve una solicitud APROBADA; " + id + " esta " + solicitud.estado());
        }
        porLibro(solicitud).forEach((libroId, cantidad) -> {
            // Si el libro se hubiera retirado del catalogo, no hay a donde reponer.
            catalogo.porId(libroId).ifPresent(libro -> inventario.reponer(libro, cantidad));
        });
        return publicar(TiposEvento.SOLICITUD_DEVUELTA, empleado, existente(id));
    }

    private Solicitud publicar(String tipo, String actor, Solicitud solicitud) {
        bandeja.registrar(tipo, actor, solicitud.id(), Map.of("solicitud", solicitud));
        return solicitud;
    }

    private Solicitud existente(String id) {
        return solicitudes.buscar(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No existe la solicitud " + id));
    }

    private static ResponseStatusException yaResuelta(Solicitud solicitud) {
        return new ResponseStatusException(HttpStatus.CONFLICT,
                "La solicitud " + solicitud.id() + " ya no esta RECIBIDA (esta " + solicitud.estado() + ")");
    }

    /** Cantidades sumadas por libro: una solicitud podria repetir un titulo en dos lineas. */
    private static Map<String, Integer> porLibro(Solicitud solicitud) {
        Map<String, Integer> cantidades = new LinkedHashMap<>();
        solicitud.lineas().forEach(l -> cantidades.merge(l.libroId(), l.cantidad(), Integer::sum));
        return cantidades;
    }

    private static Instant ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
