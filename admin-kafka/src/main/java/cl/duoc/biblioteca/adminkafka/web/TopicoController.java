package cl.duoc.biblioteca.adminkafka.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.biblioteca.adminkafka.dto.AumentoParticiones;
import cl.duoc.biblioteca.adminkafka.dto.CambioConfiguracion;
import cl.duoc.biblioteca.adminkafka.dto.NombreTopico;
import cl.duoc.biblioteca.adminkafka.dto.NuevoTopico;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.DetalleTopico;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.ResumenTopico;
import cl.duoc.biblioteca.adminkafka.servicio.KafkaAdminService;
import jakarta.validation.Valid;

/**
 * Topicos.
 *
 *   GET    /api/admin/topicos[?internos=true]        200 todos, con particiones y replicas
 *   GET    /api/admin/topicos/{nombre}               200 particiones (lider, replicas, ISR,
 *                                                    offsets) y configuracion propia; 404
 *   POST   /api/admin/topicos                        201 + Location; 400 nombre, tamano o
 *                                                    configuracion invalidos; 409 ya existe
 *   PATCH  /api/admin/topicos/{nombre}/configuracion 200; 400 clave no permitida o valor
 *                                                    invalido; 404; 409 cleanup.policy de un
 *                                                    topico protegido
 *   POST   /api/admin/topicos/{nombre}/particiones   200; 404; 409 protegido o no aumenta
 *   DELETE /api/admin/topicos/{nombre}               204; 404; 409 protegido
 *
 * Solo EMPLEADO (SecurityConfig). Toda la logica esta en KafkaAdminService.
 */
@RestController
@RequestMapping({ "/api/admin/topicos", "/v1/api/admin/topicos" })
public class TopicoController {

    private final KafkaAdminService servicio;

    public TopicoController(KafkaAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<ResumenTopico> listar(@RequestParam(defaultValue = "false") boolean internos) {
        return servicio.listarTopicos(internos);
    }

    @GetMapping("/{nombre}")
    public DetalleTopico ver(@PathVariable @NombreTopico String nombre) {
        return servicio.verTopico(nombre);
    }

    @PostMapping
    public ResponseEntity<DetalleTopico> crear(@Valid @RequestBody NuevoTopico topico) {
        DetalleTopico creado = servicio.crearTopico(topico);
        return ResponseEntity.created(URI.create("/api/admin/topicos/" + creado.nombre())).body(creado);
    }

    @PatchMapping("/{nombre}/configuracion")
    public DetalleTopico configurar(@PathVariable @NombreTopico String nombre,
            @Valid @RequestBody CambioConfiguracion cambio) {
        return servicio.cambiarConfiguracion(nombre, cambio);
    }

    @PostMapping("/{nombre}/particiones")
    public DetalleTopico aumentarParticiones(@PathVariable @NombreTopico String nombre,
            @Valid @RequestBody AumentoParticiones pedido) {
        return servicio.aumentarParticiones(nombre, pedido);
    }

    @DeleteMapping("/{nombre}")
    public ResponseEntity<Void> eliminar(@PathVariable @NombreTopico String nombre) {
        servicio.eliminarTopico(nombre);
        return ResponseEntity.noContent().build();
    }
}
