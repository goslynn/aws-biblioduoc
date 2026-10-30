import { HttpClient } from '@angular/common/http';
import { Injectable, inject } from '@angular/core';
import { Observable } from 'rxjs';

import { awsConfig } from '../aws-config';

/** GET /api/avisos/correo (ContactoController.EstadoAvisos). */
export interface EstadoAvisos {
  activo: boolean;
  email: string | null;
}

/**
 * Los avisos por correo: el consentimiento.
 *
 * Fijate en que activar NO manda ningun correo en el cuerpo. El backend lo
 * lee de Cognito (email verificado) por el sub del token: si viniera de aqui,
 * cualquiera podria mandar avisos al correo de otra persona.
 */
@Injectable({ providedIn: 'root' })
export class AvisosService {

  private readonly http = inject(HttpClient);
  private readonly url = `${awsConfig.apiBaseUrl}/api/avisos/correo`;

  estado(): Observable<EstadoAvisos> {
    return this.http.get<EstadoAvisos>(this.url);
  }

  /** PUT -> 200, y un correo de bienvenida. 409 si la cuenta no tiene email verificado. */
  activar(): Observable<EstadoAvisos> {
    return this.http.put<EstadoAvisos>(this.url, null);
  }

  /** DELETE -> 204. */
  desactivar(): Observable<void> {
    return this.http.delete<void>(this.url);
  }
}
