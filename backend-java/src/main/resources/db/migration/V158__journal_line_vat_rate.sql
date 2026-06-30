-- V158 — Tipo de IVA por línea de asiento (desglose contable del IVA).
--
-- Hasta ahora el asiento de una factura llevaba UNA sola línea 700 (base
-- total) y UNA sola 477 (IVA total), sin distinguir tipos. Una factura con
-- varios tipos (p.ej. 10% y 21%) quedaba "generalizada" en el asiento, y los
-- modelos AEAT (303/390) no podían derivar las bases por tipo desde la
-- contabilidad.
--
-- Esta columna etiqueta cada línea de base (7xx / 6xx) y de IVA (477 / 472)
-- con su tipo, para poder:
--   - desglosar el asiento por tipo de IVA (como en cualquier gestoría), y
--   - construir el 303/390 a partir de los asientos CONTABILIZADOS.
--
-- NULL = línea sin tipo de IVA asociado (cliente 430, retención 473,
-- tesorería, etc.). Aditiva: no toca datos existentes.

ALTER TABLE journal_entry_lines
    ADD COLUMN vat_rate DECIMAL(5,2) NULL AFTER credit;
