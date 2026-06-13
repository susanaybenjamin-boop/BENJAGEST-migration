-- ===========================================================================
-- V108 — Tipos de cotización a la Seguridad Social POR AÑO (bloque PARAM-YEAR).
--
-- Benjamin (2026-06-13): no queremos los tipos "a fuego" en el código. Igual
-- que el IRPF/IVA/retenciones (ya en tablas por año desde V31), los tipos de
-- cotización van en una tabla global por año. Cuando cambien en 2027, se
-- añade la fila de 2027 desde la pantalla de "Tipos de cotización" y el
-- cálculo de nóminas la usa automáticamente — sin tocar código.
--
-- Tabla GLOBAL (no por empresa): son tipos legales nacionales, iguales para
-- todos (mismo criterio que tax_irpf_brackets / tax_vat_history).
--
-- Valores 2026 (Orden PJC/297/2026):
--   Trabajador: cont. comunes 4,70 + desempleo 1,55 + formación 0,10 + MEI 0,15
--   Empresa:    cont. comunes 23,60 + desempleo 5,50 + FOGASA 0,20 +
--               formación 0,60 + MEI 0,75  (AT/EP va por contrato, default 1,50)
-- ===========================================================================

CREATE TABLE IF NOT EXISTS ss_contribution_rates (
    year INT NOT NULL,
    -- A cargo del trabajador
    ee_common DECIMAL(5,2) NOT NULL DEFAULT 4.70,
    ee_unemployment DECIMAL(5,2) NOT NULL DEFAULT 1.55,
    ee_training DECIMAL(5,2) NOT NULL DEFAULT 0.10,
    ee_mei DECIMAL(5,2) NOT NULL DEFAULT 0.15,
    -- A cargo de la empresa
    er_common DECIMAL(5,2) NOT NULL DEFAULT 23.60,
    er_unemployment DECIMAL(5,2) NOT NULL DEFAULT 5.50,
    er_fogasa DECIMAL(5,2) NOT NULL DEFAULT 0.20,
    er_training DECIMAL(5,2) NOT NULL DEFAULT 0.60,
    er_mei DECIMAL(5,2) NOT NULL DEFAULT 0.75,
    -- AT/EP por defecto (se puede afinar por contrato)
    default_at_ep DECIMAL(5,2) NOT NULL DEFAULT 1.50,
    legal_reference VARCHAR(200) NULL,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_ss_contribution_rates PRIMARY KEY (year)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO ss_contribution_rates
    (year, ee_common, ee_unemployment, ee_training, ee_mei,
     er_common, er_unemployment, er_fogasa, er_training, er_mei,
     default_at_ep, legal_reference)
VALUES
    (2026, 4.70, 1.55, 0.10, 0.15,
     23.60, 5.50, 0.20, 0.60, 0.75,
     1.50, 'Orden PJC/297/2026');
