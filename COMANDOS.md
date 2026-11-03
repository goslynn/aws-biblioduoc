# Glosario de comandos

Todos los comandos se corren desde la raiz del repositorio, salvo que se indique
otra cosa. El detalle de cada stack y script esta en `aws/README.md`.

## Flujos habituales

**Empezar a trabajar**

```sh
awsacademy start                          # espera unos minutos a que arranquen RDS y las EC2
./aws/pipeline/rota-credenciales-lab.sh   # solo si vas a desplegar desde GitHub
./aws/pipeline/bruno-env.sh               # solo si vas a probar con Bruno
```

**Desplegar un cambio desde GitHub**

`despliegue.yml` publica lo que esta en `main`, no lo que tienes en tu disco.

```sh
git push origin main
gh workflow run despliegue.yml -f objetivo=backend
gh run watch
```

**Crear todo de cero desde GitHub**

```sh
awsacademy start
./aws/pipeline/rota-credenciales-lab.sh
./aws/pipeline/rota-claves-demo.sh
grep -E '^SMTP_' aws/.env | gh secret set -f -
gh workflow run despliegue.yml -f objetivo=bootstrap
gh run watch
./aws/pipeline/bruno-env.sh
```

**Terminar de trabajar**

```sh
./aws/scripts/vpn-down.sh   # solo si levantaste la VPN: sigue cobrando con el lab abajo
awsacademy stop             # NO uses teardown.sh: borra todo
```

## Laboratorio

| Comando | Que hace |
|---|---|
| `awsacademy start` | Arranca el lab y renueva las credenciales locales |
| `awsacademy status` | Estado del lab, tiempo restante y presupuesto |
| `awsacademy console` | Abre la consola de AWS en el navegador |
| `awsacademy stop` | Baja el lab; se conservan los stacks y los datos |

## Credenciales y secretos de GitHub

| Comando | Que hace |
|---|---|
| `./aws/pipeline/rota-credenciales-lab.sh` | Sube las credenciales del lab a GitHub e indica cuando caducan |
| `./aws/pipeline/rota-claves-demo.sh` | Cambia las contrasenas de demo en Cognito, en `aws/.local/` y en GitHub |
| `grep -E '^SMTP_' aws/.env \| gh secret set -f -` | Sube los datos de SMTP desde `aws/.env` |
| `gh secret list` | Lista los secretos y su fecha; nunca muestra los valores |

## Desplegar desde GitHub

| Comando | Cuando usarlo |
|---|---|
| `gh workflow run despliegue.yml -f objetivo=backend` | Cambiaste Java, `aws/backend/*` o `backend.yaml` |
| `gh workflow run despliegue.yml -f objetivo=backend -f skip_tests=true` | Lo mismo, sin tests |
| `gh workflow run despliegue.yml -f objetivo=web` | Cambiaste el frontend o el `Caddyfile` |
| `gh workflow run despliegue.yml -f objetivo=api` | Cambiaste `api.yaml` |
| `gh workflow run despliegue.yml -f objetivo=bootstrap` | Otras plantillas, varios cambios o crear todo de cero |

`bootstrap` es idempotente y sirve para cualquier cambio, pero siempre recompila,
sube otro paquete y reinicia el backend. Un objetivo parcial es mas rapido y
reinicia menos.

## Seguir las ejecuciones

| Comando | Que hace |
|---|---|
| `gh run list --limit 5` | Ultimas ejecuciones y sus ID |
| `gh run list --workflow despliegue.yml` | Solo los despliegues |
| `gh run watch` | Elegir una ejecucion en curso y seguirla |
| `gh run watch <id> --exit-status` | Seguir una ejecucion; devuelve error si falla |
| `gh run view <id>` | Resumen por pasos |
| `gh run view <id> --log` | Log completo |
| `gh run view <id> --log-failed` | Solo el log de los pasos fallidos |
| `gh run view <id> --web` | Abrir la ejecucion en el navegador |
| `gh run cancel <id>` | Cancelar; puede dejar un stack actualizandose en AWS |

## Desplegar desde tu equipo

Sube el paquete del backend desde tu conexion. `DEBUG` y `REMINDER_LEAD` solo
estan disponibles por esta via.

