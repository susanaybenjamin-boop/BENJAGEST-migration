-- V178 — Subcuentas de Hacienda para la liquidación del IVA (bloque LIQ-303)
--
-- Origen: Benjamin, 2026-07-15. Miró la cuenta 477 y no cuadraba. Diagnóstico:
-- el módulo fiscal LEE la contabilidad para calcular las casillas, pero NUNCA le
-- escribe. No existe el asiento de liquidación del 303, así que la 477 acumula
-- todas las facturas del año y no se vacía jamás. Sus palabras: "cuando le cambio
-- el estado de presentado a pagado no se está generando el asiento contra la
-- hacienda, con lo cual cuando miro la cuenta 477 no está dando el valor
-- correctamente".
--
-- El asiento que falta (estándar, sin discusión):
--   Al PRESENTAR el 303 (fecha = último día del trimestre):
--       Debe   477  HP IVA repercutido      [saldo del trimestre]
--          Haber   472  HP IVA soportado     [saldo del trimestre]
--          Haber  4750  HP acreedora por IVA [diferencia, si a ingresar]
--       (o Debe 4700 HP deudora por IVA, si sale a devolver/compensar)
--   Al PAGAR:
--       Debe  4750 / Haber 572 Bancos
--
-- POR QUÉ ESTA MIGRACIÓN: la plantilla permanente (V147) solo llega a TRES
-- dígitos en el grupo 47 — tiene 470 y 475 genéricas, pero NO tiene 4750 ni 4700.
-- Solo la V46 (PGC PYMES completo) sembró el nivel de 4 dígitos, y no se aplicó a
-- todas las empresas. Es decir: hoy el asiento de liquidación NO SE PODRÍA HACER
-- en una empresa creada desde la plantilla — no hay cuenta donde meter la deuda.
-- Verificado leyendo V147 (líneas 80-86) y RegisterService.seedAccountsFromTemplate.
--
-- Decisión de Benjamin (2026-07-15): usar 4750/4700 específicas y no la 475
-- genérica, para no mezclar el IVA con retenciones y otros conceptos fiscales.
--
-- Se siguen los patrones ya establecidos:
--   • V147 para la plantilla permanente (instalaciones nuevas).
--   • V173 para sembrar en TODAS las empresas existentes.
--   • INSERT IGNORE en ambos = idempotente. Si una empresa ya las tiene (porque
--     recibió el seed de la V46), se omite y conserva las suyas.
--
-- ADITIVA: no toca ni renombra ninguna cuenta existente. La 475 y la 470
-- genéricas se quedan como están — puede haber asientos históricos colgando de
-- ellas y no se tocan.

-- 1) Plantilla permanente (empresas futuras).
INSERT IGNORE INTO pgc_template (code, name, account_type) VALUES
('4700', 'Hacienda Pública, deudora por IVA',   'ASSET'),
('4750', 'Hacienda Pública, acreedora por IVA', 'LIABILITY');

-- 2) Todas las empresas que ya existen (patrón V173).
CREATE TEMPORARY TABLE pgc_hacienda_iva_tmp (
    code VARCHAR(20) NOT NULL,
    name VARCHAR(180) NOT NULL,
    account_type VARCHAR(40) NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO pgc_hacienda_iva_tmp (code, name, account_type) VALUES
('4700', 'Hacienda Pública, deudora por IVA',   'ASSET'),
('4750', 'Hacienda Pública, acreedora por IVA', 'LIABILITY');

INSERT IGNORE INTO accounting_accounts
    (id, company_id, code, name, account_type, active, is_standard)
SELECT UUID(), c.id, t.code, t.name, t.account_type, TRUE, TRUE
  FROM companies c
 CROSS JOIN pgc_hacienda_iva_tmp t;

DROP TEMPORARY TABLE pgc_hacienda_iva_tmp;
