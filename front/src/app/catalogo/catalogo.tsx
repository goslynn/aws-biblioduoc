import { useEffect, useState, type FormEvent } from 'react';
import { Link } from 'react-router';

import { statusDe } from '../auth/http';
import type { Libro } from './libro';
import { librosService } from './libros.service';
import { agregarASeleccion, useSeleccion } from './seleccion';

/**
 * El catalogo: buscador, filtros y la lista de libros.
 *
 * El filtrado lo hace el BACKEND, no este componente. Podria hacerse aqui
 * —son 20 libros— pero entonces el buscador no probaria nada: asi cada
 * busqueda es una peticion real que atraviesa auth/http.ts, API Gateway, el
 * authorizer y Spring.
 */
export function Catalogo() {
  const seleccion = useSeleccion();

  const [texto, setTexto] = useState('');
  const [genero, setGenero] = useState('');
  const [soloDisponibles, setSoloDisponibles] = useState(false);

  const [resultados, setResultados] = useState<Libro[]>([]);
  const [generos, setGeneros] = useState<string[]>([]);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');

  /** Ids de los libros agotados que este usuario espera ("avisame"). */
  const [esperando, setEsperando] = useState<Set<string>>(new Set());

  // Los filtros van como argumentos y no se leen del estado: "Limpiar" busca
  // con los valores vacios en el mismo clic, antes de que React repinte.
  const buscar = (q = texto, g = genero, disp = soloDisponibles) => {
    setCargando(true);
    setError('');
    librosService.buscar(q, g, disp).then(
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

  const limpiar = () => {
    setTexto('');
    setGenero('');
    setSoloDisponibles(false);
    buscar('', '', false);
  };

  return (
    <>
      <section className="panel">
        <h1>Catalogo</h1>
        <p className="tenue">{resultados.length} titulo(s).</p>

        <form className="filtros" onSubmit={enviar}>
          <div className="campo ancho">
            <label htmlFor="q">Buscar por titulo, autor o ISBN</label>
            <input id="q" name="q" value={texto} onChange={(e) => setTexto(e.target.value)}
                   placeholder="por ejemplo: parra, oauth, 978-…" />
          </div>

          <div className="campo">
            <label htmlFor="genero">Genero</label>
            <select id="genero" name="genero" value={genero} onChange={(e) => setGenero(e.target.value)}>
              <option value="">Todos</option>
              {generos.map((g) => <option key={g} value={g}>{g}</option>)}
            </select>
          </div>

          <div className="campo casilla">
            <label htmlFor="disp">
              <input id="disp" type="checkbox" name="disp" checked={soloDisponibles}
                     onChange={(e) => setSoloDisponibles(e.target.checked)} />
              Solo disponibles
            </label>
          </div>

          <div className="campo acciones">
            <button type="submit">Buscar</button>
            <button type="button" className="sutil" onClick={limpiar}>Limpiar</button>
          </div>
        </form>
      </section>

      {error && <p className="fallo">{error}</p>}

      {cargando ? (
        <p className="tenue">Cargando el catalogo…</p>
      ) : resultados.length === 0 && !error ? (
        <p className="aviso">Ningun libro coincide con la busqueda.</p>
      ) : null}

      <div className="rejilla">
        {resultados.map((libro) => (
          <article key={libro.id} className="panel libro">
            <header>
              <span className="etiqueta">{libro.genero}</span>
              {libro.disponible
                ? <span className="etiqueta si">{libro.ejemplares} disponible(s)</span>
                : <span className="etiqueta no">Sin ejemplares</span>}
            </header>

            <h3>{libro.titulo}</h3>
            <p className="tenue">{libro.autor} · {libro.anio}</p>
            <p className="sinopsis">{libro.sinopsis}</p>
            <p className="tenue"><code>{libro.id}</code> · ISBN {libro.isbn} · {libro.ubicacion}</p>

            {libro.disponible ? (
              <button type="button" disabled={seleccion.contiene(libro.id)} onClick={() => agregarASeleccion(libro)}>
                {seleccion.contiene(libro.id) ? 'Ya esta en tu solicitud' : 'Agregar a la solicitud'}
              </button>
            ) : (
              <button type="button" className={esperando.has(libro.id) ? 'sutil' : undefined}
                      onClick={() => alternarAvisame(libro)}>
                {esperando.has(libro.id) ? 'Dejar de avisarme' : 'Avisame cuando vuelva'}
              </button>
            )}
          </article>
        ))}
      </div>

      {!seleccion.vacia && (
        <aside className="panel barra">
          <span>{seleccion.cantidadTotal} ejemplar(es) seleccionado(s)</span>
          <Link to="/solicitud"><button type="button">Completar la solicitud</button></Link>
        </aside>
      )}
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
