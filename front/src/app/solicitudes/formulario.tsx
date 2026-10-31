import { useState, type FormEvent } from 'react';
import { Link } from 'react-router';

import { cuerpoDe, statusDe } from '../auth/http';
import { quitarDeSeleccion, useSeleccion, vaciarSeleccion } from '../catalogo/seleccion';
import { SEDES, type ErrorApi, type Sede, type Solicitud } from './solicitud';
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
      items: seleccion.items.map((l) => ({ libroId: l.libro.id, cantidad: l.cantidad })),
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

  if (creada) {
    return (
      <section className="panel">
        <h1>Solicitud enviada</h1>
        <p className="exito">
          Se registro la solicitud <strong>{creada.id}</strong> en estado {creada.estado}.
        </p>

        <dl className="datos">
          <dt>Solicitante</dt>
          <dd><code>{creada.solicitante}</code></dd>
          <dt>Retiro en</dt><dd>{creada.sede}</dd>
          <dt>Dias</dt><dd>{creada.dias}</dd>
          <dt>Devolver antes del</dt><dd>{creada.fechaDevolucion}</dd>
          {creada.motivo && <><dt>Motivo</dt><dd>{creada.motivo}</dd></>}
        </dl>

        <h3>Libros</h3>
        <ul>
          {creada.lineas.map((linea) => (
            <li key={linea.libroId}>{linea.cantidad} x {linea.titulo} <span className="tenue">({linea.autor})</span></li>
          ))}
        </ul>

        <div className="acciones">
          <Link to="/mis-solicitudes"><button type="button">Ver mis solicitudes</button></Link>
          <Link to="/catalogo"><button type="button" className="secundario" onClick={() => setCreada(null)}>Volver al catalogo</button></Link>
        </div>
      </section>
    );
  }

  return (
    <section className="panel">
      <h1>Completar la solicitud</h1>

      {seleccion.vacia ? (
        <>
          <p className="aviso">Todavia no has elegido ningun libro.</p>
          <Link to="/catalogo"><button type="button">Ir al catalogo</button></Link>
        </>
      ) : (
        <>
          <h3>Libros seleccionados</h3>
          <ul className="seleccionados">
            {seleccion.items.map((linea) => (
              <li key={linea.libro.id}>
                <span><strong>{linea.cantidad} x</strong> {linea.libro.titulo}</span>
                <button type="button" className="sutil" onClick={() => quitarDeSeleccion(linea.libro.id)}>Quitar</button>
              </li>
            ))}
          </ul>

          <form onSubmit={enviar}>
            <div className="campos">
              <div>
                <label htmlFor="dias">Dias de prestamo (1 a 30)</label>
                <input id="dias" name="dias" type="number" min={1} max={30} required
                       value={dias} onChange={(e) => setDias(e.target.valueAsNumber)} />
                {!diasValidos && <p className="fallo">El prestamo va de 1 a 30 dias.</p>}
              </div>

              <div>
                <label htmlFor="sede">Retiro en</label>
                <select id="sede" name="sede" value={sede} onChange={(e) => setSede(e.target.value as Sede)}>
                  {SEDES.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </div>
            </div>

            <div className="campo-motivo">
              <label htmlFor="motivo">Motivo (opcional)</label>
              <textarea id="motivo" name="motivo" rows={3} value={motivo} onChange={(e) => setMotivo(e.target.value)}
                        placeholder="Para que necesitas estos libros" />
              <p className="tenue">{motivo.trim().length} / 300 caracteres</p>
              {!motivoValido && <p className="fallo">El motivo no puede superar 300 caracteres.</p>}
            </div>

            <button type="submit" disabled={!puedeEnviar}>
              {enviando ? 'Enviando…' : 'Enviar solicitud'}
            </button>
          </form>

          {error && (
            <>
              <p className="fallo">{error}</p>
              {Object.keys(detalles).length > 0 && (
                <ul className="fallo">
                  {Object.entries(detalles).map(([campo, mensaje]) => (
                    <li key={campo}><code>{campo}</code>: {mensaje}</li>
                  ))}
                </ul>
              )}
            </>
          )}
        </>
      )}
    </section>
  );
}

/** Cada codigo tiene una causa distinta y se diagnostica distinto. */
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
