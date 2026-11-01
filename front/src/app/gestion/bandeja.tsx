import { useEffect, useState, type FormEvent } from 'react';

import { cuerpoDe, statusDe } from '../auth/http';
import { librosService } from '../catalogo/libros.service';
import { diasHasta, fechaHora } from '../formato';
import { IconoAlerta, IconoCheck } from '../iconos';
import { NOMBRE_SEDE, claseEstado, type EstadoSolicitud, type Solicitud } from '../solicitudes/solicitud';
import { gestionService } from './gestion.service';

/** El cuerpo de error de Spring: el nuestro (mensaje, detalles) o el suyo por defecto (message). */
interface CuerpoError {
  message?: string;
  mensaje?: string;
  detalles?: Record<string, string>;
}

/** Las pestanas de la bandeja, en el orden en que el empleado las recorre. */
const PESTANAS: { estado: EstadoSolicitud | ''; nombre: string }[] = [
  { estado: 'RECIBIDA', nombre: 'Por revisar' },
  { estado: 'APROBADA', nombre: 'Prestadas' },
  { estado: 'RECHAZADA', nombre: 'Rechazadas' },
  { estado: 'DEVUELTA', nombre: 'Devueltas' },
  { estado: '', nombre: 'Todas' },
];

/** Rechazos frecuentes: rellenan el motivo, que el empleado puede retocar. */
const MOTIVOS_RAPIDOS = [
  'No quedan ejemplares suficientes de este titulo.',
  'El libro no esta disponible en la sede elegida.',
  'El plazo pedido es demasiado largo para este titulo.',
];

/**
 * La bandeja del empleado: todas las solicitudes y las tres acciones de su
 * ciclo de vida (aprobar, rechazar con motivo, registrar la devolucion).
 *
 * Tras cada accion se recarga la lista entera en vez de parchear la fila: el
 * estado lo decide el servidor (por ejemplo, otro empleado pudo aprobarla un
 * segundo antes y la respuesta es 409).
 *
 * Junto a cada libro se ve el stock actual, para no aprobar a ciegas. Es una
 * AYUDA: la comprobacion que manda es la del backend, que responde 409 si al
 * aprobar ya no alcanza.
 */
