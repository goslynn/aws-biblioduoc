import { useEffect, useState } from 'react';
import { Link } from 'react-router';

import { statusDe } from '../auth/http';
import { avisosService, type EstadoAvisos } from '../avisos/avisos.service';
import { Tapa } from '../catalogo/tapa';
import { Esqueleto } from '../esqueleto';
import { diasHasta, fechaHora } from '../formato';
import { IconoReloj } from '../iconos';
import { NOMBRE_ESTADO, NOMBRE_SEDE, claseEstado, type Solicitud } from './solicitud';
import { solicitudesService } from './solicitudes.service';

/** En curso: lo que todavia pide algo al estudiante (esperar o devolver). */
const enCurso = (s: Solicitud) => s.estado === 'RECIBIDA' || s.estado === 'APROBADA';

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
export function MisSolicitudes() {
  // null = todavia no hay respuesta. Es distinto de [] (no hay solicitudes): si
  // se confunden, la pantalla dice "no tienes prestamos" antes de mostrarlos.
  const [lista, setLista] = useState<Solicitud[] | null>(null);
  const [error, setError] = useState('');

  // undefined = consultando; null = la consulta fallo.
  const [avisos, setAvisos] = useState<EstadoAvisos | null | undefined>(undefined);
  const [errorAvisos, setErrorAvisos] = useState('');

  const [pestana, setPestana] = useState<'curso' | 'historial'>('curso');

  /** Recargar deja la lista a la vista hasta que llega la nueva: nada salta. */
  const cargar = () => {
    setError('');
    solicitudesService.mias().then(
      setLista,
      (fallo) => {
        setError(`HTTP ${statusDe(fallo)}: no se pudieron cargar las solicitudes.`);
        setLista((actual) => actual ?? []);
      },
    );
  };

  useEffect(() => {
    cargar();
    avisosService.estado().then(setAvisos, () => setAvisos(null));
  }, []);

  const activarAvisos = () => {
    setErrorAvisos('');
    avisosService.activar().then(setAvisos, (fallo) => setErrorAvisos(statusDe(fallo) === 409
      ? 'Tu cuenta no tiene un correo verificado en Cognito.'
      : `HTTP ${statusDe(fallo)}: no se pudieron activar los avisos.`));
  };

  const desactivarAvisos = () => {
    setErrorAvisos('');
    avisosService.desactivar().then(
      () => setAvisos({ activo: false, email: null }),
      (fallo) => setErrorAvisos(`HTTP ${statusDe(fallo)}: no se pudieron desactivar los avisos.`),
    );
  };

  const vista = (lista ?? []).filter((s) => (pestana === 'curso') === enCurso(s));
  const cuantasEnCurso = (lista ?? []).filter(enCurso).length;

  return (
    <>
      <section className="encabezado con-pestanas">
        <div className="contenedor">
          <div className="titulo-pagina">
            <div>
              <h1>Mis solicitudes</h1>
              <p className="bajada">En que esta cada prestamo y cuando tienes que devolverlo.</p>
            </div>
            <div className="acciones">
              <button type="button" className="sutil" onClick={cargar}>Recargar</button>
              <Link to="/catalogo" className="boton secundario">Nueva solicitud</Link>
            </div>
          </div>
          <div className="pestanas" role="tablist" aria-label="Solicitudes">
            <button type="button" role="tab" aria-selected={pestana === 'curso'} onClick={() => setPestana('curso')}>
              En curso <span className={lista ? undefined : 'oculto'}>({cuantasEnCurso})</span>
            </button>
            <button type="button" role="tab" aria-selected={pestana === 'historial'} onClick={() => setPestana('historial')}>
              Historial <span className={lista ? undefined : 'oculto'}>({(lista?.length ?? 0) - cuantasEnCurso})</span>
            </button>
          </div>
        </div>
      </section>

      <div className="contenedor columnas">
        <div className="principal" role="tabpanel">
          {error && <p className="fallo">{error}</p>}
          {lista === null && <Esqueleto />}

          {lista !== null && vista.length === 0 && !error && (
            <div className="vacio">
              {pestana === 'curso' ? (
                <>
                  <h2>No tienes prestamos en curso</h2>
                  <p>Cuando envies una solicitud, aqui veras en que esta.</p>
                  <Link to="/catalogo" className="boton primario">Ir al catalogo</Link>
                </>
              ) : (
                <>
                  <h2>Todavia no hay historial</h2>
                  <p>Aqui quedan las solicitudes devueltas y las rechazadas.</p>
                </>
              )}
            </div>
          )}

          {vista.map((solicitud) => <TarjetaSolicitud key={solicitud.id} solicitud={solicitud} />)}
        </div>

        <aside className="lateral panel avisos" aria-labelledby="avisos">
          <div className="cabeza">
            <h2 id="avisos">Avisos por correo</h2>
            {avisos && (
              <button type="button" role="switch" className="interruptor" aria-checked={avisos.activo}
                      aria-labelledby="avisos" onClick={avisos.activo ? desactivarAvisos : activarAvisos} />
            )}
          </div>
          {avisos?.activo ? (
            <>
              <p>Te escribimos a <strong>{avisos.email}</strong> cuando:</p>
              <ul>
                <li>registramos tu solicitud</li>
                <li>la aprobamos o la rechazamos</li>
                <li>se acerca el vencimiento de un prestamo</li>
                <li>vuelve un libro que esperas</li>
              </ul>
            </>
          ) : avisos ? (
            <p className="bajada">
              Estan desactivados. Activalos para recibir el comprobante, la resolucion y los
              recordatorios en el correo con el que te registraste. Puedes desactivarlos cuando quieras.
            </p>
          ) : avisos === null && (
            <p className="tenue">No se pudo consultar el estado de los avisos.</p>
          )}
          {errorAvisos && <p className="fallo">{errorAvisos}</p>}
        </aside>
      </div>
    </>
  );
}

