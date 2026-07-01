-- =============================================================================
-- V162 -- Retirada del modulo "Sugerencias" (PORT-3 SUG, V83).
--
-- Decision Benjamin 2026-07-01: el modulo no tenia sentido de producto tal
-- como estaba construido -- era per-tenant (nadie fuera de la propia empresa
-- lo veia), la columna "answer" nunca se podia escribir desde ningun
-- endpoint, y prometia un "modulo fabricante" que jamas se construyo. Se
-- retira por completo en vez de dejarlo a medias.
--
-- Desactiva el modulo para todas las empresas, lo borra del catalogo y
-- elimina la tabla. No se conserva ningun dato de sugerencias enviadas.
-- =============================================================================

DELETE cm FROM company_modules cm
  JOIN module_catalog mc ON mc.id = cm.module_id
 WHERE mc.slug = 'suggestions';

DELETE FROM module_catalog WHERE slug = 'suggestions';

DROP TABLE IF EXISTS suggestions;
