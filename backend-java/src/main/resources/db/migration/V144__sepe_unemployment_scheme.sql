-- ===========================================================================
-- V144 — Esquema de cotización por DESEMPLEO en el catálogo legal SEPE.
--
-- Bloque CONTRATO-MODALIDADES (Benjamin 2026-06-25): "que quede completo, por
-- ley, sin dejarnos ningún tipo ni matiz". El catálogo legal de modalidades YA
-- existe: sepe_contract_types (V74) con todos los códigos SEPE y su familia.
-- NO duplicamos ese catálogo: lo EXTENDEMOS con el dato que faltaba para el
-- cálculo de la nómina — qué tipo de desempleo cotiza cada código.
--
-- Orden PJC/297/2026 (tipos de desempleo 2026):
--   · Contratación INDEFINIDA: 7,05 % (5,50 empresa + 1,55 trabajador).
--     Incluye —y esto es el MATIZ importante— no solo los indefinidos, sino
--     también fijos-discontinuos, contratos FORMATIVOS (alternancia y práctica),
--     de relevo, de SUSTITUCIÓN/interinidad y los de personas con discapacidad.
--   · Contratación de DURACIÓN DETERMINADA (temporal): 8,30 % (6,70 + 1,60).
--     Solo los realmente temporales: circunstancias de la producción, inserción
--     y los vinculados a Fondos Europeos.
--
-- Por eso NO se puede derivar el esquema de la familia a secas (la familia
-- TEMPORAL contiene la sustitución, que cotiza al tipo INDEFINIDO). Se marca
-- código a código. Editable (no-code): si la ley cambia, se ajusta la columna.
-- Default INDEFINIDO → los contratos antiguos (code 100) no cambian de cálculo.
-- ===========================================================================

ALTER TABLE sepe_contract_types
    ADD COLUMN unemployment_scheme VARCHAR(20) NOT NULL DEFAULT 'INDEFINIDO'
        AFTER family;

-- Marcar como TEMPORAL solo los códigos de duración determinada que cotizan
-- desempleo al 8,30 %. El resto se queda en INDEFINIDO (default).
--   300 obra/servicio (legacy), 410/510 circunstancias producción,
--   420 eventual (legacy), 405/505 inserción, 406/506 Fondos Europeos.
-- NOTA: 411/511 (sustitución/interinidad) NO entran aquí — cotizan al esquema
-- INDEFINIDO por ley, igual que los formativos (421/521) y prácticas (401/501).
UPDATE sepe_contract_types
   SET unemployment_scheme = 'TEMPORAL'
 WHERE code IN ('300', '410', '510', '420', '405', '505', '406', '506');
