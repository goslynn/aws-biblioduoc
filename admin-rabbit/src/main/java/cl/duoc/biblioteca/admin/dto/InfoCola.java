package cl.duoc.biblioteca.admin.dto;

/**
 * Respuesta de GET /api/admin/colas/{nombre} y del POST que la crea.
 *
 * @param nombre       la cola
 * @param mensajes     mensajes listos para entregar
 * @param consumidores consumidores conectados
 */
public record InfoCola(String nombre, long mensajes, int consumidores) {
}
