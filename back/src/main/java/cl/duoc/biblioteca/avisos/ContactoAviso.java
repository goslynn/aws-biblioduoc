package cl.duoc.biblioteca.avisos;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

/**
 * A quien se puede escribir (tabla contacto_aviso, V3). Que exista la fila ES
 * el consentimiento: sin fila, no sale ningun correo a esa persona.
 *
 * @param solicitante claim sub del estudiante
 * @param email       copiado de Cognito (verificado), nunca escrito por el usuario
 * @param activadoEn  cuando activo los avisos
 */
@Table("contacto_aviso")
public record ContactoAviso(@Id String solicitante, String email, Instant activadoEn) {
}
