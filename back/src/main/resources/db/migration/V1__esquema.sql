-- =============================================================================
--  V1  --  Esquema de la biblioteca y rol de la aplicacion
-- =============================================================================
--
--  QUIEN LO EJECUTA
--    Flyway, al arrancar la aplicacion, conectado con el usuario MAESTRO de RDS
--    (spring.flyway.user). Es el unico momento en que se usa: la aplicacion
--    atiende las peticiones con el rol biblioteca_app que se crea al final.
--
--  POR QUE LA APLICACION NO USA EL USUARIO MAESTRO
--    Minimo privilegio. biblioteca_app puede leer el catalogo y registrar
--    solicitudes, y nada mas: ni DDL, ni UPDATE, ni DELETE. Una inyeccion SQL o
--    una credencial filtrada de la aplicacion no alcanzan para borrar tablas ni
--    para reescribir el historial de solicitudes.
--
--  El placeholder appPassword (ver CREATE ROLE, al final) lo rellena la variable
--  BIBLIOTECA_APP_PASSWORD, que aws/backend.yaml resuelve desde
--  el secreto AppSecret de aws/database.yaml. No esta escrito en ningun sitio.
-- =============================================================================

-- Busqueda sin tildes ("garcia" encuentra "Garcia" con tilde). RDS la trae
-- instalada; crearla exige el usuario maestro, por eso va aqui y no en la app.
CREATE EXTENSION IF NOT EXISTS unaccent;

-- -----------------------------------------------------------------------------
--  Catalogo. Lo siembra V2; la aplicacion solo lo lee.
-- -----------------------------------------------------------------------------
CREATE TABLE libro (
    id          VARCHAR(10)  PRIMARY KEY,                 -- "L-001"
    titulo      VARCHAR(200) NOT NULL,
    autor       VARCHAR(200) NOT NULL,
    genero      VARCHAR(50)  NOT NULL,
    anio        INTEGER      NOT NULL,
    isbn        VARCHAR(20)  NOT NULL UNIQUE,
    ejemplares  INTEGER      NOT NULL CHECK (ejemplares >= 0),  -- 0 = no disponible
    ubicacion   VARCHAR(50)  NOT NULL,
    sinopsis    VARCHAR(500) NOT NULL
);

-- -----------------------------------------------------------------------------
--  Solicitudes de prestamo.
--
--  Los CHECK repiten los enums de Java (EstadoSolicitud, Sede) a proposito: la
--  base no debe aceptar un valor que la aplicacion no sabria leer, venga de
--  donde venga la escritura.
-- -----------------------------------------------------------------------------
CREATE SEQUENCE solicitud_seq;   -- el correlativo de "SOL-0001"

CREATE TABLE solicitud (
    id                VARCHAR(20)  PRIMARY KEY,
    solicitante       VARCHAR(200) NOT NULL,              -- claim sub o client_id del JWT
    estado            VARCHAR(20)  NOT NULL
                      CHECK (estado IN ('RECIBIDA', 'APROBADA', 'RECHAZADA')),
    dias              INTEGER      NOT NULL CHECK (dias BETWEEN 1 AND 30),
    motivo            VARCHAR(300) NOT NULL,
    sede              VARCHAR(20)  NOT NULL
                      CHECK (sede IN ('SAN_JOAQUIN', 'PLAZA_OESTE', 'PUENTE_ALTO',
                                      'VINA_DEL_MAR', 'CONCEPCION')),
    creada_en         TIMESTAMPTZ  NOT NULL,
    fecha_devolucion  DATE         NOT NULL
);

-- "Mis solicitudes" filtra por solicitante y ordena por fecha.
CREATE INDEX solicitud_solicitante_idx ON solicitud (solicitante, creada_en DESC);

-- Titulo y autor se COPIAN del catalogo: la solicitud es un documento
-- historico y tiene que seguir siendo legible aunque el catalogo cambie. Por
-- eso libro_id no es clave foranea hacia libro.
CREATE TABLE linea_solicitud (
    solicitud_id  VARCHAR(20)  NOT NULL REFERENCES solicitud (id),
    orden         INTEGER      NOT NULL,                  -- conserva el orden del formulario
    libro_id      VARCHAR(10)  NOT NULL,
    titulo        VARCHAR(200) NOT NULL,
    autor         VARCHAR(200) NOT NULL,
    cantidad      INTEGER      NOT NULL CHECK (cantidad BETWEEN 1 AND 3),
    PRIMARY KEY (solicitud_id, orden)
);

-- -----------------------------------------------------------------------------
--  El rol de la aplicacion, con lo justo.
-- -----------------------------------------------------------------------------
CREATE ROLE biblioteca_app LOGIN PASSWORD '${appPassword}';

GRANT SELECT                ON libro           TO biblioteca_app;
GRANT SELECT, INSERT        ON solicitud       TO biblioteca_app;
GRANT SELECT, INSERT        ON linea_solicitud TO biblioteca_app;
GRANT USAGE                 ON SEQUENCE solicitud_seq TO biblioteca_app;
