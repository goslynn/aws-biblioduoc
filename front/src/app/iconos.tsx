import type { ReactNode } from 'react';

/**
 * Iconos de trazo, todos decorativos (aria-hidden): el texto del boton o del
 * enlace que los acompana es el que se lee. Un boton que solo lleva icono
 * pone su propio aria-label.
 */
function Icono({ tamano = 20, grosor = 2, children }: { tamano?: number; grosor?: number; children: ReactNode }) {
  return (
    <svg width={tamano} height={tamano} viewBox="0 0 24 24" fill="none" stroke="currentColor"
         strokeWidth={grosor} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
      {children}
    </svg>
  );
}

type Props = { tamano?: number };

export const IconoLibro = ({ tamano = 22 }: Props) => (
  <Icono tamano={tamano}>
    <path d="M12 6.5c-1.6-1.2-3.6-1.8-6-1.8v13c2.4 0 4.4.6 6 1.8 1.6-1.2 3.6-1.8 6-1.8v-13c-2.4 0-4.4.6-6 1.8Z" />
    <path d="M12 6.5v13" />
  </Icono>
);

export const IconoLupa = ({ tamano }: Props) => (
  <Icono tamano={tamano}><circle cx="10.5" cy="10.5" r="6.5" /><path d="M15.5 15.5 21 21" /></Icono>
);

export const IconoCheck = ({ tamano = 18 }: Props) => (
  <Icono tamano={tamano} grosor={2.4}><path d="m5 12.5 4.5 4.5L19 7.5" /></Icono>
);

export const IconoMas = ({ tamano = 18 }: Props) => (
  <Icono tamano={tamano} grosor={2.4}><path d="M12 5v14M5 12h14" /></Icono>
);

export const IconoCerrar = ({ tamano = 18 }: Props) => (
  <Icono tamano={tamano}><path d="M6 6l12 12M18 6 6 18" /></Icono>
);

export const IconoCampana = ({ tamano = 18 }: Props) => (
  <Icono tamano={tamano}><path d="M6 16V11a6 6 0 0 1 12 0v5l1.5 2h-15Z" /><path d="M10 20.5a2 2 0 0 0 4 0" /></Icono>
);

export const IconoReloj = ({ tamano = 22 }: Props) => (
  <Icono tamano={tamano}><circle cx="12" cy="12" r="9" /><path d="M12 7v5l3.5 2" /></Icono>
);

export const IconoCalendario = ({ tamano = 20 }: Props) => (
  <Icono tamano={tamano}><rect x="3.5" y="5" width="17" height="15" rx="2" /><path d="M3.5 10h17M8 3v4M16 3v4" /></Icono>
);

export const IconoUbicacion = ({ tamano = 16 }: Props) => (
  <Icono tamano={tamano}><path d="M12 21s-7-6.2-7-11.5a7 7 0 0 1 14 0C19 14.8 12 21 12 21Z" /><circle cx="12" cy="9.5" r="2.5" /></Icono>
);

export const IconoFlechaAbajo = ({ tamano = 20 }: Props) => (
  <Icono tamano={tamano} grosor={2.2}><path d="m6 9 6 6 6-6" /></Icono>
);

export const IconoAlerta = ({ tamano = 14 }: Props) => (
  <Icono tamano={tamano} grosor={2.4}><circle cx="12" cy="12" r="9" /><path d="M12 8v5M12 16.5v.5" /></Icono>
);

export const IconoFiltros = ({ tamano = 18 }: Props) => (
  <Icono tamano={tamano}><path d="M4 6h16M7 12h10M10 18h4" /></Icono>
);
