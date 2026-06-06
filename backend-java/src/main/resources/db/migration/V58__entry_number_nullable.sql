-- V58 — entry_number nullable + DRAFTs sin número
--
-- Cambio del flujo de numeración del Diario contable:
--   • DRAFT  → entry_number NULL (es un borrador, aún no entra en el Diario)
--   • POSTED → entry_number = nextval (asignado en el momento de validar)
--
-- Razón: el asesor reportó que si crea las facturas en orden 1-2-3 pero
-- las VALIDA en orden 3-1-2, el Diario salía con los números de creación,
-- no con los de validación. El Diario español debe ir consecutivo por
-- fecha de asiento o por orden de inscripción, sin huecos visibles entre
-- los POSTED.
--
-- MariaDB permite múltiples NULL en UNIQUE constraints, así que basta
-- con relajar la columna a NULL — la UK uk_journal_entries_company_number
-- sigue funcionando: solo aplica entre filas con entry_number != NULL.
--
-- Limpieza opcional: borrar los entry_number de los DRAFT existentes, así
-- el siguiente POST reasigna correctamente sin saltar.

ALTER TABLE journal_entries
    MODIFY COLUMN entry_number INT NULL;

-- DRAFTs existentes pasan a NULL para que al validarlos se renumeren
-- limpiamente. Lo hacemos en una sola UPDATE idempotente.
UPDATE journal_entries
   SET entry_number = NULL
 WHERE status = 'DRAFT';
