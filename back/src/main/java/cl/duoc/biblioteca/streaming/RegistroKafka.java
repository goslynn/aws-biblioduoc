package cl.duoc.biblioteca.streaming;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.annotation.Id;
import org.springframework.data.domain.Persistable;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Una fila de la bandeja de salida de Kafka (tabla evento_kafka, V4): un DTO
 * ya serializado, con su topico y su clave, esperando a RelayKafka.
 *
 * {@link Persistable} con isNew() = true por lo mismo que EventoPendiente: el
 * id lo pone la aplicacion, y la fila nunca se actualiza.
 */
@Table("evento_kafka")
public record RegistroKafka(@Id UUID id, String topico, String clave, String cuerpo, Instant creadoEn)
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
