package cl.duoc.biblioteca.adminkafka.web;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.biblioteca.adminkafka.dto.IdGrupo;
import cl.duoc.biblioteca.adminkafka.dto.ReinicioOffsets;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.DetalleGrupo;
import cl.duoc.biblioteca.adminkafka.dto.Vistas.ResumenGrupo;
import cl.duoc.biblioteca.adminkafka.servicio.KafkaAdminService;
import jakarta.validation.Valid;

/**
 * Consumer groups: la supervision del consumo.
 *
 *   GET    /api/admin/grupos                 200 id, estado (STABLE, EMPTY...) y tipo
 *   GET    /api/admin/grupos/{id}            200 miembros con sus particiones, offset
 *                                            confirmado, fin y LAG por particion; 404
 *   POST   /api/admin/grupos/{id}/offsets    200 reinicio a INICIO, FIN o FECHA; 400; 404;
 *                                            409 si el grupo tiene consumidores activos
 *   DELETE /api/admin/grupos/{id}            204; 404; 409 con consumidores activos
 *
 * Solo EMPLEADO (SecurityConfig).
 */
@RestController
@RequestMapping({ "/api/admin/grupos", "/v1/api/admin/grupos" })
public class GrupoController {

    private final KafkaAdminService servicio;

    public GrupoController(KafkaAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public List<ResumenGrupo> listar() {
        return servicio.listarGrupos();
    }

    @GetMapping("/{id}")
    public DetalleGrupo ver(@PathVariable @IdGrupo String id) {
        return servicio.verGrupo(id);
    }

    @PostMapping("/{id}/offsets")
    public DetalleGrupo reiniciarOffsets(@PathVariable @IdGrupo String id,
            @Valid @RequestBody ReinicioOffsets pedido) {
        return servicio.reiniciarOffsets(id, pedido);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> eliminar(@PathVariable @IdGrupo String id) {
        servicio.eliminarGrupo(id);
        return ResponseEntity.noContent().build();
    }
}
