package cl.duoc.biblioteca.solicitudes;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/gestion/bandeja-vivo -> las solicitudes pendientes y cuantas hay
 * por sede, segun Kafka (H9).
 *
 * La vista de gestion la consulta cada pocos segundos: si aparece una
 * solicitud nueva, la muestra sin que el empleado recargue. Responde desde
 * ProyeccionBandeja (grupo bandeja-gestion), sin tocar la base.
 *
 * Solo EMPLEADO, por la ruta (/api/gestion/** en SecurityConfig): un
 * estudiante recibe 403. En API Gateway entra por /api/gestion/{proxy+}.
 */
@RestController
@RequestMapping({ "/api/gestion/bandeja-vivo", "/v1/api/gestion/bandeja-vivo" })
public class BandejaVivoController {

    private final ProyeccionBandeja bandeja;

    public BandejaVivoController(ProyeccionBandeja bandeja) {
        this.bandeja = bandeja;
    }

    @GetMapping
    public ProyeccionBandeja.BandejaVivo ver() {
        return bandeja.instantanea();
    }
}
