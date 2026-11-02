package cl.duoc.biblioteca.admin.web;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.biblioteca.admin.dto.NuevoBinding;
import cl.duoc.biblioteca.admin.servicio.RabbitAdminService;
import jakarta.validation.Valid;

/**
 * Bindings. Un binding no tiene nombre: lo identifican exchange, cola y
 * routingKey. Por eso el DELETE lleva esas tres partes como parametros de la
 * URL (un DELETE con cuerpo no es fiable a traves de proxies).
 *
 *   POST   /api/admin/bindings                                   201; 400; 404
 *          {exchange, cola, routingKey}                          exchange o cola
 *   DELETE /api/admin/bindings?exchange=..&cola=..&routingKey=.. 204; 400; 404;
 *                                                                409 cola protegida
 *
 * Solo EMPLEADO (SecurityConfig). Toda la logica esta en RabbitAdminService.
 */
@RestController
@RequestMapping({ "/api/admin/bindings", "/v1/api/admin/bindings" })
public class BindingController {

    private final RabbitAdminService servicio;

    public BindingController(RabbitAdminService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<NuevoBinding> crear(@Valid @RequestBody NuevoBinding binding) {
        servicio.crearBinding(binding);
        return ResponseEntity.status(HttpStatus.CREATED).body(binding);
    }

    @DeleteMapping
    public ResponseEntity<Void> eliminar(@Valid @ModelAttribute NuevoBinding binding) {
        servicio.eliminarBinding(binding);
        return ResponseEntity.noContent().build();
    }
}
