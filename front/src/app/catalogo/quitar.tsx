import { IconoCerrar } from '../iconos';
import type { Libro } from './libro';
import { quitarDeSeleccion } from './seleccion';

/**
 * Saca un libro de la solicitud. Va en cada linea del resumen del catalogo y
 * del formulario; la cantidad no se edita (siempre es un ejemplar).
 */
export function Quitar({ libro }: { libro: Libro }) {
  return (
    <button type="button" className="sutil icono quitar" onClick={() => quitarDeSeleccion(libro.id)}
            aria-label={`Quitar ${libro.titulo} de la solicitud`} title="Quitar">
      <IconoCerrar />
    </button>
  );
}
