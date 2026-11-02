package cl.duoc.biblioteca.comun.seguridad;

import java.text.ParseException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;

/**
 * Como se valida un JWT de Cognito y que autoridades da, IGUAL en todos los
 * microservicios.
 *
 * Aqui vive lo que no depende de las rutas de cada servicio: el decodificador
 * (firma, emisor, expiracion, token_use) y la regla de quien es empleado. Las
 * reglas de ruta (que exige cada URL) son de cada servicio, en su propio
 * SecurityConfig, que usa {@link #convertidor()}.
 *
 * Que un servicio importe esta clase y no la copie es lo que garantiza que el
 * administrador de RabbitMQ y la API de la biblioteca acepten exactamente los
 * mismos tokens y deduzcan el mismo rol.
 *
 * Spring convierte cada valor del claim "scope" en una authority con prefijo
 * "SCOPE_". Por eso "biblioteca-api/acceso" se comprueba como
 * "SCOPE_biblioteca-api/acceso": la barra dentro del nombre es legal.
 */
@Configuration
public class ValidacionJwt {

    /** Inyectado por la variable de entorno BIBLIOTECA_SCOPE (aws/backend.yaml). */
    @Value("${biblioteca.scope}")
    private String scopeAcceso;

    /** Inyectado por BIBLIOTECA_SCOPE_GESTION: el scope del cliente M2M backoffice. */
    @Value("${biblioteca.scope-gestion}")
    private String scopeGestion;

    /** biblioteca.grupo-empleados de application.properties: el grupo de Cognito de los empleados. */
    @Value("${biblioteca.grupo-empleados}")
    private String grupoEmpleados;

    /** Inyectado por BIBLIOTECA_ISSUER: el emisor que se exige en el claim "iss". */
    @Value("${biblioteca.issuer}")
    private String issuer;

    /**
     * Inyectado por BIBLIOTECA_JWKS: las claves PUBLICAS de Cognito, en JSON.
     * Las descarga aws/pipeline/build-backend.sh al desplegar. Ver jwtDecoder().
     */
    @Value("${biblioteca.jwks}")
    private String jwks;

    /** La authority que da el scope de acceso de los estudiantes. */
    public String autoridadAcceso() {
        return "SCOPE_" + scopeAcceso;
    }

    /**
     * De un JWT validado a la lista de autoridades de Spring.
     *
     *   * Cada valor del claim "scope" -> "SCOPE_<valor>" (lo de siempre).
     *   * ROLE_EMPLEADO si se cumple UNA de dos:
     *       - el token es de una PERSONA del grupo de empleados: Cognito pone
     *         sus grupos en el claim "cognito:groups" del access token. Se exige
     *         ademas el scope de acceso: un token de usuario sin custom scope
     *         (admin-initiate-auth) no debe convertirse en empleado aqui aunque
     *         el gateway ya lo hubiera rechazado.
     *       - el token es del cliente M2M backoffice: trae el scope de gestion.
     *         Un client_credentials no tiene usuario ni grupos; su scope es lo
     *         unico que lo distingue.
     *
     * Es un bean aparte para que los tests lo usen tal cual con jwt().
     */
    @Bean
    public Converter<Jwt, Collection<GrantedAuthority>> autoridades() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        String acceso = autoridadAcceso();
        String gestion = "SCOPE_" + scopeGestion;

        return jwt -> {
            Collection<GrantedAuthority> autoridades = new ArrayList<>(scopes.convert(jwt));
            boolean conAcceso = autoridades.stream().anyMatch(a -> acceso.equals(a.getAuthority()));
            boolean conGestion = autoridades.stream().anyMatch(a -> gestion.equals(a.getAuthority()));
            List<String> grupos = jwt.getClaimAsStringList("cognito:groups");
            boolean delGrupo = grupos != null && grupos.contains(grupoEmpleados);

            if ((conAcceso && delGrupo) || conGestion) {
                autoridades.add(new SimpleGrantedAuthority(Roles.AUTORIDAD_EMPLEADO));
            }
            return autoridades;
        };
    }

    /** Para oauth2ResourceServer(...).jwt(...) del SecurityConfig de cada servicio. */
    public JwtAuthenticationConverter convertidor() {
        JwtAuthenticationConverter convertidor = new JwtAuthenticationConverter();
        convertidor.setJwtGrantedAuthoritiesConverter(autoridades());
        return convertidor;
    }

    /**
     * Decodificador con validacion ENDURECIDA.
     *
     * El validador por defecto comprueba firma, expiracion y emisor, pero no
     * el tipo de token: un ID TOKEN del mismo pool esta igual de bien firmado.
     * Aqui se exige ademas token_use == "access".
     *
     * ¿No sobra, si API Gateway ya rechaza el id_token por no traer "scope"?
     * Delante de API Gateway, si. Pero esta clase existe precisamente para los
     * caminos que NO pasan por API Gateway (la VPN, y el administrador de
     * RabbitMQ, que no esta detras del gateway), y ahi nadie mas lo comprueba.
     * (Un access token de Cognito no lleva claim "aud" sino "client_id", por
     * eso la validacion de audiencia estandar no aplica.)
     */
    @Bean
    public JwtDecoder jwtDecoder() throws ParseException {
        // LAS CLAVES LLEGAN EMBEBIDAS, NO SE DESCARGAN.
        //   Viene de cuando el backend era una Lambda en una VPC privada SIN
        //   salida a Internet, que no podia pedirle el JWKS a Cognito. La EC2
        //   si tiene salida, pero se mantiene: arranca sin depender de la red y
        //   fija las claves que se aceptan. El pipeline lo descarga al
        //   desplegar y lo entrega en BIBLIOTECA_JWKS. Son claves publicas:
        //   no hay ningun secreto en esa variable.
        //
        //   Cognito no rota sus claves de firma por su cuenta. Si algun dia lo
        //   hiciera, todo token nuevo daria 401 ("kid" desconocido) hasta
        //   volver a correr aws/pipeline/build-backend.sh.
        //
        //   Se fija ademas RS256, el unico algoritmo que usa Cognito: un token
        //   firmado con cualquier otro se rechaza aunque la clave encaje.
        DefaultJWTProcessor<SecurityContext> procesador = new DefaultJWTProcessor<>();
        procesador.setJWSKeySelector(new JWSVerificationKeySelector<>(
                JWSAlgorithm.RS256, new ImmutableJWKSet<>(JWKSet.parse(jwks))));
        // Nimbus trae su propia verificacion de claims; la desactivamos para
        // que la haga Spring con los validadores de aqui abajo, como antes.
        procesador.setJWTClaimsSetVerifier((claims, contexto) -> { });
        NimbusJwtDecoder decoder = new NimbusJwtDecoder(procesador);

        // Emisor y expiracion se siguen comprobando exactamente igual: no
        // necesitan red, solo comparan claims del token ya verificado.
        OAuth2TokenValidator<Jwt> porDefecto = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> soloAccessToken = jwt -> {
            if ("access".equals(jwt.getClaimAsString("token_use"))) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                    "invalid_token",
                    "Se requiere un access token (token_use=access); un id_token no sirve para llamar a esta API",
                    null));
        };

        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(porDefecto, soloAccessToken));
        return decoder;
    }
}
