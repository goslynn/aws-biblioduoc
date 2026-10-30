import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { awsConfig } from '../aws-config';
import { Libro } from './libro';

/**
 * Acceso al catalogo. Encapsula la URL de la API y la forma de las peticiones.
 *
 * Los componentes llaman a metodos con nombre de negocio ("buscar", "porId") y
 * no saben ni la URL ni que se viaja por HTTP. Del token no se ocupa nadie
 * aqui: lo pone authInterceptor.
 */
@Injectable({ providedIn: 'root' })
export class LibrosService {

  private readonly http = inject(HttpClient);
  private readonly base = `${awsConfig.apiBaseUrl}/api/libros`;

  /** GET /api/libros con los filtros que vengan informados. */
  buscar(texto: string, genero: string, soloDisponibles: boolean): Observable<Libro[]> {
    let params = new HttpParams();
    if (texto.trim()) {
      params = params.set('q', texto.trim());
    }
    if (genero) {
      params = params.set('genero', genero);
    }
    if (soloDisponibles) {
      params = params.set('soloDisponibles', 'true');
    }
    return this.http.get<Libro[]>(this.base, { params });
  }

  /** GET /api/libros/generos, para poblar el desplegable del filtro. */
  generos(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/generos`);
  }

  /** GET /api/libros/avisame -> ids de los libros agotados que espera el usuario. */
  esperando(): Observable<string[]> {
    return this.http.get<string[]>(`${this.base}/avisame`);
  }

  /**
   * POST /api/libros/{id}/avisame -> 201. Solo con un libro agotado (409 si
   * tiene stock). El correo llega cuando un empleado cargue ejemplares o se
   * registre una devolucion.
   */
  avisame(id: string): Observable<void> {
    return this.http.post<void>(`${this.base}/${id}/avisame`, null);
  }

  /** DELETE /api/libros/{id}/avisame -> 204. */
  dejarDeEsperar(id: string): Observable<void> {
    return this.http.delete<void>(`${this.base}/${id}/avisame`);
  }

  /** GET /api/libros/{id}. Un id inexistente devuelve 404, generado por Spring. */
  porId(id: string): Observable<Libro> {
    return this.http.get<Libro>(`${this.base}/${id}`);
  }
}
