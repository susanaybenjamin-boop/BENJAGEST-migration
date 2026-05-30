-- ===========================================================================
-- V7__module_catalog_and_activation.sql
--
-- Fundamento del sistema modular y multi-tenant. Cierra dos huecos:
--
-- 1) Que la app tenga un CATALOGO conocido de funciones agrupadas por
--    categoria, en lugar de listas hardcodeadas en Java. Cada modulo
--    tiene su slug (estable, codigo lo usa), su label (UI lo muestra),
--    su jerarquia (categoria -> sub-modulo) y opcionalmente una
--    dependencia (un modulo que debe estar activo para poder usarlo).
--
-- 2) Que cada empresa pueda tener su subconjunto de modulos activos,
--    en lugar del campo company_settings.enabled_modules (JSON) que
--    Pablo planteo en V2 pero nadie lee aun. Esto sustituye al JSON
--    con una tabla normalizada que ademas guarda quien activo/desactivo
--    y cuando (auditoria barata).
--
-- Decisiones (memoria/project-benjagest-architecture):
--   - Arbol de 2 niveles (categoria + sub-modulo). parent_id NULL = raiz.
--   - Por defecto activado depende del company_type (logica en backend,
--     no en este seed). En este seed solo se siembra la activacion para
--     las dos empresas demo existentes.
--   - Dependencias auto-activan (logica en backend, no en este seed).
--   - Desactivar = ocultar, nunca borrar datos asociados.
-- ===========================================================================

-- ---------------------------------------------------------------------------
-- 1) Catalogo
-- ---------------------------------------------------------------------------
CREATE TABLE module_catalog (
    id CHAR(36) NOT NULL,
    slug VARCHAR(60) NOT NULL,
    label VARCHAR(120) NOT NULL,
    description TEXT NULL,
    parent_id CHAR(36) NULL,
    requires_module_id CHAR(36) NULL,
    icon VARCHAR(60) NULL,
    display_order INT NOT NULL DEFAULT 0,
    advisory_only BOOLEAN NOT NULL DEFAULT FALSE,
    active_in_catalog BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_module_catalog PRIMARY KEY (id),
    CONSTRAINT uk_module_catalog_slug UNIQUE (slug),
    CONSTRAINT fk_module_catalog_parent FOREIGN KEY (parent_id) REFERENCES module_catalog (id),
    CONSTRAINT fk_module_catalog_requires FOREIGN KEY (requires_module_id) REFERENCES module_catalog (id),
    INDEX ix_module_catalog_parent (parent_id),
    INDEX ix_module_catalog_active (active_in_catalog)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 2) Activacion por empresa
