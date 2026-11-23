package cl.duoc.biblioteca.catalogo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.Repository;

/**
 * La tabla {@code libro}. Spring Data JDBC genera la implementacion.
 *
 * POR QUE {@code Repository} Y NO {@code CrudRepository}
 *   CrudRepository traeria save(), delete(), deleteAll()... que biblioteca_app
 *   no puede ejecutar (sobre esta tabla solo tiene SELECT y UPDATE de la
 *   columna ejemplares, ver V3). Declarar solo lo que se usa es la Segregacion
 *   de Interfaces llevada a los permisos: lo que no esta aqui no se puede
 *   llamar por error, y si alguien lo agregara, la base lo rechazaria igual.
 *
 * Las consultas con {@code @Query} son las que Spring Data no puede derivar
 * de un nombre de metodo: la busqueda sin tildes (unaccent) y las dos
 * sentencias atomicas de stock. Todos los valores van como parametros
 * ligados (:texto, :id): nunca se concatena nada al SQL.
 */
public interface RepositorioLibros extends Repository<Libro, String> {

    /** Busqueda exacta por clave primaria (CatalogoLibros normaliza el id antes). */
    Optional<Libro> findById(String id);

    /**
     * Busqueda del catalogo. Los tres filtros se combinan con Y logico y todos
     * son opcionales: un texto o genero vacio se desactiva con "= ''" en vez
     * de armar el SQL a trozos.
     *
     * @param patron          texto a buscar YA escapado para LIKE (ver CatalogoLibros)
     * @param genero          coincidencia exacta, sin distinguir mayusculas
     * @param soloDisponibles si es true, descarta los libros sin ejemplares
     */
    @Query("""
            SELECT * FROM libro
            WHERE (:patron = '' OR unaccent(lower(titulo)) LIKE '%' || unaccent(lower(:patron)) || '%'
                                OR unaccent(lower(autor))  LIKE '%' || unaccent(lower(:patron)) || '%'
                                OR lower(isbn)             LIKE '%' || lower(:patron) || '%')
              AND (:genero = '' OR lower(genero) = lower(:genero))
              AND (NOT :soloDisponibles OR ejemplares > 0)
            ORDER BY titulo
            """)
    List<Libro> buscar(String patron, String genero, boolean soloDisponibles);

    @Query("SELECT DISTINCT genero FROM libro ORDER BY genero")
    List<String> generos();

    /**
     * Resta ejemplares SOLO si alcanzan, en una sentencia atomica, y devuelve
     * el stock que quedo (lo publica el catalogo en vivo por Kafka).
     *
     * Leer el stock, comprobarlo en Java y luego restar dejaria una ventana
     * en la que dos aprobaciones simultaneas ven "queda 1" y se llevan 2. Con
     * la condicion dentro del UPDATE, Postgres la evalua con la fila ya
     * bloqueada: la segunda aprobacion cambia 0 filas.
     *
     * Sin {@code @Modifying}, igual que {@link #sumar}: con RETURNING es una consulta.
     *
     * @return los ejemplares que quedan, o vacio si no alcanzaban
     */
    @Query("UPDATE libro SET ejemplares = ejemplares - :cantidad WHERE id = :id AND ejemplares >= :cantidad "
            + "RETURNING ejemplares")
    Optional<Integer> descontar(String id, int cantidad);

    /**
     * Suma ejemplares (devolucion o carga de stock) y devuelve el total
     * resultante, leido con la fila bloqueada: dos reposiciones simultaneas
     * ven cada una SU valor, nunca el mismo.
     *
     * No lleva {@code @Modifying} a proposito: con RETURNING la sentencia
     * devuelve filas, y se ejecuta como consulta.
     */
    @Query("UPDATE libro SET ejemplares = ejemplares + :cantidad WHERE id = :id RETURNING ejemplares")
    Optional<Integer> sumar(String id, int cantidad);
}
