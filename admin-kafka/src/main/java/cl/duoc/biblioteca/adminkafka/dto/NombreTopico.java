package cl.duoc.biblioteca.adminkafka.dto;

import static java.lang.annotation.ElementType.ANNOTATION_TYPE;
import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Un nombre valido de topico, en UN solo lugar (DTO y variables de ruta).
 *
 *   * no vacio y hasta 249 caracteres (el limite de Kafka);
 *   * minusculas, digitos, punto, guion y guion bajo, empezando por letra o
 *     digito: la convencion de la biblioteca (biblioteca.inventario);
 *   * sin el prefijo "__", que Kafka reserva para sus topicos internos
 *     (__consumer_offsets).
 */
@NotBlank(message = "el nombre no puede estar vacio")
@Size(max = 249, message = "el nombre no puede superar 249 caracteres")
@Pattern(regexp = NombreTopico.PATRON, message = "el nombre solo admite minusculas, digitos, '.', '-' y '_', "
        + "debe empezar por letra o digito (los que empiezan por '__' son internos de Kafka)")
@Constraint(validatedBy = {})
@Documented
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, ANNOTATION_TYPE })
public @interface NombreTopico {

    String PATRON = "^[a-z0-9][a-z0-9._-]*$";

    String message() default "nombre de topico invalido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
