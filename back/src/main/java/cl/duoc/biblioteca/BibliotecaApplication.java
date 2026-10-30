package cl.duoc.biblioteca;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Punto de entrada de la API interna de la Biblioteca Duoc.
 *
 * Es un proceso que vive SIEMPRE, en un contenedor de la EC2 (aws/backend.yaml),
 * junto al broker RabbitMQ. Eso es lo que permite tener, en el mismo jar:
 *
 *   * la API REST que recibe API Gateway (por el NLB, puerto 8080),
 *   * los consumidores de RabbitMQ (@RabbitListener), que necesitan una
 *     conexion abierta todo el tiempo,
 *   * las tareas periodicas (@Scheduled): el relay de la bandeja de salida y
 *     el programador de recordatorios.
 *
 * Una Lambda no sirve para lo segundo ni lo tercero: se congela entre
 * peticiones, y Lambda solo se dispara desde colas de Amazon MQ, que el
 * Learner Lab no permite.
 */
@EnableScheduling
@SpringBootApplication
public class BibliotecaApplication {

    public static void main(String[] args) {
        SpringApplication.run(BibliotecaApplication.class, args);
    }
}
