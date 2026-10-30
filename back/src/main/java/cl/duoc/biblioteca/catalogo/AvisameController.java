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
import org.springframework.web.server.ResponseStatusException;

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
 */
@RestController
@RequestMapping({ "/api/libros", "/v1/api/libros" })
public class AvisameController {

    private final CatalogoLibros catalogo;
    private final SuscripcionesLibro suscripciones;

    public AvisameController(CatalogoLibros catalogo, SuscripcionesLibro suscripciones) {
        this.catalogo = catalogo;
        this.suscripciones = suscripciones;
    }

    /** GET /api/libros/avisame -> ids de los libros que espera el portador del token. */
    @GetMapping("/avisame")
    public List<String> mios(Principal principal) {
        return suscripciones.deSolicitante(principal.getName());
    }

    @PostMapping("/{id}/avisame")
    public ResponseEntity<Void> suscribir(@PathVariable String id, Principal principal) {
        Libro libro = existente(id);
        if (libro.disponible()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    libro.titulo() + " tiene ejemplares disponibles: pidelo directamente");
        }
        suscripciones.suscribir(libro.id(), principal.getName());
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/avisame")
    public ResponseEntity<Void> cancelar(@PathVariable String id, Principal principal) {
        suscripciones.cancelar(existente(id).id(), principal.getName());
        return ResponseEntity.noContent().build();
    }

    private Libro existente(String id) {
        return catalogo.porId(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No existe el libro " + id));
    }
}
