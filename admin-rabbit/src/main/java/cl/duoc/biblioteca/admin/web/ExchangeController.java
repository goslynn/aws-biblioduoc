package cl.duoc.biblioteca.admin.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.biblioteca.admin.dto.NombreAmqp;
import cl.duoc.biblioteca.admin.dto.NuevoExchange;
import cl.duoc.biblioteca.admin.servicio.RabbitAdminService;
import jakarta.validation.Valid;

/**
 * Exchanges.
 *
 *   POST   /api/admin/exchanges            201; 400 invalido; 409 ya existe
 *   DELETE /api/admin/exchanges/{nombre}   204; 404; 409 protegido
 *
 * Solo EMPLEADO (SecurityConfig). Toda la logica esta en RabbitAdminService.
 */
@RestController
@RequestMapping({ "/api/admin/exchanges", "/v1/api/admin/exchanges" })
public class ExchangeController {

    private final RabbitAdminService servicio;

    public ExchangeController(RabbitAdminService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<NuevoExchange> crear(@Valid @RequestBody NuevoExchange exchange) {
        servicio.crearExchange(exchange);
        return ResponseEntity.created(URI.create("/api/admin/exchanges/" + exchange.nombre())).body(exchange);
    }

    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> eliminar(@PathVariable @NombreAmqp String nombre) {
        servicio.eliminarExchange(nombre);
        return ResponseEntity.noContent().build();
    }
}
