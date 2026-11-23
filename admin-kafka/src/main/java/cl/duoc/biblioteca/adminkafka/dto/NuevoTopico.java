package cl.duoc.biblioteca.adminkafka.dto;

import java.util.Map;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/**
 * Cuerpo de POST /api/admin/topicos.
 *
 * @param nombre        ver {@link NombreTopico}
 * @param particiones   1 a 12; por defecto 3 (una por broker y por hilo consumidor)
 * @param replicas      1 a 3 (hay tres brokers); por defecto 3
 * @param configuracion claves de configuracion de topico permitidas
 *                      (admin.kafka.configuraciones-permitidas): retention.ms,
 *                      cleanup.policy, min.insync.replicas...; opcional
 */
public record NuevoTopico(

        @NombreTopico
        String nombre,

        @Min(value = 1, message = "particiones tiene que ser al menos 1")
        @Max(value = 12, message = "particiones no puede superar 12")
        Integer particiones,

        @Min(value = 1, message = "replicas tiene que ser al menos 1")
        @Max(value = 3, message = "replicas no puede superar 3: hay tres brokers")
        Short replicas,

        Map<String, String> configuracion) {

    public NuevoTopico {
        particiones = particiones == null ? 3 : particiones;
        replicas = replicas == null ? 3 : replicas;
        configuracion = configuracion == null ? Map.of() : Map.copyOf(configuracion);
    }
}
