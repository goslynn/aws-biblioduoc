package cl.duoc.biblioteca.avisos;

import java.time.Duration;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;

/**
 * El cliente de Cognito, como bean: Spring lo cierra al apagar la aplicacion
 * (antes se creaba en un campo y nunca se cerraba) y los tiempos de espera
 * quedan explicitos.
 *
 * CREDENCIALES
 *   Ninguna escrita: el SDK usa la cadena por defecto, que en la EC2 resuelve
 *   el perfil de instancia LabInstanceProfile (rol LabRole). No hay Access Keys.
 *
 * TIEMPOS DE ESPERA
 *   Sin ellos, una red lenta hacia Cognito dejaba colgado el PUT de activar
 *   avisos (y su hilo de Tomcat) tanto como el SDK quisiera. Con 2 s por
 *   intento y 5 s en total, el peor caso es un 503 rapido.
 *
 * Solo existe si hay pool configurado (BIBLIOTECA_USER_POOL_ID).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty("biblioteca.cognito.user-pool-id")
public class ConfigCognito {

    @Bean(destroyMethod = "close")
    CognitoIdentityProviderClient clienteCognito() {
        return CognitoIdentityProviderClient.builder()
                .overrideConfiguration(c -> c
                        .apiCallAttemptTimeout(Duration.ofSeconds(2))
                        .apiCallTimeout(Duration.ofSeconds(5)))
                .build();
    }

    @Bean
    DirectorioUsuarios directorioCognito(CognitoIdentityProviderClient cognito,
            @Value("${biblioteca.cognito.user-pool-id}") String userPoolId) {
        return new DirectorioCognito(cognito, userPoolId);
    }
}
