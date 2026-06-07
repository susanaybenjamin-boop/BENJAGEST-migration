-- =========================================================================
-- V67 — Slice 5B: reparto granular de módulos por asignación (Opción B).
--
-- Decisión Benjamin 2026-06-07: la asignación de cliente a empleado se
-- complementa con una lista de módulos concretos que ese empleado
-- gestiona en ese cliente. Casos cubiertos:
--
--   - Pepe lleva el cliente X "entero" → asignación sin filas en
--     assignment_modules → "todos los módulos activos del cliente".
--   - Pepe lleva contratos+nóminas del cliente X → asignación + 2 filas
--     (module_slug='labor', module_slug='payslips').
--   - Pepe y María comparten el cliente X (Pepe Labor, María Accounting)
--     → DOS filas en client_assignments (una por empleado), cada una
--     con su sub-lista en assignment_modules.
--
-- Delegación: a nivel de asignación. Si Pepe se delega a María por
-- vacaciones, María hereda automáticamente TODOS los módulos que Pepe
-- tenía en cada cliente, sin tener que crear filas adicionales.
--
-- FK a module_catalog garantiza que solo se asigne un módulo del
-- catálogo activo. ON DELETE CASCADE en assignment_id para que al
-- borrar una asignación, sus módulos se vayan con ella.
-- =========================================================================

CREATE TABLE assignment_modules (
    assignment_id CHAR(36) NOT NULL,
    module_slug VARCHAR(60) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_assignment_modules PRIMARY KEY (assignment_id, module_slug),
    CONSTRAINT fk_am_assignment FOREIGN KEY (assignment_id)
        REFERENCES client_assignments (id) ON DELETE CASCADE,
    CONSTRAINT fk_am_module FOREIGN KEY (module_slug)
        REFERENCES module_catalog (slug),

    INDEX ix_am_module (module_slug)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
