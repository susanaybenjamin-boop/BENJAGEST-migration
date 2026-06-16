-- RETA-4: forma jurídica de la empresa. Decisión Benjamin 2026-06-15.
-- Si AUTONOMO → la empresa ES el autónomo (perfil RETA desde la propia empresa).
-- Si es sociedad → el titular OWNER que cotiza RETA aporta el perfil.
ALTER TABLE companies
    ADD COLUMN IF NOT EXISTS legal_form VARCHAR(20) NULL AFTER company_type;
-- Valores usados por la UI: AUTONOMO, SL, SA, SLU, SC, CB, COOPERATIVA, OTRO.
