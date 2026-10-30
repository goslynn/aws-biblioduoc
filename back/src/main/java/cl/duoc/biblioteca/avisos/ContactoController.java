package cl.duoc.biblioteca.avisos;

import java.security.Principal;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import cl.duoc.biblioteca.eventos.BandejaDeSalida;
import cl.duoc.biblioteca.eventos.TiposEvento;

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
 * porque Cognito lo exigio al registrarse.
 */
@RestController
@RequestMapping({ "/api/avisos/correo", "/v1/api/avisos/correo" })
public class ContactoController {

    public record EstadoAvisos(boolean activo, String email) {
    }

    private final ContactosAviso contactos;
    private final DirectorioUsuarios directorio;
    private final BandejaDeSalida bandeja;

    public ContactoController(ContactosAviso contactos, DirectorioUsuarios directorio, BandejaDeSalida bandeja) {
        this.contactos = contactos;
        this.directorio = directorio;
        this.bandeja = bandeja;
    }

    @GetMapping
    public EstadoAvisos estado(Principal principal) {
        return contactos.correoDe(principal.getName())
                .map(email -> new EstadoAvisos(true, email))
                .orElse(new EstadoAvisos(false, null));
    }

    @PutMapping
    @Transactional
    public EstadoAvisos activar(Principal principal) {
        String sub = principal.getName();
        String email = directorio.correoVerificado(sub).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.CONFLICT, "Esta cuenta no tiene un email verificado en Cognito"));

        boolean nuevo = contactos.correoDe(sub).isEmpty();
        contactos.guardar(sub, email);
        if (nuevo) {
            bandeja.registrar(TiposEvento.AVISO_ACTIVADO, sub, "AVISOS", Map.of("email", email));
        }
        return new EstadoAvisos(true, email);
    }

    @DeleteMapping
    public ResponseEntity<Void> desactivar(Principal principal) {
        contactos.borrar(principal.getName());
        return ResponseEntity.noContent().build();
    }
}
