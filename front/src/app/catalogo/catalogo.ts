import { Component, OnInit, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { RouterLink } from '@angular/router';

import { Libro } from './libro';
import { LibrosService } from './libros.service';
import { SeleccionService } from './seleccion.service';

/**
 * El catalogo: buscador, filtros y la lista de libros.
 *
 * El filtrado lo hace el BACKEND, no este componente. Podria hacerse aqui
 * —son 20 libros— pero entonces el buscador no probaria nada: asi cada
 * busqueda es una peticion real que atraviesa el interceptor, API Gateway,
 * el authorizer y Spring.
 */
@Component({
  selector: 'app-catalogo',
  imports: [FormsModule, RouterLink],
  templateUrl: './catalogo.html',
})
export class Catalogo implements OnInit {

  private readonly libros = inject(LibrosService);
  readonly seleccion = inject(SeleccionService);

  texto = '';
  genero = '';
  soloDisponibles = false;

  readonly resultados = signal<Libro[]>([]);
  readonly generos = signal<string[]>([]);
  readonly cargando = signal(false);
  readonly error = signal('');

  /** Ids de los libros agotados que este usuario espera ("avisame"). */
  readonly esperando = signal<Set<string>>(new Set());

  ngOnInit(): void {
    this.libros.esperando().subscribe({
      next: (ids) => this.esperando.set(new Set(ids)),
      error: () => this.esperando.set(new Set()),
    });
    this.libros.generos().subscribe({
      next: (lista) => this.generos.set(lista),
      error: () => this.generos.set([]),
    });
    this.buscar();
  }

  buscar(): void {
    this.cargando.set(true);
    this.error.set('');
    this.libros.buscar(this.texto, this.genero, this.soloDisponibles).subscribe({
      next: (libros) => {
        this.resultados.set(libros);
        this.cargando.set(false);
      },
      error: (fallo) => {
        // Se muestra el codigo HTTP a proposito: 401 (sin token, token
        // invalido, o token sin el scope), 403 (ruta que el gateway no conoce)
        // y 500 se diagnostican distinto, y esconderlos detras de "algo salio
        // mal" no ayuda a nadie.
        this.error.set(this.describir(fallo.status));
        this.resultados.set([]);
        this.cargando.set(false);
      },
    });
  }

  /**
   * "Avisame cuando vuelva" en un libro agotado. El aviso llega por correo
   * cuando un empleado cargue stock o se devuelva un ejemplar, si los avisos
   * estan activos (en "Mis solicitudes").
   */
  alternarAvisame(libro: Libro): void {
    const espera = this.esperando().has(libro.id);
    const peticion = espera ? this.libros.dejarDeEsperar(libro.id) : this.libros.avisame(libro.id);
    peticion.subscribe({
      next: () => {
        const nuevo = new Set(this.esperando());
        if (espera) { nuevo.delete(libro.id); } else { nuevo.add(libro.id); }
        this.esperando.set(nuevo);
      },
      // 409: alguien cargo stock entretanto. Se recarga para mostrarlo.
      error: (fallo) => fallo.status === 409 ? this.buscar() : this.error.set(this.describir(fallo.status)),
    });
  }

  limpiar(): void {
    this.texto = '';
    this.genero = '';
    this.soloDisponibles = false;
    this.buscar();
  }

  private describir(status: number): string {
    switch (status) {
      case 0:   return 'No hubo respuesta (error de red o de CORS). Revisa la pestana Network.';
      case 401: return 'HTTP 401: el token falta, no es valido, o no trae el scope que exige la API. Vuelve a iniciar sesion.';
      case 403: return 'HTTP 403: el gateway no reconoce esa ruta. Si la ruta es correcta, revisa el scope del token.';
      default:  return `HTTP ${status}: no se pudo cargar el catalogo.`;
    }
  }
}
