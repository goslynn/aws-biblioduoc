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
