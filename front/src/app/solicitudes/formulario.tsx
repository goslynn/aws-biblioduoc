import { Fragment, useState, type FormEvent } from 'react';
import { Link } from 'react-router';

import { cuerpoDe, statusDe } from '../auth/http';
import { Quitar } from '../catalogo/quitar';
import { useSeleccion, vaciarSeleccion } from '../catalogo/seleccion';
import { Tapa } from '../catalogo/tapa';
import { fecha } from '../formato';
import { IconoCalendario, IconoCheck, IconoFlechaAbajo } from '../iconos';
import { NOMBRE_ESTADO, NOMBRE_SEDE, SEDES, type ErrorApi, type Sede, type Solicitud } from './solicitud';
import { solicitudesService } from './solicitudes.service';

/**
 * El formulario de solicitud: los libros elegidos en el catalogo, mas los
 * datos del prestamo.
 *
 * Las validaciones de aqui son las MISMAS que las del backend, y esta bien que
 * esten duplicadas: la del navegador es comodidad (avisa antes de gastar una
 * peticion) y la del servidor es la que manda. Si solo estuviera la del
 * navegador, bastaria con un curl para saltarsela.
 */
export function FormularioSolicitud() {
  const seleccion = useSeleccion();

  const [dias, setDias] = useState(14);
  const [motivo, setMotivo] = useState('');
  const [sede, setSede] = useState<Sede>('SAN_JOAQUIN');

  const [enviando, setEnviando] = useState(false);
  const [creada, setCreada] = useState<Solicitud | null>(null);
  const [error, setError] = useState('');
  const [detalles, setDetalles] = useState<Record<string, string>>({});

  const motivoValido = motivo.trim().length <= 300;
  const diasValidos = dias >= 1 && dias <= 30;
  const puedeEnviar = !seleccion.vacia && motivoValido && diasValidos && !enviando;

  const enviar = (evento: FormEvent) => {
    evento.preventDefault();
    setEnviando(true);
    setError('');
    setDetalles({});

    solicitudesService.crear({
      // Siempre un ejemplar por libro: ver catalogo/seleccion.ts.
      items: seleccion.items.map((libro) => ({ libroId: libro.id, cantidad: 1 })),
      dias,
      motivo: motivo.trim(),
      sede,
    }).then(
      (solicitud) => {
        setCreada(solicitud);
        vaciarSeleccion();
        setMotivo('');
        setEnviando(false);
      },
      (fallo) => {
        const cuerpo = cuerpoDe<ErrorApi>(fallo);
        setError(describir(statusDe(fallo), cuerpo?.mensaje));
        setDetalles(cuerpo?.detalles ?? {});
        setEnviando(false);
      },
    );
  };

  /** Fecha orientativa para el estudiante; la definitiva la fija el backend al aprobar. */
  const devolucionEstimada = diasValidos
    ? fecha(new Date(Date.now() + dias * 86_400_000))
    : '—';

  if (creada) {
    return (
      <div className="contenedor cuerpo">
        <Pasos actual={3} />
        <section className="panel confirmacion" aria-labelledby="enviada">
          <span className="check"><IconoCheck tamano={28} /></span>
          <div className="pila">
            <h1 id="enviada">Solicitud {creada.id} enviada</h1>
            <p className="bajada">
              Quedo <strong>{NOMBRE_ESTADO[creada.estado].toLowerCase()}</strong>. Si tienes los avisos
              activos, te escribiremos cuando un empleado la resuelva.
            </p>
          </div>

          <dl className="cifras">
            <div><dt>Retiro</dt><dd>{NOMBRE_SEDE[creada.sede]}</dd></div>
            <div><dt>Plazo</dt><dd>{creada.dias} dias</dd></div>
            <div><dt>Devolucion estimada</dt><dd>{creada.fechaDevolucion}</dd></div>
          </dl>

          <ul className="lista-libros">
            {creada.lineas.map((linea) => (
              <li key={linea.libroId}>
                <Tapa titulo={linea.titulo} chica />
                <span><strong>{linea.titulo}</strong><small>{linea.autor}</small></span>
              </li>
            ))}
          </ul>
          {creada.motivo && <p className="tenue">Motivo: {creada.motivo}</p>}
          <p className="tenue">Solicitante <code>{creada.solicitante}</code></p>

          <div className="acciones">
            <Link to="/mis-solicitudes" className="boton primario grande">Ver el estado</Link>
            <Link to="/catalogo" className="boton secundario grande">Seguir en el catalogo</Link>
          </div>
        </section>
      </div>
    );
  }

  if (seleccion.vacia) {
    return (
      <div className="contenedor cuerpo">
        <Pasos actual={1} />
        <div className="vacio">
          <h1>Tu solicitud esta vacia</h1>
          <p>Agrega libros desde el catalogo y vuelve aqui para enviarlos.</p>
          <Link to="/catalogo" className="boton primario">Ir al catalogo</Link>
        </div>
      </div>
    );
  }

  return (
    <div className="contenedor cuerpo">
      <Pasos actual={2} />

      <div className="pila">
        <h1>Confirma tu solicitud</h1>
        <p className="bajada">Dinos donde la retiras y por cuanto tiempo. Revisa el resumen antes de enviar.</p>
      </div>

      {error && (
        <div className="fallo" role="alert">
          <p>{error}</p>
          {Object.keys(detalles).length > 0 && (
            <ul>
              {Object.entries(detalles).map(([campo, mensaje]) => (
                <li key={campo}><code>{campo}</code>: {mensaje}</li>
              ))}
            </ul>
          )}
        </div>
      )}

      <form className="columnas" onSubmit={enviar}>
        <div className="principal">
          <div className="panel bloque">
            <label htmlFor="sede">¿Donde la retiras?</label>
            <p id="sede-ayuda" className="ayuda">La sede donde un empleado te entregara los libros.</p>
            <div className="desplegable">
              <select id="sede" name="sede" aria-describedby="sede-ayuda" value={sede}
                      onChange={(e) => setSede(e.target.value as Sede)}>
                {SEDES.map((s) => <option key={s} value={s}>{NOMBRE_SEDE[s]}</option>)}
              </select>
              <IconoFlechaAbajo />
            </div>
          </div>

          <fieldset className="panel bloque">
            <legend className="rotulo">¿Por cuantos dias?</legend>
            <div className="segmentos" role="radiogroup" aria-label="Plazos frecuentes">
              {PLAZOS.map((d) => (
                <button key={d} type="button" role="radio" aria-checked={dias === d} onClick={() => setDias(d)}>
                  {d} dias
                </button>
              ))}
              <div className="otro">
                <label htmlFor="dias">u otro</label>
                <input id="dias" name="dias" type="number" min={1} max={30} required
                       value={Number.isNaN(dias) ? '' : dias} onChange={(e) => setDias(e.target.valueAsNumber)} />
              </div>
            </div>
            {diasValidos ? (
              <p className="recuadro">
                <IconoCalendario />
                <span>Devolucion estimada: <strong>{devolucionEstimada}</strong>. La fecha definitiva se fija al aprobarse.</span>
              </p>
            ) : (
              <p className="fallo">El prestamo va de 1 a 30 dias.</p>
            )}
          </fieldset>

          <div className="panel bloque">
            <label htmlFor="motivo">Motivo <small>(opcional)</small></label>
            <p id="motivo-ayuda" className="ayuda">Ayuda al empleado a priorizar, por ejemplo una evaluacion cercana.</p>
            <textarea id="motivo" name="motivo" rows={3} value={motivo} onChange={(e) => setMotivo(e.target.value)}
                      aria-describedby="motivo-ayuda motivo-cuenta" aria-invalid={!motivoValido}
                      placeholder="Para el proyecto de titulo de Ingenieria en Informatica" />
            <p id="motivo-cuenta" className="contador-motivo">{motivo.trim().length} / 300</p>
            {!motivoValido && <p className="fallo">El motivo no puede superar 300 caracteres.</p>}
          </div>
        </div>

        <aside className="lateral panel panel-resumen" aria-labelledby="resumen">
          <header>
            <h2 id="resumen">Resumen</h2>
            <span>{seleccion.total} libro(s)</span>
          </header>
          <div>
            <ul className="lista-libros">
              {seleccion.items.map((libro) => (
                <li key={libro.id}>
                  <Tapa titulo={libro.titulo} genero={libro.genero} chica />
                  <span><strong>{libro.titulo}</strong><small>{libro.autor}</small></span>
                  <Quitar libro={libro} />
                </li>
              ))}
            </ul>
            <Link to="/catalogo">+ Agregar otro libro</Link>

            <dl className="datos">
              <dt>Retiro</dt><dd>{NOMBRE_SEDE[sede]}</dd>
              <dt>Plazo</dt><dd>{diasValidos ? `${dias} dias` : '—'}</dd>
              <dt>Devolver antes del</dt><dd>{devolucionEstimada}</dd>
            </dl>

            <button type="submit" className="primario grande" disabled={!puedeEnviar}>
              {enviando ? 'Enviando…' : 'Enviar solicitud'}
            </button>
            <p className="letra-chica">
              Un empleado la revisa y descuenta el stock al aprobarla. La resolucion te llega por
              correo si tienes los avisos activos.
            </p>
          </div>
        </aside>
      </form>
    </div>
  );
}

