/** Un libro tal como lo devuelve GET /api/libros (ver Libro.java en el backend). */
export interface Libro {
  id: string;
  titulo: string;
  autor: string;
  genero: string;
  anio: number;
  isbn: string;
  ejemplares: number;
  ubicacion: string;
  sinopsis: string;
  /** Campo calculado por el backend: ejemplares > 0 */
  disponible: boolean;
}

/**
 * GET /api/libros/stock-vivo (ProyeccionStock.java): el ultimo stock de cada
 * libro que CAMBIO, segun el consumidor de Kafka. Los que no aparecen no han
 * cambiado desde que existe el topico: vale lo que dijo GET /api/libros.
 */
export interface StockVivo {
  disponibles: Record<string, number>;
  /** Cuando se aplico el ultimo cambio; null si aun no llego ninguno. */
  actualizadoEn: string | null;
}
