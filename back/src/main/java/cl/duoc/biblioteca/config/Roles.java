package cl.duoc.biblioteca.config;

import org.springframework.security.core.Authentication;

/**
 * El unico rol de la aplicacion aparte del estudiante.
 *
 * No lo trae ningun token tal cual: lo DEDUCE SecurityConfig a partir del
 * grupo de Cognito (personas) o del scope de gestion (el cliente M2M
 * backoffice). Ver {@link SecurityConfig#autoridades}.
 */
public final class Roles {

    /** Nombre para hasRole(...): Spring le antepone "ROLE_". */
    public static final String EMPLEADO = "EMPLEADO";
    public static final String AUTORIDAD_EMPLEADO = "ROLE_" + EMPLEADO;

    private Roles() {
    }

    public static boolean esEmpleado(Authentication autenticacion) {
        return autenticacion != null && autenticacion.getAuthorities().stream()
                .anyMatch(a -> AUTORIDAD_EMPLEADO.equals(a.getAuthority()));
    }
}
