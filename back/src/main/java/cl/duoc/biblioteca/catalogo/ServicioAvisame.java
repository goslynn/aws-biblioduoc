package cl.duoc.biblioteca.catalogo;

import java.util.List;

import org.springframework.stereotype.Service;

import cl.duoc.biblioteca.comun.errores.ConflictoDeNegocio;

/**
 * Las reglas del "avisame cuando vuelva". El aviso en si no sale de aqui: lo
 * dispara ServicioInventario al reponer stock (evento stock.repuesto) y lo
 * manda ConsumidorDisponibilidad.
 */
@Service
public class ServicioAvisame {

    private final CatalogoLibros catalogo;
    private final RepositorioSuscripciones suscripciones;

    public ServicioAvisame(CatalogoLibros catalogo, RepositorioSuscripciones suscripciones) {
        this.catalogo = catalogo;
        this.suscripciones = suscripciones;
    }

    public List<String> librosQueEspera(String solicitante) {
        return suscripciones.librosDe(solicitante);
    }

    /** 404 si el libro no existe; 409 si TIENE ejemplares: no hay nada que esperar. */
    public void suscribir(String libroId, String solicitante) {
        Libro libro = catalogo.existente(libroId);
        if (libro.disponible()) {
            throw new ConflictoDeNegocio(libro.titulo() + " tiene ejemplares disponibles: pidelo directamente");
        }
        suscripciones.suscribir(libro.id(), solicitante);
    }

    /** 404 si el libro no existe; si no estaba suscrito, no pasa nada. */
    public void cancelar(String libroId, String solicitante) {
        cancelarSinValidar(catalogo.existente(libroId).id(), solicitante);
    }

    /** Para el consumidor de disponibilidad: el id ya viene del evento y es canonico. */
    public void cancelarSinValidar(String libroId, String solicitante) {
        suscripciones.deleteById(new SuscripcionLibro.Clave(libroId, solicitante));
    }
}
