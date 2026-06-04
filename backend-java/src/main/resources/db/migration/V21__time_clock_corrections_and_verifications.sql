-- ===========================================================================
-- V21__time_clock_corrections_and_verifications.sql
--
-- Slice C4-A — soporte legal RD 8/2019 art. 34.9 + 35.8:
--
--   - 34.9 ("inalterabilidad"): un fichaje original NO se modifica;
--     toda corrección se materializa como un APUNTE vinculado al
--     original que documenta el cambio, su motivo y quien lo aprueba.
--   - 35.8 ("verificación pública"): cada fichaje genera un Código
--     Seguro de Verificación (CSV) que el trabajador puede pasar a la
--     Inspección de Trabajo o a un tercero, y este puede comprobar la
--     autenticidad del fichaje sin necesidad de credenciales.
--
-- Modelo:
--   - time_clock_corrections: 1 fila por corrección. Apunta al fichaje
--     original (no lo modifica) + describe el cambio propuesto + motivo +
--     quien lo aplica (manager / trabajador / sistema) + fecha. La
--     "verdad" del fichaje sigue siendo el original; las correcciones
--     se interpretan en cascada al leer.
--   - time_clock_verifications: 1 fila por CSV emitido. Es una entidad
--     separada porque el mismo fichaje puede tener varios CSVs a lo
--     largo del tiempo (un trabajador puede pedir otro CSV si pierde el
--     primero — el CSV no es secreto, solo identificador).
-- ===========================================================================

CREATE TABLE time_clock_corrections (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    original_event_id CHAR(36) NOT NULL,
    correction_type VARCHAR(30) NOT NULL,
    proposed_event_type VARCHAR(30) NULL,
    proposed_event_time TIMESTAMP NULL,
    reason TEXT NOT NULL,
    requested_by CHAR(36) NULL,
    approved_by CHAR(36) NULL,
    approved_at TIMESTAMP NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_time_clock_corrections PRIMARY KEY (id),
    CONSTRAINT fk_tcc_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_tcc_original FOREIGN KEY (original_event_id) REFERENCES time_clock_events (id),
    CONSTRAINT fk_tcc_requester FOREIGN KEY (requested_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_tcc_approver FOREIGN KEY (approved_by) REFERENCES user_accounts (id),
    CONSTRAINT ck_tcc_type CHECK (correction_type IN ('TIME_ADJUST', 'TYPE_CHANGE', 'VOID')),
    CONSTRAINT ck_tcc_status CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED')),
    INDEX ix_tcc_original (original_event_id),
    INDEX ix_tcc_company_status (company_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE time_clock_verifications (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    event_id CHAR(36) NOT NULL,
    csv_code VARCHAR(32) NOT NULL,
    issued_to CHAR(36) NULL,
    issued_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    revoked_at TIMESTAMP NULL,
    CONSTRAINT pk_time_clock_verifications PRIMARY KEY (id),
    CONSTRAINT uk_time_clock_verifications_csv UNIQUE (csv_code),
    CONSTRAINT fk_tcv_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_tcv_event FOREIGN KEY (event_id) REFERENCES time_clock_events (id),
    CONSTRAINT fk_tcv_issued_to FOREIGN KEY (issued_to) REFERENCES user_accounts (id),
    INDEX ix_tcv_event (event_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
