package cl.duoc.biblioteca.eventos;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Una fila de la bandeja de salida (tabla evento_pendiente, V3): el evento ya
 * serializado, esperando a que RelayEventos lo publique.
 *
 * {@link Persistable} con isNew() = true: el id (UUID) lo pone la aplicacion,
 * y sin esto Spring Data creeria que la fila ya existe y haria UPDATE. Una
 * fila de la bandeja nunca se modifica: se inserta y, al publicarse, se borra.
 */
@Table("evento_pendiente")
public record EventoPendiente(@Id UUID id, String tipo, String cuerpo, Instant creadoEn)
        implements Persistable<UUID> {

    @Override
    public UUID getId() {
        return id;
    }

    @Override
    public boolean isNew() {
        return true;
    }
}
