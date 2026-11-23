package cl.duoc.biblioteca.avisos;

import java.util.Optional;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import cl.duoc.biblioteca.comun.errores.DependenciaNoDisponible;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.AttributeType;
import software.amazon.awssdk.services.cognitoidentityprovider.model.UserType;

/**
 * Lee el email verificado de un usuario con cognito-idp:ListUsers. Es el
 * ADAPTADOR de {@link DirectorioUsuarios} hacia AWS; lo crea ConfigCognito
 * solo si hay pool configurado. Los tests usan un directorio falso.
 *
 * POR QUE ListUsers Y NO AdminGetUser
 *   AdminGetUser busca por nombre de usuario, y con UsernameAttributes=email
 *   ese nombre es un identificador interno. El sub, en cambio, es lo que trae
 *   el token, y ListUsers permite filtrar por el.
 *
 * ERRORES
 *   Si Cognito no responde o niega el permiso, sale un 503 con un mensaje
 *   generico: el error del SDK incluye el ARN del rol y el id de la cuenta, y
 *   eso va al log, no al cliente.
 */
public class DirectorioCognito implements DirectorioUsuarios {

    private static final Logger log = LoggerFactory.getLogger(DirectorioCognito.class);

    /**
     * El sub va DENTRO de una expresion de filtro (sub = "..."): una comilla o
     * una barra invertida la romperian o cambiarian su sentido. Un sub de
     * Cognito es un UUID, y un client_id de M2M es alfanumerico: se acepta
     * solo eso (lista blanca, no lista negra).
     */
    private static final Pattern SUB_VALIDO = Pattern.compile("[A-Za-z0-9-]{1,128}");

    private final CognitoIdentityProviderClient cognito;
    private final String userPoolId;

    public DirectorioCognito(CognitoIdentityProviderClient cognito, String userPoolId) {
        this.cognito = cognito;
        this.userPoolId = userPoolId;
    }

    @Override
    public Optional<String> correoVerificado(String sub) {
        if (sub == null || !SUB_VALIDO.matcher(sub).matches()) {
            return Optional.empty();
        }
        try {
            return cognito.listUsers(r -> r.userPoolId(userPoolId).filter("sub = \"" + sub + "\"").limit(1))
                    .users().stream().findFirst()
                    .filter(u -> "true".equals(atributo(u, "email_verified")))
                    .map(u -> atributo(u, "email"));
        } catch (SdkException e) {
            log.error("Cognito ListUsers fallo para el sub {}: {}", sub, e.toString());
            throw new DependenciaNoDisponible("No se pudo consultar el directorio de usuarios; reintenta en unos segundos");
        }
    }

    private static String atributo(UserType usuario, String nombre) {
        return usuario.attributes().stream()
                .filter(a -> nombre.equals(a.name()))
                .map(AttributeType::value)
                .findFirst()
                .orElse(null);
    }
}
