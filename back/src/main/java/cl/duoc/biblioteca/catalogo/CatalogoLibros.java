package cl.duoc.biblioteca.catalogo;

import java.util.List;
import java.util.Optional;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * El catalogo de libros, leido de la tabla {@code libro} de PostgreSQL.
 *
 * DE DONDE SALEN LOS DATOS
 *   De la migracion db/migration/V2__catalogo_inicial.sql (el seed), que Flyway
 *   aplica al arrancar. La aplicacion se conecta con el rol biblioteca_app, que
 *   sobre esta tabla solo tiene SELECT: el catalogo no se puede modificar desde
 *   la API ni aunque hubiera un fallo en este codigo.
 *
 * La interfaz publica es la misma que cuando el catalogo vivia en un JSON, asi
 * que LibroController no se entero del cambio.
 */
@Component
public class CatalogoLibros {

    private static final String COLUMNAS =
            "SELECT id, titulo, autor, genero, anio, isbn, ejemplares, ubicacion, sinopsis FROM libro ";

    private final JdbcClient jdbc;

    public CatalogoLibros(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Todos los libros, ordenados por titulo. */
    public List<Libro> todos() {
        return jdbc.sql(COLUMNAS + "ORDER BY titulo").query(Libro.class).list();
    }

    /**
     * Busqueda del catalogo. Los tres filtros se combinan con Y logico y todos
     * son opcionales: sin ninguno, devuelve el catalogo entero.
     *
     * Cada filtro va como parametro ligado (:texto, :genero), nunca concatenado
     * en el SQL: es lo que hace imposible la inyeccion. Un filtro vacio se
     * desactiva con "parametro = ''" en vez de armar el SQL a trozos.
     *
     * @param texto           busca en titulo, autor e ISBN, sin distinguir
     *                        mayusculas ni tildes (extension unaccent, ver V1)
     * @param genero          coincidencia exacta de genero, sin distinguir mayusculas
     * @param soloDisponibles si es true, descarta los libros sin ejemplares
     */
    public List<Libro> buscar(String texto, String genero, boolean soloDisponibles) {
        return jdbc.sql(COLUMNAS + """
                WHERE (:texto = '' OR unaccent(lower(titulo)) LIKE '%' || unaccent(lower(:texto)) || '%'
                                   OR unaccent(lower(autor))  LIKE '%' || unaccent(lower(:texto)) || '%'
                                   OR lower(isbn)             LIKE '%' || lower(:texto) || '%')
                  AND (:genero = '' OR lower(genero) = lower(:genero))
                  AND (NOT :soloDisponibles OR ejemplares > 0)
                ORDER BY titulo
                """)
                .param("texto", limpiar(texto))
                .param("genero", limpiar(genero))
                .param("soloDisponibles", soloDisponibles)
                .query(Libro.class)
                .list();
    }

    /** Los generos presentes en el catalogo, para poblar el filtro del frontend. */
    public List<String> generos() {
        return jdbc.sql("SELECT DISTINCT genero FROM libro ORDER BY genero").query(String.class).list();
    }

    /** Vacio si el id no existe: quien llama decide si eso es un 404. */
    public Optional<Libro> porId(String id) {
        return jdbc.sql(COLUMNAS + "WHERE upper(id) = upper(:id)")
                .param("id", id)
                .query(Libro.class)
                .optional();
    }

    /** null y espacios cuentan como "sin filtro". */
    private static String limpiar(String texto) {
        return texto == null ? "" : texto.trim();
    }
}
