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
 *   sobre esta tabla tiene SELECT y UPDATE de UNA columna, ejemplares (V3): el
 *   stock cambia al aprobar, devolver y cargar; titulo, autor o ISBN no se
 *   pueden modificar desde la API ni aunque hubiera un fallo en este codigo.
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

    /**
     * Resta ejemplares SOLO si alcanzan, en una sentencia atomica.
     *
     * Leer el stock, comprobarlo en Java y luego restar dejaria una ventana
     * en la que dos aprobaciones simultaneas ven "queda 1" y se llevan 2. Con
     * la condicion dentro del UPDATE, Postgres la evalua con la fila ya
     * bloqueada: la segunda aprobacion cambia 0 filas.
     *
     * @return false si no quedaban suficientes ejemplares
     */
    public boolean descontar(String libroId, int cantidad) {
        return jdbc.sql("UPDATE libro SET ejemplares = ejemplares - :n WHERE id = :id AND ejemplares >= :n")
                .param("id", libroId)
                .param("n", cantidad)
                .update() == 1;
    }

    /**
     * Suma ejemplares (devolucion o carga de stock).
     *
     * @return los ejemplares que hay DESPUES de sumar, o vacio si el libro no existe
     */
    public Optional<Integer> sumar(String libroId, int cantidad) {
        return jdbc.sql("UPDATE libro SET ejemplares = ejemplares + :n WHERE id = :id RETURNING ejemplares")
                .param("id", libroId)
                .param("n", cantidad)
                .query(Integer.class)
                .optional();
    }

    /** null y espacios cuentan como "sin filtro". */
    private static String limpiar(String texto) {
        return texto == null ? "" : texto.trim();
    }
}