export function Bandeja() {
  const [filtro, setFiltro] = useState<EstadoSolicitud | ''>('RECIBIDA');

  const [lista, setLista] = useState<Solicitud[]>([]);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');
  const [exito, setExito] = useState('');

  /** Ejemplares en estante por id de libro. */
  const [stock, setStock] = useState<Record<string, number>>({});

  const [rechazando, setRechazando] = useState('');
  const [motivo, setMotivo] = useState('');

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
    librosService.buscar('', '', false).then(
      (libros) => setStock(Object.fromEntries(libros.map((l) => [l.id, l.ejemplares]))),
      () => setStock({}),
    );
  };

  useEffect(() => cargar(), []);

  const elegir = (estado: EstadoSolicitud | '') => {
    setFiltro(estado);
    setExito('');
    setRechazando('');
    cargar(estado);
  };

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
    tras(gestionService.rechazar(s.id, motivo), `${s.id} rechazada. El estudiante recibira el motivo por correo.`);
  };

  const recibidas = lista.filter((s) => s.estado === 'RECIBIDA');
  const resueltas = lista.filter((s) => s.estado !== 'RECIBIDA');

  return (
    <>
      <section className="encabezado con-pestanas">
        <div className="contenedor">
          <div className="titulo-pagina">
            <div>
              <h1>Bandeja de solicitudes</h1>
              <p className="bajada">
                Aprobar descuenta el stock; rechazar pide un motivo; registrar la devolucion lo
                repone. El estudiante recibe cada resolucion por correo, si activo los avisos.
              </p>
            </div>
            <button type="button" className="sutil" onClick={() => cargar()}>Recargar</button>
          </div>
          <div className="pestanas" role="tablist" aria-label="Estado">
            {PESTANAS.map((p) => (
              <button key={p.nombre} type="button" role="tab" aria-selected={filtro === p.estado}
                      onClick={() => elegir(p.estado)}>
                {p.nombre}
                {filtro === p.estado && !cargando && <span className="contador">{lista.length}</span>}
              </button>
            ))}
          </div>
        </div>
      </section>

      <div className="contenedor cuerpo" role="tabpanel">
        {exito && <p className="exito" role="status"><IconoCheck /> {exito}</p>}
        {error && <p className="fallo" role="alert">{error}</p>}
        {cargando && <p className="tenue">Cargando…</p>}

        {!cargando && lista.length === 0 && !error && (
          <div className="vacio">
            <h2>{filtro === 'RECIBIDA' ? 'Bandeja al dia' : 'Nada por aqui'}</h2>
            <p>{filtro === 'RECIBIDA' ? 'No quedan solicitudes por revisar.' : 'No hay solicitudes en este estado.'}</p>
          </div>
        )}

        {recibidas.map((s) => {
          const faltaStock = s.lineas.some((l) => (stock[l.libroId] ?? Infinity) < l.cantidad);
          return (
            <article key={s.id} className="panel solicitud">
              <div className="fila-bandeja">
                <div className="id">
                  <h2>{s.id}</h2>
                  <span>{fechaHora(s.creadaEn)}</span>
                  <span>Solicitante <code>{s.solicitante}</code></span>
                </div>

                <div className="lineas">
                  {s.lineas.map((linea) => {
                    const hay = stock[linea.libroId];
                    return (
                      <div key={linea.libroId} className="linea">
                        <strong>{linea.cantidad} ×</strong>
                        <span>{linea.titulo}</span>
                        {hay === undefined ? null : hay >= linea.cantidad ? (
                          <span className="stock-ok">stock {hay}</span>
                        ) : (
                          <span className="stock-falta"><IconoAlerta /> stock {hay}, pide {linea.cantidad}</span>
                        )}
                      </div>
                    );
                  })}
                  <p className="tenue">{s.dias} dias · retiro en <strong>{NOMBRE_SEDE[s.sede]}</strong></p>
                  {s.motivo && <p className="cita">“{s.motivo}”</p>}
                </div>

                <div className="botones">
                  <button type="button" className="secundario" onClick={() => abrirRechazo(s)}>Rechazar</button>
                  <button type="button" className="primario" disabled={faltaStock}
                          title={faltaStock ? 'No alcanza el stock para aprobarla' : undefined}
                          onClick={() => tras(gestionService.aprobar(s.id), `${s.id} aprobada. Se desconto el stock y se aviso al estudiante.`)}>
                    Aprobar
                  </button>
                </div>
              </div>

              {rechazando === s.id && (
                <form className="rechazo" onSubmit={(e) => rechazar(e, s)}>
                  <label htmlFor={`motivo-${s.id}`}>Motivo del rechazo <small>(lo lee el estudiante)</small></label>
                  <div className="chips">
                    {MOTIVOS_RAPIDOS.map((m) => (
                      <button key={m} type="button" className="chip" aria-pressed={motivo === m} onClick={() => setMotivo(m)}>{m}</button>
                    ))}
                  </div>
                  <textarea id={`motivo-${s.id}`} name="motivo" rows={2} maxLength={300} required
                            value={motivo} onChange={(e) => setMotivo(e.target.value)} />
                  <div className="botones">
                    <button type="button" className="sutil" onClick={() => setRechazando('')}>Cancelar</button>
                    <button type="submit" className="peligro" disabled={!motivo.trim()}>Confirmar rechazo</button>
                  </div>
                </form>
              )}
            </article>
          );
        })}

        {resueltas.length > 0 && (
          <div className="tabla">
            <table>
              <thead>
                <tr>
                  <th scope="col">Solicitud</th>
                  <th scope="col">Estado</th>
                  <th scope="col">Libros</th>
                  <th scope="col">Sede</th>
                  <th scope="col">Vence / resuelta</th>
                  <th scope="col"><span className="solo-lectores">Accion</span></th>
                </tr>
              </thead>
              <tbody>
                {resueltas.map((s) => {
                  const urgente = s.estado === 'APROBADA' && !!s.venceEn && diasHasta(s.venceEn) <= 1;
                  return (
                    <tr key={s.id} className={urgente ? 'urgente' : undefined}>
                      <td><strong>{s.id}</strong></td>
                      <td><span className={claseEstado(s.estado)}>{s.estado}</span></td>
                      <td>
                        {s.lineas.map((l) => `${l.cantidad} × ${l.titulo}`).join(', ')}
                        {s.motivoRechazo && <div className="tenue">Motivo: {s.motivoRechazo}</div>}
                      </td>
                      <td className="gris">{NOMBRE_SEDE[s.sede]}</td>
                      <td className="gris">
                        {s.estado === 'APROBADA' && s.venceEn
                          ? <>{urgente ? <strong>{fechaHora(s.venceEn)}</strong> : fechaHora(s.venceEn)}</>
                          : s.resueltaEn ? fechaHora(s.resueltaEn) : '—'}
                      </td>
                      <td>
                        {s.estado === 'APROBADA' && (
                          <button type="button" className="secundario"
                                  onClick={() => tras(gestionService.devolver(s.id), `Devolucion de ${s.id} registrada; el stock se repuso.`)}>
                            Registrar devolucion
                          </button>
                        )}
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        )}
      </div>
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
