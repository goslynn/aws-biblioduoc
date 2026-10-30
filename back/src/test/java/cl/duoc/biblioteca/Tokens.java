package cl.duoc.biblioteca;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.util.Collection;
import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/**
 * Tokens simulados para cada actor, pasados por el MISMO convertidor de
 * autoridades que usa la aplicacion (SecurityConfig#autoridades). Asi los
 * tests prueban la regla real de "quien es empleado", no una lista de
 * autoridades escrita a mano.
 */
public record Tokens(Converter<Jwt, Collection<GrantedAuthority>> autoridades) {

    public static final String ACCESO = "biblioteca-api/acceso";
    public static final String GESTION = "biblioteca-api/gestion";

    /** Un estudiante: usuario del flujo del navegador, con el scope de acceso. */
    public JwtRequestPostProcessor alumno(String sub) {
        return token(sub, ACCESO, List.of());
    }

    /** Un empleado: mismo flujo, pero en el grupo "empleados". */
    public JwtRequestPostProcessor empleado(String sub) {
        return token(sub, ACCESO, List.of("empleados"));
    }

    /** El cliente M2M backoffice: client_credentials con el scope de gestion. */
    public JwtRequestPostProcessor backoffice() {
        return token("m2m-backoffice", GESTION, null);
    }

    /** Un usuario del grupo, pero SIN el scope de acceso: no debe ser empleado. */
    public JwtRequestPostProcessor empleadoSinScope(String sub) {
        return token(sub, "aws.cognito.signin.user.admin", List.of("empleados"));
    }

    private JwtRequestPostProcessor token(String sub, String scope, List<String> grupos) {
        return jwt().jwt(b -> {
            b.subject(sub).claim("scope", scope).claim("token_use", "access");
            if (grupos != null) {
                b.claim("cognito:groups", grupos);
            }
        }).authorities(autoridades);
    }
}
