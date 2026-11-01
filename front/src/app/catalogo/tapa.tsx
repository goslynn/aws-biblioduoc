/**
 * La "tapa" de un libro: un lomo de color segun el genero, con la inicial del
 * titulo. Es decorativa (el catalogo no tiene portadas) y solo ayuda a
 * reconocer de un vistazo el mismo libro en el catalogo, la solicitud y el
 * seguimiento.
 *
 * El color va en el atributo style desde React, que lo aplica por el DOM y no
 * como estilo en linea del HTML: la CSP del Caddyfile (style-src 'self') no lo
 * bloquea.
 */
const COLORES: Record<string, string> = {
  Informatica: '#2f3e46',
  Seguridad: '#5a3e36',
  Cloud: '#2e4057',
  Literatura: '#4a3b52',
  Historia: '#4f4a3a',
  Metodologia: '#3d4f45',
  Lenguaje: '#4a4140',
  Matematicas: '#33414f',
};

export function Tapa({ titulo, genero, chica = false }: { titulo: string; genero?: string; chica?: boolean }) {
  return (
    <span className={chica ? 'tapa chica' : 'tapa'} aria-hidden="true"
          style={{ background: (genero && COLORES[genero]) || '#3a3a3a' }}>
      {titulo.charAt(0)}
    </span>
  );
}
