package cl.duoc.biblioteca.solicitudes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.biblioteca.catalogo.CatalogoLibros;
import cl.duoc.biblioteca.catalogo.Libro;
import cl.duoc.biblioteca.catalogo.ServicioInventario;
import cl.duoc.biblioteca.comun.errores.ConflictoDeNegocio;
import cl.duoc.biblioteca.comun.errores.RecursoNoEncontrado;
import cl.duoc.biblioteca.eventos.BandejaDeSalida;
import cl.duoc.biblioteca.eventos.TiposEvento;
import cl.duoc.biblioteca.streaming.BandejaKafka;
import cl.duoc.biblioteca.streaming.CambioStock.MotivoStock;

/**
 * El ciclo de vida de un prestamo, con su evento.
 *
 * Cada metodo publico es UNA transaccion que hace el cambio de negocio y deja
 * el evento en la bandeja de salida (ver BandejaDeSalida). Si algo falla a
 * mitad —por ejemplo, el segundo libro de la solicitud ya no tiene stock— el
 * rollback deshace tambien el descuento del primero y el evento: no queda un
 * correo de "aprobada" de una solicitud que no se aprobo.
 *
 * Los errores son de dominio (RecursoNoEncontrado = 404, ConflictoDeNegocio
 * = 409): este servicio no sabe de HTTP.
 */
@Service
public class ServicioPrestamos {

    private final RepositorioSolicitudes solicitudes;
    private final CatalogoLibros catalogo;
    private final ServicioInventario inventario;
    private final BandejaDeSalida bandeja;
    private final BandejaKafka streaming;

    public ServicioPrestamos(RepositorioSolicitudes solicitudes, CatalogoLibros catalogo,
            ServicioInventario inventario, BandejaDeSalida bandeja, BandejaKafka streaming) {
        this.solicitudes = solicitudes;
        this.catalogo = catalogo;
        this.inventario = inventario;
        this.bandeja = bandeja;
        this.streaming = streaming;
    }

    /**
     * Crea la solicitud de un estudiante a partir del formulario ya validado.
     *
     * Cada item se resuelve contra el catalogo (404 si el libro no existe) y
     * se comprueba que haya ejemplares (409 si no). Ese 409 es un aviso
     * temprano: el stock se descuenta recien al APROBAR, porque entre medio
     * otro estudiante puede llevarse el ultimo.
     */
    @Transactional
    public Solicitud crear(String solicitante, NuevaSolicitud formulario) {
        List<LineaSolicitud> lineas = new ArrayList<>();
        for (ItemSolicitado item : formulario.items()) {
            Libro libro = catalogo.existente(item.libroId());
            if (!libro.disponible()) {
                throw new ConflictoDeNegocio(
                        "Sin ejemplares disponibles de " + libro.titulo() + " (" + libro.id() + ")");
            }
            if (item.cantidad() > libro.ejemplares()) {
                throw new ConflictoDeNegocio("Solo quedan " + libro.ejemplares() + " ejemplares de " + libro.titulo());
            }
            lineas.add(new LineaSolicitud(libro.id(), libro.titulo(), libro.autor(), item.cantidad()));
        }

        Solicitud creada = solicitudes.save(Solicitud.recibida(solicitante, lineas, formulario, ahora()));
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
        porLibro(solicitud).forEach((libroId, cantidad) -> inventario.descontar(libroId, cantidad, "aprobar " + id));
        return publicar(TiposEvento.SOLICITUD_APROBADA, empleado, existente(id));
    }

    /** RECIBIDA -> RECHAZADA. 404 si no existe; 409 si ya no esta RECIBIDA. */
    @Transactional
    public Solicitud rechazar(String id, String empleado, String motivo) {
        Solicitud solicitud = existente(id);
        if (!solicitudes.rechazar(id, empleado, motivo.strip(), ahora())) {
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
            throw new ConflictoDeNegocio(
                    "Solo se devuelve una solicitud APROBADA; " + id + " esta " + solicitud.estado());
        }
        porLibro(solicitud).forEach((libroId, cantidad) ->
                // Si el libro se hubiera retirado del catalogo, no hay a donde reponer.
                catalogo.porId(libroId).ifPresent(libro ->
                        inventario.reponer(libro, cantidad, MotivoStock.DEVOLUCION)));
        return publicar(TiposEvento.SOLICITUD_DEVUELTA, empleado, existente(id));
    }

    /** La solicitud, o 404. */
    public Solicitud existente(String id) {
        return solicitudes.findById(id).orElseThrow(() -> new RecursoNoEncontrado("No existe la solicitud " + id));
    }

    /**
     * Deja los DOS avisos del cambio, en la misma transaccion: el evento para
     * RabbitMQ (correos, bitacora) y el hecho para Kafka (bandeja en vivo, H9).
     */
    private Solicitud publicar(String tipo, String actor, Solicitud solicitud) {
        bandeja.registrar(tipo, actor, solicitud.id(), Map.of("solicitud", solicitud));
        streaming.hechoSolicitud(solicitud);
        return solicitud;
    }

    private static ConflictoDeNegocio yaResuelta(Solicitud solicitud) {
        return new ConflictoDeNegocio(
                "La solicitud " + solicitud.id() + " ya no esta RECIBIDA (esta " + solicitud.estado() + ")");
    }

    /**
     * Cantidades por libro, en orden de id.
     *
     * EL ORDEN EVITA UN INTERBLOQUEO. Cada UPDATE de stock bloquea la fila del
     * libro hasta el commit. Si una aprobacion toca L-001 y luego L-002, y otra
     * a la vez L-002 y luego L-001, cada una espera a la otra y Postgres aborta
     * una con un error de deadlock (un 500). Con todas las transacciones
     * bloqueando en el mismo orden, la segunda simplemente espera.
     */
    private static Map<String, Integer> porLibro(Solicitud solicitud) {
        Map<String, Integer> cantidades = new TreeMap<>();
        solicitud.lineas().forEach(l -> cantidades.merge(l.libroId(), l.cantidad(), Integer::sum));
        return cantidades;
    }

    private static Instant ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
