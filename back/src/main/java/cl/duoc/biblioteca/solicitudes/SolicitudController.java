package cl.duoc.biblioteca.solicitudes;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.biblioteca.comun.errores.RecursoNoEncontrado;
import cl.duoc.biblioteca.comun.seguridad.Roles;
import jakarta.validation.Valid;

/**
 * Solicitudes de prestamo, vistas por el ESTUDIANTE: crear y consultar las
 * propias. Crear es SOLO de estudiantes (un empleado recibe 403, regla en
 * SecurityConfig). Aprobar, rechazar y devolver es cosa de empleados y vive en
 * {@link GestionSolicitudController}, bajo /api/gestion.
 *
 * El POST demuestra la cadena completa de seguridad: el token tiene que pasar
 * por el authorizer de API Gateway, por Spring Security y recien entonces
 * llega a este metodo, que ademas LEE la identidad desde ese mismo token.
 */
@RestController
@RequestMapping({ "/api/solicitudes", "/v1/api/solicitudes" })
public class SolicitudController {

    private final ListadoSolicitudes listado;
    private final RepositorioSolicitudes solicitudes;
    private final ServicioPrestamos prestamos;

    public SolicitudController(ListadoSolicitudes listado, RepositorioSolicitudes solicitudes,
            ServicioPrestamos prestamos) {
        this.listado = listado;
        this.solicitudes = solicitudes;
        this.prestamos = prestamos;
    }

    /**
     * POST /api/solicitudes -> 201 con la solicitud creada.
     *
     * Codigos que puede devolver, y quien los produce:
     *   201  la solicitud se creo (y el evento
     *        solicitud.creada queda en la bandeja
     *        de salida: el comprobante por correo
     *        llega despues, sin hacer esperar)   -> ServicioPrestamos
     *   400  el formulario no valida            -> Bean Validation (@Valid)
     *   404  algun libroId no existe            -> ServicioPrestamos
     *   409  algun libro no tiene ejemplares    -> ServicioPrestamos
     *        (es un aviso temprano: el stock se
     *        descuenta recien al APROBAR)
     *   401  sin token, token invalido, o token
     *        valido SIN el scope exigido        -> API Gateway / Spring Security
     *   403  token valido SIN el scope, pero
     *        solo llamando a la EC2 sin pasar
     *        por el gateway                     -> Spring Security (hasAuthority)
     *   403  el portador es EMPLEADO (grupo
     *        empleados o backoffice): los
     *        empleados gestionan, no piden       -> Spring Security (SecurityConfig)
     *
     * EL SOLICITANTE NO VIENE DEL FORMULARIO. Sale de {@code Principal}, que
     * Spring rellena con el JWT ya validado (claim "sub" para un usuario,
     * "client_id" para un cliente maquina a maquina). Es la prueba, dentro del
     * codigo, de que la identidad llego intacta hasta el backend.
     */
    @PostMapping
    public ResponseEntity<Solicitud> crear(@Valid @RequestBody NuevaSolicitud formulario, Authentication principal) {
        Solicitud creada = prestamos.crear(principal.getName(), formulario);
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
        return listado.deSolicitante(principal.getName());
    }

    /**
     * GET /api/solicitudes/{id} -> 200 si es del portador o si este es
     * empleado. Una solicitud AJENA responde 404, no 403: un 403 confirmaria
     * que ese id existe, y los ids son correlativos faciles de recorrer.
     */
    @GetMapping("/{id}")
    public Solicitud porId(@PathVariable String id, Authentication principal) {
        return solicitudes.findById(id)
                .filter(s -> s.solicitante().equals(principal.getName()) || Roles.esEmpleado(principal))
                .orElseThrow(() -> new RecursoNoEncontrado("No existe la solicitud " + id));
    }
}
