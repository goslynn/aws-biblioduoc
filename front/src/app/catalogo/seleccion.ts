import { crearAlmacen } from '../almacen';
import type { Libro } from './libro';

/*
 * La "bolsa" de libros que el usuario va eligiendo en el catalogo y que
 * despues envia en una sola solicitud.
 *
 * UN EJEMPLAR POR LIBRO, sin cantidad editable: un estudiante no necesita dos
 * copias del mismo titulo, y asi el stock alcanza para mas personas. La API
 * admite hasta 3 por linea (la usa tambien Bruno); el limite de 1 es una
 * decision de esta interfaz, y por eso la solicitud se arma con cantidad 1.
 *
 * Vive SOLO en el navegador y no se guarda en ningun sitio: es un borrador. El
 * backend solo se entera cuando se envia el formulario. Se pierde al recargar,
 * y eso esta bien: no es informacion que valga la pena persistir.
 *
 * Es un almacen de modulo y no estado de un componente porque la comparten
 * tres sitios que no se contienen entre si: el catalogo, el formulario y el
 * contador de la cabecera.
 */
const libros = crearAlmacen<Libro[]>([]);

/** Anade el libro; si ya estaba, no hace nada. */
export function agregarASeleccion(libro: Libro): void {
  libros.actualizar((actuales) => (actuales.some((l) => l.id === libro.id) ? actuales : [...actuales, libro]));
}

export function quitarDeSeleccion(id: string): void {
  libros.actualizar((actuales) => actuales.filter((l) => l.id !== id));
}

export function vaciarSeleccion(): void {
  libros.fijar([]);
}

/** Hook: la seleccion vigente y lo que se deriva de ella. */
export function useSeleccion() {
  const items = libros.usar();
  return {
    items,
    total: items.length,
    vacia: items.length === 0,
    contiene: (id: string) => items.some((l) => l.id === id),
  };
}
