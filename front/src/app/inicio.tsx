/** Portada publica: lo unico que se puede ver sin iniciar sesion. */
export function Inicio() {
  return (
    <>
      <section className="hero">
        <div className="hero-texto">
          <h1>Tu biblioteca, siempre a mano</h1>
          <p>
            Consulta el catalogo, arma tu solicitud de prestamo y sigue su estado,
            todo desde un solo lugar.
          </p>
          <p className="tenue">
            ¿Primera vez aqui? Crea tu cuenta con tu correo: te enviaremos un codigo
            de seis digitos para activarla. La contrasena necesita al menos 8
            caracteres, con una mayuscula, una minuscula y un numero.
          </p>
        </div>

        <div className="hero-ilustracion">
          <svg viewBox="0 0 300 200" role="img" aria-label="Estante de libros">
            <line x1="0" y1="192" x2="300" y2="192" stroke="var(--linea)" strokeWidth="4" />
            <rect x="8" y="70" width="22" height="122" rx="3" style={{ fill: 'var(--duoc)' }} />
            <rect x="34" y="45" width="18" height="147" rx="3" style={{ fill: 'var(--acento)' }} />
            <rect x="56" y="90" width="26" height="102" rx="3" style={{ fill: 'var(--tinta-suave)' }} />
            <rect x="86" y="30" width="16" height="162" rx="3" style={{ fill: 'var(--duoc-claro)' }} stroke="var(--duoc)" strokeWidth="1.5" />
            <rect x="106" y="60" width="24" height="132" rx="3" style={{ fill: 'var(--acento-fondo)' }} stroke="var(--acento)" strokeWidth="1.5" />
            <rect x="134" y="50" width="20" height="142" rx="3" style={{ fill: 'var(--duoc)' }} />
            <rect x="158" y="85" width="22" height="107" rx="3" style={{ fill: 'var(--tinta)' }} />
            <rect x="184" y="40" width="18" height="152" rx="3" style={{ fill: 'var(--acento)' }} />
            <rect x="206" y="65" width="26" height="127" rx="3" style={{ fill: 'var(--duoc-claro)' }} stroke="var(--duoc)" strokeWidth="1.5" />
            <rect x="236" y="95" width="20" height="97" rx="3" style={{ fill: 'var(--tinta-suave)' }} />
            <rect x="260" y="55" width="24" height="137" rx="3" style={{ fill: 'var(--acento-fondo)' }} stroke="var(--acento)" strokeWidth="1.5" />
          </svg>
        </div>
      </section>

      <section className="funciones">
        <article>
          <span className="icono-funcion" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor"
                 strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="10.5" cy="10.5" r="6.5" />
              <line x1="15.5" y1="15.5" x2="21" y2="21" />
            </svg>
          </span>
          <h3>Catalogo</h3>
          <p>Busca por titulo, autor o categoria y filtra por disponibilidad.</p>
        </article>

        <article>
          <span className="icono-funcion" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor"
                 strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <rect x="5" y="3" width="14" height="18" rx="2" />
              <line x1="8" y1="8" x2="16" y2="8" />
              <line x1="8" y1="12" x2="16" y2="12" />
              <line x1="8" y1="16" x2="13" y2="16" />
            </svg>
          </span>
          <h3>Solicitudes</h3>
          <p>Arma tu pedido con varios libros y envialo en un solo paso.</p>
        </article>

        <article>
          <span className="icono-funcion" aria-hidden="true">
            <svg viewBox="0 0 24 24" width="22" height="22" fill="none" stroke="currentColor"
                 strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round">
              <circle cx="12" cy="12" r="9" />
              <path d="M12 7v5l3.5 2" />
            </svg>
          </span>
          <h3>Seguimiento</h3>
          <p>Revisa el estado de cada solicitud que has enviado.</p>
        </article>
      </section>
    </>
  );
}