/** Plazos de un toque; el campo "u otro" admite cualquiera entre 1 y 30. */
const PLAZOS = [7, 14, 21, 30];

/** Donde va el estudiante: elegir libros, confirmar, enviada. */
function Pasos({ actual }: { actual: 1 | 2 | 3 }) {
  const pasos = ['Elegir libros', 'Confirmar', 'Enviada'];
  return (
    <ol className="pasos-flujo" aria-label="Pasos">
      {pasos.map((nombre, i) => {
        const n = i + 1;
        const hecho = n < actual;
        return (
          <Fragment key={nombre}>
            {i > 0 && <li className="raya" aria-hidden="true" />}
            <li className={hecho ? 'hecho' : undefined} aria-current={n === actual ? 'step' : undefined}>
              <span className="punto">{hecho ? <IconoCheck tamano={14} /> : n}</span>
              {n === 1 && actual === 2 ? <Link to="/catalogo">{nombre}</Link> : nombre}
            </li>
          </Fragment>
        );
      })}
    </ol>
  );
}

function describir(status: number, mensaje?: string): string {
  switch (status) {
    case 0:   return 'No hubo respuesta (error de red o de CORS). Mira la pestana Network.';
    case 400: return `HTTP 400: el formulario no paso la validacion del servidor. ${mensaje ?? ''}`;
    case 401: return 'HTTP 401: el token falta, no es valido, o no trae el scope que exige la API. Vuelve a iniciar sesion.';
    case 403: return 'HTTP 403: el gateway no reconoce esa ruta. Si la ruta es correcta, revisa el scope del token.';
    case 404: return `HTTP 404: ${mensaje ?? 'alguno de los libros ya no esta en el catalogo.'}`;
    case 409: return `HTTP 409: ${mensaje ?? 'no quedan ejemplares suficientes.'}`;
    default:  return `HTTP ${status}: no se pudo enviar la solicitud.`;
  }
}
