package cl.duoc.biblioteca.avisos;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import cl.duoc.biblioteca.comun.errores.DependenciaNoDisponible;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.cognitoidentityprovider.CognitoIdentityProviderClient;
import software.amazon.awssdk.services.cognitoidentityprovider.model.ListUsersRequest;

/** El adaptador de Cognito, sin red: el cliente del SDK es un doble. */
class DirectorioCognitoTest {

    // CALLS_REAL_METHODS: listUsers(Consumer) es un metodo por defecto que
    // arma el request y llama a listUsers(ListUsersRequest), el que se simula.
    private final CognitoIdentityProviderClient cognito = mock(CognitoIdentityProviderClient.class, CALLS_REAL_METHODS);
    private final DirectorioCognito directorio = new DirectorioCognito(cognito, "us-east-1_pruebas");

    @Test
    @DisplayName("un sub con comillas o barras no llega al filtro de Cognito")
    void subFueraDeListaBlanca() {
        assertThat(directorio.correoVerificado("a\" OR email ^= \"")).isEmpty();
        assertThat(directorio.correoVerificado("abc\\")).isEmpty();
        verify(cognito, never()).listUsers(any(ListUsersRequest.class));
    }

    @Test
    @DisplayName("si Cognito falla sale un 503 generico, sin el detalle de AWS")
    void cognitoCaido() {
        doThrow(SdkClientException.create("User: arn:aws:sts::123456789012:assumed-role/LabRole is not authorized"))
                .when(cognito).listUsers(any(ListUsersRequest.class));

        assertThatThrownBy(() -> directorio.correoVerificado("0f1e2d3c-aaaa-bbbb-cccc-1234567890ab"))
                .isInstanceOf(DependenciaNoDisponible.class)
                .message().doesNotContain("arn:").doesNotContain("123456789012");
    }
}
