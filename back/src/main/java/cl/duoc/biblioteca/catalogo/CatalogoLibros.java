package cl.duoc.biblioteca.catalogo;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.stereotype.Service;

import cl.duoc.biblioteca.comun.errores.RecursoNoEncontrado;

/**
 * La LECTURA del catalogo: lo que necesitan los controladores y los demas
 * servicios para encontrar un libro. Los cambios de stock son de
 * {@link ServicioInventario}.
 *
 * DE DONDE SALEN LOS DATOS
 *   De la migracion db/migration/V2__catalogo_inicial.sql (el seed), que Flyway
 *   aplica al arrancar. Titulo, autor o ISBN no se pueden modificar desde la
 *   API ni aunque hubiera un fallo en este codigo: biblioteca_app solo tiene
 *   UPDATE de la columna ejemplares.
 */
@Service
public class CatalogoLibros {

    /** Busquedas mas largas no son de una persona: se cortan antes de llegar a la base. */
    static final int MAX_TEXTO = 100;

    private final RepositorioLibros libros;

    public CatalogoLibros(RepositorioLibros libros) {
        this.libros = libros;
    }

    /**
     * @param texto           busca en titulo, autor e ISBN, sin distinguir
     *                        mayusculas ni tildes (extension unaccent, ver V1)
     * @param genero          coincidencia exacta de genero, sin distinguir mayusculas
     * @param soloDisponibles si es true, descarta los libros sin ejemplares
     */
    public List<Libro> buscar(String texto, String genero, boolean soloDisponibles) {
        return libros.buscar(patronLike(texto), limpiar(genero), soloDisponibles);
    }

    /** Los generos presentes en el catalogo, para poblar el filtro del frontend. */
    public List<String> generos() {
        return libros.generos();
    }

    /**
     * Vacio si el id no existe: quien llama decide si eso es un 404.
     *
     * El id se acepta sin distinguir mayusculas ("l-001"), pero se normaliza
     * AQUI y no con upper(id) en el SQL: asi la consulta usa la clave primaria
     * en vez de recorrer la tabla entera.
     */
    public Optional<Libro> porId(String id) {
        return id == null ? Optional.empty() : libros.findById(id.strip().toUpperCase(Locale.ROOT));
    }

    /** El libro, o un 404 con el id pedido. */
    public Libro existente(String id) {
        return porId(id).orElseThrow(() -> new RecursoNoEncontrado("No existe el libro " + id));
    }

    /**
     * El texto del usuario como patron LIKE LITERAL. Sin escapar, un "%" o un
     * "_" funcionarian como comodines: no es inyeccion SQL (va ligado), pero
     * "_" encontraria cualquier titulo con al menos una letra.
     */
    static String patronLike(String texto) {
        return limpiar(texto).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    /** null y espacios cuentan como "sin filtro". */
    private static String limpiar(String texto) {
        return texto == null ? "" : texto.strip();
    }
}
