import { useEffect, useState } from 'react';
import { Link } from 'react-router';

import { statusDe } from '../auth/http';
import { avisosService, type EstadoAvisos } from '../avisos/avisos.service';
import { fechaHora } from '../formato';
import { claseEstado, type EstadoSolicitud, type Solicitud } from './solicitud';
import { solicitudesService } from './solicitudes.service';

const NOMBRE_ESTADO: Record<EstadoSolicitud, string> = {
  RECIBIDA: 'En revision',
  APROBADA: 'Aprobada',
  RECHAZADA: 'Rechazada',
  DEVUELTA: 'Devuelta',
};

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
  const [lista, setLista] = useState<Solicitud[]>([]);
  const [cargando, setCargando] = useState(false);
  const [error, setError] = useState('');

  const [avisos, setAvisos] = useState<EstadoAvisos | null>(null);
  const [errorAvisos, setErrorAvisos] = useState('');

  const cargar = () => {
    setCargando(true);
    setError('');
    solicitudesService.mias().then(
      (solicitudes) => {
        setLista(solicitudes);
        setCargando(false);
      },
      (fallo) => {
        setError(`HTTP ${statusDe(fallo)}: no se pudieron cargar las solicitudes.`);
        setCargando(false);
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

  return (
    <>
      <section className="panel">
        <h1>Mis solicitudes</h1>
        <button type="button" className="secundario" onClick={cargar}>Recargar</button>
      </section>

      <section className="panel">
        <h2>Avisos por correo</h2>
        {avisos && (avisos.activo ? (
          <>
            <p>
              Te avisamos a <strong>{avisos.email}</strong> cuando registremos, aprobemos o
              rechacemos una solicitud, antes de que venza un prestamo y cuando vuelva un libro
              que esperas.
            </p>
            <button type="button" className="sutil" onClick={desactivarAvisos}>Desactivar avisos</button>
          </>
        ) : (
          <>
            <p className="tenue">
              Activalos para recibir el comprobante, la resolucion y los recordatorios en el correo
              con el que te registraste. Puedes desactivarlos cuando quieras.
            </p>
            <button type="button" onClick={activarAvisos}>Activar avisos por correo</button>
          </>
        ))}
        {errorAvisos && <p className="fallo">{errorAvisos}</p>}
      </section>

      {error && <p className="fallo">{error}</p>}
      {cargando && <p className="tenue">Cargando…</p>}

      {!cargando && lista.length === 0 && !error && (
        <p className="aviso">
          No hay solicitudes a tu nombre todavia. <Link to="/catalogo">Ir al catalogo</Link>.
        </p>
      )}

      {lista.map((solicitud) => (
        <article key={solicitud.id} className="panel solicitud">
          <header>
            <h3>{solicitud.id}</h3>
            <span className={claseEstado(solicitud.estado)}>{NOMBRE_ESTADO[solicitud.estado]}</span>
          </header>
          <p className="tenue">
            {solicitud.dias} dias · retiro en {solicitud.sede} ·{' '}
            {solicitud.venceEn
              ? `devolver antes del ${fechaHora(solicitud.venceEn)}`
              : `devolucion estimada ${solicitud.fechaDevolucion}`}
          </p>
          {solicitud.motivoRechazo && <p className="fallo">Motivo del rechazo: {solicitud.motivoRechazo}</p>}
          {solicitud.motivo && <p>{solicitud.motivo}</p>}
          <ul>
            {solicitud.lineas.map((linea) => (
              <li key={linea.libroId}>{linea.cantidad} x {linea.titulo} <span className="tenue">({linea.autor})</span></li>
            ))}
          </ul>
        </article>
      ))}
    </>
  );
}
