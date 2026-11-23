package cl.duoc.biblioteca.catalogo;

import java.security.Principal;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Avisame cuando vuelva": suscribirse a un libro agotado.
 *
 * El aviso en si no sale de aqui. Lo dispara una carga de stock o una
 * devolucion que deja al libro con ejemplares (evento stock.repuesto), y lo
 * manda ConsumidorDisponibilidad por la cola de correo masivo.
 *
 * Codigos:
 *   201  suscrito (tambien si ya lo estaba: es idempotente)
 *   204  baja hecha (tambien si no estaba suscrito)
 *   404  el libro no existe
 *   409  el libro TIENE ejemplares: no hay nada que esperar, se pide directamente
 *
 * Las reglas viven en ServicioAvisame; aqui solo se traduce HTTP.
 */
@RestController
@RequestMapping({ "/api/libros", "/v1/api/libros" })
public class AvisameController {

    private final ServicioAvisame avisame;

    public AvisameController(ServicioAvisame avisame) {
        this.avisame = avisame;
    }

    /** GET /api/libros/avisame -> ids de los libros que espera el portador del token. */
    @GetMapping("/avisame")
    public List<String> mios(Principal principal) {
        return avisame.librosQueEspera(principal.getName());
    }

    @PostMapping("/{id}/avisame")
    public ResponseEntity<Void> suscribir(@PathVariable String id, Principal principal) {
        avisame.suscribir(id, principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/avisame")
    public ResponseEntity<Void> cancelar(@PathVariable String id, Principal principal) {
        avisame.cancelar(id, principal.getName());
        return ResponseEntity.noContent().build();
    }
}
