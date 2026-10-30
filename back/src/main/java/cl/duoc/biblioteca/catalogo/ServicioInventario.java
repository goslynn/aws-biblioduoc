package cl.duoc.biblioteca.catalogo;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import cl.duoc.biblioteca.eventos.BandejaDeSalida;
import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.eventos.TiposEvento;

/**
 * Entradas de stock: la carga que hace un empleado y la reposicion por
 * devolucion. Las dos pueden disparar los "avisame".
 *
 * La regla que importa: se avisa cuando un libro PASA DE 0 A TENER ALGUNO, no
 * cada vez que entra un ejemplar. Si ya habia 3 y llegan 2, nadie estaba
 * esperando (el boton "avisame" solo aparece con 0).
 */
@Service
public class ServicioInventario {

    private final CatalogoLibros catalogo;
    private final BandejaDeSalida bandeja;

    public ServicioInventario(CatalogoLibros catalogo, BandejaDeSalida bandeja) {
        this.catalogo = catalogo;
        this.bandeja = bandeja;
    }

    /** Carga de stock de un empleado o del backoffice: deja rastro en la bitacora. */
    @Transactional
    public Libro cargar(String libroId, int cantidad, String actor) {
        Libro libro = catalogo.porId(libroId).orElseThrow(() -> new ResponseStatusException(
                HttpStatus.NOT_FOUND, "No existe el libro " + libroId));

        int disponibles = reponer(libro, cantidad);
        bandeja.registrar(TiposEvento.STOCK_CARGADO, actor, libro.id(), Map.of(
                "libroId", libro.id(), "titulo", libro.titulo(),
                "cantidad", cantidad, "disponibles", disponibles));

        return catalogo.porId(libro.id()).orElseThrow();
    }

    /**
     * Suma ejemplares y, si el libro estaba agotado, publica stock.repuesto.
     * Debe correr dentro de la transaccion de quien llama (carga o devolucion).
     *
     * @return los ejemplares disponibles despues de sumar
     */
    @Transactional
    public int reponer(Libro libro, int cantidad) {
        int disponibles = catalogo.sumar(libro.id(), cantidad).orElseThrow();
        if (disponibles - cantidad == 0) {
            bandeja.registrar(TiposEvento.STOCK_REPUESTO, Evento.SISTEMA, libro.id(), Map.of(
                    "libroId", libro.id(), "titulo", libro.titulo(), "disponibles", disponibles));
        }
        return disponibles;
    }
}
