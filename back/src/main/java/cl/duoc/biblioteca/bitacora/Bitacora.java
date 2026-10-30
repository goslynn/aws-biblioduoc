package cl.duoc.biblioteca.bitacora;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import cl.duoc.biblioteca.eventos.Evento;

/** Tabla bitacora (V3): un registro por evento publicado, de solo INSERT. */
@Component
public class Bitacora {

    /** Una fila tal como la ve el empleado. */
    public record Entrada(String tipo, String actor, String referencia, Instant ocurridoEn, String detalle) {
    }

    private final JdbcClient jdbc;

    public Bitacora(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Idempotente por evento_id: una reentrega no duplica la fila. */
    public void anotar(Evento evento, String detalleJson) {
        jdbc.sql("""
                INSERT INTO bitacora (evento_id, tipo, actor, referencia, detalle, ocurrido_en)
                VALUES (:id, :tipo, :actor, :referencia, :detalle, :ocurrido)
                ON CONFLICT (evento_id) DO NOTHING
                """)
                .param("id", evento.id())
                .param("tipo", evento.tipo())
                .param("actor", evento.actor() == null ? Evento.SISTEMA : evento.actor())
                .param("referencia", evento.referencia() == null ? "-" : evento.referencia())
                .param("detalle", detalleJson)
                .param("ocurrido", Timestamp.from(evento.ocurridoEn() == null ? Instant.now() : evento.ocurridoEn()))
                .update();
    }

    public List<Entrada> ultimas(int limite) {
        return jdbc.sql("""
                SELECT tipo, actor, referencia, ocurrido_en, detalle
                FROM bitacora ORDER BY ocurrido_en DESC, id DESC LIMIT :limite
                """)
                .param("limite", limite)
                .query((rs, n) -> new Entrada(rs.getString("tipo"), rs.getString("actor"),
                        rs.getString("referencia"), rs.getTimestamp("ocurrido_en").toInstant(),
                        rs.getString("detalle")))
                .list();
    }
}
