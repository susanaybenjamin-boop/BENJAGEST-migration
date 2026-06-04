-- ===========================================================================
-- V23__vat_rates_catalog.sql
--
-- Catalogo configurable de tipos impositivos (IVA + IRPF) por empresa.
--
-- Antes los porcentajes se introducian a mano linea por linea en el
-- editor de facturas. Esto rompia en dos escenarios reales:
--   - Operador entraba 0.21 en vez de 21 (factor incorrecto).
--   - Cambia un tipo legal (p.ej. IVA reducido temporal sanitario al
--     0%) y el operador olvida actualizar las facturas recurrentes.
--
-- Con catalogo:
--   - Editor selecciona del catalogo (combo) en lugar de campo libre.
--   - Al cambiar el tipo legal, una actualizacion central impacta a
--     todas las facturas que aun no estan validadas.
--   - El catalogo es por empresa (no global), por si una empresa esta
--     en regimen especial o exenta.
--
-- Decisiones:
--   - kind: VAT (IVA) o WITHHOLDING (IRPF) — la UI los muestra en
--     listados separados. Se podrian anyadir RECARGO_EQUIVALENCIA u
--     otros en el futuro sin cambiar schema.
--   - Cada empresa arranca con los tipos espanyoles estandar
--     (IVA 21/10/4/0 + IRPF 15/7/19). Si tiene regimen especial, los
--     desactiva con active=FALSE; siguen en BD para historia de
--     facturas pasadas pero no aparecen en el combo del editor.
-- ===========================================================================

CREATE TABLE vat_rates (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    kind VARCHAR(20) NOT NULL,
    code VARCHAR(20) NOT NULL,
    label VARCHAR(120) NOT NULL,
    percent DECIMAL(5,2) NOT NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_vat_rates PRIMARY KEY (id),
    CONSTRAINT uk_vat_rates_company_code UNIQUE (company_id, kind, code),
    CONSTRAINT fk_vat_rates_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_vat_rates_kind CHECK (kind IN ('VAT', 'WITHHOLDING')),
    CONSTRAINT ck_vat_rates_percent CHECK (percent >= 0 AND percent <= 100),
    INDEX ix_vat_rates_company_active (company_id, active)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- Seed: tipos estandar espanyoles para TODAS las empresas existentes.
-- IVA general 21% queda marcado como default (lo que sale en el combo
-- del editor al crear una linea nueva).
-- ---------------------------------------------------------------------------
INSERT INTO vat_rates (id, company_id, kind, code, label, percent, is_default, active)
SELECT UUID(), c.id, 'VAT', 'IVA21', 'IVA general 21%',   21.00, TRUE,  TRUE FROM companies c
UNION ALL
SELECT UUID(), c.id, 'VAT', 'IVA10', 'IVA reducido 10%',  10.00, FALSE, TRUE FROM companies c
UNION ALL
SELECT UUID(), c.id, 'VAT', 'IVA4',  'IVA superreducido 4%', 4.00, FALSE, TRUE FROM companies c
UNION ALL
SELECT UUID(), c.id, 'VAT', 'IVA0',  'Exento de IVA',     0.00, FALSE, TRUE FROM companies c
UNION ALL
SELECT UUID(), c.id, 'WITHHOLDING', 'IRPF15', 'Retencion IRPF 15%', 15.00, TRUE, TRUE FROM companies c
UNION ALL
SELECT UUID(), c.id, 'WITHHOLDING', 'IRPF7',  'Retencion IRPF 7%',   7.00, FALSE, TRUE FROM companies c
UNION ALL
SELECT UUID(), c.id, 'WITHHOLDING', 'IRPF19', 'Retencion IRPF 19%', 19.00, FALSE, TRUE FROM companies c;
