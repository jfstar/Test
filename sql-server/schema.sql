/* ==========================================================================
   schema.sql

   Crea la base de datos CuentasDB y las tablas cuenta / movimiento con las
   llaves, tipos y restricciones que respaldan las reglas de negocio ya
   implementadas en el microservicio:
     - saldo/monto en DECIMAL(19,4)
     - un DEBIT nunca deja el saldo negativo (CHECK a nivel de fila +
       validacion transaccional en la app, que es quien decide el monto).
     - idempotencia: (cuenta_id, idempotency_key) unico SOLO cuando la clave
       fue enviada (indice unico filtrado, ya que la clave es opcional).
     - IDs UNIQUEIDENTIFIER (UUID) generados por la aplicacion, para evitar
       enumeracion de cuentas/movimientos desde el API publico.

   Ejecutar de principio a fin sobre una instancia limpia de SQL Server
   (Developer/Express, gratuitas). Idempotente: puede volver a ejecutarse
   sin fallar gracias a los DROP ... IF EXISTS.
   ========================================================================== */

IF DB_ID(N'CuentasDB') IS NULL
BEGIN
    CREATE DATABASE CuentasDB;
END
GO

USE CuentasDB;
GO


SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

-- ------------------------------------------------------------------------
-- Limpieza para poder re-ejecutar el script sin errores (orden por FK)
-- ------------------------------------------------------------------------
IF OBJECT_ID(N'dbo.sp_estado_cuenta', N'P') IS NOT NULL
    DROP PROCEDURE dbo.sp_estado_cuenta;
GO

IF OBJECT_ID(N'dbo.movimiento', N'U') IS NOT NULL
    DROP TABLE dbo.movimiento;
GO

IF OBJECT_ID(N'dbo.cuenta', N'U') IS NOT NULL
    DROP TABLE dbo.cuenta;
GO

-- ------------------------------------------------------------------------
-- Tabla: cuenta
-- ------------------------------------------------------------------------
CREATE TABLE dbo.cuenta
(
    id              UNIQUEIDENTIFIER NOT NULL
                        CONSTRAINT df_cuenta_id DEFAULT NEWID(),
    titular         NVARCHAR(150)    NOT NULL,
    saldo           DECIMAL(19,4)    NOT NULL
                        CONSTRAINT df_cuenta_saldo DEFAULT (0),
    -- DATETIMEOFFSET(6), no DATETIME2: Hibernate mapea java.time.Instant a
    -- TIMESTAMP_UTC, que en el dialecto de SQL Server corresponde a
    -- datetimeoffset(6). Usar DATETIME2 aqui rompe la validacion de esquema
    -- de Hibernate al arrancar (ddl-auto=validate en el perfil sqlserver).
    fecha_creacion  DATETIMEOFFSET(6) NOT NULL
                        CONSTRAINT df_cuenta_fecha_creacion DEFAULT (CAST(SYSUTCDATETIME() AS DATETIMEOFFSET)),
    -- Contador de version para el bloqueo optimista de JPA (@Version),
    -- segunda linea de defensa junto al bloqueo pesimista explicito que
    -- aplica el microservicio al debitar/acreditar.
    version         BIGINT           NOT NULL
                        CONSTRAINT df_cuenta_version DEFAULT (0),

    CONSTRAINT pk_cuenta PRIMARY KEY CLUSTERED (id),
    CONSTRAINT ck_cuenta_saldo_no_negativo CHECK (saldo >= 0),
    CONSTRAINT ck_cuenta_titular_no_vacio CHECK (LEN(LTRIM(RTRIM(titular))) > 0)
);
GO

-- ------------------------------------------------------------------------
-- Tabla: movimiento
-- ------------------------------------------------------------------------
CREATE TABLE dbo.movimiento
(
    id                  UNIQUEIDENTIFIER NOT NULL
                            CONSTRAINT df_movimiento_id DEFAULT NEWID(),
    cuenta_id           UNIQUEIDENTIFIER NOT NULL,
    tipo                VARCHAR(10)      NOT NULL,
    monto               DECIMAL(19,4)    NOT NULL,
    -- Saldo de la cuenta inmediatamente despues de aplicar este movimiento
    -- (running balance materializado). Se guarda en el momento de la
    -- transaccion para que el estado de cuenta (sp_estado_cuenta) no tenga
    -- que recalcular sumas acumuladas de todo el historial en cada lectura.
    saldo_resultante    DECIMAL(19,4)    NOT NULL,
    -- Clave de idempotencia enviada por el cliente (cabecera Idempotency-Key
    -- del microservicio). Opcional: puede no venir en la peticion.
    idempotency_key     VARCHAR(100)     NULL,
    -- Mismo motivo que cuenta.fecha_creacion: debe ser DATETIMEOFFSET(6)
    -- para coincidir con el mapeo de Hibernate para java.time.Instant.
    fecha               DATETIMEOFFSET(6) NOT NULL
                            CONSTRAINT df_movimiento_fecha DEFAULT (CAST(SYSUTCDATETIME() AS DATETIMEOFFSET)),

    CONSTRAINT pk_movimiento PRIMARY KEY CLUSTERED (id),
    CONSTRAINT fk_movimiento_cuenta FOREIGN KEY (cuenta_id)
        REFERENCES dbo.cuenta (id),
    CONSTRAINT ck_movimiento_tipo CHECK (tipo IN ('CREDIT', 'DEBIT')),
    CONSTRAINT ck_movimiento_monto_positivo CHECK (monto > 0),
    CONSTRAINT ck_movimiento_saldo_resultante_no_negativo CHECK (saldo_resultante >= 0)
);
GO

-- Indice de soporte para el patron de acceso mas comun: listar/paginar los
-- movimientos de una cuenta ordenados por fecha (estado de cuenta). Al
-- incluir "fecha" en la llave se cubre el filtro por rango y el ORDER BY
-- del procedimiento sin necesidad de un sort adicional en el plan.
CREATE NONCLUSTERED INDEX ix_movimiento_cuenta_fecha
    ON dbo.movimiento (cuenta_id, fecha)
    INCLUDE (tipo, monto, saldo_resultante, idempotency_key);
GO

-- Unicidad de idempotencia por cuenta, aplicada SOLO cuando el cliente envio
-- una clave (indice unico filtrado). SQL Server no considera NULL=NULL en un
-- indice unico estandar salvo que se filtre explicitamente: sin el WHERE,
-- una unica fila con idempotency_key NULL ya bloquearia el resto de
-- movimientos sin clave. El filtro deja pasar cuantos movimientos sin clave
-- se necesiten, y fuerza unicidad real donde importa.
CREATE UNIQUE NONCLUSTERED INDEX ux_movimiento_idempotency
    ON dbo.movimiento (cuenta_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
GO
