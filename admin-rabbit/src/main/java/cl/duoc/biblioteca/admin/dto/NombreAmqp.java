package cl.duoc.biblioteca.admin.dto;

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
 * Un nombre valido para una cola o un exchange, en UN solo lugar: lo usan los
 * DTO y las variables de ruta ({nombre} de los DELETE).
 *
 *   * no vacio;
 *   * hasta 255 caracteres (el limite de AMQP 0-9-1 para un shortstr);
 *   * minusculas, digitos, punto, guion y guion bajo, empezando por letra o
 *     digito: la convencion de la topologia de la biblioteca
 *     (notif.prestamos, correo.espera...);
 *   * sin el prefijo "amq.", que RabbitMQ reserva para lo suyo.
 *
 * Cada regla tiene su propio mensaje: el 400 dice cual fallo.
 */
@NotBlank(message = "el nombre no puede estar vacio")
@Size(max = 255, message = "el nombre no puede superar 255 caracteres")
@Pattern(regexp = NombreAmqp.PATRON, message = "el nombre solo admite minusculas, digitos, '.', '-' y '_', "
        + "debe empezar por letra o digito y no puede empezar por 'amq.' (reservado de RabbitMQ)")
@Constraint(validatedBy = {})
@Documented
@Retention(RUNTIME)
@Target({ FIELD, METHOD, PARAMETER, ANNOTATION_TYPE })
public @interface NombreAmqp {

    String PATRON = "^(?!amq\\.)[a-z0-9][a-z0-9._-]*$";

    String message() default "nombre de cola o exchange invalido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
