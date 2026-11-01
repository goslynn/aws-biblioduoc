/**
 * Una fecha ISO del backend como "dd-MM-yyyy HH:mm", en la hora local del
 * navegador (lo que hacia el pipe date de Angular con ese mismo patron).
 */
export function fechaHora(iso: string): string {
  const fecha = new Date(iso);
  const dos = (n: number) => String(n).padStart(2, '0');
  return `${dos(fecha.getDate())}-${dos(fecha.getMonth() + 1)}-${fecha.getFullYear()} `
    + `${dos(fecha.getHours())}:${dos(fecha.getMinutes())}`;
}

/** Una fecha como "dd-MM-yyyy", sin la hora. */
export function fecha(valor: Date): string {
  const dos = (n: number) => String(n).padStart(2, '0');
  return `${dos(valor.getDate())}-${dos(valor.getMonth() + 1)}-${valor.getFullYear()}`;
}

/**
 * Dias de calendario desde hoy hasta una fecha ISO: 0 si vence hoy, negativo
 * si ya vencio. Cuenta dias, no horas: lo que importa al estudiante es el dia.
 */
export function diasHasta(iso: string): number {
  const hoy = new Date();
  const objetivo = new Date(iso);
  const medianoche = (d: Date) => new Date(d.getFullYear(), d.getMonth(), d.getDate()).getTime();
  return Math.round((medianoche(objetivo) - medianoche(hoy)) / 86_400_000);
}
