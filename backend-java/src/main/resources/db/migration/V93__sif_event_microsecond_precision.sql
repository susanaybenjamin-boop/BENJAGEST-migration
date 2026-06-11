-- =========================================================
-- V93 — SIF-CHAIN-FIX precisión microsegundos
-- Slice 2026-06-11 noche.
--
-- Bug raíz: SifEventService.appendEvent llamaba a
-- OffsetDateTime.now(Madrid).truncatedTo(ChronoUnit.SECONDS)
-- — los tres eventos del arranque (SYSTEM_START + dos
-- ANOMALY_DETECTION_*_RUN) caen en el mismo segundo y por tanto
-- en el mismo generated_at. findLastHashForCompany ordenaba por
-- (generated_at DESC, id DESC) con id=UUID, así que el desempate
-- era ALFABÉTICO (no determinista respecto al orden real de
-- inserción). El segundo y tercer evento podían leer un prev_hash
-- distinto del que realmente les precedía, rompiendo la cadena.
-- findChainOrderedAscForCompany sufría el mismo problema al
-- verificar.
--
-- Fix: TIMESTAMP(6) (microsegundos). Java pasa OffsetDateTime sin
-- truncar; cada llamada a now() difiere en microsegundos. Orden
-- estable e impossible que dos eventos consecutivos colisionen.
--
-- El hash NO cambia: el format "yyyy-MM-dd'T'HH:mm:ssxxx" ignora
-- fracción de segundo, así que insert y verify producen la misma
-- cadena canónica antes y después del ALTER.
-- =========================================================

ALTER TABLE sif_event_registry
    MODIFY COLUMN generated_at TIMESTAMP(6) NOT NULL;
