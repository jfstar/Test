/* ==========================================================================
   Para poder probar manualmente sp_estado_cuenta.sql: dos cuentas con varios movimientos
   distribuidos en distintas fechas, suficientes para ejercitar paginacion,
   filtro por rango de fechas e idempotencia (una clave repetida y varias
   filas sin clave conviviendo gracias al indice unico filtrado).

   Los literales de fecha incluyen el offset explicito "+00:00" porque las
   columnas son DATETIMEOFFSET (ver schema.sql) y la aplicacion siempre
   persiste instantes en UTC: dejarlo explicito evita cualquier ambiguedad
   sobre que offset asumiria SQL Server al convertir el string.

   Ejecutar despues de schema.sql y sp_estado_cuenta.sql.
   IDs fijos (no NEWID()) para que los ejemplos de EXEC sean reproducibles.
   ========================================================================== */

USE CuentasDB;
GO

-- Requerido para poder hacer DML (DELETE/INSERT) sobre dbo.movimiento: tiene
-- un indice unico filtrado (ux_movimiento_idempotency), y SQL Server exige
-- QUOTED_IDENTIFIER ON en la sesion para cualquier operacion sobre una tabla
-- con indices filtrados/indexados, no solo al crearlos. Cada invocacion de
-- sqlcmd abre una sesion nueva, por lo que hay que repetirlo aqui aunque ya
-- este en schema.sql.
SET ANSI_NULLS ON;
SET QUOTED_IDENTIFIER ON;
GO

DELETE FROM dbo.movimiento;
DELETE FROM dbo.cuenta;
GO

-- ------------------------------------------------------------------------
-- Cuenta 1: varios movimientos a lo largo de 3 meses (para paginar)
-- ------------------------------------------------------------------------
INSERT INTO dbo.cuenta (id, titular, saldo, fecha_creacion)
VALUES ('11111111-1111-1111-1111-111111111111', 'Maria Perez (cuenta demo)', 1000.0000, '2026-01-01T08:00:00+00:00');

INSERT INTO dbo.movimiento (id, cuenta_id, tipo, monto, saldo_resultante, idempotency_key, fecha) VALUES
('a1111111-0001-0001-0001-000000000001', '11111111-1111-1111-1111-111111111111', 'CREDIT', 200.0000, 700.0000,  NULL,           '2026-01-05T09:15:00+00:00'),
('a1111111-0001-0001-0001-000000000002', '11111111-1111-1111-1111-111111111111', 'DEBIT',  150.0000, 550.0000,  'seed-key-001', '2026-01-10T14:30:00+00:00'),
('a1111111-0001-0001-0001-000000000003', '11111111-1111-1111-1111-111111111111', 'CREDIT', 100.0000, 650.0000,  NULL,           '2026-02-01T10:00:00+00:00'),
('a1111111-0001-0001-0001-000000000004', '11111111-1111-1111-1111-111111111111', 'DEBIT',  300.0000, 350.0000,  NULL,           '2026-02-15T16:45:00+00:00'),
('a1111111-0001-0001-0001-000000000005', '11111111-1111-1111-1111-111111111111', 'CREDIT', 50.0000,  400.0000,  NULL,           '2026-03-01T11:20:00+00:00'),
('a1111111-0001-0001-0001-000000000006', '11111111-1111-1111-1111-111111111111', 'DEBIT',  400.0000, 0.0000,    NULL,           '2026-03-10T13:00:00+00:00'),
('a1111111-0001-0001-0001-000000000007', '11111111-1111-1111-1111-111111111111', 'CREDIT', 1000.0000,1000.0000, NULL,           '2026-03-15T09:00:00+00:00');
GO

-- Nota: cuenta.saldo (1000.0000) coincide a proposito con el
-- saldo_resultante del ultimo movimiento (2026-03-15), tal como lo dejaria
-- el microservicio tras aplicar la cadena completa de movimientos.

-- ------------------------------------------------------------------------
-- Cuenta 2: pocos movimientos, termina en saldo 0 (caso limite)
-- ------------------------------------------------------------------------
INSERT INTO dbo.cuenta (id, titular, saldo, fecha_creacion)
VALUES ('22222222-2222-2222-2222-222222222222', 'Carlos Gomez (cuenta demo)', 0.0000, '2026-01-01T08:00:00+00:00');

INSERT INTO dbo.movimiento (id, cuenta_id, tipo, monto, saldo_resultante, idempotency_key, fecha) VALUES
('a2222222-0002-0002-0002-000000000001', '22222222-2222-2222-2222-222222222222', 'DEBIT', 250.0000, 750.0000, NULL, '2026-01-20T12:00:00+00:00'),
('a2222222-0002-0002-0002-000000000002', '22222222-2222-2222-2222-222222222222', 'DEBIT', 750.0000, 0.0000,   NULL, '2026-02-05T17:30:00+00:00');
GO

-- ------------------------------------------------------------------------
-- Cuenta 3: recien creada, sin movimientos (para probar SP con 0 filas)
-- ------------------------------------------------------------------------
INSERT INTO dbo.cuenta (id, titular, saldo, fecha_creacion)
VALUES ('33333333-3333-3333-3333-333333333333', 'Ana Torres (cuenta demo, sin movimientos)', 300.0000, '2026-03-20T08:00:00+00:00');
GO