-- ---------------------------------------------------------------------------
CREATE TABLE company_modules (
    id CHAR(36) NOT NULL,
    company_id CHAR(36) NOT NULL,
    module_id CHAR(36) NOT NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    activated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    activated_by CHAR(36) NULL,
    deactivated_at TIMESTAMP NULL,
    deactivated_by CHAR(36) NULL,
    CONSTRAINT pk_company_modules PRIMARY KEY (id),
    CONSTRAINT uk_company_modules UNIQUE (company_id, module_id),
    CONSTRAINT fk_company_modules_company FOREIGN KEY (company_id) REFERENCES companies (id),
    CONSTRAINT fk_company_modules_module FOREIGN KEY (module_id) REFERENCES module_catalog (id),
    CONSTRAINT fk_company_modules_activated_by FOREIGN KEY (activated_by) REFERENCES user_accounts (id),
    CONSTRAINT fk_company_modules_deactivated_by FOREIGN KEY (deactivated_by) REFERENCES user_accounts (id),
    INDEX ix_company_modules_company_active (company_id, active),
    INDEX ix_company_modules_module (module_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ---------------------------------------------------------------------------
-- 3) Seed del catalogo - categorias raiz
-- ---------------------------------------------------------------------------
INSERT INTO module_catalog (id, slug, label, description, parent_id, icon, display_order, advisory_only)
VALUES
    (UUID(), 'core', 'Nucleo', 'Modulos transversales siempre disponibles.', NULL, 'fas-cube', 10, FALSE),
    (UUID(), 'billing', 'Facturacion', 'Emision de facturas, series, VeriFactu y cobros.', NULL, 'fas-file-invoice-dollar', 20, FALSE),
    (UUID(), 'purchases', 'Compras', 'Proveedores, facturas recibidas y gastos.', NULL, 'fas-receipt', 30, FALSE),
    (UUID(), 'accounting', 'Contabilidad', 'PGC, asientos, inmovilizado y cierres.', NULL, 'fas-balance-scale', 40, FALSE),
    (UUID(), 'tax', 'Fiscal', 'Modelos AEAT, SII, renta y sociedades.', NULL, 'fas-percentage', 50, FALSE),
    (UUID(), 'labor', 'Laboral', 'Empleados, contratos, nominas y SS.', NULL, 'fas-hard-hat', 60, FALSE),
    (UUID(), 'time-clock', 'Fichajes', 'Registro horario RD 8/2019 y jornadas.', NULL, 'fas-clock', 70, FALSE),
    (UUID(), 'self-employed', 'RETA', 'Autonomos: perfiles, tramos, cambios de base.', NULL, 'fas-user-tie', 80, FALSE),
    (UUID(), 'documents', 'Documentos', 'Certificados digitales y credenciales externas.', NULL, 'fas-folder-open', 90, FALSE),
    (UUID(), 'calendar', 'Calendario', 'Agenda, festivos e integraciones.', NULL, 'fas-calendar-alt', 100, FALSE),
    (UUID(), 'notifications', 'Notificaciones', 'Avisos, alertas y DEHu.', NULL, 'fas-bell', 110, FALSE),
    (UUID(), 'reports', 'Informes', 'Reporting transversal.', NULL, 'fas-chart-line', 120, FALSE),
    (UUID(), 'advisory', 'Asesoria', 'Funciones especificas de gestoria sobre cartera de clientes.', NULL, 'fas-briefcase', 130, TRUE),
    (UUID(), 'kiosk', 'Kiosko', 'Dispositivo compartido de fichaje.', NULL, 'fas-tablet-alt', 140, FALSE);

-- ---------------------------------------------------------------------------
-- 4) Seed del catalogo - sub-modulos por categoria
--    Cada sub-modulo resuelve su parent_id consultando por slug a las
--    categorias recien insertadas. Asi no dependemos de UUIDs fijos.
-- ---------------------------------------------------------------------------

-- core
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'customers', 'Clientes', (SELECT id FROM module_catalog WHERE slug='core'), 'fas-users', 1
UNION ALL SELECT UUID(), 'settings', 'Ajustes', (SELECT id FROM module_catalog WHERE slug='core'), 'fas-cog', 2
UNION ALL SELECT UUID(), 'users', 'Usuarios y roles', (SELECT id FROM module_catalog WHERE slug='core'), 'fas-users-cog', 3;

-- billing
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'issuers', 'Emisores', (SELECT id FROM module_catalog WHERE slug='billing'), 'fas-file-signature', 1
UNION ALL SELECT UUID(), 'invoice-series', 'Series de numeracion', (SELECT id FROM module_catalog WHERE slug='billing'), 'fas-hashtag', 2
UNION ALL SELECT UUID(), 'sales-invoices', 'Facturas emitidas', (SELECT id FROM module_catalog WHERE slug='billing'), 'fas-file-invoice', 3
UNION ALL SELECT UUID(), 'recurring-invoices', 'Facturas recurrentes', (SELECT id FROM module_catalog WHERE slug='billing'), 'fas-redo', 4
UNION ALL SELECT UUID(), 'sales-payments', 'Cobros', (SELECT id FROM module_catalog WHERE slug='billing'), 'fas-money-check', 5
UNION ALL SELECT UUID(), 'verifactu', 'VeriFactu (AEAT)', (SELECT id FROM module_catalog WHERE slug='billing'), 'fas-shield-alt', 6;

