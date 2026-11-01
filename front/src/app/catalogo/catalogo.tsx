import { useEffect, useMemo, useState, type FormEvent } from 'react';
import { Link } from 'react-router';

import { statusDe } from '../auth/http';
import { IconoCampana, IconoCerrar, IconoCheck, IconoFiltros, IconoFlechaAbajo, IconoLupa, IconoMas } from '../iconos';
import type { Libro } from './libro';
import { librosService } from './libros.service';
import { Quitar } from './quitar';
import { agregarASeleccion, quitarDeSeleccion, useSeleccion } from './seleccion';
import { Tapa } from './tapa';

type Disponibilidad = 'todos' | 'disponibles' | 'agotados';
type Orden = 'relevancia' | 'titulo' | 'recientes' | 'antiguos' | 'ejemplares';

const ORDENES: Record<Orden, string> = {
  relevancia: 'Relevancia',
  titulo: 'Titulo (A-Z)',
  recientes: 'Mas recientes',
  antiguos: 'Mas antiguos',
  ejemplares: 'Mas ejemplares',
};

/** Si el panel de filtros queda abierto entre visitas. Es comodidad: si falla, se abre segun el ancho. */
const CLAVE_PANEL = 'catalogo.filtros-abiertos';

function panelInicial(): boolean {
  try {
    const guardado = localStorage.getItem(CLAVE_PANEL);
    if (guardado !== null) {
      return guardado === 'true';
    }
  } catch {
    // Almacenamiento bloqueado: se decide por el ancho de la pantalla.
  }
  return window.matchMedia('(min-width: 900px)').matches;
}

/** Sin tildes ni mayusculas: "garcia" encuentra "Garcia" y "García". */
const normalizar = (texto: string) => texto.normalize('NFD').replace(/[̀-ͯ]/g, '').toLowerCase();

/**
 * El catalogo: buscador, panel de filtros y la lista de libros.
 *
 * El filtrado se reparte en dos capas, y a proposito:
 *   * El texto, el genero y "con ejemplares" los resuelve el BACKEND: cada
 *     cambio es una peticion real que atraviesa auth/http.ts, API Gateway, el
 *     authorizer y Spring (GET /api/libros?q=&genero=&soloDisponibles=).
 *   * Autor, fecha de publicacion, ubicacion, "agotados" y el orden AFINAN en el
 *     navegador la lista que devolvio la API, que no recibe esos parametros.
 *     Son 20 libros: pedirlos otra vez para reordenarlos no aporta nada.
 */
