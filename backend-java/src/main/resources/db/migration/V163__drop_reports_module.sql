-- =============================================================================
-- V163 -- Retirada del modulo "Informes" (slug "reports").
--
-- Decision Benjamin 2026-07-01: "Informes" nunca fue un modulo de informes.
-- Era el hueco generico de WorkspaceRepository (usado antes de que cada modulo
-- tuviera pantalla propia) sin migrar nunca a algo real: la consulta detras
-- del boton era un SELECT sobre la tabla `notifications` (avisos manuales),
-- con la etiqueta interna "module.unit.alerts" (avisos), no informes. El
-- formulario "Nuevo" ni siquiera tenia campos propios (caia al generico
-- Titulo/Detalle/Prioridad). Nada en el backend insertaba en esa tabla salvo
-- el propio formulario manual de este modulo. Los informes contables reales
-- (Balance, PyG, Libro Mayor, Sumas y Saldos, PDF) viven en el modulo
-- Contabilidad y no dependen de esto.
--
-- La tabla `notifications` NO se borra: la usa tambien el dashboard de inicio
-- (contador de avisos + lista de "ultimas notificaciones"), que es una
-- funcionalidad aparte y se queda intacta (aunque, al no existir ya ninguna
-- via para escribir en esa tabla, mostrara vacio hasta que se decida darle
-- un uso real).
-- =============================================================================

DELETE cm FROM company_modules cm
  JOIN module_catalog mc ON mc.id = cm.module_id
 WHERE mc.slug = 'reports';

DELETE FROM module_catalog WHERE slug = 'reports';
