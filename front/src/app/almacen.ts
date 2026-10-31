import { useSyncExternalStore } from 'react';

/**
 * Un valor compartido por toda la aplicacion, que los componentes leen con un
 * hook y que se puede leer y cambiar tambien FUERA de React.
 *
 * POR QUE ESTO Y NO UN CONTEXT
 *   La sesion la consultan las guardas de las rutas (los loader de React
 *   Router), que corren antes de pintar nada y fuera de cualquier componente:
 *   un Context no les llega. Con un valor de modulo, guardas y componentes
 *   leen el MISMO estado, igual que hacian los servicios singleton de Angular
 *   (providedIn: 'root').
 *
 * useSyncExternalStore es la pieza de React pensada justo para esto: cada vez
 * que el valor cambia, repinta los componentes que lo usan.
 */
export interface Almacen<T> {
  leer(): T;
  fijar(valor: T): void;
  actualizar(cambio: (actual: T) => T): void;
  /** Hook: el valor vigente, y repinta el componente cuando cambia. */
  usar(): T;
}

export function crearAlmacen<T>(inicial: T): Almacen<T> {
  let valor = inicial;
  const oyentes = new Set<() => void>();

  const suscribir = (oyente: () => void) => {
    oyentes.add(oyente);
    return () => oyentes.delete(oyente);
  };
  const leer = () => valor;
  const fijar = (nuevo: T) => {
    valor = nuevo;
    oyentes.forEach((oyente) => oyente());
  };

  return {
    leer,
    fijar,
    actualizar: (cambio) => fijar(cambio(valor)),
    usar: () => useSyncExternalStore(suscribir, leer),
  };
}
