package cl.duoc.biblioteca.bitacora;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * GET /api/gestion/bitacora?limite=50 -> los ultimos eventos, del mas nuevo al
 * mas viejo: quien aprobo, rechazo, devolvio o cargo stock, y cuando.
 * Solo empleados (lo exige SecurityConfig por la ruta).
 */
@RestController
@RequestMapping({ "/api/gestion/bitacora", "/v1/api/gestion/bitacora" })
public class BitacoraController {

    private final Bitacora bitacora;

    public BitacoraController(Bitacora bitacora) {
        this.bitacora = bitacora;
    }

    @GetMapping
    public List<Bitacora.Entrada> ultimas(@RequestParam(name = "limite", defaultValue = "50") int limite) {
        return bitacora.ultimas(Math.clamp(limite, 1, 200));
    }
}
