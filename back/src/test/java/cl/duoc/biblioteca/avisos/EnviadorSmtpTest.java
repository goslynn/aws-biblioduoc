package cl.duoc.biblioteca.avisos;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;

/**
 * Que fallo del SMTP se reintenta y cual no. GreenMail no sabe rechazar con
 * un codigo concreto, asi que se prueba con las excepciones tal como las arma
 * JavaMailSender: el rechazo NO es la causa, va en getMessageExceptions().
 */
class EnviadorSmtpTest {

    private static MailSendException rechazo(Exception delServidor) {
        return new MailSendException(Map.of(new Object(), delServidor));
    }

    private static SMTPAddressFailedException direccion(int codigo) throws Exception {
        return new SMTPAddressFailedException(new InternetAddress("alguien@prueba.cl"), "RCPT TO", codigo, "rechazado");
    }

    @Test
    @DisplayName("550 (buzon inexistente) es permanente: no se gastan reintentos")
    void rechazo5xx() throws Exception {
        assertThat(EnviadorSmtp.esPermanente(rechazo(direccion(550)))).isTrue();
    }

    @Test
    @DisplayName("452 (cuota llena) es transitorio: el servidor pide reintentar")
    void rechazo4xx() throws Exception {
        assertThat(EnviadorSmtp.esPermanente(rechazo(direccion(452)))).isFalse();
    }

    @Test
    @DisplayName("sin conexion con el servidor es transitorio")
    void sinConexion() {
        // Lo que llega cuando el servidor no contesta: una MessagingException
        // (MailConnectException en Angus), sin codigo SMTP.
        assertThat(EnviadorSmtp.esPermanente(rechazo(new MessagingException("Couldn't connect to host")))).isFalse();
    }
}
