package cl.duoc.biblioteca.admin.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.biblioteca.admin.dto.InfoCola;
import cl.duoc.biblioteca.admin.dto.NombreAmqp;
import cl.duoc.biblioteca.admin.dto.NuevaCola;
import cl.duoc.biblioteca.admin.servicio.RabbitAdminService;
import jakarta.validation.Valid;

/**
 * Colas.
 *
 *   POST   /api/admin/colas              201 + Location; 400 invalida; 404 DLX
 *                                        inexistente; 409 ya existe
 *   GET    /api/admin/colas/{nombre}     200 mensajes y consumidores; 404
 *   DELETE /api/admin/colas/{nombre}     204; 404; 409 protegida o con
 *          [?forzar=true]                mensajes (forzar=true la borra igual)
 *
 * Solo EMPLEADO (SecurityConfig). Toda la logica esta en RabbitAdminService.
 */
@RestController
@RequestMapping({ "/api/admin/colas", "/v1/api/admin/colas" })
public class ColaController {

    private final RabbitAdminService servicio;

    public ColaController(RabbitAdminService servicio) {
        this.servicio = servicio;
    }

    @PostMapping
    public ResponseEntity<InfoCola> crear(@Valid @RequestBody NuevaCola cola) {
        InfoCola creada = servicio.crearCola(cola);
        return ResponseEntity.created(URI.create("/api/admin/colas/" + creada.nombre())).body(creada);
    }

    @GetMapping("/{nombre}")
    public InfoCola ver(@PathVariable @NombreAmqp String nombre) {
        return servicio.verCola(nombre);
    }

    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> eliminar(@PathVariable @NombreAmqp String nombre,
            @RequestParam(defaultValue = "false") boolean forzar) {
        servicio.eliminarCola(nombre, forzar);
        return ResponseEntity.noContent().build();
    }
}
