package cl.duoc.biblioteca.avisos;

import java.util.Optional;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * Lee el email verificado de un usuario con cognito-idp:ListUsers.
 *
 * CREDENCIALES
 *   Ninguna escrita: el SDK usa la cadena por defecto, que en la EC2 resuelve
 *   el perfil de instancia LabInstanceProfile (rol LabRole). No hay Access Keys.
 *
 * POR QUE ListUsers Y NO AdminGetUser
 *   AdminGetUser busca por nombre de usuario, y con UsernameAttributes=email
 *   ese nombre es un identificador interno. El sub, en cambio, es lo que trae
 *   el token, y ListUsers permite filtrar por el.
 *
 * Solo existe si hay pool configurado (BIBLIOTECA_USER_POOL_ID). Los tests
 * usan un directorio falso.
 */
@Component
@ConditionalOnProperty("biblioteca.cognito.user-pool-id")
public class DirectorioCognito implements DirectorioUsuarios {

    private final CognitoIdentityProviderClient cognito = CognitoIdentityProviderClient.create();
    private final String userPoolId;

    public DirectorioCognito(@Value("${biblioteca.cognito.user-pool-id}") String userPoolId) {
        this.userPoolId = userPoolId;
    }

    @Override
    public Optional<String> correoVerificado(String sub) {
        // El sub va dentro de una expresion de filtro: una comilla la romperia.
        // Un sub legitimo de Cognito es un UUID y nunca la trae.
        if (sub == null || sub.contains("\"")) {
            return Optional.empty();
        }
        return cognito.listUsers(r -> r.userPoolId(userPoolId).filter("sub = \"" + sub + "\"").limit(1))
                .users().stream().findFirst()
                .filter(u -> "true".equals(atributo(u, "email_verified")))
                .map(u -> atributo(u, "email"));
    }

    private static String atributo(UserType usuario, String nombre) {
        return usuario.attributes().stream()
                .filter(a -> nombre.equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }
}
