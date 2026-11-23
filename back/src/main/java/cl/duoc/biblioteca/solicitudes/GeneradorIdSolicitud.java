package cl.duoc.biblioteca.solicitudes;

import org.springframework.data.relational.core.mapping.event.BeforeConvertCallback;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Asigna el id "SOL-0001" a una solicitud nueva justo antes de guardarla.
 *
 * Es el mecanismo que documenta Spring Data JDBC para ids que no genera la
 * base: el agregado llega con id null (por eso se considera NUEVO y se hace
 * INSERT) y este callback le pone el id antes de convertirlo en filas.
 *
 * El correlativo sale de una secuencia de Postgres, segura con varias
 * peticiones a la vez. El formato se aplica en Java: el lpad(..., 4, '0') de
 * antes TRUNCABA los numeros de mas de cuatro cifras (la solicitud 12345 salia
 * como SOL-1234 y chocaba con la clave primaria de la 1234).
 */
@Component
public class GeneradorIdSolicitud implements BeforeConvertCallback<Solicitud> {

    private final JdbcClient jdbc;

    public GeneradorIdSolicitud(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Solicitud onBeforeConvert(Solicitud solicitud) {
        if (solicitud.id() != null) {
            return solicitud;
        }
        long numero = jdbc.sql("SELECT nextval('solicitud_seq')").query(Long.class).single();
        return solicitud.conId(formatear(numero));
    }

    /** 4 cifras como minimo, nunca menos digitos de los que tiene el numero. */
    static String formatear(long numero) {
        return "SOL-%04d".formatted(numero);
    }
}
