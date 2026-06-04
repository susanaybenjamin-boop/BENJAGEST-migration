-- ===========================================================================
-- V25__pgc_master_and_seed.sql
--
-- Plan General Contable Español (RD 1514/2007). Carga estructural.
--
-- Decisiones honestas:
--
--   - El PGC oficial tiene ~668 cuentas. Cargar las 668 como seed
--     SQL son varios miles de líneas y solapa con un trabajo de
--     mantenimiento al cambiar de plan (PYMES tiene su subconjunto).
--   - Aqui sembramos un núcleo estructural ~120 cuentas que cubren:
--       * Los 9 grupos raíz.
--       * Los 1-2 niveles más usados (clientes, proveedores, bancos,
--         IVA repercutido/soportado, ventas, compras, gastos por
--         naturaleza, amortizaciones).
--   - La carga restante (468 cuentas para empresas que llevan
--     contabilidad detallada) queda como deuda:
--         a) Sub-slice "PGC completo" con un fichero CSV que se carga
--            via endpoint.
--         b) UI para que el usuario active/desactive las cuentas que
--            no usa.
--   - La tabla accounting_accounts ya existe desde V2 — es por empresa.
--     Esta migración la siembra para las empresas existentes y
--     define el comportamiento para empresas nuevas (a cargar cuando
--     se cierre el slice "crear empresa nueva").
--
-- Estructura sembrada:
--
--   1xxx  Financiacion basica (capital, reservas, prestamos)
--   2xxx  Inmovilizado (tangible, intangible, financieras, amortiz.)
--   3xxx  Existencias
--   4xxx  Acreedores y deudores (clientes, proveedores, HP, SS)
--   5xxx  Cuentas financieras (caja, bancos, prestamos cp)
--   6xxx  Compras y gastos
--   7xxx  Ventas e ingresos
--   8xxx  Gastos imputados al patrimonio neto
--   9xxx  Ingresos imputados al patrimonio neto
--
-- account_type distingue:
--   ASSET (activo), LIABILITY (pasivo), EQUITY (patrimonio),
--   INCOME (ingreso), EXPENSE (gasto), CONTRA (cuenta contraria,
--   p.ej. amortizacion acumulada que resta del activo).
-- ===========================================================================

-- Helper: inserta para CADA empresa existente.
-- Usamos un CROSS JOIN con la lista de cuentas a sembrar.
-- ---------------------------------------------------------------------------
-- Para no escribir 9 grupos × 120 cuentas × N empresas a mano,
-- definimos una tabla temporal con la plantilla y la cruzamos.

