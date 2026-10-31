import { useEffect } from 'react';
import { NavLink, Outlet, useLocation, useMatches, useNavigate } from 'react-router';

import { cerrarSesion, iniciarSesion, refrescar, registrarse, useSesion } from './auth/sesion';
import { useSeleccion, vaciarSeleccion } from './catalogo/seleccion';
import { rutasDiagnostico } from './diagnostico/rutas';
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
   * sentido para quien todavia no ha entrado.
   */
  useEffect(() => {
    void refrescar().then((sesion) => {
      if (sesion.autenticado && ubicacion.pathname.startsWith('/inicio')) {
        void navegar('/catalogo');
      }
    });
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

  const salir = () => {
    vaciarSeleccion();
    void cerrarSesion();
  };

  return (
    <>
      <header className="cabecera">
        <div className="marca">
          <span className="logo" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="28" height="28" fill="none" stroke="currentColor"
                 strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 6.5c-1.6-1.2-3.6-1.8-6-1.8v13c2.4 0 4.4.6 6 1.8 1.6-1.2 3.6-1.8 6-1.8v-13c-2.4 0-4.4.6-6 1.8Z" />
              <path d="M12 6.5v13" />
            </svg>
          </span>
          <div>
            <strong>Biblioteca Duoc</strong>
            <span className="tenue">Plataforma interna de prestamos</span>
          </div>
        </div>

        {estado.autenticado ? (
          <>
            <nav>
              <NavLink to="/catalogo" className={claseEnlace}>Catalogo</NavLink>
              <NavLink to="/solicitud" className={claseEnlace}>
                Solicitud
                {seleccion.cantidadTotal > 0 && <span className="contador">{seleccion.cantidadTotal}</span>}
              </NavLink>
              <NavLink to="/mis-solicitudes" className={claseEnlace}>Mis solicitudes</NavLink>
              {estado.empleado && (
                <>
                  <NavLink to="/gestion/solicitudes" className={claseEnlace}>Bandeja</NavLink>
                  <NavLink to="/gestion/stock" className={claseEnlace}>Stock</NavLink>
                </>
              )}
              {hayDiagnostico && <NavLink to="/diagnostico" className={claseEnlace}>Diagnostico</NavLink>}
            </nav>

            <div className="usuario">
              <span className="tenue">{estado.usuario}</span>
              <button type="button" className="sutil" onClick={salir}>Cerrar sesion</button>
            </div>
          </>
        ) : (
          <div className="acciones-header">
            <button type="button" className="sutil" onClick={() => void iniciarSesion()}>Iniciar sesion</button>
            <button type="button" onClick={registrarse}>Crear cuenta</button>
          </div>
        )}
      </header>

      <main>
        <Outlet />
      </main>

      <footer className="tenue">
        DSY1107 · EA1 Cloud Native en AWS
      </footer>
    </>
  );
}
