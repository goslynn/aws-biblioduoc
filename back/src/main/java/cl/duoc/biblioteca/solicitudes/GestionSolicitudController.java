package cl.duoc.biblioteca.solicitudes;

import java.security.Principal;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * La bandeja del EMPLEADO: ver todas las solicitudes y resolverlas.
 *
 * Que solo entre un empleado lo decide SecurityConfig por la ruta
 * (/api/gestion/** exige ROLE_EMPLEADO), no este controlador: asi una ruta
 * nueva bajo /gestion nace protegida aunque alguien olvide anotarla.
 *
 * Cada accion responde 200 con la solicitud ya cambiada, y su correo al
 * estudiante sale despues, por RabbitMQ. Errores:
 *   400  rechazo sin motivo                          -> Bean Validation
 *   403  el token no es de empleado                  -> Spring Security
 *   404  la solicitud no existe                      -> ServicioPrestamos
 *   409  no esta en el estado de origen, o al
 *        aprobar ya no queda stock                   -> ServicioPrestamos
 */
@RestController
@RequestMapping({ "/api/gestion/solicitudes", "/v1/api/gestion/solicitudes" })
public class GestionSolicitudController {

    /** Cuerpo de POST .../rechazo. */
    public record Rechazo(
            @NotBlank(message = "hay que explicar al estudiante por que se rechaza")
            @Size(max = 300, message = "el motivo no puede superar 300 caracteres")
            String motivo) {
    }

    private final RepositorioSolicitudes solicitudes;
    private final ServicioPrestamos prestamos;

    public GestionSolicitudController(RepositorioSolicitudes solicitudes, ServicioPrestamos prestamos) {
        this.solicitudes = solicitudes;
        this.prestamos = prestamos;
    }

    /** GET /api/gestion/solicitudes?estado= -> todas, o las de un estado. */
    @GetMapping
    public List<Solicitud> listar(@RequestParam(name = "estado", required = false) EstadoSolicitud estado) {
        return estado == null ? solicitudes.todas() : solicitudes.porEstado(estado);
    }

    @PostMapping("/{id}/aprobacion")
    public Solicitud aprobar(@PathVariable String id, Principal empleado) {
        return prestamos.aprobar(id, empleado.getName());
    }

    @PostMapping("/{id}/rechazo")
    public Solicitud rechazar(@PathVariable String id, @Valid @RequestBody Rechazo rechazo, Principal empleado) {
        return prestamos.rechazar(id, empleado.getName(), rechazo.motivo());
    }

    @PostMapping("/{id}/devolucion")
    public Solicitud devolver(@PathVariable String id, Principal empleado) {
        return prestamos.devolver(id, empleado.getName());
    }
}
