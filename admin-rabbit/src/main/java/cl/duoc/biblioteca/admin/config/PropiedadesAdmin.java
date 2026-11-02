package cl.duoc.biblioteca.admin.config;

import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Lo que el administrador NO puede tocar, leido de application.properties
 * (admin.rabbit.*). Es el unico lugar con nombres de colas y exchanges en este
 * microservicio.
 *
 * @param protegidos colas y exchanges de la topologia de la biblioteca (los
 *                   declara la API en TopologiaRabbit). Borrarlos, o
 *                   cambiarles los bindings, dejaria a la API sin avisos.
 *                   Los nombres que empiezan por "amq." los reserva RabbitMQ y
 *                   los rechaza la validacion (NombreAmqp).
 */
@ConfigurationProperties("admin.rabbit")
public record PropiedadesAdmin(Set<String> protegidos) {

    public PropiedadesAdmin {
        protegidos = protegidos == null ? Set.of() : Set.copyOf(protegidos);
    }

    public boolean esProtegido(String nombre) {
        return protegidos.contains(nombre);
    }
}
