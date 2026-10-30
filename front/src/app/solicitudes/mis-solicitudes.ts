import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { RouterLink } from '@angular/router';

import { AvisosService, EstadoAvisos } from '../avisos/avisos.service';
import { Solicitud } from './solicitud';
import { SolicitudesService } from './solicitudes.service';

/**
 * Las solicitudes del usuario que tiene la sesion abierta, y el interruptor de
 * los avisos por correo.
 *
 * El filtro "mias" lo aplica el BACKEND comparando con el sujeto del token: el
 * frontend no manda ningun identificador de usuario, porque un identificador
 * enviado por el cliente es un identificador que el cliente puede cambiar.
 * Por la misma razon, activar los avisos no manda el correo: el backend lo lee
 * de Cognito.
 */
@Component({
  selector: 'app-mis-solicitudes',
  imports: [RouterLink, DatePipe],
  templateUrl: './mis-solicitudes.html',
})
export class MisSolicitudes implements OnInit {

  private readonly solicitudes = inject(SolicitudesService);
  private readonly avisosService = inject(AvisosService);

  readonly lista = signal<Solicitud[]>([]);
  readonly cargando = signal(false);
  readonly error = signal('');

  readonly avisos = signal<EstadoAvisos | null>(null);
  readonly errorAvisos = signal('');

  ngOnInit(): void {
    this.cargar();
    this.avisosService.estado().subscribe({
      next: (estado) => this.avisos.set(estado),
      error: () => this.avisos.set(null),
    });
  }

  cargar(): void {
    this.cargando.set(true);
    this.error.set('');
    this.solicitudes.mias().subscribe({
      next: (solicitudes) => {
        this.lista.set(solicitudes);
        this.cargando.set(false);
      },
      error: (fallo) => {
        this.error.set(`HTTP ${fallo.status}: no se pudieron cargar las solicitudes.`);
        this.cargando.set(false);
      },
    });
  }

  activarAvisos(): void {
    this.errorAvisos.set('');
    this.avisosService.activar().subscribe({
      next: (estado) => this.avisos.set(estado),
      error: (fallo) => this.errorAvisos.set(fallo.status === 409
        ? 'Tu cuenta no tiene un correo verificado en Cognito.'
        : `HTTP ${fallo.status}: no se pudieron activar los avisos.`),
    });
  }

  desactivarAvisos(): void {
    this.errorAvisos.set('');
    this.avisosService.desactivar().subscribe({
      next: () => this.avisos.set({ activo: false, email: null }),
      error: (fallo) => this.errorAvisos.set(`HTTP ${fallo.status}: no se pudieron desactivar los avisos.`),
    });
  }
}
