import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { awsConfig } from '../aws-config';
import { NuevaSolicitud, Solicitud } from './solicitud';

/**
 * Solicitudes de prestamo.
 *
 * Fijate en lo que NO se manda al crear: el solicitante. El backend lo saca
 * del JWT ya validado. Si viniera del formulario, cualquiera podria pedir
 * libros a nombre de otra persona.
 */
@Injectable({ providedIn: 'root' })
export class SolicitudesService {

  private readonly http = inject(HttpClient);
  private readonly base = `${awsConfig.apiBaseUrl}/api/solicitudes`;

  /** POST /api/solicitudes -> 201. Puede dar 400 (validacion), 404 o 409. */
  crear(solicitud: NuevaSolicitud): Observable<Solicitud> {
    return this.http.post<Solicitud>(this.base, solicitud);
  }

  /**
   * GET /api/solicitudes -> solo las del portador del token. El filtro lo
   * aplica el backend con el sub del JWT; la bandeja completa es de
   * empleados (GestionService).
   */
  mias(): Observable<Solicitud[]> {
    return this.http.get<Solicitud[]>(this.base);
  }
}
