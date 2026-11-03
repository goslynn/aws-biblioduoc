/**
 * Lo que ocupa el lugar de una lista mientras llega la primera respuesta. Sin
 * esto, la pantalla pasa por "no hay nada" antes de mostrar los datos, y eso
 * se ve como un parpadeo. Aparece con un pequeno retraso (styles.css): si la
 * API responde rapido, no llega a verse.
 */
export function Esqueleto({ filas = 3 }: { filas?: number }) {
  return (
    <div className="esqueletos" role="status">
      <span className="solo-lectores">Cargando…</span>
      {Array.from({ length: filas }, (_, i) => <div key={i} className="esqueleto" aria-hidden="true" />)}
    </div>
  );
}
