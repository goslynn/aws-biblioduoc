package cl.duoc.biblioteca.admin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservicio administrador de RabbitMQ.
 *
 * Crea y borra colas, exchanges y bindings por REST. Corre en su propio
 * contenedor, en la misma EC2 que la API y el cluster (aws/backend/compose.yaml),
 * en el puerto 8081.
 *
 * SOLO INTERNO: no esta detras de API Gateway. Se llega por la VPN opcional
 * (aws/vpn.yaml), y aun asi exige un JWT de Cognito con rol EMPLEADO: la misma
 * validacion que la API, del modulo comun.
 *
 * Escanea su propio paquete y el de comun (seguridad JWT y errores). La API
 * no necesita nombrarlo: su paquete base, cl.duoc.biblioteca, ya lo contiene.
 */
@SpringBootApplication(scanBasePackages = { "cl.duoc.biblioteca.admin", "cl.duoc.biblioteca.comun" })
public class AdminRabbitApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminRabbitApplication.class, args);
    }
}
