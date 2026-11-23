# bruno/CLAUDE.md

Antes de tocar esta carpeta, invoca el skill `bruno-collections`.

## Matriz de entornos

```sh
cd bruno && bru run --env aws                   # estudiante: 200/201, y 403 en /gestion (RBAC)
bru run --env aws-backoffice                    # empleado (M2M scope gestion): 200 en /gestion, 403 al crear
                                                #  (correr antes --env aws: deja la RECIBIDA que aprueba)
bru run --env aws-usuario-sin-scope             # 401
bru run --env aws-id-token                      # 401
bru run --env aws-token-invalido                # 401
SSL_CERT_DIR=/etc/ssl/certs bru run --env aws   # NixOS, si OpenSSL se queja

# administrador de RabbitMQ: otro servicio, solo por la VPN (vpn-up.sh)
bru run admin --env aws-admin-vpn               # 201 / 200 / 204 (empleado)
bru run admin --env aws-admin-vpn-estudiante    # 403

# administrador de Kafka: igual, puerto 8082, carpeta admin-kafka/
bru run admin-kafka --env aws-admin-kafka-vpn             # ciclo completo (empleado)
bru run admin-kafka --env aws-admin-kafka-vpn-estudiante  # 403
```

Las carpetas `admin/` y `admin-kafka/` se saltan solas en los entornos de la
API (no tienen `ADMIN_URL` ni `ADMIN_KAFKA_URL`). El runner `bru` 4.2.1 **solo envia los query params que estan
tambien en la `url`** (medido): si agregas uno activo, ponlo en los dos sitios.
