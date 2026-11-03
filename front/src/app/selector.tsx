import { useEffect, useId, useRef, useState, type KeyboardEvent } from 'react';

import { IconoCheck, IconoFlechaAbajo } from './iconos';

export interface OpcionSelector<T extends string> {
  valor: T;
  texto: string;
}

interface Props<T extends string> {
  /** El id del boton: es a lo que apunta el <label htmlFor> de quien lo usa. */
  id: string;
  valor: T;
  opciones: OpcionSelector<T>[];
  alCambiar: (valor: T) => void;
  describidoPor?: string;
}

/**
 * EL selector de la aplicacion: todos los desplegables son este componente.
 *
 * No es un <select> a proposito: la lista que abre un <select> (y la de un
 * <datalist>) la pinta el navegador y el CSS no llega a ella, asi que nunca se
 * veria con la marca. El teclado es el de un <select> (patron "select-only
 * combobox" de WAI-ARIA): flechas, Inicio, Fin, Enter, Espacio, Esc y escribir
 * la primera letra.
 *
 * El foco se queda siempre en el boton: la opcion resaltada se anuncia con
 * aria-activedescendant. La lista se cierra al perder el foco y tambien al tocar
 * fuera, que hay que mirarlo aparte: Safari no le da el foco a un boton al que
 * se le hace clic, asi que ahi nunca lo pierde.
 */
export function Selector<T extends string>({ id, valor, opciones, alCambiar, describidoPor }: Props<T>) {
  const idLista = useId();
  const raiz = useRef<HTMLDivElement>(null);
  const lista = useRef<HTMLUListElement>(null);
  const [abierto, setAbierto] = useState(false);
  const [activa, setActiva] = useState(0);

  // Lo que se lleva escrito para saltar por la primera letra, y su temporizador.
  const escrito = useRef({ texto: '', temporizador: 0 });

  const posicion = opciones.findIndex((o) => o.valor === valor);

  const abrir = () => {
    setActiva(Math.max(posicion, 0));
    setAbierto(true);
  };

  const elegir = (indice: number) => {
    const opcion = opciones[indice];
    if (opcion) {
      alCambiar(opcion.valor);
    }
    setAbierto(false);
  };

  useEffect(() => {
    if (abierto) {
      lista.current?.children[activa]?.scrollIntoView({ block: 'nearest' });
    }
  }, [abierto, activa]);

  useEffect(() => {
    if (!abierto) {
      return;
    }
    const alTocar = (evento: PointerEvent) => {
      if (!raiz.current?.contains(evento.target as Node)) {
        setAbierto(false);
      }
    };
    document.addEventListener('pointerdown', alTocar);
    return () => document.removeEventListener('pointerdown', alTocar);
  }, [abierto]);

  const saltarA = (letra: string) => {
    window.clearTimeout(escrito.current.temporizador);
    escrito.current.texto += letra.toLowerCase();
    escrito.current.temporizador = window.setTimeout(() => { escrito.current.texto = ''; }, 500);
    const indice = opciones.findIndex((o) => o.texto.toLowerCase().startsWith(escrito.current.texto));
    if (indice >= 0) {
      setActiva(indice);
      setAbierto(true);
    }
  };

  const alPulsar = (evento: KeyboardEvent) => {
    const { key } = evento;
    if (key === 'Tab') {
      setAbierto(false);
      return;
    }
    if (key.length === 1 && key !== ' ' && !evento.ctrlKey && !evento.metaKey && !evento.altKey) {
      evento.preventDefault();
      saltarA(key);
      return;
    }
    if (!abierto) {
      if (['ArrowDown', 'ArrowUp', 'Enter', ' '].includes(key)) {
        evento.preventDefault();
        abrir();
      }
      return;
    }
    const ultima = opciones.length - 1;
    const siguiente: Record<string, number> = {
      ArrowDown: Math.min(activa + 1, ultima),
      ArrowUp: Math.max(activa - 1, 0),
      Home: 0,
      End: ultima,
    };
    if (key in siguiente) {
      evento.preventDefault();
      setActiva(siguiente[key]);
    } else if (key === 'Enter' || key === ' ') {
      evento.preventDefault();
      elegir(activa);
    } else if (key === 'Escape') {
      evento.preventDefault();
      setAbierto(false);
    }
  };

  return (
    <div ref={raiz} className="selector">
      <button id={id} type="button" role="combobox" className="selector-boton"
              aria-haspopup="listbox" aria-expanded={abierto} aria-controls={idLista}
              aria-activedescendant={abierto ? `${idLista}-${activa}` : undefined}
              aria-describedby={describidoPor}
              onClick={() => (abierto ? setAbierto(false) : abrir())}
              onBlur={() => setAbierto(false)}
              onKeyDown={alPulsar}>
        <span>{opciones[posicion]?.texto ?? valor}</span>
        <IconoFlechaAbajo />
      </button>
      {abierto && (
        // preventDefault: un clic dentro de la lista no le quita el foco al boton.
        <ul id={idLista} ref={lista} role="listbox" className="selector-lista"
            onMouseDown={(e) => e.preventDefault()}>
          {opciones.map((opcion, i) => (
            <li key={opcion.valor} id={`${idLista}-${i}`} role="option"
                aria-selected={i === posicion} className={i === activa ? 'activa' : undefined}
                onMouseEnter={() => setActiva(i)} onClick={() => elegir(i)}>
              <span>{opcion.texto}</span>
              {i === posicion && <IconoCheck />}
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
