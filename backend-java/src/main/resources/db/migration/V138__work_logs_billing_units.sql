-- ===========================================================================
-- V138 — TRB-1: modelo de valoración de trabajos (modulo Trabajos).
--
-- Decisión Benjamin (2026-06-22): un trabajo se valora como en CONTENDO — no
-- solo por horas: puede ser por HORAS, DÍAS o MESES (cantidad × precio/unidad)
-- O por PRECIO CERRADO (trabajo cerrado, importe fijo).
--
-- Añade a work_logs (que ya tiene customer_id, is_billable, billable_amount,
-- status, billed_invoice_line_id, invoice_id, approved_by_user_id):
--   billing_unit  HOURS | DAYS | MONTHS | FIXED
--   quantity      cantidad (horas/días/meses); NULL en FIXED
--   unit_price    precio por unidad;           NULL en FIXED
--   billable_amount sigue siendo el importe final (quantity*unit_price o el cerrado).
--
-- Aditiva: no toca seeds ni datos existentes (columnas nullable).
-- ===========================================================================

ALTER TABLE work_logs
    ADD COLUMN billing_unit VARCHAR(10) NULL,
    ADD COLUMN quantity DECIMAL(10,2) NULL,
    ADD COLUMN unit_price DECIMAL(12,2) NULL;
