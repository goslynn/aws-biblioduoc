import { http } from '../auth/http';
import type { Libro } from './libro';

/**
 * Acceso al catalogo. Encapsula la URL de la API y la forma de las peticiones.
 *
 * Los componentes llaman a funciones con nombre de negocio ("buscar", "porId")
 * y no saben ni la URL ni que se viaja por HTTP. Del token no se ocupa nadie
 * aqui: lo pone auth/http.ts.
 */
const base = '/api/libros';

export const librosService = {
  /** GET /api/libros con los filtros que vengan informados. */
  buscar(texto: string, genero: string, soloDisponibles: boolean): Promise<Libro[]> {
    return http.get<Libro[]>(base, {
      q: texto.trim() || undefined,
      genero: genero || undefined,
      soloDisponibles: soloDisponibles ? 'true' : undefined,
    });
  },

  /** GET /api/libros/generos, para poblar el desplegable del filtro. */
  generos(): Promise<string[]> {
    return http.get<string[]>(`${base}/generos`);
  },

  /** GET /api/libros/avisame -> ids de los libros agotados que espera el usuario. */
  esperando(): Promise<string[]> {
    return http.get<string[]>(`${base}/avisame`);
  },

  /**
   * POST /api/libros/{id}/avisame -> 201. Solo con un libro agotado (409 si
   * tiene stock). El correo llega cuando un empleado cargue ejemplares o se
   * registre una devolucion.
   */
  avisame(id: string): Promise<void> {
    return http.post<void>(`${base}/${id}/avisame`);
  },

  /** DELETE /api/libros/{id}/avisame -> 204. */
  dejarDeEsperar(id: string): Promise<void> {
    return http.delete<void>(`${base}/${id}/avisame`);
  },

  /** GET /api/libros/{id}. Un id inexistente devuelve 404, generado por Spring. */
  porId(id: string): Promise<Libro> {
    return http.get<Libro>(`${base}/${id}`);
  },
};
