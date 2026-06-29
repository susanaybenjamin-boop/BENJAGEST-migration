-- OFF-1 (2026-06-29) — Sincronización offline de fichajes (kiosco + PWA).
--
-- client_uuid: identificador generado en el DISPOSITIVO al fichar (también
-- offline). Permite que la sincronización por lotes sea IDEMPOTENTE: si el mismo
-- fichaje se reenvía (reintento de red), no se duplica. Los fichajes online
-- normales no llevan uuid (NULL) — MariaDB permite múltiples NULL en un índice
-- único, así que no rompe los existentes ni los nuevos online.
ALTER TABLE time_clock_events
    ADD COLUMN client_uuid CHAR(36) NULL AFTER origin;

CREATE UNIQUE INDEX uk_tce_company_client_uuid
    ON time_clock_events (company_id, client_uuid);
