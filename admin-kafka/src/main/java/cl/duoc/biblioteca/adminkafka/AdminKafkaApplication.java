package cl.duoc.biblioteca.adminkafka;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Microservicio administrador de Kafka.
 *
 * Crea, describe, reconfigura y borra topicos, y supervisa y reposiciona
 * consumer groups (lag por particion, reinicio de offsets), por REST. Corre en
 * su propio contenedor, en la misma EC2 que la API y los brokers
 * (aws/backend/compose.yaml), en el puerto 8082.
 *
 * SOLO INTERNO, igual que admin-rabbit: no esta detras de API Gateway. Se llega
 * por la VPN opcional (aws/vpn.yaml), y aun asi exige un JWT de Cognito con rol
 * EMPLEADO (la misma validacion del modulo comun).
 */
@SpringBootApplication(scanBasePackages = { "cl.duoc.biblioteca.adminkafka", "cl.duoc.biblioteca.comun" })
public class AdminKafkaApplication {

    public static void main(String[] args) {
        SpringApplication.run(AdminKafkaApplication.class, args);
    }
}
