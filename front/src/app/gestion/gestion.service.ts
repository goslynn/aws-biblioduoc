import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { awsConfig } from '../aws-config';
import { Libro } from '../catalogo/libro';
import { EstadoSolicitud, Solicitud } from '../solicitudes/solicitud';

/**
 * Las rutas de EMPLEADO (/api/gestion/**).
 *
 * Que solo las use un empleado lo decide SPRING: un estudiante que llamara a
 * estos metodos recibiria 403. Las vistas que los usan estan detras de
 * empleadoGuard solo para no ensenarle botones que no le van a funcionar.
 *
 * Cada accion responde con la solicitud ya cambiada; el correo al estudiante
 * sale despues, por RabbitMQ, sin que esta peticion lo espere.
 */
@Injectable({ providedIn: 'root' })
export class GestionService {

  private readonly http = inject(HttpClient);
  private readonly base = `${awsConfig.apiBaseUrl}/api/gestion`;

  /** GET /api/gestion/solicitudes?estado= -> la bandeja completa, o filtrada. */
  solicitudes(estado: EstadoSolicitud | ''): Observable<Solicitud[]> {
    const params = estado ? new HttpParams().set('estado', estado) : new HttpParams();
    return this.http.get<Solicitud[]>(`${this.base}/solicitudes`, { params });
  }

  /** 200; 409 si ya no esta RECIBIDA o ya no alcanza el stock. */
  aprobar(id: string): Observable<Solicitud> {
    return this.http.post<Solicitud>(`${this.base}/solicitudes/${id}/aprobacion`, null);
  }

  /** 200; 400 sin motivo; 409 si ya no esta RECIBIDA. */
  rechazar(id: string, motivo: string): Observable<Solicitud> {
    return this.http.post<Solicitud>(`${this.base}/solicitudes/${id}/rechazo`, { motivo });
  }

  /** 200; 409 si no estaba APROBADA. Repone el stock (y dispara los "avisame"). */
  devolver(id: string): Observable<Solicitud> {
    return this.http.post<Solicitud>(`${this.base}/solicitudes/${id}/devolucion`, null);
  }

  /** 200 con el libro actualizado; 400 si la cantidad no esta entre 1 y 100. */
  cargarStock(id: string, cantidad: number): Observable<Libro> {
    return this.http.post<Libro>(`${this.base}/libros/${id}/stock`, { cantidad });
  }
}
