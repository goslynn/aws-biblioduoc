import { useEffect, useState } from 'react';

/**
 * Consulta una vista "en vivo" cada {@code intervaloMs} y devuelve la ultima
 * respuesta (null hasta la primera). Lo usan el catalogo (stock en vivo, H8) y
 * la bandeja del empleado (solicitudes en vivo, H9): las dos vistas que
 * alimenta Kafka en el backend.
 *
 * POR QUE SONDEO Y NO SSE O WEBSOCKET
 *   Todo pasa por API Gateway REST, que bufferea la respuesta entera y corta a
 *   los 29 s: un flujo abierto no llega al navegador. La parte "en tiempo
 *   real" ocurre en el backend (los consumidores de Kafka actualizan la
 *   proyeccion al instante); el navegador solo la lee cada pocos segundos, y
 *   esa lectura no toca la base de datos.
 *
 * Con la pestana oculta no consulta nada (ahorra peticiones y tokens), y al
 * volver a ella pide una lectura enseguida. Un fallo deja el ultimo valor: si
 * la API no responde un momento, la pantalla no parpadea.
 */
export function useSondeo<T>(pedir: () => Promise<T>, intervaloMs: number): T | null {
  const [valor, setValor] = useState<T | null>(null);

  useEffect(() => {
    let vigente = true;
    const leer = () => {
      if (document.hidden) {
        return;
      }
      pedir().then(
        (v) => { if (vigente) setValor(v); },
        () => { /* se conserva el ultimo valor */ },
      );
    };
    leer();
    const reloj = window.setInterval(leer, intervaloMs);
    document.addEventListener('visibilitychange', leer);
    return () => {
      vigente = false;
      window.clearInterval(reloj);
      document.removeEventListener('visibilitychange', leer);
    };
  }, [pedir, intervaloMs]);

  return valor;
}
