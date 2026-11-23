package cl.duoc.biblioteca.catalogo;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import cl.duoc.biblioteca.streaming.CambioStock;

/**
 * El stock "en vivo" del catalogo (H8): el ultimo {@link CambioStock} de cada
 * libro, en memoria. La alimenta ConsumidorCatalogoVivo; la lee
 * GET /api/libros/stock-vivo, que el catalogo del navegador consulta cada
 * pocos segundos sin tocar la base de datos.
 *
 * Es logica de dominio pura: no sabe de Kafka (ni particiones, ni offsets),
 * solo de cambios de stock.
 *
 * IDEMPOTENTE Y A PRUEBA DE DESORDEN
 *   Guarda el estado (disponibles), no suma diferencias: aplicar dos veces el
 *   mismo cambio da lo mismo. Y si llegara un cambio mas viejo que el ya
 *   aplicado, gana el mas reciente.
 *
 * POR QUE EN MEMORIA
 *   El topico es compactado y guarda el ultimo stock de cada libro: al
 *   arrancar, el consumidor lo relee desde el principio y la proyeccion se
 *   reconstruye sola. No necesita tabla ni permisos nuevos. Vale porque hay
 *   UNA instancia de la API; con varias, cada una deberia leer todas las
 *   particiones (un grupo por instancia).
 */
@Component
public class ProyeccionStock {

    /**
     * @param disponibles   id del libro -> ejemplares, solo de los libros que cambiaron
     * @param actualizadoEn cuando se aplico el ultimo cambio; null si aun no llega ninguno
     */
    public record StockVivo(Map<String, Integer> disponibles, Instant actualizadoEn) {
    }

    private final Map<String, CambioStock> ultimos = new ConcurrentHashMap<>();
    private volatile Instant actualizadoEn;

    public void aplicar(CambioStock cambio) {
        ultimos.merge(cambio.idLibro(), cambio,
                (actual, nuevo) -> nuevo.ocurridoEn().isBefore(actual.ocurridoEn()) ? actual : nuevo);
        actualizadoEn = Instant.now();
    }

    public StockVivo instantanea() {
        Map<String, Integer> disponibles = new TreeMap<>();
        ultimos.forEach((libro, cambio) -> disponibles.put(libro, cambio.disponibles()));
        return new StockVivo(disponibles, actualizadoEn);
    }
}
