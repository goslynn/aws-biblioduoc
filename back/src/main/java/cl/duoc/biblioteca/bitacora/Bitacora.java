package cl.duoc.biblioteca.bitacora;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Limit;
import org.springframework.stereotype.Service;

import cl.duoc.biblioteca.eventos.Evento;

/** La bitacora: anotar un evento y leer los ultimos. */
@Service
public class Bitacora {

    /** Una fila tal como la ve el empleado. */
    public record Entrada(String tipo, String actor, String referencia, Instant ocurridoEn, String detalle) {
    }

    private final RepositorioBitacora registros;

    public Bitacora(RepositorioBitacora registros) {
        this.registros = registros;
    }

    /** Idempotente por evento_id. Los campos que falten se completan para no perder el registro. */
    public void anotar(Evento evento, String detalleJson) {
        registros.anotar(evento.id(), evento.tipo(),
                evento.actor() == null ? Evento.SISTEMA : evento.actor(),
                evento.referencia() == null ? "-" : evento.referencia(),
                detalleJson,
                evento.ocurridoEn() == null ? Instant.now() : evento.ocurridoEn());
    }

    public List<Entrada> ultimas(int limite) {
        return registros.findByOrderByOcurridoEnDescIdDesc(Limit.of(limite));
    }
}
