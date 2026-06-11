-- =========================================================
-- V91 — REC-IGNORE
-- Slice 2026-06-11. Catálogo de candidatos de recurrencia que
-- el asesor o el empresario han decidido SILENCIAR (no volver a
-- proponer en el banner).
--
-- Motivo: hay gastos repetidos que no son recurrentes reales
-- (multas, regalos a clientes, comisiones puntuales del mismo
-- tercero). Cuando aparecen 2 veces el detector los propone una
-- y otra vez; el botón "Silenciar" del UI manda el candidato a
-- esta tabla y RecurringCandidateService los excluye al listar.
--
-- {@code ignore_until} NULL = silencio indefinido. Si tiene fecha,
-- al pasar esa fecha el candidato vuelve a aparecer.
-- =========================================================

CREATE TABLE IF NOT EXISTS recurring_candidates_ignored (
    id                   CHAR(36)        NOT NULL,
    company_id           CHAR(36)        NOT NULL,
    kind                 VARCHAR(20)     NOT NULL,
    party_nif            VARCHAR(20)     NOT NULL DEFAULT '',
    party_name_norm      VARCHAR(200)    NOT NULL,
    total_amount         DECIMAL(15,2)   NOT NULL,
    ignore_until         DATE            NULL,
    reason               VARCHAR(500)    NULL,
    created_by_user_id   CHAR(36)        NULL,
    created_at           TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_rec_ignored_key
        (company_id, kind, party_nif, party_name_norm, total_amount),
    KEY idx_rec_ignored_company (company_id),
    CONSTRAINT fk_rec_ignored_company
        FOREIGN KEY (company_id) REFERENCES companies(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