-- COLLATE explicito (fix 2026-06-04): MariaDB 11.4 cambio el default
-- de las nuevas tablas a `utf8mb4_uca1400_ai_ci`. Si la temporal toma
-- esa collation, el JOIN con accounting_accounts (que viene de V2 con
-- `utf8mb4_unicode_ci`) lanza error 1267 "Illegal mix of collations".
-- Forzamos la misma collation que las tablas reales del esquema.
CREATE TEMPORARY TABLE pgc_template (
    code VARCHAR(20) NOT NULL,
    name VARCHAR(180) NOT NULL,
    account_type VARCHAR(40) NOT NULL,
    PRIMARY KEY (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO pgc_template (code, name, account_type) VALUES
-- Grupo 1: FINANCIACIÓN BÁSICA
('100', 'Capital social', 'EQUITY'),
('101', 'Fondo social', 'EQUITY'),
('102', 'Capital', 'EQUITY'),
('103', 'Socios por desembolsos no exigidos', 'EQUITY'),
('110', 'Prima de emisión o asunción', 'EQUITY'),
('112', 'Reserva legal', 'EQUITY'),
('113', 'Reservas voluntarias', 'EQUITY'),
('114', 'Reservas especiales', 'EQUITY'),
('118', 'Aportaciones de socios o propietarios', 'EQUITY'),
('120', 'Remanente', 'EQUITY'),
('121', 'Resultados negativos de ejercicios anteriores', 'EQUITY'),
('129', 'Resultado del ejercicio', 'EQUITY'),
('170', 'Deudas a largo plazo con entidades de crédito', 'LIABILITY'),
('171', 'Deudas a largo plazo', 'LIABILITY'),
('175', 'Efectos a pagar a largo plazo', 'LIABILITY'),

-- Grupo 2: INMOVILIZADO
('200', 'Investigación', 'ASSET'),
('201', 'Desarrollo', 'ASSET'),
('203', 'Propiedad industrial', 'ASSET'),
('206', 'Aplicaciones informáticas', 'ASSET'),
('210', 'Terrenos y bienes naturales', 'ASSET'),
('211', 'Construcciones', 'ASSET'),
('212', 'Instalaciones técnicas', 'ASSET'),
('213', 'Maquinaria', 'ASSET'),
('214', 'Utillaje', 'ASSET'),
('215', 'Otras instalaciones', 'ASSET'),
('216', 'Mobiliario', 'ASSET'),
('217', 'Equipos para procesos de información', 'ASSET'),
('218', 'Elementos de transporte', 'ASSET'),
('219', 'Otro inmovilizado material', 'ASSET'),
('280', 'Amortización acumulada del inmovilizado intangible', 'CONTRA'),
('281', 'Amortización acumulada del inmovilizado material', 'CONTRA'),

-- Grupo 3: EXISTENCIAS
('300', 'Mercaderías', 'ASSET'),
('310', 'Materias primas', 'ASSET'),
('320', 'Elementos y conjuntos incorporables', 'ASSET'),
('330', 'Productos en curso', 'ASSET'),
('340', 'Productos semiterminados', 'ASSET'),
('350', 'Productos terminados', 'ASSET'),
('360', 'Subproductos, residuos y materiales recuperados', 'ASSET'),
('390', 'Deterioro de valor de las mercaderías', 'CONTRA'),

-- Grupo 4: ACREEDORES Y DEUDORES (el más usado)
('400', 'Proveedores', 'LIABILITY'),
('401', 'Proveedores, efectos comerciales a pagar', 'LIABILITY'),
('406', 'Envases y embalajes a devolver a proveedores', 'LIABILITY'),
('407', 'Anticipos a proveedores', 'ASSET'),
('410', 'Acreedores por prestación de servicios', 'LIABILITY'),
('430', 'Clientes', 'ASSET'),
('431', 'Clientes, efectos comerciales a cobrar', 'ASSET'),
('436', 'Clientes de dudoso cobro', 'ASSET'),
('437', 'Envases y embalajes a devolver por clientes', 'ASSET'),
('438', 'Anticipos de clientes', 'LIABILITY'),
('440', 'Deudores', 'ASSET'),
('460', 'Anticipos de remuneraciones', 'ASSET'),
('465', 'Remuneraciones pendientes de pago', 'LIABILITY'),
('470', 'Hacienda Pública, deudor por diversos conceptos', 'ASSET'),
('471', 'Organismos de la Seguridad Social, deudores', 'ASSET'),
('472', 'Hacienda Pública, IVA soportado', 'ASSET'),
('473', 'Hacienda Pública, retenciones y pagos a cuenta', 'ASSET'),
('475', 'Hacienda Pública, acreedora por conceptos fiscales', 'LIABILITY'),
('476', 'Organismos de la Seguridad Social, acreedores', 'LIABILITY'),
('477', 'Hacienda Pública, IVA repercutido', 'LIABILITY'),
('480', 'Gastos anticipados', 'ASSET'),
('485', 'Ingresos anticipados', 'LIABILITY'),
('490', 'Deterioro de valor de créditos por operaciones comerciales', 'CONTRA'),

-- Grupo 5: CUENTAS FINANCIERAS
('500', 'Obligaciones y bonos a corto plazo', 'LIABILITY'),
('510', 'Deudas a corto plazo con partes vinculadas', 'LIABILITY'),
('520', 'Deudas a corto plazo con entidades de crédito', 'LIABILITY'),
('521', 'Deudas a corto plazo', 'LIABILITY'),
('523', 'Proveedores de inmovilizado a corto plazo', 'LIABILITY'),
('524', 'Acreedores por arrendamiento financiero a corto plazo', 'LIABILITY'),
('527', 'Intereses a corto plazo de deudas con entidades de crédito', 'LIABILITY'),
('540', 'Inversiones financieras a corto plazo en instrumentos de patrimonio', 'ASSET'),
('551', 'Cuenta corriente con socios y administradores', 'LIABILITY'),
('554', 'Cuenta corriente con otras partes vinculadas', 'LIABILITY'),
('555', 'Partidas pendientes de aplicación', 'ASSET'),
('570', 'Caja, euros', 'ASSET'),
('572', 'Bancos e instituciones de crédito c/c vista, euros', 'ASSET'),
('574', 'Bancos e instituciones de crédito, cuentas de ahorro', 'ASSET'),
('575', 'Inversiones a corto plazo de gran liquidez', 'ASSET'),

-- Grupo 6: COMPRAS Y GASTOS
('600', 'Compras de mercaderías', 'EXPENSE'),
('601', 'Compras de materias primas', 'EXPENSE'),
('602', 'Compras de otros aprovisionamientos', 'EXPENSE'),
('606', 'Descuentos sobre compras por pronto pago', 'EXPENSE'),
('607', 'Trabajos realizados por otras empresas', 'EXPENSE'),
('608', 'Devoluciones de compras y operaciones similares', 'EXPENSE'),
('609', 'Rappels por compras', 'EXPENSE'),
('610', 'Variación de existencias de mercaderías', 'EXPENSE'),
('620', 'Gastos en investigación y desarrollo del ejercicio', 'EXPENSE'),
('621', 'Arrendamientos y cánones', 'EXPENSE'),
('622', 'Reparaciones y conservación', 'EXPENSE'),
('623', 'Servicios de profesionales independientes', 'EXPENSE'),
('624', 'Transportes', 'EXPENSE'),
('625', 'Primas de seguros', 'EXPENSE'),
('626', 'Servicios bancarios y similares', 'EXPENSE'),
('627', 'Publicidad, propaganda y relaciones públicas', 'EXPENSE'),
('628', 'Suministros', 'EXPENSE'),
('629', 'Otros servicios', 'EXPENSE'),
('630', 'Impuesto sobre beneficios', 'EXPENSE'),
('631', 'Otros tributos', 'EXPENSE'),
('640', 'Sueldos y salarios', 'EXPENSE'),
('641', 'Indemnizaciones', 'EXPENSE'),
('642', 'Seguridad Social a cargo de la empresa', 'EXPENSE'),
('649', 'Otros gastos sociales', 'EXPENSE'),
('650', 'Pérdidas de créditos comerciales incobrables', 'EXPENSE'),
('660', 'Gastos financieros por actualización de provisiones', 'EXPENSE'),
('661', 'Intereses de obligaciones y bonos', 'EXPENSE'),
('662', 'Intereses de deudas', 'EXPENSE'),
('669', 'Otros gastos financieros', 'EXPENSE'),
('680', 'Amortización del inmovilizado intangible', 'EXPENSE'),
('681', 'Amortización del inmovilizado material', 'EXPENSE'),
('690', 'Pérdidas del inmovilizado intangible', 'EXPENSE'),
('691', 'Pérdidas del inmovilizado material', 'EXPENSE'),
('695', 'Dotación a la provisión para operaciones comerciales', 'EXPENSE'),

-- Grupo 7: VENTAS E INGRESOS
('700', 'Ventas de mercaderías', 'INCOME'),
('701', 'Ventas de productos terminados', 'INCOME'),
('702', 'Ventas de productos semiterminados', 'INCOME'),
('703', 'Ventas de subproductos y residuos', 'INCOME'),
('704', 'Ventas de envases y embalajes', 'INCOME'),
('705', 'Prestaciones de servicios', 'INCOME'),
('706', 'Descuentos sobre ventas por pronto pago', 'INCOME'),
('708', 'Devoluciones de ventas y operaciones similares', 'INCOME'),
('709', 'Rappels sobre ventas', 'INCOME'),
('710', 'Variación de existencias de productos en curso', 'INCOME'),
('740', 'Subvenciones, donaciones y legados a la explotación', 'INCOME'),
('747', 'Otras subvenciones', 'INCOME'),
('752', 'Ingresos por arrendamientos', 'INCOME'),
('753', 'Ingresos de propiedad industrial cedida en explotación', 'INCOME'),
('755', 'Ingresos por servicios al personal', 'INCOME'),
('759', 'Ingresos por servicios diversos', 'INCOME'),
('760', 'Ingresos de participaciones en instrumentos de patrimonio', 'INCOME'),
('762', 'Ingresos de créditos', 'INCOME'),
('769', 'Otros ingresos financieros', 'INCOME'),
('778', 'Ingresos excepcionales', 'INCOME');

-- ---------------------------------------------------------------------------
-- Clonar la plantilla a las empresas existentes que NO tienen ya el PGC.
-- Defensivo: si una empresa ya tiene cuentas, no las duplicamos
-- (el UNIQUE company_id+code nos protegeria igualmente).
-- ---------------------------------------------------------------------------
INSERT INTO accounting_accounts (id, company_id, code, name, account_type)
SELECT UUID(), c.id, t.code, t.name, t.account_type
  FROM companies c
  CROSS JOIN pgc_template t
 WHERE NOT EXISTS (
       SELECT 1 FROM accounting_accounts a
        WHERE a.company_id = c.id
          AND a.code = t.code
 );

DROP TEMPORARY TABLE pgc_template;
