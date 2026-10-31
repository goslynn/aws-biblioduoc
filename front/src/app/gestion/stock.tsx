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

  return (
    <>
      <section className="panel">
        <h1>Stock</h1>
        <p className="tenue">
          Los libros agotados van primero. Al cargar uno que estaba en 0, cada
          estudiante que pulso "Avisame" recibe un correo.
        </p>
      </section>

      {exito && <p className="exito">{exito}</p>}
      {error && <p className="fallo">{error}</p>}

      <div className="rejilla">
        {lista.map((libro) => (
          <article key={libro.id} className="panel libro">
            <header>
              <span className="etiqueta">{libro.genero}</span>
              {libro.disponible
                ? <span className="etiqueta si">{libro.ejemplares} disponible(s)</span>
                : <span className="etiqueta no">Sin ejemplares</span>}
            </header>
            <h3>{libro.titulo}</h3>
            <p className="tenue"><code>{libro.id}</code> · {libro.ubicacion}</p>

            <form className="filtros" onSubmit={(e) => cargarStock(e, libro)}>
              <div className="campo">
                <label htmlFor={`cantidad-${libro.id}`}>Ejemplares a cargar</label>
                <input id={`cantidad-${libro.id}`} name={`cantidad-${libro.id}`} type="number" min={1} max={100}
                       value={cantidades[libro.id] ?? 1}
                       onChange={(e) => setCantidades({ ...cantidades, [libro.id]: e.target.valueAsNumber })} />
              </div>
              <div className="campo acciones">
                <button type="submit">Cargar</button>
              </div>
            </form>
          </article>
        ))}
      </div>
    </>
  );
}
