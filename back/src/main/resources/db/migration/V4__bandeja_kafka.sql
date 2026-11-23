-- =============================================================================
--  V4: la bandeja de salida de KAFKA.
--
--  Mismo patron que evento_pendiente (V3), pero en una tabla PROPIA y con su
--  propio relay (streaming/RelayKafka). Por que no reutilizar evento_pendiente:
--
--    * Independencia. La pauta exige que Kafka complemente la arquitectura
--      SIN afectar a RabbitMQ. Con una sola tabla y un solo relay, un Kafka
--      caido haria rollback del lote entero y los correos (RabbitMQ) dejarian
--      de salir. Con dos tablas, cada broker vacia la suya a su ritmo.
--    * Otro contenido. evento_pendiente guarda el sobre generico Evento para
--      el exchange topic; aqui va un DTO tipado (CambioStock, HechoSolicitud)
--      con su topico y su CLAVE de particion.
--
--  La fila se escribe en la MISMA transaccion que el cambio de negocio
--  (BandejaKafka, Propagation.MANDATORY) y RelayKafka la borra cuando el
--  broker confirmo la escritura en las replicas (acks=all).
-- =============================================================================

CREATE TABLE evento_kafka (
    id         UUID          PRIMARY KEY,
    topico     VARCHAR(100)  NOT NULL,                    -- biblioteca.inventario...
    clave      VARCHAR(100)  NOT NULL,                    -- clave de particion: L-001, SOL-0001
    cuerpo     TEXT          NOT NULL,                    -- el DTO en JSON
    creado_en  TIMESTAMPTZ   NOT NULL
);

-- Igual que evento_pendiente: se inserta y se borra, nunca se modifica.
GRANT SELECT, INSERT, DELETE ON evento_kafka TO biblioteca_app;
