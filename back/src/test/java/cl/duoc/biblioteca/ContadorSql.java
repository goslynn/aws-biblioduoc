package cl.duoc.biblioteca;

import java.util.List;
import java.util.function.Supplier;

import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Cuenta las sentencias SQL que ejecuta un bloque de codigo, escuchando el
 * log DEBUG de JdbcTemplate (por donde pasa todo Spring Data JDBC). Sirve
 * para comprobar que una lectura no degenera en N+1 consultas.
 */
public final class ContadorSql {

    private ContadorSql() {
    }

    public record Medicion<T>(T resultado, List<String> sentencias) {
    }

    public static <T> Medicion<T> medir(Supplier<T> bloque) {
        Logger jdbc = (Logger) LoggerFactory.getLogger("org.springframework.jdbc.core.JdbcTemplate");
        Level anterior = jdbc.getLevel();
        ListAppender<ILoggingEvent> capturas = new ListAppender<>();
        capturas.start();
        jdbc.addAppender(capturas);
        jdbc.setLevel(Level.DEBUG);
        try {
            T resultado = bloque.get();
            // Solo las de ESTE hilo: los consumidores de RabbitMQ escriben en
            // la base al mismo tiempo. JdbcTemplate anuncia cada sentencia con
            // "Executing ... statement [<sql>]".
            String hilo = Thread.currentThread().getName();
            List<String> sentencias = capturas.list.stream()
                    .filter(e -> hilo.equals(e.getThreadName()))
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(m -> m.startsWith("Executing") && m.contains("statement ["))
                    .toList();
            return new Medicion<>(resultado, sentencias);
        } finally {
            jdbc.setLevel(anterior);
            jdbc.detachAppender(capturas);
        }
    }
}
