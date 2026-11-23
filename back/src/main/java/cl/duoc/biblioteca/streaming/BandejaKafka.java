package cl.duoc.biblioteca.streaming;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.biblioteca.solicitudes.LineaSolicitud;
import cl.duoc.biblioteca.solicitudes.Solicitud;
import cl.duoc.biblioteca.streaming.CambioStock.MotivoStock;
import tools.jackson.databind.json.JsonMapper;

/**
 * El PRODUCTOR de Kafka visto desde el negocio: arma el DTO tipado y lo deja
 * en la bandeja de salida (tabla evento_kafka) dentro de la transaccion de
 * quien llama. RelayKafka lo publica despues.
 *
 * Por que no llamar a KafkaTemplate aqui mismo: el cambio de negocio se
 * confirma en PostgreSQL y el registro en Kafka, dos sistemas. Escritos por
 * separado, uno sale bien y el otro no (el stock bajo pero el catalogo en vivo
 * nunca se entero). Es la misma regla de la API con RabbitMQ: ninguna peticion
 * publica directo en un broker.
 *
 * {@code Propagation.MANDATORY}: llamarlo fuera de una transaccion es un error
 * de programacion y falla.
 */
@Component
public class BandejaKafka {

    private final RepositorioRegistrosKafka registros;
    private final JsonMapper json;

    public BandejaKafka(RepositorioRegistrosKafka registros, JsonMapper json) {
        this.registros = registros;
        this.json = json;
    }

    /** El stock de un libro quedo en {@code disponibles} (H8). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void cambioStock(String idLibro, int disponibles, int delta, MotivoStock motivo) {
        UUID id = UUID.randomUUID();
        guardar(id, TopologiaKafka.INVENTARIO, idLibro,
                new CambioStock(id, idLibro, disponibles, delta, motivo, ahora()));
    }

    /** La solicitud acaba de quedar en su estado actual (H9). */
    @Transactional(propagation = Propagation.MANDATORY)
    public void hechoSolicitud(Solicitud solicitud) {
        UUID id = UUID.randomUUID();
        int ejemplares = solicitud.lineas().stream().mapToInt(LineaSolicitud::cantidad).sum();
        guardar(id, TopologiaKafka.SOLICITUDES, solicitud.id(), new HechoSolicitud(id, solicitud.id(),
                solicitud.estado(), solicitud.sede(), ejemplares,
                solicitud.lineas().stream().map(LineaSolicitud::titulo).toList(), ahora()));
    }

    private void guardar(UUID id, String topico, String clave, Object dto) {
        registros.save(new RegistroKafka(id, topico, clave, json.writeValueAsString(dto), ahora()));
    }

    private static Instant ahora() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }
}
