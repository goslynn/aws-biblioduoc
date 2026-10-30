package cl.duoc.biblioteca.avisos;

/**
 * Un correo listo para salir: es el mensaje de las colas correo.*.
 *
 * Lleva el texto ya redactado y la direccion ya resuelta, de modo que
 * {@link EnviadorSmtp} no consulte nada: solo envia. Si el SMTP falla y el
 * mensaje vuelve horas despues, sale exactamente el mismo correo.
 *
 * @param mensajeId  unico por destinatario y evento; es la clave de
 *                   idempotencia en la tabla correo_enviado
 * @param para       direccion de destino (la verificada en Cognito)
 * @param asunto     linea de asunto
 * @param cuerpo     texto plano
 */
public record Correo(String mensajeId, String para, String asunto, String cuerpo) {
}
