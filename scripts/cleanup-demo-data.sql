-- ===========================================================================
-- cleanup-demo-data.sql
--
-- Vacia los datos de demostración de la asesoría para empezar desde cero.
-- Borra en orden inverso al de FK para no chocar con constraints.
--
-- NO borra:
--   - Tablas de configuración (companies, user_accounts, module_catalog,
--     reta_profiles vacíos, tax_model_catalog seed, vat_rates, etc.).
--   - Tabla de auditoría (audit_events) — queda como histórico.
--   - Schema history de Flyway.
--   - sif_event_registry (eventos del SIF — cadena legal, no se borra
--     porque la cadena rompería; los eventos referenciando facturas
--     borradas se quedan apuntando a null vía SET NULL si lo soporta
--     la FK, si no se limpian aparte).
--
-- Uso: pegar en cliente SQL o:
--   mariadb -h localhost -P 3307 -u benjagest -p benjagest < cleanup-demo-data.sql
-- ===========================================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- Facturas y todo lo que cuelga
-- ============================================================

DELETE FROM verifactu_registry;
DELETE FROM sales_invoice_payments;
DELETE FROM sales_invoice_lines;
DELETE FROM sales_invoices;
DELETE FROM recurring_invoices;
DELETE FROM sii_submissions;

-- Si existieran (tablas de V2 que el código actual quizá no usa)
DELETE FROM verifactu_records;

-- ============================================================
-- Compras (cuando se implementen)
-- ============================================================

DELETE FROM purchase_invoices;

-- ============================================================
-- Trabajos / fichajes / horas
-- ============================================================

DELETE FROM time_clock_corrections;
DELETE FROM time_clock_verifications;
DELETE FROM time_clock_events;
DELETE FROM daily_work_reports;
DELETE FROM work_logs;
DELETE FROM work_items;

-- ============================================================
-- Catálogos personalizados por cliente
-- ============================================================

DELETE FROM catalog_items;
DELETE FROM customer_tariffs;
DELETE FROM customer_addresses;
DELETE FROM customer_contacts;

-- ============================================================
-- Clientes
-- ============================================================

DELETE FROM customers;

-- ============================================================
-- Limpieza de eventos del SIF que referencian facturas borradas
-- ============================================================
-- Los eventos del SIF (registry) NO se borran porque son la cadena
-- legal del sistema (eventos como SYSTEM_START, etc.). Solo los
-- eventos vinculados a facturas concretas se podrían orfanar, pero
-- el campo entity_id es texto libre, sin FK — quedan inertes.

SET FOREIGN_KEY_CHECKS = 1;

-- ============================================================
-- Verificación: contador final
-- ============================================================

SELECT 'customers' AS tabla, COUNT(*) AS filas FROM customers
UNION ALL SELECT 'sales_invoices', COUNT(*) FROM sales_invoices
UNION ALL SELECT 'sales_invoice_lines', COUNT(*) FROM sales_invoice_lines
UNION ALL SELECT 'verifactu_registry', COUNT(*) FROM verifactu_registry
UNION ALL SELECT 'work_logs', COUNT(*) FROM work_logs
UNION ALL SELECT 'time_clock_events', COUNT(*) FROM time_clock_events;
