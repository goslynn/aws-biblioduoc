import { iniciarSesion, registrarse } from './auth/sesion';
import { Tapa } from './catalogo/tapa';
import { IconoUbicacion } from './iconos';
import { NOMBRE_SEDE, SEDES } from './solicitudes/solicitud';

/**
 * Portada publica: lo unico que se puede ver sin iniciar sesion. Su unico
 * proposito es que la persona entre o cree su cuenta; por eso las dos
 * acciones se repiten arriba, en la cabecera y en el titular.
 *
 * La tarjeta de la derecha es una MUESTRA fija de como se ve una solicitud
 * aprobada, no datos reales: aqui todavia no hay token para pedirlos.
 */
export function Inicio() {
  return (
    <>
      <section className="hero">
        <div className="contenedor">
          <div className="hero-texto">
            <span className="sello">Para estudiantes de Duoc UC</span>
            <h1>Pide tus libros <span>sin hacer fila.</span></h1>
            <p>
              Busca en el catalogo, junta varios titulos en una sola solicitud y
              retiralos en tu sede. Te avisamos por correo cuando este aprobada y
              antes de que venza.
            </p>
            <div className="acciones">
              <button type="button" className="primario grande" onClick={registrarse}>Crear mi cuenta</button>
              <button type="button" className="secundario grande" onClick={() => void iniciarSesion()}>Ya tengo cuenta</button>
            </div>
            <p className="nota">
              Te enviaremos un codigo de 6 digitos al correo para activarla. La
              contrasena necesita al menos 8 caracteres, con una mayuscula, una
              minuscula y un numero.
            </p>
          </div>

          <div className="hero-muestra" aria-hidden="true">
            <div className="tarjeta">
              <div className="fila">
                <strong>SOL-0042</strong>
                <span className="etiqueta si">Aprobada</span>
              </div>
              <ol className="progreso">
                <li className="hecho">Recibida</li>
                <li aria-current="step">Aprobada</li>
                <li>Devuelta</li>
              </ol>
              <div className="libro-mini">
                <Tapa titulo="Clean Code" genero="Informatica" chica />
                <span><strong>Clean Code</strong><small>Robert C. Martin</small></span>
              </div>
              <div className="libro-mini">
                <Tapa titulo="OAuth 2 in Action" genero="Seguridad" chica />
                <span><strong>OAuth 2 in Action</strong><small>Justin Richer y Antonio Sanso</small></span>
              </div>
            </div>
          </div>
        </div>
      </section>

      <section className="contenedor seccion" aria-labelledby="como">
        <header>
          <h2 id="como">Como funciona</h2>
          <p>Tres pasos, y en cada uno sabes en que esta tu prestamo.</p>
        </header>
        <ol className="pasos">
          <li className="panel">
            <span className="numero">1</span>
            <h3>Busca y elige</h3>
            <p>
              Filtra por genero o disponibilidad y agrega los titulos que
              necesitas. Si uno esta agotado, pide que te avisemos cuando vuelva.
            </p>
          </li>
          <li className="panel">
            <span className="numero">2</span>
            <h3>Envia tu solicitud</h3>
            <p>
              Elige la sede de retiro y por cuantos dias (hasta 30). Un empleado
              de la biblioteca la revisa y la aprueba.
            </p>
          </li>
          <li className="panel">
            <span className="numero">3</span>
            <h3>Retira y devuelve</h3>
            <p>
              Con los avisos activos recibes el comprobante, la resolucion y un
              recordatorio antes del vencimiento.
            </p>
          </li>
        </ol>
      </section>

      <section className="contenedor" aria-labelledby="sedes">
        <div className="panel sedes">
          <div>
            <h2 id="sedes">Retira en {SEDES.length} sedes</h2>
            <p>Eliges la sede al enviar la solicitud.</p>
          </div>
          <ul>
            {SEDES.map((sede) => (
              <li key={sede}><IconoUbicacion />{NOMBRE_SEDE[sede]}</li>
            ))}
          </ul>
        </div>
      </section>
    </>
  );
}
