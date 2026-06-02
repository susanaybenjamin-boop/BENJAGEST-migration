-- V16: Series reservadas auto-creadas por empresa
--
-- Decision (2026-06-02): el usuario solo define la serie STANDARD (su
-- numeracion de facturas normales). Las series para PROFORMA y
-- RECTIFYING se reservan por el sistema con codigos fijos PROF y RECT.
-- Asi BENJAGEST cumple por defecto con RD 1619/2012 Art. 13 (las
-- rectificativas DEBEN ir en serie separada de las normales) sin que
-- el usuario tenga que recordar configurarlo.
--
-- Estrategia: para cada companies.id que no tenga ya una serie con ese
-- codigo, insertar una nueva con UUID generado al vuelo. Idempotente:
-- si la migracion se re-ejecuta o si el usuario ya las creo a mano, no
-- duplica nada (UNIQUE (company_id, code) protege ademas a nivel de BD).

INSERT INTO invoice_series (id, company_id, code, invoice_kind, numbering_type,
                            format_template, next_number, current_year, locked, active)
SELECT UUID(),
       c.id,
       'PROF',
       'PROFORMA',
       'BY_YEAR',
       '{CODE}-{YYYY}-{0000}',
       1,
       YEAR(CURRENT_DATE),
       FALSE,
       TRUE
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM invoice_series s
        WHERE s.company_id = c.id
          AND s.code = 'PROF'
 );

INSERT INTO invoice_series (id, company_id, code, invoice_kind, numbering_type,
                            format_template, next_number, current_year, locked, active)
SELECT UUID(),
       c.id,
       'RECT',
       'RECTIFYING',
       'BY_YEAR',
       '{CODE}-{YYYY}-{0000}',
       1,
       YEAR(CURRENT_DATE),
       FALSE,
       TRUE
  FROM companies c
 WHERE NOT EXISTS (
       SELECT 1 FROM invoice_series s
        WHERE s.company_id = c.id
          AND s.code = 'RECT'
 );
