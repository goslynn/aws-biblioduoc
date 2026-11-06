package cl.duoc.biblioteca.catalogo;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catalogo de libros: la parte de solo lectura de la plataforma.
 *
 * POR QUE DOS PREFIJOS EN @RequestMapping
 *   /api/libros      es la ruta historica, la que expone api.yaml recurso por
 *                    recurso con su scope por metodo.
 *   /v1/api/libros   es la ruta versionada. En API Gateway
 *                    /v1 es un unico recurso proxy, asi que el enrutado fino
 *                    lo hace Spring aqui mismo. Cuando exista un /v2 con un
 *                    contrato incompatible, sera OTRA clase controladora y las
 *                    dos convivirian sin romper a nadie.
 */
@RestController
@RequestMapping({ "/api/libros", "/v1/api/libros" })
public class LibroController {

    private final CatalogoLibros catalogo;

    public LibroController(CatalogoLibros catalogo) {
        this.catalogo = catalogo;
    }

    /**
     * GET /api/libros?q=&genero=&soloDisponibles=
     *
     * Sin parametros devuelve el catalogo completo; con ellos, el resultado de
     * la busqueda. Siempre 200, aunque el resultado sea una lista vacia: "no
     * encontre nada" no es un error del cliente.
     */
    @GetMapping
    public List<Libro> buscar(
            @RequestParam(name = "q", required = false) String q,
            @RequestParam(name = "genero", required = false) String genero,
            @RequestParam(name = "soloDisponibles", defaultValue = "false") boolean soloDisponibles) {
        return catalogo.buscar(q, genero, soloDisponibles);
    }

    /** GET /api/libros/generos -> los generos disponibles, para el filtro del frontend. */
    @GetMapping("/generos")
    public List<String> generos() {
        return catalogo.generos();
    }

    /**
     * GET /api/libros/{id}
     *
     * El 404 de aqui es IMPORTANTE para el laboratorio: la infraestructura no
     * lo puede producir (API Gateway responde 403 a una ruta que no conoce),
     * asi que recibirlo demuestra que la peticion atraveso el authorizer, el
     * VPC Link, el NLB y Spring, y llego hasta este metodo.
     */
    @GetMapping("/{id}")
    public Libro porId(@PathVariable String id) {
        return catalogo.porId(id).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No existe el libro " + id));
    }
}