-- purchases
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'suppliers', 'Proveedores', (SELECT id FROM module_catalog WHERE slug='purchases'), 'fas-truck', 1
UNION ALL SELECT UUID(), 'purchase-invoices', 'Facturas recibidas', (SELECT id FROM module_catalog WHERE slug='purchases'), 'fas-file-import', 2
UNION ALL SELECT UUID(), 'recurring-expenses', 'Gastos recurrentes', (SELECT id FROM module_catalog WHERE slug='purchases'), 'fas-sync-alt', 3;

-- accounting
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'chart-of-accounts', 'Plan contable', (SELECT id FROM module_catalog WHERE slug='accounting'), 'fas-book', 1
UNION ALL SELECT UUID(), 'journal-entries', 'Asientos contables', (SELECT id FROM module_catalog WHERE slug='accounting'), 'fas-list-ol', 2
UNION ALL SELECT UUID(), 'fixed-assets', 'Inmovilizado', (SELECT id FROM module_catalog WHERE slug='accounting'), 'fas-building', 3
UNION ALL SELECT UUID(), 'year-closing', 'Cierre de ejercicio', (SELECT id FROM module_catalog WHERE slug='accounting'), 'fas-lock', 4;

-- tax
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'tax-models', 'Modelos AEAT', (SELECT id FROM module_catalog WHERE slug='tax'), 'fas-file-alt', 1
UNION ALL SELECT UUID(), 'aeat-consultations', 'Consultas y discrepancias AEAT', (SELECT id FROM module_catalog WHERE slug='tax'), 'fas-question-circle', 2
UNION ALL SELECT UUID(), 'sii', 'SII (suministro inmediato)', (SELECT id FROM module_catalog WHERE slug='tax'), 'fas-paper-plane', 3;

-- labor (employees primero, payrolls/medical depende de employees - dependencia se carga despues)
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'employees', 'Empleados', (SELECT id FROM module_catalog WHERE slug='labor'), 'fas-id-badge', 1
UNION ALL SELECT UUID(), 'contracts', 'Contratos', (SELECT id FROM module_catalog WHERE slug='labor'), 'fas-file-contract', 2
UNION ALL SELECT UUID(), 'payrolls', 'Nominas', (SELECT id FROM module_catalog WHERE slug='labor'), 'fas-money-bill-wave', 3
UNION ALL SELECT UUID(), 'medical-leaves', 'Bajas laborales', (SELECT id FROM module_catalog WHERE slug='labor'), 'fas-briefcase-medical', 4
UNION ALL SELECT UUID(), 'social-security', 'Seguridad Social', (SELECT id FROM module_catalog WHERE slug='labor'), 'fas-shield-alt', 5;

-- time-clock
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'time-events', 'Fichaje', (SELECT id FROM module_catalog WHERE slug='time-clock'), 'fas-stopwatch', 1
UNION ALL SELECT UUID(), 'work-reports', 'Partes de trabajo', (SELECT id FROM module_catalog WHERE slug='time-clock'), 'fas-clipboard-list', 2
UNION ALL SELECT UUID(), 'absences', 'Ausencias', (SELECT id FROM module_catalog WHERE slug='time-clock'), 'fas-user-clock', 3;

