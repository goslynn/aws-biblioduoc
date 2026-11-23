package cl.duoc.biblioteca.solicitudes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import cl.duoc.biblioteca.ContadorSql;
import cl.duoc.biblioteca.ServiciosDePrueba;

/**
 * La capa de persistencia de las solicitudes (Spring Data JDBC: el agregado
 * RepositorioSolicitudes y el modelo de lectura ListadoSolicitudes) contra el
 * Postgres de Testcontainers, con los permisos reales de biblioteca_app.
 *
 * Lo que se mide aqui es lo que un repositorio generado podria hacer peor que
 * el SQL a mano de antes: cuantas sentencias cuesta leer un agregado con sus
 * lineas, y si lo leido es identico a lo guardado.
 */
@SpringBootTest
@Import(ServiciosDePrueba.class)
class RepositorioSolicitudesTest {

    @Autowired
    ServicioPrestamos prestamos;

    @Autowired
    RepositorioSolicitudes solicitudes;

    @Autowired
    ListadoSolicitudes listado;

    private Solicitud crear(String sub, String... libros) {
        List<ItemSolicitado> items = Arrays.stream(libros).map(l -> new ItemSolicitado(l, 1)).toList();
        return prestamos.crear(sub, new NuevaSolicitud(items, 7, "prueba de persistencia", Sede.PLAZA_OESTE));
    }

    @Test
    @DisplayName("un listado cuesta 2 sentencias sea cual sea su tamano: sin N+1")
    void listadoSinNMasUno() {
        String sub = "persistencia-" + UUID.randomUUID();
        for (int i = 0; i < 4; i++) {
            crear(sub, "L-001", "L-013");
        }

        var medicion = ContadorSql.medir(() -> listado.deSolicitante(sub));

        assertThat(medicion.resultado()).hasSize(4).allMatch(s -> s.lineas().size() == 2);
        assertThat(medicion.sentencias()).hasSize(2);
        assertThat(ContadorSql.medir(() -> listado.porEstado(EstadoSolicitud.RECIBIDA)).sentencias()).hasSize(2);
        assertThat(ContadorSql.medir(() -> listado.todas()).sentencias()).hasSize(2);
    }

    @Test
    @DisplayName("findById devuelve exactamente lo guardado: campos, instantes y orden de las lineas")
    void idaYVuelta() {
        Solicitud creada = crear("persistencia-" + UUID.randomUUID(), "L-013", "L-001", "L-014");

        var medicion = ContadorSql.medir(() -> solicitudes.findById(creada.id()));

        // Cabecera + lineas: dos sentencias para UN agregado, no mas.
        assertThat(medicion.sentencias()).hasSize(2);
        // Record: igualdad campo a campo, incluidos los instantes (al
        // microsegundo) y el ORDEN de las lineas (columna orden).
        assertThat(medicion.resultado()).contains(creada);
        assertThat(creada.lineas()).extracting(LineaSolicitud::libroId).containsExactly("L-013", "L-001", "L-014");
    }

    @Test
    @DisplayName("el listado arma cada solicitud con SUS lineas, aunque compartan libros")
    void lineasDeCadaUna() {
        String sub = "persistencia-" + UUID.randomUUID();
        Solicitud una = crear(sub, "L-001");
        Solicitud otra = crear(sub, "L-013", "L-001", "L-014");

        assertThat(listado.deSolicitante(sub)).containsExactly(otra, una);
        assertThat(listado.porIds(List.of(una.id(), otra.id()))).containsExactly(otra, una);
    }

    @Test
    @DisplayName("los listados van de la mas reciente a la mas antigua")
    void orden() {
        String sub = "persistencia-" + UUID.randomUUID();
        String primera = crear(sub, "L-001").id();
        String segunda = crear(sub, "L-001").id();

        assertThat(listado.deSolicitante(sub)).extracting(Solicitud::id).containsExactly(segunda, primera);
    }

    @Test
    @DisplayName("el id no se trunca pasadas las 4 cifras (el lpad de antes daba SOL-1234 para la 12345)")
    void formatoDelId() {
        assertThat(GeneradorIdSolicitud.formatear(7)).isEqualTo("SOL-0007");
        assertThat(GeneradorIdSolicitud.formatear(9999)).isEqualTo("SOL-9999");
        assertThat(GeneradorIdSolicitud.formatear(12345)).isEqualTo("SOL-12345");
    }
}
