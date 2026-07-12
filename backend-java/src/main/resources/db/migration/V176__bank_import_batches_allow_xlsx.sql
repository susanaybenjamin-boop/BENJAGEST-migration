-- F1-BANCO fix (2026-07-12): el formato XLSX (extracto Excel de banca online,
-- p.ej. BBVA) NO estaba permitido en el CHECK ck_bib_format de
-- bank_import_batches (solo N43/CSV/MT940/CAMT053/MANUAL) -> el INSERT del batch
-- al importar un .xlsx fallaba con HTTP 500 (CONSTRAINT ck_bib_format).
-- El parser XLSX se añadió (F1-BANCO) pero la restricción no se actualizó, y no
-- saltó hasta el primer import real end-to-end de Benjamin (2026-07-12).
-- Additive: solo amplía la lista de formatos permitidos.
ALTER TABLE bank_import_batches
    DROP CONSTRAINT ck_bib_format,
    ADD CONSTRAINT ck_bib_format CHECK (source_format IN
        ('N43', 'CSV', 'XLSX', 'MT940', 'CAMT053', 'MANUAL'));
