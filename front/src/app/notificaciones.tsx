import { crearAlmacen } from './almacen';
import { IconoAlerta, IconoCampana, IconoCerrar, IconoCheck } from './iconos';

/*
 * Notificaciones emergentes ("toasts") de las vistas EN VIVO.
 *
 * Las emiten solo las dos vistas que alimenta Kafka (vivo.ts): el catalogo
 * cuando cambia el stock de un libro de la lista (H8) y la bandeja del
 * empleado cuando llega una solicitud nueva (H9). No son la confirmacion de
 * una accion propia (para eso estan los mensajes .exito/.fallo de cada vista):
 * avisan de algo que paso EN OTRO LADO mientras se miraba la pantalla.
 *
 * No hay peticiones nuevas: se comparan dos lecturas seguidas del mismo sondeo
 * y se avisa de la diferencia. La primera lectura solo fija la linea base, asi
 * que abrir la vista no dispara una rafaga de avisos.
 *
 * Es un almacen de modulo (como la seleccion) y se pinta una sola vez, en App:
 * un aviso sobrevive a cambiar de vista y no depende de quien lo emitio.
 */

export type TonoNotificacion = 'info' | 'exito' | 'alerta';

export interface Notificacion {
  id: number;
  tono: TonoNotificacion;
  titulo: string;
  detalle?: string;
}

/** Cuanto queda a la vista cada aviso. */
const DURACION_MS = 7000;
/** Mas de esto a la vez tapa la pantalla: se descartan los mas viejos. */
const MAXIMO = 4;

const avisos = crearAlmacen<Notificacion[]>([]);
const relojes = new Map<number, number>();
let siguiente = 1;

export function notificar(aviso: Omit<Notificacion, 'id'>): void {
  const id = siguiente++;
  avisos.actualizar((actuales) => {
    const lista = [...actuales, { ...aviso, id }];
    lista.slice(0, Math.max(0, lista.length - MAXIMO)).forEach((viejo) => cancelarReloj(viejo.id));
    return lista.slice(-MAXIMO);
  });
  programar(id);
}

export function descartar(id: number): void {
  cancelarReloj(id);
  avisos.actualizar((actuales) => actuales.filter((a) => a.id !== id));
}

/** Al cerrar sesion: lo que se aviso a una persona no lo ve la siguiente. */
export function limpiarNotificaciones(): void {
  relojes.forEach((reloj) => window.clearTimeout(reloj));
  relojes.clear();
  avisos.fijar([]);
}

function programar(id: number): void {
  cancelarReloj(id);
  relojes.set(id, window.setTimeout(() => descartar(id), DURACION_MS));
}

function cancelarReloj(id: number): void {
  const reloj = relojes.get(id);
  if (reloj !== undefined) {
    window.clearTimeout(reloj);
    relojes.delete(id);
  }
}

const ICONO = { info: IconoCampana, exito: IconoCheck, alerta: IconoAlerta };

function Simbolo({ tono }: { tono: TonoNotificacion }) {
  const Icono = ICONO[tono];
  return <Icono tamano={18} />;
}

/**
 * La pila de avisos, abajo a la derecha. role="status" + aria-live="polite":
 * un lector de pantalla lee cada aviso al aparecer sin cortar lo que estaba
 * leyendo. Pasar el raton (o el foco) por un aviso lo deja quieto; al salir,
 * vuelve a contar desde cero.
 */
export function Notificaciones() {
  const lista = avisos.usar();
  return (
    <div className="notificaciones" role="status" aria-live="polite">
      {lista.map((n) => (
        <div key={n.id} className={`notificacion ${n.tono}`}
             onMouseEnter={() => cancelarReloj(n.id)} onMouseLeave={() => programar(n.id)}
             onFocus={() => cancelarReloj(n.id)} onBlur={() => programar(n.id)}>
          <span className="simbolo"><Simbolo tono={n.tono} /></span>
          <div className="texto">
            <span className="en-vivo">en vivo</span>
            <strong>{n.titulo}</strong>
            {n.detalle && <span className="tenue">{n.detalle}</span>}
          </div>
          <button type="button" className="sutil icono" aria-label="Cerrar aviso" onClick={() => descartar(n.id)}>
            <IconoCerrar tamano={16} />
          </button>
        </div>
      ))}
    </div>
  );
}
