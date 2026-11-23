package cl.duoc.biblioteca.adminkafka.dto;

import static java.lang.annotation.ElementType.FIELD;
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

/** Un id de consumer group: letras, digitos, '.', '-' y '_' (catalogo-vivo, bandeja-gestion). */
@NotBlank(message = "el id del grupo no puede estar vacio")
@Size(max = 255, message = "el id del grupo no puede superar 255 caracteres")
@Pattern(regexp = "^[A-Za-z0-9._-]+$", message = "el id del grupo solo admite letras, digitos, '.', '-' y '_'")
@Constraint(validatedBy = {})
@Documented
@Retention(RUNTIME)
@Target({ FIELD, PARAMETER })
public @interface IdGrupo {

    String message() default "id de grupo invalido";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
