package cl.duoc.biblioteca.eventos;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Algo que PASO en la biblioteca, contado una sola vez y en pasado.
 *
 * Es el sobre de todos los mensajes del exchange {@code biblioteca.eventos}.
 * Quien lo publica no sabe quien lo va a leer: el correo, la bitacora o un
 * consumidor que todavia no existe se suscriben por {@link #tipo()}, que es la
 * routing key del exchange topic.
 *
 * @param id          identificador unico; los consumidores lo usan para no
 *                    procesar dos veces la misma reentrega
 * @param tipo        routing key: "solicitud.aprobada", "stock.repuesto"...
 * @param ocurridoEn  cuando paso, en UTC
 * @param actor       quien lo provoco: claim sub, client_id, o "sistema"
 * @param referencia  la entidad afectada: "SOL-0012" o "L-005"
 * @param datos       lo necesario para reaccionar sin volver a consultar
 */
public record Evento(
        UUID id,
        String tipo,
        Instant ocurridoEn,
        String actor,
        String referencia,
        Map<String, Object> datos) {

    /** Actor de los eventos que no provoca una persona (recordatorios, stock repuesto). */
    public static final String SISTEMA = "sistema";
}
