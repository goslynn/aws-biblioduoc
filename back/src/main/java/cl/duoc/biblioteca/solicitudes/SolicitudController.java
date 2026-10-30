package cl.duoc.biblioteca.solicitudes;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
import cl.duoc.biblioteca.config.Roles;
import jakarta.validation.Valid;

/**
 * Solicitudes de prestamo, vistas por el ESTUDIANTE: crear y consultar las
 * propias. Aprobar, rechazar y devolver es cosa de empleados y vive en
 * {@link GestionSolicitudController}, bajo /api/gestion.
 *
 * El POST demuestra la cadena completa de seguridad: el token tiene que pasar
 * por el authorizer de API Gateway, por Spring Security y recien entonces
 * llega a este metodo, que ademas LEE la identidad desde ese mismo token.
 */
@RestController
@RequestMapping({ "/api/solicitudes", "/v1/api/solicitudes" })
public class SolicitudController {

    private final RepositorioSolicitudes solicitudes;
    private final ServicioPrestamos prestamos;
    private final CatalogoLibros catalogo;

    public SolicitudController(RepositorioSolicitudes solicitudes, ServicioPrestamos prestamos,
            CatalogoLibros catalogo) {
        this.solicitudes = solicitudes;
        this.prestamos = prestamos;
        this.catalogo = catalogo;
    }

    /**
     * POST /api/solicitudes -> 201 con la solicitud creada.
     *
     * Codigos que puede devolver, y quien los produce:
     *   201  la solicitud se creo (y el evento
     *        solicitud.creada queda en la bandeja
     *        de salida: el comprobante por correo
     *        llega despues, sin hacer esperar)   -> este metodo
     *   400  el formulario no valida            -> Bean Validation (@Valid)
     *   404  algun libroId no existe            -> este metodo
     *   409  algun libro no tiene ejemplares    -> este metodo
     *        (es un aviso temprano: el stock se
     *        descuenta recien al APROBAR)
     *   401  sin token, token invalido, o token
     *        valido SIN el scope exigido        -> API Gateway / Spring Security
     *   403  token valido SIN el scope, pero
     *        solo llamando a la EC2 sin pasar
     *        por el gateway                     -> Spring Security (hasAuthority)
     *
     * EL SOLICITANTE NO VIENE DEL FORMULARIO. Sale de {@code Principal}, que
     * Spring rellena con el JWT ya validado (claim "sub" para un usuario,
     * "client_id" para un cliente maquina a maquina). Es la prueba, dentro del
     * codigo, de que la identidad llego intacta hasta el backend.
     */
    @PostMapping
    public ResponseEntity<Solicitud> crear(@Valid @RequestBody NuevaSolicitud formulario, Authentication principal) {
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

        Solicitud creada = prestamos.crear(principal.getName(), formulario, lineas);
        return ResponseEntity
                .created(URI.create("/api/solicitudes/" + creada.id()))
                .body(creada);
    }

    /**
     * GET /api/solicitudes -> las del portador del token, y SOLO esas.
     *
     * Antes, sin parametros devolvia todas: cualquier estudiante veia las
     * solicitudes de los demas. Con RBAC, la bandeja completa es de empleados
     * (GET /api/gestion/solicitudes). {@code mias} se sigue aceptando para no
     * romper clientes viejos, pero ya no cambia nada.
     */
    @GetMapping
    public List<Solicitud> listar(
            @RequestParam(name = "mias", defaultValue = "true") boolean mias,
            Authentication principal) {
        return solicitudes.deSolicitante(principal.getName());
    }

    /**
     * GET /api/solicitudes/{id} -> 200 si es del portador o si este es
     * empleado. Una solicitud AJENA responde 404, no 403: un 403 confirmaria
     * que ese id existe, y los ids son correlativos faciles de recorrer.
     */
    @GetMapping("/{id}")
    public Solicitud porId(@PathVariable String id, Authentication principal) {
        return solicitudes.buscar(id)
                .filter(s -> s.solicitante().equals(principal.getName()) || Roles.esEmpleado(principal))
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No existe la solicitud " + id));
    }
}
