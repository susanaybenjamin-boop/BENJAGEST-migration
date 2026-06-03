-- ===========================================================================
-- V18__sif_event_registry.sql
--
-- Slice VF-EVENTS — Registro de Eventos del SIF, obligatorio en
-- modalidad NO VeriFactu (RD 1007/2023 + Orden HAC/1177/2024,
-- art. 16 y FAQ AEAT). En modalidad VeriFactu este registro NO se
-- exige (AEAT ya tiene los datos en tiempo real).
--
-- Modelo:
--   - 1 fila por evento, encadenada por (company_id) con hash SHA-256.
--   - La cadena es independiente de la cadena de facturas — una
--     factura no rompe la cadena de eventos ni viceversa.
--   - Sin distincion TEST/PROD: los eventos solo aplican a NO VeriFactu,
--     y NO VeriFactu no tiene environment AEAT.
--
-- Tipos de evento (los 9 obligatorios + 4 auxiliares):
--   1. SYSTEM_START                       — arranque del SIF como NO VERI*FACTU.
--   2. SYSTEM_STOP                        — apagado del SIF como NO VERI*FACTU.
--   3. ANOMALY_DETECTION_INVOICES_RUN     — se lanza el job de deteccion de
--                                           anomalias en registros de facturacion.
--   4. ANOMALY_DETECTION_INVOICES_HIT     — el job ENCONTRO anomalia.
--   5. ANOMALY_DETECTION_EVENTS_RUN       — idem para registros de eventos.
--   6. ANOMALY_DETECTION_EVENTS_HIT       — idem hit.
--   7. BACKUP_RESTORED                    — restauracion de copia de seguridad.
--   8. EXPORT_INVOICES                    — exportacion de registros de facturacion.
--   9. EXPORT_EVENTS                      — exportacion de registros de eventos.
--   + INVOICE_VALIDATED                   — auxiliar: operacion de facturacion completada.
--   + INVOICE_VOIDED                      — auxiliar: anulacion con vinculo (RECTIFYING validada).
--   + SUMMARY_6H                          — resumen cada 6h de operacion (Orden HAC).
--   + SUMMARY_SHUTDOWN                    — resumen final antes del SYSTEM_STOP.
--
-- Status:
--   PENDING   — calculado, sin firmar ni exportar.
--   SIGNED    — firmado con XAdES local (VF-SIGN, pendiente).
--   EXPORTED  — incluido en una exportacion EXPORT_EVENTS.
--
-- Reglas de integridad:
--   - INDEX (company_id, generated_at) — la cadena se recorre asi.
--   - INDEX (company_id, event_type) — para filtros UI rapidos.
--   - generated_at se persiste EXPLICITO (mismo patron que VF2.bugfix
--     para verifactu_registry): el hash incluye la hora como string ISO
--     truncada a segundo, y si dejasemos DEFAULT CURRENT_TIMESTAMP la
--     verificacion daria falso positivo de cadena rota.
-- ===========================================================================

CREATE TABLE sif_event_registry (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    event_type VARCHAR(40) NOT NULL,
    payload TEXT NULL,
    hash_current VARCHAR(64) NOT NULL,
    hash_previous VARCHAR(64) NOT NULL,
    generated_at TIMESTAMP NOT NULL,
    signed_at TIMESTAMP NULL,
    signature_data TEXT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    CONSTRAINT pk_sif_event_registry PRIMARY KEY (id),
    CONSTRAINT fk_sif_event_registry_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT ck_sif_event_registry_type CHECK (event_type IN (
        'SYSTEM_START',
        'SYSTEM_STOP',
        'ANOMALY_DETECTION_INVOICES_RUN',
        'ANOMALY_DETECTION_INVOICES_HIT',
        'ANOMALY_DETECTION_EVENTS_RUN',
        'ANOMALY_DETECTION_EVENTS_HIT',
        'BACKUP_RESTORED',
        'EXPORT_INVOICES',
        'EXPORT_EVENTS',
        'INVOICE_VALIDATED',
        'INVOICE_VOIDED',
        'SUMMARY_6H',
        'SUMMARY_SHUTDOWN'
    )),
    CONSTRAINT ck_sif_event_registry_status CHECK (status IN ('PENDING', 'SIGNED', 'EXPORTED')),
    INDEX ix_sif_event_registry_chain (company_id, generated_at),
    INDEX ix_sif_event_registry_type (company_id, event_type)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
