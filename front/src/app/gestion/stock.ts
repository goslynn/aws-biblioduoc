import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';

import { Libro } from '../catalogo/libro';
import { LibrosService } from '../catalogo/libros.service';
import { GestionService } from './gestion.service';

/**
 * Carga de stock. Si un libro pasa de 0 a tener ejemplares, quienes pulsaron
 * "Avisame" reciben un correo (por la cola masiva de RabbitMQ): por eso los
 * agotados se muestran primero.
 */
@Component({
  selector: 'app-stock',
  imports: [FormsModule],
  templateUrl: './stock.html',
})
export class Stock implements OnInit {

  private readonly libros = inject(LibrosService);
  private readonly gestion = inject(GestionService);

  readonly lista = signal<Libro[]>([]);
  readonly error = signal('');
  readonly exito = signal('');

  /** Cantidad a cargar por libro, editable en cada fila. */
  readonly cantidades: Record<string, number> = {};

  ngOnInit(): void {
    this.cargar();
  }

  cargar(): void {
    this.libros.buscar('', '', false).subscribe({
      next: (libros) => {
        this.lista.set([...libros].sort((a, b) => a.ejemplares - b.ejemplares));
        libros.forEach((l) => (this.cantidades[l.id] ??= 1));
      },
      error: (fallo) => this.error.set(`HTTP ${fallo.status}: no se pudo cargar el catalogo.`),
    });
  }

  cargarStock(libro: Libro): void {
    const cantidad = Number(this.cantidades[libro.id]);
    const estabaAgotado = !libro.disponible;
    this.gestion.cargarStock(libro.id, cantidad).subscribe({
      next: (actualizado) => {
        this.error.set('');
        this.exito.set(
          `${actualizado.titulo}: ahora hay ${actualizado.ejemplares} ejemplar(es).` +
          (estabaAgotado ? ' Quienes lo esperaban recibiran un aviso por correo.' : ''));
        this.cargar();
      },
      error: (fallo) => {
        this.exito.set('');
        this.error.set(fallo.status === 403
          ? 'HTTP 403: tu cuenta no es de empleado.'
          : `HTTP ${fallo.status}: no se pudo cargar el stock (la cantidad va de 1 a 100).`);
      },
    });
  }
}
