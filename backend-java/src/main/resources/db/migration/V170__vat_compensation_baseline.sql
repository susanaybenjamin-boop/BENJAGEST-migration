-- V170 (bloque IVA-COMP, 2026-07-09): saldo INICIAL de cuotas de IVA a
-- compensar de periodos anteriores (modelo 303, casilla 110).
--
-- La compensacion de IVA arrastra saldo que puede venir de ANTES de usar
-- BENJAGEST (y cruza años). Igual que el baseline de migracion de
-- facturas, la empresa que migra a media vida teclea UNA vez su "IVA a
-- compensar pendiente" de partida y su fecha de corte (año + trimestre
-- desde el que BENJAGEST empieza a arrastrar). De ahi en adelante el 303
-- lo calcula solo trimestre a trimestre.
--
-- Una fila por empresa (UNIQUE company_id). opening_balance >= 0 (es el
-- IVA negativo acumulado, expresado en positivo, como la casilla 110).

CREATE TABLE vat_compensation_baseline (
    id              CHAR(36)      NOT NULL,
    company_id      CHAR(36)      NOT NULL,
    opening_balance DECIMAL(14,2) NOT NULL DEFAULT 0.00,
    as_of_year      INT           NOT NULL,
    as_of_quarter   INT           NOT NULL,
    notes           TEXT          NULL,
    created_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMP     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_vat_compensation_baseline PRIMARY KEY (id),
    CONSTRAINT uq_vat_compensation_baseline_company UNIQUE (company_id),
    CONSTRAINT ck_vat_compensation_baseline_quarter CHECK (as_of_quarter BETWEEN 1 AND 4),
    CONSTRAINT fk_vat_compensation_baseline_company
        FOREIGN KEY (company_id) REFERENCES companies (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
