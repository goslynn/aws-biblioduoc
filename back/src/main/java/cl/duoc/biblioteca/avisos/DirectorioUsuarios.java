package cl.duoc.biblioteca.avisos;

import java.util.Optional;

/**
 * De donde sale el correo de un estudiante.
 *
 * El access token de Cognito NO trae el claim "email" (eso va en el id token,
 * que esta API no acepta). Y pedirle el correo al frontend seria creerle al
 * cliente. Por eso el backend se lo pregunta al propio Cognito, por el sub
 * del token ya validado. Ver {@link DirectorioCognito}.
 */
public interface DirectorioUsuarios {

    /**
     * @param sub claim sub del token
     * @return el email, solo si Cognito lo marca como verificado; vacio si el
     *         usuario no existe (por ejemplo, un cliente M2M) o no lo verifico
     */
    Optional<String> correoVerificado(String sub);
}
