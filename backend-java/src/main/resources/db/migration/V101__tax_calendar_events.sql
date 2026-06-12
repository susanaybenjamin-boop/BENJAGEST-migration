-- =============================================================================
-- V101 — CAL-FISCAL: calendario fiscal AEAT
--
-- Vencimientos de los modelos AEAT más comunes:
--   - 303 IVA trimestral (4 trimestres)
--   - 130 IRPF autónomo trimestral (4 trimestres)
--   - 111 IRPF retenciones trabajadores trimestral (4 trimestres)
--   - 190 IRPF retenciones anual (resumen año anterior)
--   - 347 operaciones con terceros >3005,06€ anual
--   - 390 IVA anual
--   - 200 Impuesto Sociedades (cierre fiscal)
--
-- Fechas tomadas del calendario AEAT estándar. Si AEAT publica
-- modificaciones puntuales (p.ej. festivos que mueven el límite),
-- el asesor puede editar la fila.
--
-- Modelo:
--   - company_id NULL → vencimiento genérico (aparece para TODAS las
--     empresas).
--   - company_id != NULL → vencimiento específico de una empresa
--     (cuando, p.ej., cambia la periodicidad).
--   - status PENDING|SUBMITTED|CANCELLED para que el asesor pueda
--     marcar lo presentado.
-- =============================================================================

CREATE TABLE IF NOT EXISTS tax_calendar_events (
    id              CHAR(36)     NOT NULL,
    company_id      CHAR(36)     NULL,
    model_code      VARCHAR(10)  NOT NULL,
    period_label    VARCHAR(20)  NOT NULL,
    due_date        DATE         NOT NULL,
    description     VARCHAR(300) NOT NULL,
    fiscal_year     INT          NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    submitted_at    TIMESTAMP    NULL,
    submitted_by    CHAR(36)     NULL,
    notes           VARCHAR(500) NULL,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tax_calendar_due (due_date, company_id),
    KEY idx_tax_calendar_company (company_id, due_date),
    CONSTRAINT ck_tax_calendar_status CHECK (
        status IN ('PENDING', 'SUBMITTED', 'CANCELLED')
    )
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- Seed 2026
INSERT INTO tax_calendar_events (id, model_code, period_label, due_date, description, fiscal_year) VALUES
(UUID(), '303', 'Q1-2026', '2026-04-20', 'Modelo 303 IVA - 1T 2026 (enero-marzo)', 2026),
(UUID(), '303', 'Q2-2026', '2026-07-20', 'Modelo 303 IVA - 2T 2026 (abril-junio)', 2026),
(UUID(), '303', 'Q3-2026', '2026-10-20', 'Modelo 303 IVA - 3T 2026 (julio-septiembre)', 2026),
(UUID(), '303', 'Q4-2026', '2027-01-30', 'Modelo 303 IVA - 4T 2026 (octubre-diciembre)', 2026),
(UUID(), '130', 'Q1-2026', '2026-04-20', 'Modelo 130 IRPF autónomo - 1T 2026', 2026),
(UUID(), '130', 'Q2-2026', '2026-07-20', 'Modelo 130 IRPF autónomo - 2T 2026', 2026),
(UUID(), '130', 'Q3-2026', '2026-10-20', 'Modelo 130 IRPF autónomo - 3T 2026', 2026),
(UUID(), '130', 'Q4-2026', '2027-01-30', 'Modelo 130 IRPF autónomo - 4T 2026', 2026),
(UUID(), '111', 'Q1-2026', '2026-04-20', 'Modelo 111 IRPF retenciones - 1T 2026', 2026),
(UUID(), '111', 'Q2-2026', '2026-07-20', 'Modelo 111 IRPF retenciones - 2T 2026', 2026),
(UUID(), '111', 'Q3-2026', '2026-10-20', 'Modelo 111 IRPF retenciones - 3T 2026', 2026),
(UUID(), '111', 'Q4-2026', '2027-01-20', 'Modelo 111 IRPF retenciones - 4T 2026', 2026),
(UUID(), '190', 'ANNUAL-2025', '2026-01-31', 'Modelo 190 - Resumen anual IRPF retenciones 2025', 2025),
(UUID(), '347', 'ANNUAL-2025', '2026-02-28', 'Modelo 347 - Operaciones con terceros >3.005,06€ año 2025', 2025),
(UUID(), '390', 'ANNUAL-2025', '2026-01-30', 'Modelo 390 - Resumen anual IVA 2025', 2025),
(UUID(), '200', 'ANNUAL-2025', '2026-07-25', 'Modelo 200 - Impuesto Sociedades cierre 2025', 2025);

-- Seed 2027
INSERT INTO tax_calendar_events (id, model_code, period_label, due_date, description, fiscal_year) VALUES
(UUID(), '303', 'Q1-2027', '2027-04-20', 'Modelo 303 IVA - 1T 2027', 2027),
(UUID(), '303', 'Q2-2027', '2027-07-20', 'Modelo 303 IVA - 2T 2027', 2027),
(UUID(), '303', 'Q3-2027', '2027-10-20', 'Modelo 303 IVA - 3T 2027', 2027),
(UUID(), '303', 'Q4-2027', '2028-01-30', 'Modelo 303 IVA - 4T 2027', 2027),
(UUID(), '130', 'Q1-2027', '2027-04-20', 'Modelo 130 IRPF autónomo - 1T 2027', 2027),
(UUID(), '130', 'Q2-2027', '2027-07-20', 'Modelo 130 IRPF autónomo - 2T 2027', 2027),
(UUID(), '130', 'Q3-2027', '2027-10-20', 'Modelo 130 IRPF autónomo - 3T 2027', 2027),
(UUID(), '130', 'Q4-2027', '2028-01-30', 'Modelo 130 IRPF autónomo - 4T 2027', 2027),
(UUID(), '111', 'Q1-2027', '2027-04-20', 'Modelo 111 IRPF retenciones - 1T 2027', 2027),
(UUID(), '111', 'Q2-2027', '2027-07-20', 'Modelo 111 IRPF retenciones - 2T 2027', 2027),
(UUID(), '111', 'Q3-2027', '2027-10-20', 'Modelo 111 IRPF retenciones - 3T 2027', 2027),
(UUID(), '111', 'Q4-2027', '2028-01-20', 'Modelo 111 IRPF retenciones - 4T 2027', 2027),
(UUID(), '190', 'ANNUAL-2026', '2027-01-31', 'Modelo 190 - Resumen anual IRPF retenciones 2026', 2026),
(UUID(), '347', 'ANNUAL-2026', '2027-02-28', 'Modelo 347 - Operaciones con terceros año 2026', 2026),
(UUID(), '390', 'ANNUAL-2026', '2027-01-30', 'Modelo 390 - Resumen anual IVA 2026', 2026),
(UUID(), '200', 'ANNUAL-2026', '2027-07-25', 'Modelo 200 - Impuesto Sociedades cierre 2026', 2026);
