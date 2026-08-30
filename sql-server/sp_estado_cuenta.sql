/* ==========================================================================
   Procedimiento que devuelve el estado de cuenta de una cuenta para un
   rango de fechas, paginado, incluyendo el saldo corriente (running
   balance) de cada movimiento.

   Ejecutar despues de schema.sql (requiere las tablas dbo.cuenta y
   dbo.movimiento).
   ========================================================================== */

USE CuentasDB;
GO

SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

IF OBJECT_ID(N'dbo.sp_estado_cuenta', N'P') IS NOT NULL
    DROP PROCEDURE dbo.sp_estado_cuenta;
GO

CREATE PROCEDURE dbo.sp_estado_cuenta
    @cuenta_id      UNIQUEIDENTIFIER,
    @fecha_desde    DATETIME2(7)  = NULL,   -- NULL = sin limite inferior
    @fecha_hasta    DATETIME2(7)  = NULL,   -- NULL = sin limite superior
    @pagina         INT           = 1,      -- 1-based
    @tamano_pagina  INT           = 20
AS
BEGIN
    SET NOCOUNT ON;

    -- ---------------------------------------------------------------
    -- Validaciones de entrada: fallar rapido con un mensaje claro en
    -- vez de devolver una pagina vacia o silenciosa ante datos invalidos.
    -- ---------------------------------------------------------------
    IF @pagina IS NULL OR @pagina < 1
    BEGIN
        THROW 50001, 'El parametro @pagina debe ser mayor o igual a 1.', 1;
    END

    IF @tamano_pagina IS NULL OR @tamano_pagina < 1 OR @tamano_pagina > 500
    BEGIN
        THROW 50002, 'El parametro @tamano_pagina debe estar entre 1 y 500.', 1;
    END

    IF NOT EXISTS (SELECT 1 FROM dbo.cuenta WHERE id = @cuenta_id)
    BEGIN
        THROW 50003, 'La cuenta especificada no existe.', 1;
    END

    IF @fecha_desde IS NOT NULL AND @fecha_hasta IS NOT NULL AND @fecha_desde > @fecha_hasta
    BEGIN
        THROW 50004, '@fecha_desde no puede ser posterior a @fecha_hasta.', 1;
    END

    -- Rango efectivo: NULL se normaliza a los extremos representables para
    -- poder usar un solo predicado BETWEEN-like, que el indice
    -- ix_movimiento_cuenta_fecha(cuenta_id, fecha) puede usar por seek.
    -- Los parametros se reciben como DATETIME2 por comodidad del llamador
    -- (acepta '2026-01-01' sin offset); al compararlos contra
    -- movimiento.fecha (DATETIMEOFFSET) SQL Server los convierte
    -- implicitamente asumiendo offset +00:00, consistente con que la app
    -- siempre persiste instantes en UTC.
    DECLARE @desde DATETIME2(7) = ISNULL(@fecha_desde, CONVERT(DATETIME2(7), '0001-01-01'));
    DECLARE @hasta DATETIME2(7) = ISNULL(@fecha_hasta, CONVERT(DATETIME2(7), '9999-12-31 23:59:59.9999999'));
    DECLARE @offset INT = (@pagina - 1) * @tamano_pagina;

    -- ---------------------------------------------------------------
    -- Result set 1: encabezado de la cuenta (contexto para el cliente:
    -- titular y saldo actual, que puede diferir del saldo_corriente del
    -- ultimo movimiento listado si la pagina/rango no llega hasta el
    -- movimiento mas reciente).
    -- ---------------------------------------------------------------
    SELECT
        c.id                AS cuenta_id,
        c.titular,
        c.saldo             AS saldo_actual,
        c.fecha_creacion
    FROM dbo.cuenta c
    WHERE c.id = @cuenta_id;

    -- ---------------------------------------------------------------
    -- Result set 2: movimientos paginados con saldo corriente.
    --
    -- El saldo corriente (running balance) no se recalcula sumando el
    -- historico en cada consulta: se lee directamente de
    -- movimiento.saldo_resultante, que el microservicio persiste en el
    -- momento de cada transaccion (ver Tarea 1). Es un valor acumulado
    -- absoluto (no relativo al rango de fechas filtrado), por lo que
    -- sigue siendo correcto aunque @fecha_desde/@fecha_hasta acoten la
    -- ventana visible.
    --
    -- total_registros usa COUNT(*) OVER() para devolver el total de filas
    -- que cumplen el filtro junto con la pagina solicitada, evitando una
    -- segunda consulta separada (SELECT COUNT(*) ...) solo para poder
    -- calcular el numero de paginas en el cliente.
    -- ---------------------------------------------------------------
    SELECT
        m.id,
        m.cuenta_id,
        m.tipo,
        m.monto,
        m.saldo_resultante                     AS saldo_corriente,
        m.idempotency_key,
        m.fecha,
        COUNT(*) OVER ()                        AS total_registros,
        @pagina                                 AS pagina,
        @tamano_pagina                          AS tamano_pagina
    FROM dbo.movimiento m
    WHERE m.cuenta_id = @cuenta_id
      AND m.fecha >= @desde
      AND m.fecha <= @hasta
    ORDER BY m.fecha ASC, m.id ASC
    OFFSET @offset ROWS FETCH NEXT @tamano_pagina ROWS ONLY;
END
GO

/* ==========================================================================
   Ejemplos de invocacion (ajustar @cuenta_id a un id real, ver datos_prueba.sql)
   ==========================================================================

   -- Primera pagina, sin filtro de fechas, 10 movimientos por pagina
   EXEC dbo.sp_estado_cuenta
        @cuenta_id = '11111111-1111-1111-1111-111111111111',
        @pagina = 1,
        @tamano_pagina = 10;

   -- Rango de fechas especifico, segunda pagina
   EXEC dbo.sp_estado_cuenta
        @cuenta_id = '11111111-1111-1111-1111-111111111111',
        @fecha_desde = '2026-01-01',
        @fecha_hasta = '2026-12-31',
        @pagina = 2,
        @tamano_pagina = 5;
   ========================================================================== */
