package cl.duoc.biblioteca.avisos;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * Un correo que ya salio (tabla correo_enviado, V3). Es la clave de la
 * idempotencia del envio: ver {@link RepositorioCorreosEnviados}.
 */
@Table("correo_enviado")
public record CorreoEnviado(@Id String mensajeId, String destinatario, String asunto, Instant enviadoEn) {
}
