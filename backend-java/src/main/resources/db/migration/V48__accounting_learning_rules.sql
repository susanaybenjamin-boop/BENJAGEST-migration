-- ===========================================================================
-- V48__accounting_learning_rules.sql
--
-- Aprendizaje contable por feedback humano.
--
-- Idea: cuando el sistema genera un asiento automáticamente (al validar
-- una factura emitida, al guardar una factura recibida, al ejecutar el
-- cierre), las cuentas propuestas pueden ser incorrectas. El asesor las
-- corrige antes de marcar el asiento como POSTED. Cada corrección genera
-- (o refuerza) una regla en esta tabla. La próxima vez, el sistema usa
-- esa regla para proponer mejor.
--
-- Ejemplo:
--   Asesor ve un asiento de Iberdrola con cuenta 600 (Compras), lo cambia
--   a 628 (Suministros). El sistema crea regla:
--     kind = EXPENSE_ACCOUNT_BY_SUPPLIER_NIF
--     match_supplier_nif = A95758389
--     target_account_code = 628
--   Próxima factura de Iberdrola: el sistema propone 628 directamente.
--
-- Notas legales / honestas:
--   - El asiento sigue estando bajo responsabilidad del asesor. Las
--     reglas SON sugerencias del sistema, NO automatismos ciegos. El
--     asesor siempre puede editar antes de POSTED.
--   - El aprendizaje se queda en la empresa (company_id). No
--     hay cross-tenant — cada empresa entrena su propio "modelo".
--   - Confianza simple por contador, no ML real. Por encima de
--     90% se marca como "consolidada" para que la UI baje la prominencia
--     del aviso de propuesta.
-- ===========================================================================

CREATE TABLE IF NOT EXISTS accounting_learning_rules (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    rule_kind VARCHAR(40) NOT NULL,
    -- Criterio de match (al menos uno NOT NULL según rule_kind)
    match_supplier_nif VARCHAR(20) NULL,
    match_customer_nif VARCHAR(20) NULL,
    match_keyword VARCHAR(120) NULL,
    match_amount_min DECIMAL(14,2) NULL,
    match_amount_max DECIMAL(14,2) NULL,
    -- Resultado propuesto
    target_account_id CHAR(36) NULL,
    target_account_code VARCHAR(20) NULL,
    target_vat_percent DECIMAL(5,2) NULL,
    -- Estadísticas de confianza
    times_applied INT NOT NULL DEFAULT 0,
    times_overridden INT NOT NULL DEFAULT 0,
    confidence DECIMAL(5,2) NOT NULL DEFAULT 50.00,
    -- Trazabilidad
    learned_from_entry_id CHAR(36) NULL,
    learned_from_line_id CHAR(36) NULL,
    created_by_user_id CHAR(36) NULL,
    last_applied_at TIMESTAMP NULL,
    -- Estado
    active BOOLEAN NOT NULL DEFAULT TRUE,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_accounting_learning_rules PRIMARY KEY (id),
    CONSTRAINT fk_alr_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_alr_account FOREIGN KEY (target_account_id) REFERENCES accounting_accounts (id),
    CONSTRAINT fk_alr_entry   FOREIGN KEY (learned_from_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT fk_alr_user    FOREIGN KEY (created_by_user_id) REFERENCES user_accounts (id),
    CONSTRAINT ck_alr_kind CHECK (rule_kind IN (
        'EXPENSE_ACCOUNT_BY_SUPPLIER_NIF',
        'EXPENSE_ACCOUNT_BY_KEYWORD',
        'INCOME_ACCOUNT_BY_CUSTOMER_NIF',
        'INCOME_ACCOUNT_BY_KEYWORD',
        'VAT_RATE_BY_SUPPLIER_NIF'
    )),
    INDEX ix_alr_company_kind_active (company_id, rule_kind, active),
    INDEX ix_alr_supplier (company_id, match_supplier_nif),
    INDEX ix_alr_customer (company_id, match_customer_nif)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- Histórico de propuestas/correcciones por línea de asiento.
-- Permite auditar y mostrar al asesor "este asiento llegó a POSTED tras
-- N correcciones — aquí están". También sirve para re-entrenar reglas
-- si el asesor cambia de criterio (las correcciones anteriores quedan).
-- ===========================================================================

CREATE TABLE IF NOT EXISTS accounting_learning_events (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    journal_entry_id CHAR(36) NOT NULL,
    line_id CHAR(36) NULL,
    event_kind VARCHAR(40) NOT NULL,
    -- Cuentas antes/después (cuando aplica)
    from_account_id CHAR(36) NULL,
    to_account_id CHAR(36) NULL,
    from_amount DECIMAL(14,2) NULL,
    to_amount DECIMAL(14,2) NULL,
    -- Regla afectada (si aplica)
    related_rule_id CHAR(36) NULL,
    -- Quien y cuando
    actor_user_id CHAR(36) NULL,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    notes TEXT NULL,
    CONSTRAINT pk_accounting_learning_events PRIMARY KEY (id),
    CONSTRAINT fk_ale_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_ale_entry   FOREIGN KEY (journal_entry_id) REFERENCES journal_entries (id),
    CONSTRAINT fk_ale_rule    FOREIGN KEY (related_rule_id) REFERENCES accounting_learning_rules (id),
    CONSTRAINT fk_ale_user    FOREIGN KEY (actor_user_id) REFERENCES user_accounts (id),
    CONSTRAINT ck_ale_kind CHECK (event_kind IN (
        'AUTO_PROPOSED', 'ACCOUNT_CORRECTED', 'AMOUNT_CORRECTED',
        'LINE_ADDED', 'LINE_REMOVED', 'ENTRY_ACCEPTED', 'ENTRY_REJECTED',
        'RULE_CREATED', 'RULE_REINFORCED', 'RULE_WEAKENED'
    )),
    INDEX ix_ale_entry (journal_entry_id),
    INDEX ix_ale_company_time (company_id, occurred_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ===========================================================================
-- Marca en cada asiento si fue propuesto por reglas (cuántas líneas),
-- útil para el dashboard del asesor: "tienes 12 asientos auto-propuestos
-- esperando tu validación".
--
-- Idempotente vía information_schema.
-- ===========================================================================

SET @col_ap := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE()
                    AND table_name = 'journal_entries'
                    AND column_name = 'auto_proposed');
SET @sql_ap := IF(@col_ap = 0,
    'ALTER TABLE journal_entries ADD COLUMN auto_proposed BOOLEAN NOT NULL DEFAULT FALSE AFTER reviewed',
    'SELECT 1');
PREPARE stmt FROM @sql_ap; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @col_pc := (SELECT COUNT(*) FROM information_schema.columns
                  WHERE table_schema = DATABASE()
                    AND table_name = 'journal_entries'
                    AND column_name = 'proposed_confidence');
SET @sql_pc := IF(@col_pc = 0,
    'ALTER TABLE journal_entries ADD COLUMN proposed_confidence DECIMAL(5,2) NULL AFTER auto_proposed',
    'SELECT 1');
PREPARE stmt FROM @sql_pc; EXECUTE stmt; DEALLOCATE PREPARE stmt;
