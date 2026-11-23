package cl.duoc.biblioteca.catalogo;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Un libro del catalogo: una fila de la tabla {@code libro} tal cual.
 *
 * Es un {@code record} inmutable y a la vez la entidad de Spring Data JDBC:
 * las columnas salen de los componentes (titulo -> titulo, en snake_case si
 * hiciera falta) y {@link RepositorioLibros} lo lee y escribe sin setters ni
 * mapeos a mano.
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
@Table("libro")
public record Libro(
        @Id String id,
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
     * componentes; cualquier metodo extra se ignora salvo que se marque. A
     * Spring Data no le afecta: mapea componentes, no metodos.
     */
    @JsonProperty("disponible")
    public boolean disponible() {
        return ejemplares > 0;
    }
}
