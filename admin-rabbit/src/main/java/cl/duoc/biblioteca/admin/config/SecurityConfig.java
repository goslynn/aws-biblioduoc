package cl.duoc.biblioteca.admin.config;

import jakarta.servlet.DispatcherType;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

import cl.duoc.biblioteca.comun.seguridad.Roles;
import cl.duoc.biblioteca.comun.seguridad.ValidacionJwt;

/**
 * Seguridad del administrador: TODO exige rol EMPLEADO.
 *
 * Este servicio no esta detras de API Gateway: es la UNICA capa. Por eso la
 * validacion del token es la misma, linea por linea, que la de la API
 * (ValidacionJwt, modulo comun): firma con el JWKS de Cognito, emisor,
 * expiracion, token_use=access. Y el rol, el mismo: persona del grupo
 * "empleados" con el scope de acceso, o el cliente M2M backoffice.
 *
 * Un estudiante con un token valido recibe 403; sin token, 401.
 *
 * No hay CORS: ningun navegador llama a este servicio.
 */
@Configuration
public class SecurityConfig {

    private final ValidacionJwt validacion;

    public SecurityConfig(ValidacionJwt validacion) {
        this.validacion = validacion;
    }

    @Bean
    SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .authorizeHttpRequests(auth -> auth
                        // El reenvio interno a /error (404, 409...) vuelve a
                        // pasar por estas reglas: sin esto saldria 403 vacio.
                        // Ver el SecurityConfig de la API.
                        .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                        // Healthcheck del contenedor (compose.yaml).
                        .requestMatchers("/health").permitAll()
                        .requestMatchers("/api/admin/**", "/v1/api/admin/**").hasRole(Roles.EMPLEADO)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.jwtAuthenticationConverter(validacion.convertidor())))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // Sin sesion ni cookies: no hay nada que falsear con CSRF.
                .csrf(csrf -> csrf.disable());

        return http.build();
    }
}
