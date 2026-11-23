package cl.duoc.biblioteca.catalogo;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * "Avisame cuando vuelva": un estudiante espera un libro agotado. Tabla
 * suscripcion_libro (V3), con clave compuesta (libro, estudiante).
 *
 * @param id       la clave compuesta; Spring Data JDBC la mapea a las dos columnas
 * @param creadaEn cuando se suscribio: los avisos salen en ese orden
 */
@Table("suscripcion_libro")
public record SuscripcionLibro(@Id Clave id, Instant creadaEn) {

    /** Las dos columnas de la clave primaria. */
    public record Clave(String libroId, String solicitante) {
    }
}
