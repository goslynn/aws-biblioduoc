import { DatePipe } from '@angular/common';
import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { ESTADOS, EstadoSolicitud, Solicitud } from '../solicitudes/solicitud';
import { GestionService } from './gestion.service';

/**
 * La bandeja del empleado: todas las solicitudes y las tres acciones de su
 * ciclo de vida (aprobar, rechazar con motivo, registrar la devolucion).
 *
 * Tras cada accion se recarga la lista entera en vez de parchear la fila: el
 * estado lo decide el servidor (por ejemplo, otro empleado pudo aprobarla un
 * segundo antes y la respuesta es 409).
 */
@Component({
  selector: 'app-bandeja',
  imports: [FormsModule, DatePipe],
  templateUrl: './bandeja.html',
})
export class Bandeja implements OnInit {

  private readonly gestion = inject(GestionService);

  readonly estados = ESTADOS;
  filtro: EstadoSolicitud | '' = 'RECIBIDA';

  readonly lista = signal<Solicitud[]>([]);
  readonly cargando = signal(false);
  readonly error = signal('');
  readonly exito = signal('');

  /** Solicitud cuyo formulario de rechazo esta abierto, y su motivo. */
  readonly rechazando = signal('');
  motivo = '';

  ngOnInit(): void {
    this.cargar();
  }

  cargar(): void {
    this.cargando.set(true);
    this.error.set('');
    this.gestion.solicitudes(this.filtro).subscribe({
      next: (solicitudes) => {
        this.lista.set(solicitudes);
        this.cargando.set(false);
      },
      error: (fallo) => {
        this.error.set(this.describir(fallo));
        this.cargando.set(false);
      },
    });
  }

  aprobar(s: Solicitud): void {
    this.gestion.aprobar(s.id).subscribe(this.tras(`${s.id} aprobada. El estudiante recibira el aviso por correo.`));
  }

  abrirRechazo(s: Solicitud): void {
    this.rechazando.set(s.id);
    this.motivo = '';
  }

  rechazar(s: Solicitud): void {
    this.gestion.rechazar(s.id, this.motivo).subscribe(this.tras(`${s.id} rechazada.`));
  }

  devolver(s: Solicitud): void {
    this.gestion.devolver(s.id).subscribe(this.tras(`Devolucion de ${s.id} registrada; el stock se repuso.`));
  }

  /** Lo comun a las tres acciones: mensaje, cerrar el formulario y recargar. */
  private tras(mensaje: string) {
    return {
      next: () => {
        this.exito.set(mensaje);
        this.error.set('');
        this.rechazando.set('');
        this.cargar();
      },
      error: (fallo: { status: number; error?: { message?: string; mensaje?: string; detalles?: Record<string, string> } }) => {
        this.exito.set('');
        this.error.set(this.describir(fallo));
        this.cargar();
      },
    };
  }

  private describir(fallo: { status: number; error?: { message?: string; mensaje?: string; detalles?: Record<string, string> } }): string {
    const cuerpo = fallo.error;
    const detalle = cuerpo?.detalles ? Object.values(cuerpo.detalles).join('; ') : '';
    switch (fallo.status) {
      case 403: return 'HTTP 403: tu cuenta no es de empleado. Spring rechazo la peticion.';
      case 409: return `HTTP 409: ${cuerpo?.message ?? 'la solicitud ya no esta en ese estado'}.`;
      case 400: return `HTTP 400: ${detalle || cuerpo?.mensaje || 'datos invalidos'}.`;
      default:  return `HTTP ${fallo.status}: no se pudo completar la accion.`;
    }
  }
}