-- self-employed
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'reta-profiles', 'Perfiles RETA', (SELECT id FROM module_catalog WHERE slug='self-employed'), 'fas-user-tie', 1
UNION ALL SELECT UUID(), 'reta-estimates', 'Estimaciones RETA', (SELECT id FROM module_catalog WHERE slug='self-employed'), 'fas-calculator', 2
UNION ALL SELECT UUID(), 'reta-events', 'Eventos RETA', (SELECT id FROM module_catalog WHERE slug='self-employed'), 'fas-calendar-day', 3
UNION ALL SELECT UUID(), 'reta-alerts', 'Alertas RETA', (SELECT id FROM module_catalog WHERE slug='self-employed'), 'fas-exclamation-triangle', 4
UNION ALL SELECT UUID(), 'reta-brackets', 'Tramos de cotizacion', (SELECT id FROM module_catalog WHERE slug='self-employed'), 'fas-layer-group', 5
UNION ALL SELECT UUID(), 'reta-base-changes', 'Cambios de base', (SELECT id FROM module_catalog WHERE slug='self-employed'), 'fas-exchange-alt', 6
UNION ALL SELECT UUID(), 'reta-preonboarding', 'Pre-onboarding', (SELECT id FROM module_catalog WHERE slug='self-employed'), 'fas-user-plus', 7;

-- documents
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'digital-certificates', 'Certificados digitales', (SELECT id FROM module_catalog WHERE slug='documents'), 'fas-certificate', 1
UNION ALL SELECT UUID(), 'document-files', 'Archivos', (SELECT id FROM module_catalog WHERE slug='documents'), 'fas-file', 2;

-- calendar
INSERT INTO module_catalog (id, slug, label, parent_id, icon, display_order)
SELECT UUID(), 'calendar-events', 'Eventos', (SELECT id FROM module_catalog WHERE slug='calendar'), 'fas-calendar-day', 1
UNION ALL SELECT UUID(), 'calendar-integrations', 'Integraciones (Google)', (SELECT id FROM module_catalog WHERE slug='calendar'), 'fab-google', 2;

-- ---------------------------------------------------------------------------
-- 5) Dependencias declaradas en el catalogo
--    Las dependencias son orientativas: el backend AUTO-activara la
--    dependencia cuando activas el modulo que la requiere (decision 3).
-- ---------------------------------------------------------------------------
UPDATE module_catalog SET requires_module_id = (SELECT m2.id FROM (SELECT id FROM module_catalog WHERE slug='employees') m2)
 WHERE slug IN ('payrolls', 'medical-leaves', 'social-security', 'contracts');

UPDATE module_catalog SET requires_module_id = (SELECT m2.id FROM (SELECT id FROM module_catalog WHERE slug='sales-invoices') m2)
 WHERE slug IN ('sales-payments', 'recurring-invoices', 'verifactu');

UPDATE module_catalog SET requires_module_id = (SELECT m2.id FROM (SELECT id FROM module_catalog WHERE slug='reta-profiles') m2)
 WHERE slug IN ('reta-estimates', 'reta-events', 'reta-alerts', 'reta-base-changes');

UPDATE module_catalog SET requires_module_id = (SELECT m2.id FROM (SELECT id FROM module_catalog WHERE slug='journal-entries') m2)
 WHERE slug IN ('year-closing', 'fixed-assets');

-- ---------------------------------------------------------------------------
-- 6) Activacion para las empresas demo
--    - 11111111... (BENJAGEST Demo, INTERNAL/asesoria): TODO encendido.
--    - 22222222... (Obras Norte, CLIENT): todo MENOS la categoria advisory
--      y la categoria kiosk (junto con sus sub-modulos).
-- ---------------------------------------------------------------------------
INSERT INTO company_modules (id, company_id, module_id, active)
SELECT UUID(), '11111111-1111-1111-1111-111111111111', id, TRUE
FROM module_catalog;

INSERT INTO company_modules (id, company_id, module_id, active)
SELECT UUID(), '22222222-2222-2222-2222-222222222222', m.id, TRUE
FROM module_catalog m
LEFT JOIN module_catalog p ON p.id = m.parent_id
WHERE m.slug NOT IN ('advisory', 'kiosk')
  AND (p.slug IS NULL OR p.slug NOT IN ('advisory', 'kiosk'));
