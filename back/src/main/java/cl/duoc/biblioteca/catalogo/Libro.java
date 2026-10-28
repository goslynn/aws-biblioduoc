package cl.duoc.biblioteca.catalogo;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Un libro del catalogo.
 *
 * Es un {@code record} inmutable: es una fila de la tabla {@code libro} tal
 * cual. JdbcClient lo construye por nombre de columna, asi que no hace falta
 * ni JPA ni setters.
 *
 * @param id         identificador estable, formato "L-001"
 * @param titulo     titulo del libro
 * @param autor      autor o autores, en un solo campo
 * @param genero     categoria usada por el filtro del frontend
 * @param anio       ano de la edicion
 * @param isbn       ISBN-13 con guiones
 * @param ejemplares copias fisicas en la sala; 0 significa no disponible
 * @param ubicacion  estante donde encontrarlo
 * @param sinopsis   resumen de una linea
 */
public record Libro(
        String id,
        String titulo,
        String autor,
        String genero,
        int anio,
        String isbn,
        int ejemplares,
        String ubicacion,
        String sinopsis) {

    /**
     * Campo calculado: aparece como "disponible" en el JSON, de modo que el
     * frontend no tenga que repetir la regla "ejemplares > 0".
     *
     * La anotacion es OBLIGATORIA: de un record, Jackson serializa solo sus
     * componentes; cualquier metodo extra se ignora salvo que se marque.
     */
    @JsonProperty("disponible")
    public boolean disponible() {
        return ejemplares > 0;
    }
}
