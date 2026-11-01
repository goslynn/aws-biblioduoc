import { useEffect } from 'react';
import { Link, NavLink, Outlet, useLocation, useMatches, useNavigate } from 'react-router';

import { cerrarSesion, iniciarSesion, refrescar, registrarse, terminarCanje, useCanjeando, useSesion } from './auth/sesion';
import { useSeleccion, vaciarSeleccion } from './catalogo/seleccion';
import { rutasDiagnostico } from './diagnostico/rutas';
import { IconoLibro } from './iconos';
import type { DatosRuta } from './rutas';

/**
 * Enlace a la vista de diagnostico en la barra de navegacion.
 *
 * Se deduce de la ruta en vez de leer un flag aparte a proposito: asi el
 * enlace existe SI Y SOLO SI existe la ruta, y no hay dos interruptores que
 * puedan acabar contradiciendose. El unico interruptor es la variable de
 * entorno DEBUG del pipeline, que genera diagnostico/rutas.ts.
 */
const hayDiagnostico = rutasDiagnostico.length > 0;

/** NavLink marca con "activo" el enlace de la ruta abierta (antes, routerLinkActive). */
const claseEnlace = ({ isActive }: { isActive: boolean }) => (isActive ? 'activo' : undefined);

/** Marco de la aplicacion: cabecera, navegacion y estado de la sesion. */
export function App() {
  const estado = useSesion();
  const canjeando = useCanjeando();
  const seleccion = useSeleccion();
  const navegar = useNavigate();
  const ubicacion = useLocation();
  const coincidencias = useMatches();

  /**
   * Al arrancar se pregunta por la sesion. Es tambien el momento en el que
   * Amplify detecta el "?code=" con el que Cognito devuelve al usuario tras el
   * login y lo canjea por los tokens.
   *
   * Si la sesion ya viene autenticada y seguimos en la portada (recien
   * vueltos de Cognito), se salta directo al catalogo: la portada solo tiene
   * sentido para quien todavia no ha entrado. La pantalla de carga se quita
   * cuando esa navegacion TERMINA, no antes: si no, la portada asomaria un
   * instante entre la carga y el catalogo.
   */
  useEffect(() => {
    void refrescar().then(async (sesion) => {
      if (sesion.autenticado && ubicacion.pathname.startsWith('/inicio')) {
        await navegar('/catalogo', { replace: true });
      }
    }).finally(terminarCanje);
    // Solo al montar, como el ngOnInit de antes: despues, quien decide a donde
    // se va son las guardas de cada ruta.
  }, []);

  // El titulo de la pestana lo declara cada ruta en su "handle".
  const titulo = [...coincidencias].reverse()
    .map((m) => (m.handle as DatosRuta | undefined)?.titulo)
    .find(Boolean);
  useEffect(() => {
    if (titulo) {
      document.title = titulo;
    }
  }, [titulo]);

  if (canjeando) {
    return <PantallaCarga />;
  }

  const salir = () => {
    vaciarSeleccion();
    void cerrarSesion();
  };

  return (
    <>
      <header className="cabecera">
        <div className="contenedor">
          <Link to={estado.autenticado ? '/catalogo' : '/inicio'} className="marca">
            <span className="logo"><IconoLibro /></span>
            <strong>Biblioteca <span>Duoc</span></strong>
          </Link>

          {estado.autenticado ? (
            <>
              <nav aria-label="Principal">
                <NavLink to="/catalogo" className={claseEnlace}>Catalogo</NavLink>
                <NavLink to="/solicitud" className={claseEnlace}>
                  Mi solicitud
                  {!seleccion.vacia && (
                    <span className="contador" aria-label={`${seleccion.total} libros`}>{seleccion.total}</span>
                  )}
                </NavLink>
                <NavLink to="/mis-solicitudes" className={claseEnlace}>Mis solicitudes</NavLink>
                {estado.empleado && (
                  <>
                    <span className="separador" aria-hidden="true" />
                    <NavLink to="/gestion/solicitudes" className={claseEnlace}>Bandeja</NavLink>
                    <NavLink to="/gestion/stock" className={claseEnlace}>Stock</NavLink>
                  </>
                )}
                {hayDiagnostico && <NavLink to="/diagnostico" className={claseEnlace}>Diagnostico</NavLink>}
              </nav>

              <div className="usuario">
                {estado.empleado && <span className="rol">Empleado</span>}
                <span className="correo">{estado.usuario}</span>
                <button type="button" className="sutil" onClick={salir}>Salir</button>
              </div>
            </>
          ) : (
            <div className="usuario">
              <button type="button" className="sutil" onClick={() => void iniciarSesion()}>Iniciar sesion</button>
              <button type="button" className="primario" onClick={registrarse}>Crear cuenta</button>
            </div>
          )}
        </div>
      </header>

      <main>
        <Outlet />
      </main>

      <footer>
        <div className="contenedor">
          <span>Biblioteca Duoc · Prestamos en linea</span>
          <span>DSY1107 · EA1 Cloud Native en AWS</span>
        </div>
      </footer>
    </>
  );
}

/**
 * Lo que se ve al volver de Cognito mientras Amplify canjea el code por los
 * tokens. Ocupa la pantalla entera, sin cabecera: la cabecera todavia no sabe
 * si hay sesion y mostraria "Iniciar sesion" a quien acaba de hacerlo.
 */
function PantallaCarga() {
  return (
    <div className="pantalla-carga" role="status" aria-live="polite">
      <span className="logo"><IconoLibro tamano={30} /></span>
      <span className="girando" aria-hidden="true" />
      <p><strong>Iniciando sesion…</strong></p>
      <p className="tenue">Estamos validando tu acceso. Toma solo unos segundos.</p>
    </div>
  );
}
