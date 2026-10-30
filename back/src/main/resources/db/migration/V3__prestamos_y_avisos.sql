-- =============================================================================
--  V3  --  Gestion de prestamos por empleados y avisos por correo
-- =============================================================================
--
--  QUE ANADE
--    * El ciclo de vida completo de una solicitud: un EMPLEADO la aprueba
--      (descuenta stock), la rechaza (con motivo) o registra la devolucion
--      (repone stock). Nuevo estado DEVUELTA.
--    * Lo que necesitan los avisos por correo: con que direccion avisar a cada
--      estudiante (contacto_aviso), quien espera un libro agotado
--      (suscripcion_libro), que correos ya salieron (correo_enviado) y la
--      bandeja de salida de eventos hacia RabbitMQ (evento_pendiente).
--    * Una bitacora de todo lo que pasa, alimentada desde RabbitMQ.
--
--  PERMISOS
--    biblioteca_app sigue sin DDL. Gana UPDATE solo sobre las columnas que
--    cambian de verdad (el stock y el estado de la solicitud), y DELETE solo
--    donde borrar es parte del negocio (darse de baja de un aviso, vaciar la
--    bandeja de salida). El historial (bitacora, correo_enviado) sigue siendo
--    de solo INSERT.
--
--  OJO: Flyway sustituye placeholders tambien en los comentarios, asi que
--  aqui no se escribe ningun dolar seguido de llave.
-- =============================================================================

-- -----------------------------------------------------------------------------
--  Solicitud: estado DEVUELTA y los datos de la resolucion.
--
--  fecha_devolucion se mantiene (la API la sigue devolviendo), pero al aprobar
--  se recalcula desde vence_en: el plazo cuenta desde que el empleado aprueba,
--  no desde que el estudiante envio el formulario.
-- -----------------------------------------------------------------------------
ALTER TABLE solicitud DROP CONSTRAINT solicitud_estado_check;
ALTER TABLE solicitud ADD CONSTRAINT solicitud_estado_check
    CHECK (estado IN ('RECIBIDA', 'APROBADA', 'RECHAZADA', 'DEVUELTA'));

ALTER TABLE solicitud
    ADD COLUMN motivo_rechazo        VARCHAR(300),           -- solo si RECHAZADA
    ADD COLUMN resuelta_por          VARCHAR(200),           -- sub o client_id del empleado
    ADD COLUMN resuelta_en           TIMESTAMPTZ,
    ADD COLUMN vence_en              TIMESTAMPTZ,            -- resuelta_en + dias, si APROBADA
    ADD COLUMN devuelta_en           TIMESTAMPTZ,
    ADD COLUMN recordatorio_enviado  BOOLEAN NOT NULL DEFAULT FALSE;

-- El programador de recordatorios busca prestamos vivos por vencimiento.
CREATE INDEX solicitud_vencimiento_idx ON solicitud (vence_en)
    WHERE estado = 'APROBADA' AND NOT recordatorio_enviado;

-- -----------------------------------------------------------------------------
--  Con que correo avisar a cada estudiante. Que exista la fila ES el
--  consentimiento: activar avisos la crea, desactivarlos la borra. El email lo
--  copia el backend desde Cognito (verificado), nunca lo escribe el usuario.
-- -----------------------------------------------------------------------------
CREATE TABLE contacto_aviso (
    solicitante  VARCHAR(200) PRIMARY KEY,                -- claim sub
    email        VARCHAR(320) NOT NULL,
    activado_en  TIMESTAMPTZ  NOT NULL
);

-- -----------------------------------------------------------------------------
--  "Avisame cuando vuelva". Una fila por estudiante y libro agotado. Se borra
--  cuando el aviso sale, o cuando el estudiante se da de baja.
-- -----------------------------------------------------------------------------
CREATE TABLE suscripcion_libro (
    libro_id     VARCHAR(10)  NOT NULL REFERENCES libro (id),
    solicitante  VARCHAR(200) NOT NULL,
    creada_en    TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (libro_id, solicitante)
);

-- -----------------------------------------------------------------------------
--  Bandeja de salida (transactional outbox).
--
--  El evento se escribe en la MISMA transaccion que el cambio de negocio. Un
--  proceso aparte (RelayEventos) lo publica en RabbitMQ y recien entonces lo
--  borra. Asi no puede pasar que la solicitud se guarde y el evento se pierda
--  porque el broker estaba caido en ese instante.
-- -----------------------------------------------------------------------------
CREATE TABLE evento_pendiente (
    id         UUID         PRIMARY KEY,
    tipo       VARCHAR(40)  NOT NULL,                     -- routing key: solicitud.aprobada...
    cuerpo     TEXT         NOT NULL,                     -- el evento en JSON
    creado_en  TIMESTAMPTZ  NOT NULL
);

-- -----------------------------------------------------------------------------
--  Que correos ya salieron. RabbitMQ entrega "al menos una vez": si un mensaje
--  llega dos veces, esta tabla evita mandar el mismo correo dos veces.
-- -----------------------------------------------------------------------------
CREATE TABLE correo_enviado (
    mensaje_id    VARCHAR(100) PRIMARY KEY,
    destinatario  VARCHAR(320) NOT NULL,
    asunto        VARCHAR(200) NOT NULL,
    enviado_en    TIMESTAMPTZ  NOT NULL
);

-- -----------------------------------------------------------------------------
--  Bitacora: todo evento publicado, con quien lo provoco. La llena el
--  consumidor de la cola "bitacora" (binding "#" en el exchange de eventos).
--  evento_id UNIQUE la hace idempotente ante reentregas.
-- -----------------------------------------------------------------------------
CREATE TABLE bitacora (
    id           BIGSERIAL    PRIMARY KEY,
    evento_id    UUID         NOT NULL UNIQUE,
    tipo         VARCHAR(40)  NOT NULL,
    actor        VARCHAR(200) NOT NULL,                   -- sub, client_id o "sistema"
    referencia   VARCHAR(20)  NOT NULL,                   -- SOL-0001 o L-001
    detalle      TEXT         NOT NULL,                   -- el evento en JSON
    ocurrido_en  TIMESTAMPTZ  NOT NULL
);
CREATE INDEX bitacora_ocurrido_idx ON bitacora (ocurrido_en DESC);

-- -----------------------------------------------------------------------------
--  Permisos de biblioteca_app: lo justo para cada tabla.
-- -----------------------------------------------------------------------------
GRANT UPDATE (ejemplares) ON libro TO biblioteca_app;
GRANT UPDATE (estado, motivo_rechazo, resuelta_por, resuelta_en, vence_en,
              devuelta_en, recordatorio_enviado, fecha_devolucion)
      ON solicitud TO biblioteca_app;

GRANT SELECT, INSERT, UPDATE, DELETE ON contacto_aviso    TO biblioteca_app;
GRANT SELECT, INSERT, DELETE         ON suscripcion_libro TO biblioteca_app;
GRANT SELECT, INSERT, DELETE         ON evento_pendiente  TO biblioteca_app;
GRANT SELECT, INSERT                 ON correo_enviado    TO biblioteca_app;
GRANT SELECT, INSERT                 ON bitacora          TO biblioteca_app;
GRANT USAGE                          ON SEQUENCE bitacora_id_seq TO biblioteca_app;