export function Catalogo() {
  const seleccion = useSeleccion();

  // Filtros que viajan a la API.
  const [texto, setTexto] = useState('');
  const [genero, setGenero] = useState('');
  const [disponibilidad, setDisponibilidad] = useState<Disponibilidad>('todos');

  // Filtros que afinan la respuesta en el navegador.
  const [autor, setAutor] = useState('');
  const [anioDesde, setAnioDesde] = useState('');
  const [anioHasta, setAnioHasta] = useState('');
  const [ubicacion, setUbicacion] = useState('');
  const [orden, setOrden] = useState<Orden>('relevancia');

  const [panelAbierto, setPanelAbierto] = useState(panelInicial);

  const [resultados, setResultados] = useState<Libro[]>([]);
  const [generos, setGeneros] = useState<string[]>([]);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');

  /** Ids de los libros agotados que este usuario espera ("avisame"). */
  const [esperando, setEsperando] = useState<Set<string>>(new Set());

  // Los filtros van como argumentos y no se leen del estado: "Limpiar" busca
  // con los valores vacios en el mismo clic, antes de que React repinte.
  const buscar = (q = texto, g = genero, disp = disponibilidad) => {
    setCargando(true);
    setError('');
    librosService.buscar(q, g, disp === 'disponibles').then(
      (libros) => {
        setResultados(libros);
        setCargando(false);
      },
      (fallo) => {
        // Se muestra el codigo HTTP a proposito: 401 (sin token, token
        // invalido, o token sin el scope), 403 (ruta que el gateway no conoce)
        // y 500 se diagnostican distinto, y esconderlos detras de "algo salio
        // mal" no ayuda a nadie.
        setError(describir(statusDe(fallo)));
        setResultados([]);
        setCargando(false);
      },
    );
  };

  useEffect(() => {
    librosService.esperando().then(
      (ids) => setEsperando(new Set(ids)),
      () => setEsperando(new Set()),
    );
    librosService.generos().then(setGeneros, () => setGeneros([]));
    buscar();
  }, []);

  const alternarPanel = () => {
    const abierto = !panelAbierto;
    setPanelAbierto(abierto);
    try {
      localStorage.setItem(CLAVE_PANEL, String(abierto));
    } catch {
      // Sin almacenamiento, el panel simplemente no se recuerda.
    }
  };

  const enviar = (evento: FormEvent) => {
    evento.preventDefault();
    buscar();
  };

  /**
   * "Avisame cuando vuelva" en un libro agotado. El aviso llega por correo
   * cuando un empleado cargue stock o se devuelva un ejemplar, si los avisos
   * estan activos (en "Mis solicitudes").
   */
  const alternarAvisame = (libro: Libro) => {
    const espera = esperando.has(libro.id);
    const peticion = espera ? librosService.dejarDeEsperar(libro.id) : librosService.avisame(libro.id);
    peticion.then(
      () => setEsperando((actual) => {
        const nuevo = new Set(actual);
        if (espera) { nuevo.delete(libro.id); } else { nuevo.add(libro.id); }
        return nuevo;
      }),
      // 409: alguien cargo stock entretanto. Se recarga para mostrarlo.
      (fallo) => (statusDe(fallo) === 409 ? buscar() : setError(describir(statusDe(fallo)))),
    );
  };

  const elegirGenero = (g: string) => {
    setGenero(g);
    buscar(texto, g, disponibilidad);
  };

  /** "Agotados" no es un parametro de la API: se piden todos y se afina aqui. */
  const elegirDisponibilidad = (disp: Disponibilidad) => {
    setDisponibilidad(disp);
    if ((disp === 'disponibles') !== (disponibilidad === 'disponibles')) {
      buscar(texto, genero, disp);
    }
  };

  const limpiar = () => {
    setTexto('');
    setGenero('');
    setDisponibilidad('todos');
    setAutor('');
    setAnioDesde('');
    setAnioHasta('');
    setUbicacion('');
    buscar('', '', 'todos');
  };

  // Lo que ofrece cada filtro sale de la respuesta: no hay listas fijas.
  const autores = useMemo(() => [...new Set(resultados.map((l) => l.autor))].sort(), [resultados]);
  const ubicaciones = useMemo(() => [...new Set(resultados.map((l) => l.ubicacion))].sort(), [resultados]);
  const anios = resultados.map((l) => l.anio);
  const anioMin = anios.length ? Math.min(...anios) : undefined;
  const anioMax = anios.length ? Math.max(...anios) : undefined;

  const visibles = useMemo(() => {
    const buscado = normalizar(autor.trim());
    const desde = anioDesde ? Number(anioDesde) : -Infinity;
    const hasta = anioHasta ? Number(anioHasta) : Infinity;
    const filtrados = resultados.filter((l) =>
      (disponibilidad !== 'agotados' || !l.disponible)
      && (!buscado || normalizar(l.autor).includes(buscado))
      && l.anio >= desde && l.anio <= hasta
      && (!ubicacion || l.ubicacion === ubicacion));
    const comparar: Record<Orden, ((a: Libro, b: Libro) => number) | null> = {
      relevancia: null,
      titulo: (a, b) => a.titulo.localeCompare(b.titulo, 'es'),
      recientes: (a, b) => b.anio - a.anio,
      antiguos: (a, b) => a.anio - b.anio,
      ejemplares: (a, b) => b.ejemplares - a.ejemplares,
    };
    const criterio = comparar[orden];
    return criterio ? [...filtrados].sort(criterio) : filtrados;
  }, [resultados, disponibilidad, autor, anioDesde, anioHasta, ubicacion, orden]);

  /** Cada filtro activo, como chip que se quita de un toque. */
  const activos: { clave: string; texto: string; quitar: () => void }[] = [
    ...(genero ? [{ clave: 'genero', texto: genero, quitar: () => elegirGenero('') }] : []),
    ...(disponibilidad !== 'todos' ? [{
      clave: 'disp',
      texto: disponibilidad === 'disponibles' ? 'Con ejemplares' : 'Agotados',
      quitar: () => elegirDisponibilidad('todos'),
    }] : []),
    ...(autor.trim() ? [{ clave: 'autor', texto: `Autor: ${autor.trim()}`, quitar: () => setAutor('') }] : []),
    ...(anioDesde ? [{ clave: 'desde', texto: `Desde ${anioDesde}`, quitar: () => setAnioDesde('') }] : []),
    ...(anioHasta ? [{ clave: 'hasta', texto: `Hasta ${anioHasta}`, quitar: () => setAnioHasta('') }] : []),
    ...(ubicacion ? [{ clave: 'ubic', texto: ubicacion, quitar: () => setUbicacion('') }] : []),
  ];
  const hayFiltros = texto.trim() !== '' || activos.length > 0;
  const rangoInvalido = anioDesde !== '' && anioHasta !== '' && Number(anioDesde) > Number(anioHasta);

  return (
    <>
      <section className="encabezado">
        <div className="contenedor">
          <div className="titulo-pagina">
            <div>
              <h1>Catalogo</h1>
              <p className="bajada">Encuentra lo que necesitas y agregalo a tu solicitud.</p>
            </div>
          </div>

          <form role="search" className="buscador" onSubmit={enviar}>
            <label htmlFor="q" className="solo-lectores">Buscar por titulo, autor o ISBN</label>
            <div className="caja">
              <IconoLupa />
              <input id="q" name="q" type="search" value={texto} onChange={(e) => setTexto(e.target.value)}
                     placeholder="Titulo, autor o ISBN: por ejemplo parra, oauth, 978-…" />
            </div>
            <button type="submit">Buscar</button>
          </form>
        </div>
      </section>

      <div className={panelAbierto ? 'contenedor catalogo' : 'contenedor catalogo sin-filtros'}>
        {panelAbierto && (
          <aside id="panel-filtros" className="panel panel-filtros" aria-labelledby="titulo-filtros">
            <div className="cabeza">
              <h2 id="titulo-filtros">Filtros</h2>
              {activos.length > 0 && <button type="button" className="enlace" onClick={limpiar}>Limpiar</button>}
              <button type="button" className="sutil icono" onClick={alternarPanel} aria-label="Ocultar filtros">
                <IconoCerrar />
              </button>
            </div>

            <fieldset>
              <legend>Genero</legend>
              <label className="opcion">
                <input type="radio" name="genero" checked={genero === ''} onChange={() => elegirGenero('')} />
                Todos
              </label>
              {generos.map((g) => (
                <label key={g} className="opcion">
                  <input type="radio" name="genero" checked={genero === g} onChange={() => elegirGenero(g)} />
                  {g}
                </label>
              ))}
            </fieldset>

            <fieldset>
              <legend>Disponibilidad</legend>
              {(['todos', 'disponibles', 'agotados'] as const).map((d) => (
                <label key={d} className="opcion">
                  <input type="radio" name="disponibilidad" checked={disponibilidad === d}
                         onChange={() => elegirDisponibilidad(d)} />
                  {d === 'todos' ? 'Todos' : d === 'disponibles' ? 'Con ejemplares' : 'Agotados'}
                </label>
              ))}
            </fieldset>

            <div className="campo">
              <label htmlFor="autor">Autor</label>
              <input id="autor" name="autor" list="autores" value={autor} autoComplete="off"
                     onChange={(e) => setAutor(e.target.value)} placeholder="Ej. Martin" />
              <datalist id="autores">
                {autores.map((a) => <option key={a} value={a} />)}
              </datalist>
            </div>

            <fieldset>
              <legend>Publicado entre</legend>
              <div className="rango">
                <label>
                  <span className="solo-lectores">Desde</span>
                  <input name="desde" type="number" inputMode="numeric" value={anioDesde}
                         min={anioMin} max={anioMax} placeholder={anioMin ? String(anioMin) : 'Desde'}
                         onChange={(e) => setAnioDesde(e.target.value)} aria-invalid={rangoInvalido} />
                </label>
                <span aria-hidden="true">–</span>
                <label>
                  <span className="solo-lectores">Hasta</span>
                  <input name="hasta" type="number" inputMode="numeric" value={anioHasta}
                         min={anioMin} max={anioMax} placeholder={anioMax ? String(anioMax) : 'Hasta'}
                         onChange={(e) => setAnioHasta(e.target.value)} aria-invalid={rangoInvalido} />
                </label>
              </div>
              {rangoInvalido && <p className="ayuda error-campo">"Desde" no puede ser mayor que "Hasta".</p>}
            </fieldset>

            <div className="campo">
              <label htmlFor="ubicacion">Ubicacion</label>
              <div className="desplegable">
                <select id="ubicacion" name="ubicacion" value={ubicacion} onChange={(e) => setUbicacion(e.target.value)}>
                  <option value="">Todas</option>
                  {ubicaciones.map((u) => <option key={u} value={u}>{u}</option>)}
                </select>
                <IconoFlechaAbajo />
              </div>
            </div>
          </aside>
        )}

        <section className="principal" aria-label="Resultados">
          <div className="barra-resultados">
            <button type="button" className="secundario" onClick={alternarPanel}
                    aria-expanded={panelAbierto} aria-controls="panel-filtros">
              <IconoFiltros />{panelAbierto ? 'Ocultar filtros' : 'Filtros'}
              {!panelAbierto && activos.length > 0 && <span className="contador">{activos.length}</span>}
            </button>
            <p className="tenue" aria-live="polite">
              {cargando ? 'Cargando el catalogo…' : visibles.length === resultados.length
                ? <><strong>{resultados.length}</strong> titulo(s)</>
                : <><strong>{visibles.length}</strong> de {resultados.length} titulo(s)</>}
            </p>
            <label className="ordenar">
              <span>Ordenar</span>
              <span className="desplegable">
                <select name="orden" value={orden} onChange={(e) => setOrden(e.target.value as Orden)}>
                  {(Object.keys(ORDENES) as Orden[]).map((o) => <option key={o} value={o}>{ORDENES[o]}</option>)}
                </select>
                <IconoFlechaAbajo />
              </span>
            </label>
          </div>

          {activos.length > 0 && (
            <ul className="activos" aria-label="Filtros aplicados">
              {activos.map((f) => (
                <li key={f.clave}>
                  <button type="button" className="chip" onClick={f.quitar} aria-label={`Quitar filtro ${f.texto}`}>
                    {f.texto}<IconoCerrar tamano={14} />
                  </button>
                </li>
              ))}
              <li><button type="button" className="enlace" onClick={limpiar}>Limpiar todo</button></li>
            </ul>
          )}

          {error && <p className="fallo">{error}</p>}

          {!cargando && visibles.length === 0 && !error && (
            <div className="vacio">
              <h2>Ningun libro coincide</h2>
              <p>Prueba con otra palabra o quita algun filtro.</p>
              {hayFiltros && <button type="button" className="secundario" onClick={limpiar}>Quitar filtros</button>}
            </div>
          )}

          {visibles.map((libro) => (
            <article key={libro.id} className="libro">
              <Tapa titulo={libro.titulo} genero={libro.genero} />

              <div className="datos-libro">
                <span className="meta">{libro.genero} · {libro.anio}</span>
                <h2>{libro.titulo}</h2>
                <p className="autor">{libro.autor}</p>
                <p className="sinopsis">{libro.sinopsis}</p>
                <p className="tenue">{libro.ubicacion} · ISBN {libro.isbn} · <code>{libro.id}</code></p>
              </div>

              <div className="accion">
                {libro.disponible ? (
                  <>
                    <span className="etiqueta si">{libro.ejemplares} disponible(s)</span>
                    {seleccion.contiene(libro.id) ? (
                      <button type="button" className="hecho" onClick={() => quitarDeSeleccion(libro.id)}
                              aria-label={`${libro.titulo} esta en tu solicitud. Quitar`}>
                        <IconoCheck />En tu solicitud
                      </button>
                    ) : (
                      <button type="button" className="primario" onClick={() => agregarASeleccion(libro)}>
                        <IconoMas />Agregar
                      </button>
                    )}
                  </>
                ) : (
                  <>
                    <span className="etiqueta">Agotado</span>
                    {esperando.has(libro.id) ? (
                      <>
                        <button type="button" className="secundario" onClick={() => alternarAvisame(libro)}
                                aria-label={`Te avisaremos cuando vuelva ${libro.titulo}. Dejar de esperarlo`}>
                          <IconoCampana />Te avisaremos
                        </button>
                        <small>Toca para dejar de esperarlo</small>
                      </>
                    ) : (
                      <button type="button" onClick={() => alternarAvisame(libro)}>Avisame cuando vuelva</button>
                    )}
                  </>
                )}
              </div>
            </article>
          ))}
        </section>

        <aside className="lateral panel resumen" aria-labelledby="tu-solicitud">
          <div className="cabeza">
            <h2 id="tu-solicitud">Tu solicitud</h2>
            <span className="tenue">{seleccion.total} libro(s)</span>
          </div>
          {seleccion.vacia ? (
            <p className="bajada">
              Aun no agregas libros. Usa <strong>Agregar</strong> en los que necesites y los juntamos aqui.
            </p>
          ) : (
            <>
              <ul className="lista-libros">
                {seleccion.items.map((libro) => (
                  <li key={libro.id}>
                    <Tapa titulo={libro.titulo} genero={libro.genero} chica />
                    <span>
                      <strong>{libro.titulo}</strong>
                      <small>{libro.autor}</small>
                    </span>
                    <Quitar libro={libro} />
                  </li>
                ))}
              </ul>
              <Link to="/solicitud" className="boton primario grande">Continuar con la solicitud</Link>
              <p className="tenue">Un ejemplar por libro. En el siguiente paso eliges la sede de retiro y los dias de prestamo.</p>
            </>
          )}
        </aside>
      </div>
    </>
  );
}

function describir(status: number): string {
  switch (status) {
    case 0:   return 'No hubo respuesta (error de red o de CORS). Revisa la pestana Network.';
    case 401: return 'HTTP 401: el token falta, no es valido, o no trae el scope que exige la API. Vuelve a iniciar sesion.';
    case 403: return 'HTTP 403: el gateway no reconoce esa ruta. Si la ruta es correcta, revisa el scope del token.';
    default:  return `HTTP ${status}: no se pudo cargar el catalogo.`;
  }
}