| Comando | Que hace |
|---|---|
| `./aws/scripts/bootstrap.sh` | Crea o actualiza todo, en orden |
| `SKIP_WEB=true ./aws/scripts/bootstrap.sh` | Lo mismo, sin compilar ni publicar el frontend |
| `./aws/pipeline/build-backend.sh` | Compila, sube y reinicia el backend |
| `SKIP_TESTS=true ./aws/pipeline/build-backend.sh` | Lo mismo, sin tests |
| `REMINDER_LEAD=PT23H58M ./aws/pipeline/build-backend.sh` | Recordatorio a los ~2 min, para la demo |
| `./aws/pipeline/publish-web.sh` | Compila y publica el frontend |
| `SOLO_CONFIG=true ./aws/pipeline/publish-web.sh` | Solo genera los archivos del frontend |
| `DEBUG=true ./aws/pipeline/publish-web.sh` | Agrega la vista `/diagnostico`; no es entregable |
| `./aws/pipeline/api-deploy.sh` | Actualiza el stack de la API y publica el stage |
| `./aws/scripts/teardown.sh` | **Borra todo**; pide escribir `BORRAR` |

## Probar

Los entornos de Bruno se generan siempre en local y sus tokens duran 60 minutos.

| Comando | Resultado esperado |
|---|---|
| `./aws/pipeline/bruno-env.sh` | Genera los entornos con tokens nuevos |
| `cd bruno && bru run --env aws` | Estudiante: 200/201, y 403 en `/gestion` |
| `cd bruno && bru run --env aws-backoffice` | Empleado: 200 en `/gestion` |
| `cd bruno && bru run --env aws-usuario-sin-scope` | 401 |
| `cd bruno && bru run --env aws-id-token` | 401 |
| `cd bruno && bru run --env aws-token-invalido` | 401 |
| `cd bruno && bru run admin --env aws-admin-vpn` | Por la VPN: 201 / 200 / 204 |
| `cd bruno && bru run admin --env aws-admin-vpn-estudiante` | Por la VPN: 403 |

El frontend se comprueba en el navegador, con el recorrido de `front/README.md` §4.

## VPN

| Comando | Que hace |
|---|---|
| `./aws/scripts/vpn-up.sh` | Levanta la VPN hasta la base y el panel de RabbitMQ (pide sudo) |
| `./aws/scripts/vpn-down.sh` | La baja y borra su stack |

## Consultar y diagnosticar

**URL del sitio**

```sh
aws cloudformation describe-stacks --stack-name biblioteca-web \
  --query "Stacks[0].Outputs[?OutputKey=='SiteUrl'].OutputValue" --output text
```

**Salud del backend** (un 500 en todas las rutas suele ser el NLB sin destino sano)

```sh
aws elbv2 describe-target-health --target-group-arn "$(aws cloudformation describe-stacks \
  --stack-name biblioteca-backend --query "Stacks[0].Outputs[?OutputKey=='TargetGroupArn'].OutputValue" --output text)"
```

**Logs del backend** (streams `app`, `admin-rabbit`, `rabbitmq-1` y `rabbitmq-2`)

```sh
aws logs tail /biblioteca/backend --follow --region us-east-1
```

**Estado y salidas de un stack**

```sh
aws cloudformation describe-stacks --stack-name biblioteca-backend --query 'Stacks[0].StackStatus'
aws cloudformation describe-stacks --stack-name biblioteca-api --query 'Stacks[0].Outputs' --output table
```

**Primer arranque de una EC2**

```sh
aws ec2 get-console-output --instance-id <id> --latest --output text
```

**Las contrasenas de demo** estan en `aws/.local/usuario-demo.env`.

## Compilar sin desplegar

| Comando | Que hace |
|---|---|
| `./mvnw package` | Compila y prueba los tres modulos (necesita Docker) |
| `./mvnw -pl back -am package` | Solo la API y `comun` |
| `cd front && npm ci && npm run build` | Compila el frontend; antes hay que copiar las dos plantillas |

Las dos plantillas del frontend, que normalmente genera el pipeline:

```sh
cd front
cp src/app/aws-config.example.ts        src/app/aws-config.ts
cp src/app/diagnostico/rutas.example.ts src/app/diagnostico/rutas.ts
```

## Si algo falla

| Sintoma | Solucion |
|---|---|
| `despliegue.yml` falla en "Comprobar credenciales" | `awsacademy start && ./aws/pipeline/rota-credenciales-lab.sh`, y relanzar |
| El despliegue falla a medias porque caducaron las credenciales | Rotarlas y relanzar; los scripts son idempotentes |
| `objetivo=bootstrap` dice que faltan `DEMO_PASSWORD` o `EMPLOYEE_PASSWORD` | `./aws/pipeline/rota-claves-demo.sh`, y relanzar |
| 500 justo despues de levantar el lab | La app sigue arrancando; esperar un par de minutos |
| Bruno da 401 en los casos que deberian dar 200 | Tokens caducados: `./aws/pipeline/bruno-env.sh` |
| Un cambio no aparece tras desplegar desde GitHub | Faltaba `git push`: se despliega lo que esta en `main` |
