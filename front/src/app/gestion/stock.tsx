import { useEffect, useState, type FormEvent } from 'react';

import { statusDe } from '../auth/http';
import type { Libro } from '../catalogo/libro';
import { librosService } from '../catalogo/libros.service';
import { gestionService } from './gestion.service';

/**
 * Carga de stock. Si un libro pasa de 0 a tener ejemplares, quienes pulsaron
 * "Avisame" reciben un correo (por la cola masiva de RabbitMQ): por eso los
 * agotados se muestran primero.
 */
export function Stock() {
  const [lista, setLista] = useState<Libro[]>([]);
  const [error, setError] = useState('');
  const [exito, setExito] = useState('');

  /** Cantidad a cargar por libro, editable en cada fila (1 si no se toco). */
  const [cantidades, setCantidades] = useState<Record<string, number>>({});

  /** Filtro local: la lista ya viene entera (son los mismos datos del catalogo). */
  const [filtro, setFiltro] = useState<'todos' | 'agotados' | 'bajos'>('todos');

  const cargar = () => {
    librosService.buscar('', '', false).then(
      (libros) => setLista([...libros].sort((a, b) => a.ejemplares - b.ejemplares)),
      (fallo) => setError(`HTTP ${statusDe(fallo)}: no se pudo cargar el catalogo.`),
    );
  };

  useEffect(cargar, []);

  const cargarStock = (evento: FormEvent, libro: Libro) => {
    evento.preventDefault();
    const cantidad = cantidades[libro.id] ?? 1;
    const estabaAgotado = !libro.disponible;
    gestionService.cargarStock(libro.id, cantidad).then(
      (actualizado) => {
        setError('');
        setExito(
          `${actualizado.titulo}: ahora hay ${actualizado.ejemplares} ejemplar(es).` +
          (estabaAgotado ? ' Quienes lo esperaban recibiran un aviso por correo.' : ''));
        cargar();
      },
      (fallo) => {
        setExito('');
        setError(statusDe(fallo) === 403
          ? 'HTTP 403: tu cuenta no es de empleado.'
          : `HTTP ${statusDe(fallo)}: no se pudo cargar el stock (la cantidad va de 1 a 100).`);
      },
    );
  };

  const agotados = lista.filter((l) => l.ejemplares === 0).length;
  const conUno = lista.filter((l) => l.ejemplares === 1).length;
  const vista = lista.filter((l) =>
    filtro === 'todos' || (filtro === 'agotados' ? l.ejemplares === 0 : l.ejemplares <= 1));

  return (
    <>
      <section className="encabezado">
        <div className="contenedor">
          <div className="titulo-pagina">
            <div>
              <h1>Stock</h1>
              <p className="bajada">
                Repone ejemplares. Los agotados van primero: al cargar uno, cada estudiante que
                pulso "Avisame" recibe un correo.
              </p>
            </div>
          </div>

          <dl className="cifras-stock">
            <div><dt>Titulos</dt><dd>{lista.length}</dd></div>
            <div><dt>Ejemplares en estante</dt><dd>{lista.reduce((suma, l) => suma + l.ejemplares, 0)}</dd></div>
            <div className="rojo"><dt>Agotados</dt><dd>{agotados}</dd></div>
            <div className="ambar"><dt>Con un ejemplar</dt><dd>{conUno}</dd></div>
          </dl>

          <div className="chips" role="group" aria-label="Filtrar">
            <button type="button" className="chip" aria-pressed={filtro === 'todos'} onClick={() => setFiltro('todos')}>Todos</button>
            <button type="button" className="chip" aria-pressed={filtro === 'agotados'} onClick={() => setFiltro('agotados')}>Agotados</button>
            <button type="button" className="chip" aria-pressed={filtro === 'bajos'} onClick={() => setFiltro('bajos')}>Por agotarse</button>
          </div>
        </div>
      </section>

      <div className="contenedor cuerpo">
        {exito && <p className="exito" role="status">{exito}</p>}
        {error && <p className="fallo" role="alert">{error}</p>}

        {vista.length === 0 && !error ? (
          <div className="vacio">
            <h2>Nada que reponer</h2>
            <p>Ningun libro coincide con este filtro.</p>
          </div>
        ) : (
          <div className="tabla">
            <table>
              <thead>
                <tr>
                  <th scope="col">Libro</th>
                  <th scope="col">Genero</th>
                  <th scope="col">Ubicacion</th>
                  <th scope="col">En estante</th>
                  <th scope="col">Cargar ejemplares</th>
                </tr>
              </thead>
              <tbody>
                {vista.map((libro) => (
                  <tr key={libro.id}>
                    <td>
                      <span className="titulo-libro"><strong>{libro.titulo}</strong><small>{libro.id} · {libro.autor}</small></span>
                    </td>
                    <td className="gris">{libro.genero}</td>
                    <td className="gris">{libro.ubicacion}</td>
                    <td>
                      {libro.ejemplares === 0 ? <span className="etiqueta no">Agotado</span>
                        : libro.ejemplares === 1 ? <span className="etiqueta bajo">1 ejemplar</span>
                          : <strong>{libro.ejemplares}</strong>}
                    </td>
                    <td>
                      <form className="cargar" onSubmit={(e) => cargarStock(e, libro)}>
                        <label htmlFor={`cantidad-${libro.id}`} className="solo-lectores">Ejemplares a cargar de {libro.titulo}</label>
                        <input id={`cantidad-${libro.id}`} name={`cantidad-${libro.id}`} type="number" min={1} max={100}
                               value={cantidades[libro.id] ?? 1}
                               onChange={(e) => setCantidades({ ...cantidades, [libro.id]: e.target.valueAsNumber })} />
                        <button type="submit">Cargar</button>
                      </form>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </div>
    </>
  );
}
