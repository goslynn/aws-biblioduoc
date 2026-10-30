package cl.duoc.biblioteca.catalogo;

import java.security.Principal;

import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Carga de stock. Solo EMPLEADO: lo exige SecurityConfig por la ruta
 * /api/gestion/**, no este controlador.
 *
 * Codigos: 200 con el libro actualizado; 400 cantidad fuera de rango; 404 el
 * libro no existe; 403 sin rol de empleado.
 */
@RestController
@RequestMapping({ "/api/gestion/libros", "/v1/api/gestion/libros" })
public class GestionStockController {

    /** Cuerpo de POST .../stock. */
    public record CargaStock(
            @Min(value = 1, message = "hay que cargar al menos 1 ejemplar")
            @Max(value = 100, message = "no se cargan mas de 100 ejemplares de una vez")
            int cantidad) {
    }

    private final ServicioInventario inventario;

    public GestionStockController(ServicioInventario inventario) {
        this.inventario = inventario;
    }

    @PostMapping("/{id}/stock")
    public Libro cargar(@PathVariable String id, @Valid @RequestBody CargaStock carga, Principal principal) {
        return inventario.cargar(id, carga.cantidad(), principal.getName());
    }
}
