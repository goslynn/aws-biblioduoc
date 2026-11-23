package cl.duoc.biblioteca.avisos;

import java.security.Principal;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Activar y desactivar los avisos por correo: el consentimiento.
 *
 * GET    /api/avisos/correo  -> {activo, email}
 * PUT    /api/avisos/correo  -> 200 activados (y correo de bienvenida)
 *                               409 si Cognito no tiene un email verificado
 *                                   para este token (por ejemplo, un cliente M2M)
 * DELETE /api/avisos/correo  -> 204 desactivados
 *
 * EL PUT NO TIENE CUERPO A PROPOSITO. La direccion sale de Cognito por el sub
 * del token (DirectorioUsuarios), no del cliente: asi nadie puede activar
 * avisos hacia el correo de otra persona, y el correo ya esta verificado
 * porque Cognito lo exigio al registrarse. Las reglas viven en ServicioAvisos.
 *
 * 503 si Cognito no responde (DirectorioCognito).
 */
@RestController
@RequestMapping({ "/api/avisos/correo", "/v1/api/avisos/correo" })
public class ContactoController {

    public record EstadoAvisos(boolean activo, String email) {
    }

    private final ServicioAvisos avisos;

    public ContactoController(ServicioAvisos avisos) {
        this.avisos = avisos;
    }

    @GetMapping
    public EstadoAvisos estado(Principal principal) {
        return avisos.correoDe(principal.getName())
                .map(email -> new EstadoAvisos(true, email))
                .orElse(new EstadoAvisos(false, null));
    }

    @PutMapping
    public EstadoAvisos activar(Principal principal) {
        return new EstadoAvisos(true, avisos.activar(principal.getName()));
    }

    @DeleteMapping
    public ResponseEntity<Void> desactivar(Principal principal) {
        avisos.desactivar(principal.getName());
        return ResponseEntity.noContent().build();
    }
}
