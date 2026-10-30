package cl.duoc.biblioteca.solicitudes;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import cl.duoc.biblioteca.eventos.BandejaDeSalida;
import cl.duoc.biblioteca.eventos.Evento;
import cl.duoc.biblioteca.eventos.TiposEvento;

/**
 * Publica recordatorio.vence para los prestamos que vencen dentro de la
 * antelacion configurada (24 h por defecto).
 *
 * POR QUE UN @Scheduled Y NO UN MENSAJE CON TTL
 *   La alternativa "de libro" es publicar al aprobar un mensaje que expira
 *   justo antes del vencimiento. Tiene dos problemas aqui:
 *     * RabbitMQ solo expira mensajes en la CABEZA de la cola. Un prestamo de
 *       30 dias delante de uno de 1 dia bloquearia el recordatorio corto.
 *     * Un recordatorio debe poder ANULARSE si el libro se devuelve antes, y
 *       un mensaje ya publicado no se puede retirar de una cola.
 *   El estado del prestamo vive en la base, asi que el que decide CUANDO
 *   recordar es una consulta. RabbitMQ entra despues, para repartir el aviso
 *   y reintentar el envio. (El TTL si se usa donde todos esperan lo mismo:
 *   la cola correo.espera.)
 */
@Component
public class ProgramadorRecordatorios {

    private static final Logger log = LoggerFactory.getLogger(ProgramadorRecordatorios.class);

    private final RepositorioSolicitudes solicitudes;
    private final BandejaDeSalida bandeja;
    private final Duration antelacion;

    public ProgramadorRecordatorios(RepositorioSolicitudes solicitudes, BandejaDeSalida bandeja,
            @Value("${biblioteca.recordatorio.antelacion:PT24H}") Duration antelacion) {
        this.solicitudes = solicitudes;
        this.bandeja = bandeja;
        this.antelacion = antelacion;
    }

    /** @return cuantos recordatorios dejo en la bandeja de salida */
    @Scheduled(fixedDelayString = "${biblioteca.recordatorio.revision:PT1M}")
    @Transactional
    public int revisar() {
        List<String> ids = solicitudes.marcarParaRecordar(antelacion, Instant.now());
        for (String id : ids) {
            solicitudes.buscar(id).ifPresent(s -> bandeja.registrar(
                    TiposEvento.RECORDATORIO_VENCE, Evento.SISTEMA, s.id(), Map.of("solicitud", s)));
        }
        if (!ids.isEmpty()) {
            log.info("{} recordatorios de vencimiento programados", ids.size());
        }
        return ids.size();
    }
}
