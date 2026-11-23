package cl.duoc.biblioteca.avisos;

import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import cl.duoc.biblioteca.comun.errores.ConflictoDeNegocio;
import cl.duoc.biblioteca.eventos.BandejaDeSalida;
import cl.duoc.biblioteca.eventos.TiposEvento;

/**
 * Activar y desactivar los avisos por correo: el consentimiento.
 *
 * La direccion sale de Cognito por el sub del token ({@link DirectorioUsuarios}),
 * no del cliente: asi nadie puede activar avisos hacia el correo de otra
 * persona, y el correo ya esta verificado porque Cognito lo exigio al
 * registrarse.
 */
@Service
public class ServicioAvisos {

    private final RepositorioContactos contactos;
    private final DirectorioUsuarios directorio;
    private final BandejaDeSalida bandeja;
    private final TransactionTemplate transaccion;

    public ServicioAvisos(RepositorioContactos contactos, DirectorioUsuarios directorio, BandejaDeSalida bandeja,
            TransactionTemplate transaccion) {
        this.contactos = contactos;
        this.directorio = directorio;
        this.bandeja = bandeja;
        this.transaccion = transaccion;
    }

    /** El email con el que se avisa, o vacio si los avisos estan desactivados. */
    public Optional<String> correoDe(String solicitante) {
        return contactos.findById(solicitante).map(ContactoAviso::email);
    }

    /**
     * Activa los avisos con el email verificado de Cognito. La primera vez
     * deja el evento aviso.activado (el correo de bienvenida).
     *
     * La consulta a Cognito va ANTES y FUERA de la transaccion: es una llamada
     * de red de hasta varios segundos, y dentro de la transaccion tendria
     * tomada una conexion del pool (son 8) todo ese tiempo.
     *
     * @return el email activado
     * @throws ConflictoDeNegocio si Cognito no tiene un email verificado para
     *         ese sub (por ejemplo, un cliente M2M)
     */
    public String activar(String solicitante) {
        String email = directorio.correoVerificado(solicitante).orElseThrow(() ->
                new ConflictoDeNegocio("Esta cuenta no tiene un email verificado en Cognito"));

        transaccion.executeWithoutResult(estado -> {
            if (contactos.crearSiNoExiste(solicitante, email)) {
                bandeja.registrar(TiposEvento.AVISO_ACTIVADO, solicitante, "AVISOS", Map.of("email", email));
            } else {
                contactos.actualizarEmail(solicitante, email);
            }
        });
        return email;
    }

    public void desactivar(String solicitante) {
        contactos.deleteById(solicitante);
    }
}
