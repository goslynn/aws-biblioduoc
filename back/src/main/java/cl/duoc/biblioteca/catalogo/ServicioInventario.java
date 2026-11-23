package cl.duoc.biblioteca.catalogo;

import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.biblioteca.comun.errores.ConflictoDeNegocio;
import cl.duoc.biblioteca.eventos.BandejaDeSalida;
import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.eventos.TiposEvento;
import cl.duoc.biblioteca.streaming.BandejaKafka;
import cl.duoc.biblioteca.streaming.CambioStock.MotivoStock;

/**
 * TODO cambio de stock pasa por aqui: la carga que hace un empleado, la
 * reposicion por devolucion y el descuento al aprobar un prestamo. Es el unico
 * cliente de las sentencias de stock de {@link RepositorioLibros}.
 *
 * La regla de los "avisame": se avisa cuando un libro PASA DE 0 A TENER
 * ALGUNO, no cada vez que entra un ejemplar. Si ya habia 3 y llegan 2, nadie
 * estaba esperando (el boton "avisame" solo aparece con 0).
 *
 * Y la regla del catalogo en vivo (H8): CADA cambio, sea cual sea, deja un
 * CambioStock en la bandeja de Kafka con el stock resultante. Los avisos
 * (RabbitMQ) y el streaming (Kafka) salen de la misma transaccion pero por
 * bandejas separadas: si un broker cae, el otro sigue.
 */
@Service
public class ServicioInventario {

    private final CatalogoLibros catalogo;
    private final RepositorioLibros libros;
    private final BandejaDeSalida bandeja;
    private final BandejaKafka streaming;

    public ServicioInventario(CatalogoLibros catalogo, RepositorioLibros libros, BandejaDeSalida bandeja,
            BandejaKafka streaming) {
        this.catalogo = catalogo;
        this.libros = libros;
        this.bandeja = bandeja;
        this.streaming = streaming;
    }

    /** Carga de stock de un empleado o del backoffice: deja rastro en la bitacora. 404 si el libro no existe. */
    @Transactional
    public Libro cargar(String libroId, int cantidad, String actor) {
        Libro libro = catalogo.existente(libroId);

        int disponibles = reponer(libro, cantidad, MotivoStock.CARGA);
        bandeja.registrar(TiposEvento.STOCK_CARGADO, actor, libro.id(), Map.of(
                "libroId", libro.id(), "titulo", libro.titulo(),
                "cantidad", cantidad, "disponibles", disponibles));

        return catalogo.existente(libro.id());
    }

    /**
     * Suma ejemplares y, si el libro estaba agotado, publica stock.repuesto.
     * MANDATORY: corre dentro de la transaccion de quien llama (carga o
     * devolucion), para que el stock y su evento se guarden juntos.
     *
     * @return los ejemplares disponibles despues de sumar
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public int reponer(Libro libro, int cantidad, MotivoStock motivo) {
        int disponibles = libros.sumar(libro.id(), cantidad).orElseThrow();
        streaming.cambioStock(libro.id(), disponibles, cantidad, motivo);
        if (disponibles - cantidad == 0) {
            bandeja.registrar(TiposEvento.STOCK_REPUESTO, Evento.SISTEMA, libro.id(), Map.of(
                    "libroId", libro.id(), "titulo", libro.titulo(), "disponibles", disponibles));
        }
        return disponibles;
    }

    /**
     * Resta ejemplares al aprobar un prestamo, o 409 si ya no alcanzan. Dentro
     * de la transaccion de la aprobacion: si falla un libro, el rollback
     * devuelve tambien los ya descontados.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void descontar(String libroId, int cantidad, String motivo) {
        int disponibles = libros.descontar(libroId, cantidad).orElseThrow(() ->
                new ConflictoDeNegocio("No quedan " + cantidad + " ejemplares de " + libroId + " para " + motivo));
        streaming.cambioStock(libroId, disponibles, -cantidad, MotivoStock.APROBACION);
    }
}
