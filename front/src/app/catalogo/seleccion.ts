import { crearAlmacen } from '../almacen';
import type { Libro } from './libro';

/** Una linea de la seleccion: el libro elegido y cuantas copias. */
export interface LineaSeleccionada {
  libro: Libro;
  cantidad: number;
}

/*
 * La "bolsa" de libros que el usuario va eligiendo en el catalogo y que
 * despues envia en una sola solicitud.
 *
 * Vive SOLO en el navegador y no se guarda en ningun sitio: es un borrador. El
 * backend solo se entera cuando se envia el formulario. Se pierde al recargar,
 * y eso esta bien: no es informacion que valga la pena persistir.
 *
 * Es un almacen de modulo y no estado de un componente porque la comparten
 * tres sitios que no se contienen entre si: el catalogo, el formulario y el
 * contador de la cabecera.
 */
const lineas = crearAlmacen<LineaSeleccionada[]>([]);

/** Anade el libro, o le suma una copia si ya estaba (tope de 3, como el backend). */
export function agregarASeleccion(libro: Libro): void {
  lineas.actualizar((actuales) => {
    const existente = actuales.find((l) => l.libro.id === libro.id);
    if (!existente) {
      return [...actuales, { libro, cantidad: 1 }];
    }
    const tope = Math.min(3, libro.ejemplares);
    return actuales.map((l) =>
      l.libro.id === libro.id ? { ...l, cantidad: Math.min(tope, l.cantidad + 1) } : l,
    );
  });
}

export function quitarDeSeleccion(id: string): void {
  lineas.actualizar((actuales) => actuales.filter((l) => l.libro.id !== id));
}

export function vaciarSeleccion(): void {
  lineas.fijar([]);
}

/** Hook: la seleccion vigente y lo que se deriva de ella. */
export function useSeleccion() {
  const items = lineas.usar();
  return {
    items,
    cantidadTotal: items.reduce((suma, l) => suma + l.cantidad, 0),
    vacia: items.length === 0,
    contiene: (id: string) => items.some((l) => l.libro.id === id),
  };
}
