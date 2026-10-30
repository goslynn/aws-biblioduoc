package cl.duoc.biblioteca.web;

import java.time.Instant;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sonda de vida. Es el UNICO endpoint sin autenticacion.
 *
 * POR QUE EXISTE
 *   Es el health check del NLB (aws/backend.yaml): el balanceador solo manda
 *   trafico a la EC2 mientras esta ruta responde 2xx. Con Spring Security
 *   activo, cualquier ruta de /api responderia 401 a esa sonda y el NLB daria
 *   la aplicacion por caida.
 *
 * POR QUE NO ES UN AGUJERO
 *   No se publica en API Gateway: api.yaml no la enruta (todo va bajo /api y
 *   /v1/api), asi que desde Internet no existe. Solo es alcanzable desde
 *   dentro de la VPC. Ademas no revela nada: ni versiones, ni configuracion,
 *   ni datos.
 */
@RestController
public class SaludController {

    @GetMapping("/health")
    public Map<String, Object> health() {
        return Map.of(
                "estado", "ok",
                "servicio", "biblioteca-backend",
                "instante", Instant.now().toString());
    }
}