/** Una solicitud con su progreso y, si esta prestada, cuando hay que devolverla. */
function TarjetaSolicitud({ solicitud }: { solicitud: Solicitud }) {
  const { estado } = solicitud;
  const rechazada = estado === 'RECHAZADA';
  const prestada = estado === 'APROBADA' && solicitud.venceEn;

  const cuando = estado === 'RECIBIDA'
    ? `Creada ${fechaHora(solicitud.creadaEn)}`
    : estado === 'DEVUELTA' && solicitud.devueltaEn
      ? `Devuelta ${fechaHora(solicitud.devueltaEn)}`
      : solicitud.resueltaEn ? `Resuelta ${fechaHora(solicitud.resueltaEn)}` : `Creada ${fechaHora(solicitud.creadaEn)}`;

  return (
    <article className="panel solicitud">
      <div className="contenido">
        <div className="cabeza">
          <div>
            <h2>{solicitud.id}</h2>
            <span className={claseEstado(estado)}>{NOMBRE_ESTADO[estado]}</span>
          </div>
          <span className="tenue">{cuando} · {NOMBRE_SEDE[solicitud.sede]}</span>
        </div>

        <ol className="progreso" aria-label="Progreso">
          <li className={estado === 'RECIBIDA' ? undefined : 'hecho'} aria-current={estado === 'RECIBIDA' ? 'step' : undefined}>
            {estado === 'RECIBIDA' ? 'Recibida · en revision' : 'Recibida'}
          </li>
          <li className={rechazada ? 'cortado' : estado === 'DEVUELTA' ? 'hecho' : undefined}
              aria-current={estado === 'APROBADA' || rechazada ? 'step' : undefined}>
            {rechazada ? 'Rechazada' : estado === 'APROBADA' ? 'Aprobada · retirala' : 'Aprobada'}
          </li>
          <li className={estado === 'DEVUELTA' ? 'hecho' : undefined}>Devuelta</li>
        </ol>

        <ul className="libros-solicitud">
          {solicitud.lineas.map((linea) => (
            <li key={linea.libroId}>
              <Tapa titulo={linea.titulo} chica />
              <span><strong>{linea.titulo}</strong> · {linea.autor}{linea.cantidad > 1 && ` · ${linea.cantidad} ejemplares`}</span>
            </li>
          ))}
        </ul>

        {estado === 'RECIBIDA' && (
          <p className="tenue">
            {solicitud.dias} dias · devolucion estimada {solicitud.fechaDevolucion}. Un empleado la revisara pronto.
          </p>
        )}
        {solicitud.motivo && <p className="tenue">Tu motivo: {solicitud.motivo}</p>}
        {solicitud.motivoRechazo && (
          <p className="fallo"><strong>Motivo del rechazo:</strong> {solicitud.motivoRechazo}</p>
        )}
      </div>

      {prestada && <Vencimiento venceEn={solicitud.venceEn!} />}
    </article>
  );
}

/** La franja de "devuelvela antes del…": amarilla, o roja si ya vencio. */
function Vencimiento({ venceEn }: { venceEn: string }) {
  const dias = diasHasta(venceEn);
  const faltan = dias > 1 ? `Faltan ${dias} dias`
    : dias === 1 ? 'Vence manana'
      : dias === 0 ? 'Vence hoy'
        : `Vencida hace ${-dias} dia(s)`;
  return (
    <div className={dias < 0 ? 'vencimiento vencida' : 'vencimiento'}>
      <IconoReloj />
      <span>Devuelvela antes del <strong>{fechaHora(venceEn)}</strong></span>
      <span className="faltan">{faltan}</span>
    </div>
  );
}
