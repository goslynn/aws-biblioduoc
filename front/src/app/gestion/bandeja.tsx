import { useEffect, useState, type FormEvent } from 'react';

import { cuerpoDe, statusDe } from '../auth/http';
import { fechaHora } from '../formato';
import { ESTADOS, claseEstado, type EstadoSolicitud, type Solicitud } from '../solicitudes/solicitud';
import { gestionService } from './gestion.service';

/** El cuerpo de error de Spring: el nuestro (mensaje, detalles) o el suyo por defecto (message). */
interface CuerpoError {
  message?: string;
  mensaje?: string;
  detalles?: Record<string, string>;
}

/**
 * La bandeja del empleado: todas las solicitudes y las tres acciones de su
 * ciclo de vida (aprobar, rechazar con motivo, registrar la devolucion).
 *
 * Tras cada accion se recarga la lista entera en vez de parchear la fila: el
 * estado lo decide el servidor (por ejemplo, otro empleado pudo aprobarla un
 * segundo antes y la respuesta es 409).
 */
export function Bandeja() {
  const [filtro, setFiltro] = useState<EstadoSolicitud | ''>('RECIBIDA');

  const [lista, setLista] = useState<Solicitud[]>([]);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');
  const [exito, setExito] = useState('');

  /** Solicitud cuyo formulario de rechazo esta abierto, y su motivo. */
  const [rechazando, setRechazando] = useState('');
  const [motivo, setMotivo] = useState('');

  // El filtro va como argumento: al cambiar el desplegable se recarga en el
  // mismo evento, antes de que React haya repintado con el valor nuevo.
  const cargar = (estado = filtro) => {
    setCargando(true);
    setError('');
    gestionService.solicitudes(estado).then(
      (solicitudes) => {
        setLista(solicitudes);
        setCargando(false);
      },
      (fallo) => {
        setError(describir(fallo));
        setCargando(false);
      },
    );
  };

  useEffect(() => cargar(), []);

  /** Lo comun a las tres acciones: mensaje, cerrar el formulario y recargar. */
  const tras = (accion: Promise<Solicitud>, mensaje: string) => {
    accion.then(
      () => {
        setExito(mensaje);
        setError('');
        setRechazando('');
        cargar();
      },
      (fallo) => {
        setExito('');
        setError(describir(fallo));
        cargar();
      },
    );
  };

  const abrirRechazo = (s: Solicitud) => {
    setRechazando(s.id);
    setMotivo('');
  };

  const rechazar = (evento: FormEvent, s: Solicitud) => {
    evento.preventDefault();
    tras(gestionService.rechazar(s.id, motivo), `${s.id} rechazada.`);
  };

  return (
    <>
      <section className="panel">
        <h1>Bandeja de solicitudes</h1>
        <p className="tenue">
          Aprobar descuenta el stock; rechazar exige un motivo; la devolucion lo repone.
          El estudiante recibe cada resolucion por correo, si activo los avisos.
        </p>

        <form className="filtros" onSubmit={(e) => { e.preventDefault(); cargar(); }}>
          <div className="campo">
            <label htmlFor="estado">Estado</label>
            <select id="estado" name="estado" value={filtro} onChange={(e) => {
              const estado = e.target.value as EstadoSolicitud | '';
              setFiltro(estado);
              cargar(estado);
            }}>
              <option value="">Todos</option>
              {ESTADOS.map((e) => <option key={e} value={e}>{e}</option>)}
            </select>
          </div>
          <div className="campo acciones">
            <button type="submit" className="secundario">Recargar</button>
          </div>
        </form>
      </section>

      {exito && <p className="exito">{exito}</p>}
      {error && <p className="fallo">{error}</p>}
      {cargando && <p className="tenue">Cargando…</p>}

      {!cargando && lista.length === 0 && !error && <p className="aviso">No hay solicitudes en este estado.</p>}

      {lista.map((s) => (
        <article key={s.id} className="panel solicitud">
          <header>
            <h3>{s.id}</h3>
            <span className={claseEstado(s.estado)}>{s.estado}</span>
          </header>
          <p className="tenue">
            {s.dias} dias · retiro en {s.sede} · creada {fechaHora(s.creadaEn)}
            {s.venceEn && ` · vence ${fechaHora(s.venceEn)}`}
          </p>
          {s.motivo && <p>{s.motivo}</p>}
          {s.motivoRechazo && <p className="tenue">Motivo del rechazo: {s.motivoRechazo}</p>}
          <ul>
            {s.lineas.map((linea) => (
              <li key={linea.libroId}>{linea.cantidad} x {linea.titulo} <span className="tenue">({linea.libroId})</span></li>
            ))}
          </ul>

          {s.estado === 'RECIBIDA' && (rechazando === s.id ? (
            <form onSubmit={(e) => rechazar(e, s)}>
              <div className="campo campo-motivo">
                <label htmlFor={`motivo-${s.id}`}>Motivo del rechazo (lo lee el estudiante)</label>
                <textarea id={`motivo-${s.id}`} name="motivo" rows={2} maxLength={300} required
                          value={motivo} onChange={(e) => setMotivo(e.target.value)} />
              </div>
              <button type="submit" disabled={!motivo.trim()}>Confirmar rechazo</button>
              <button type="button" className="sutil" onClick={() => setRechazando('')}>Cancelar</button>
            </form>
          ) : (
            <>
              <button type="button" onClick={() => tras(gestionService.aprobar(s.id), `${s.id} aprobada. El estudiante recibira el aviso por correo.`)}>
                Aprobar
              </button>
              <button type="button" className="sutil" onClick={() => abrirRechazo(s)}>Rechazar</button>
            </>
          ))}
          {s.estado === 'APROBADA' && (
            <button type="button" className="secundario"
                    onClick={() => tras(gestionService.devolver(s.id), `Devolucion de ${s.id} registrada; el stock se repuso.`)}>
              Registrar devolucion
            </button>
          )}
        </article>
      ))}
    </>
  );
}

function describir(fallo: unknown): string {
  const cuerpo = cuerpoDe<CuerpoError>(fallo);
  const detalle = cuerpo?.detalles ? Object.values(cuerpo.detalles).join('; ') : '';
  const status = statusDe(fallo);
  switch (status) {
    case 403: return 'HTTP 403: tu cuenta no es de empleado. Spring rechazo la peticion.';
    case 409: return `HTTP 409: ${cuerpo?.message ?? 'la solicitud ya no esta en ese estado'}.`;
    case 400: return `HTTP 400: ${detalle || cuerpo?.mensaje || 'datos invalidos'}.`;
    default:  return `HTTP ${status}: no se pudo completar la accion.`;
  }
}
