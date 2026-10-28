package cl.duoc.biblioteca.solicitudes;

import java.net.URI;
import java.security.Principal;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cl.duoc.biblioteca.catalogo.CatalogoLibros;
import cl.duoc.biblioteca.catalogo.Libro;
import jakarta.validation.Valid;

/**
 * Solicitudes de prestamo: la parte de escritura de la plataforma.
 *
 * Aqui esta el unico endpoint que MODIFICA estado (POST), y por eso es el que
 * mejor demuestra la cadena completa de seguridad: el token tiene que pasar
 * por el authorizer de API Gateway, por Spring Security y recien entonces
 * llega a este metodo, que ademas LEE la identidad desde ese mismo token.
 */
@RestController
@RequestMapping({ "/api/solicitudes", "/v1/api/solicitudes" })
public class SolicitudController {

    private final RepositorioSolicitudes solicitudes;
    private final CatalogoLibros catalogo;

    public SolicitudController(RepositorioSolicitudes solicitudes, CatalogoLibros catalogo) {
        this.solicitudes = solicitudes;
        this.catalogo = catalogo;
    }

    /**
     * POST /api/solicitudes -> 201 con la solicitud creada.
     *
     * Codigos que puede devolver, y quien los produce:
     *   201  la solicitud se creo               -> este metodo
     *   400  el formulario no valida            -> Bean Validation (@Valid)
     *   404  algun libroId no existe            -> este metodo
     *   409  algun libro no tiene ejemplares    -> este metodo
     *   401  sin token, token invalido, o token
     *        valido SIN el scope exigido        -> API Gateway / Spring Security
     *   403  token valido SIN el scope, pero
     *        solo invocando la Lambda directa   -> Spring Security (hasAuthority)
     *
     * EL SOLICITANTE NO VIENE DEL FORMULARIO. Sale de {@code Principal}, que
     * Spring rellena con el JWT ya validado (claim "sub" para un usuario,
     * "client_id" para un cliente maquina a maquina). Es la prueba, dentro del
     * codigo, de que la identidad llego intacta hasta el backend.
     */
    @PostMapping
    public ResponseEntity<Solicitud> crear(@Valid @RequestBody NuevaSolicitud formulario, Principal principal) {
        List<LineaSolicitud> lineas = new ArrayList<>();

        for (ItemSolicitado item : formulario.items()) {
            Libro libro = catalogo.porId(item.libroId())
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                            "No existe el libro " + item.libroId()));

            if (!libro.disponible()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Sin ejemplares disponibles de " + libro.titulo() + " (" + libro.id() + ")");
            }
            if (item.cantidad() > libro.ejemplares()) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Solo quedan " + libro.ejemplares() + " ejemplares de " + libro.titulo());
            }

            lineas.add(new LineaSolicitud(libro.id(), libro.titulo(), libro.autor(), item.cantidad()));
        }

        Solicitud creada = solicitudes.crear(nombreDe(principal), formulario, lineas);
        return ResponseEntity
                .created(URI.create("/api/solicitudes/" + creada.id()))
                .body(creada);
    }

    /**
     * GET /api/solicitudes?estado=&mias=
     *
     * Sin parametros devuelve todas las solicitudes guardadas (la
     * bandeja del equipo de biblioteca). Con {@code mias=true} devuelve solo
     * las del portador del token, que es lo que consume la vista
     * "mis solicitudes" del frontend.
     */
    @GetMapping
    public List<Solicitud> listar(
            @RequestParam(name = "estado", required = false) EstadoSolicitud estado,
            @RequestParam(name = "mias", defaultValue = "false") boolean mias,
            Principal principal) {

        if (mias) {
            return solicitudes.deSolicitante(nombreDe(principal));
        }
        return estado == null ? solicitudes.todas() : solicitudes.porEstado(estado);
    }

    /** GET /api/solicitudes/{id} -> 200, o 404 generado por Spring si no existe. */
    @GetMapping("/{id}")
    public Solicitud porId(@PathVariable String id) {
        return solicitudes.buscar(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No existe la solicitud " + id));
    }

    /**
     * Identidad del llamante.
     *
     * Antes de la Fase 12 (cuando Spring Security todavia no esta configurado)
     * el Principal es null aunque la peticion traiga un token: en ese momento
     * el unico que lo valida es API Gateway y Spring ni lo mira. Devolver
     * "anonimo" deja esa diferencia a la vista en lugar de esconderla.
     */
    private static String nombreDe(Principal principal) {
        return principal == null ? "anonimo" : principal.getName();
    }
}
