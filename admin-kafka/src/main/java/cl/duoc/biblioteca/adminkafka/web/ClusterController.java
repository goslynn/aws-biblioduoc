package cl.duoc.biblioteca.adminkafka.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import cl.duoc.biblioteca.adminkafka.dto.Vistas.InfoCluster;
import cl.duoc.biblioteca.adminkafka.servicio.KafkaAdminService;

/**
 * GET /api/admin/cluster -> id del cluster, broker controlador y los brokers
 * vivos. Con un broker caido, aparecen dos: es la forma mas rapida de verlo.
 */
@RestController
@RequestMapping({ "/api/admin/cluster", "/v1/api/admin/cluster" })
public class ClusterController {

    private final KafkaAdminService servicio;

    public ClusterController(KafkaAdminService servicio) {
        this.servicio = servicio;
    }

    @GetMapping
    public InfoCluster ver() {
        return servicio.cluster();
    }
}
