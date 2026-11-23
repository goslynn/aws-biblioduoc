package cl.duoc.biblioteca.adminkafka.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonda de vida, sin autenticacion: la usa el healthcheck del contenedor en
 * aws/backend/compose.yaml. No revela nada.
 */
@RestController
public class SaludController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "estado", "ok",
                "servicio", "biblioteca-admin-kafka",
                "instante", Instant.now().toString());
    }
}
