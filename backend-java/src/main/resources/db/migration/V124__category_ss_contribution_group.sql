-- ===========================================================================
-- V124 — Grupo de cotización (1-11) en el catálogo de categorías profesionales
-- (VIG-0). Permite DERIVAR el grupo de cotización TGSS al elegir la categoría en
-- el alta de contrato, en vez de pedirlo a mano (resuelve la duplicidad
-- convenio "group_code" vs grupo de cotización).
--
-- Los valores son DEFAULTS RAZONABLES por categoría — el asesor puede ajustarlos
-- por convenio en el catálogo. Regla aplicada:
--   1 Ingenieros/Licenciados/alta dirección · 2 Ing. técnicos/diplomados ·
--   3 Jefes admin/de taller/encargados · 4 Ayudantes no titulados ·
--   5 Oficiales administrativos · 6 Subalternos · 7 Auxiliares administrativos ·
--   8 Oficiales 1ª/2ª de oficio (base diaria) · 9 Oficiales 3ª/especialistas
--   (diaria) · 10 Peones (diaria) · 11 Menores de 18 (diaria).
-- Oficios manuales/industriales (construcción, metal, limpieza operaria…) van a
-- grupos diarios 8-10; oficina/comercio/hostelería/sanidad/enseñanza/TIC a 1-7.
-- ===========================================================================

ALTER TABLE professional_categories
    ADD COLUMN IF NOT EXISTS ss_contribution_group SMALLINT NULL AFTER group_code;

-- Default de arranque para los que no se mapeen explícitamente (auxiliar admin).
UPDATE professional_categories SET ss_contribution_group = 7 WHERE ss_contribution_group IS NULL;

-- Mapeo por convenio (collective_agreement_id + group_code, ambos estables).
-- 01) Comercio General
UPDATE professional_categories SET ss_contribution_group = 1 WHERE collective_agreement_id='cccccccc-0001-0000-0000-000000000001' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 3 WHERE collective_agreement_id='cccccccc-0001-0000-0000-000000000001' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 5 WHERE collective_agreement_id='cccccccc-0001-0000-0000-000000000001' AND group_code='GRUPO_3';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0001-0000-0000-000000000001' AND group_code='GRUPO_4';
UPDATE professional_categories SET ss_contribution_group = 6 WHERE collective_agreement_id='cccccccc-0001-0000-0000-000000000001' AND group_code='GRUPO_5';
-- 02) Hostelería (servicio, base mensual)
UPDATE professional_categories SET ss_contribution_group = 3 WHERE collective_agreement_id='cccccccc-0002-0000-0000-000000000002' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 5 WHERE collective_agreement_id='cccccccc-0002-0000-0000-000000000002' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0002-0000-0000-000000000002' AND group_code='GRUPO_3';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0002-0000-0000-000000000002' AND group_code='GRUPO_4';
-- 03) Construcción (oficios, base diaria)
UPDATE professional_categories SET ss_contribution_group = 3  WHERE collective_agreement_id='cccccccc-0003-0000-0000-000000000003' AND group_code='NIVEL_VI';
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0003-0000-0000-000000000003' AND group_code='NIVEL_VIII';
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0003-0000-0000-000000000003' AND group_code='NIVEL_IX';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0003-0000-0000-000000000003' AND group_code='NIVEL_X';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0003-0000-0000-000000000003' AND group_code='NIVEL_XI';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0003-0000-0000-000000000003' AND group_code='NIVEL_XII';
-- 04) Oficinas y Despachos
UPDATE professional_categories SET ss_contribution_group = 1 WHERE collective_agreement_id='cccccccc-0004-0000-0000-000000000004' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 2 WHERE collective_agreement_id='cccccccc-0004-0000-0000-000000000004' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 5 WHERE collective_agreement_id='cccccccc-0004-0000-0000-000000000004' AND group_code='GRUPO_3';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0004-0000-0000-000000000004' AND group_code='GRUPO_4';
-- 05) Limpieza de Edificios (operaria, base diaria)
UPDATE professional_categories SET ss_contribution_group = 3  WHERE collective_agreement_id='cccccccc-0005-0000-0000-000000000005' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 9  WHERE collective_agreement_id='cccccccc-0005-0000-0000-000000000005' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0005-0000-0000-000000000005' AND group_code='GRUPO_3';
-- 06) Transporte por Carretera
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0006-0000-0000-000000000006' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0006-0000-0000-000000000006' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0006-0000-0000-000000000006' AND group_code='GRUPO_3';
-- 07) Metal Madrid (oficios, base diaria)
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0007-0000-0000-000000000007' AND group_code='GRUPO_5';
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0007-0000-0000-000000000007' AND group_code='GRUPO_6';
UPDATE professional_categories SET ss_contribution_group = 9  WHERE collective_agreement_id='cccccccc-0007-0000-0000-000000000007' AND group_code='GRUPO_7';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0007-0000-0000-000000000007' AND group_code='GRUPO_8';
-- 08) Metal Bizkaia
UPDATE professional_categories SET ss_contribution_group = 8 WHERE collective_agreement_id='cccccccc-0008-0000-0000-000000000008' AND group_code='GRUPO_5';
UPDATE professional_categories SET ss_contribution_group = 8 WHERE collective_agreement_id='cccccccc-0008-0000-0000-000000000008' AND group_code='GRUPO_6';
UPDATE professional_categories SET ss_contribution_group = 9 WHERE collective_agreement_id='cccccccc-0008-0000-0000-000000000008' AND group_code='GRUPO_7';
-- 09) Metal Cataluña
UPDATE professional_categories SET ss_contribution_group = 8 WHERE collective_agreement_id='cccccccc-0009-0000-0000-000000000009' AND group_code='GRUPO_5';
UPDATE professional_categories SET ss_contribution_group = 8 WHERE collective_agreement_id='cccccccc-0009-0000-0000-000000000009' AND group_code='GRUPO_6';
UPDATE professional_categories SET ss_contribution_group = 9 WHERE collective_agreement_id='cccccccc-0009-0000-0000-000000000009' AND group_code='GRUPO_7';
-- 10) Industria Cárnica
UPDATE professional_categories SET ss_contribution_group = 3  WHERE collective_agreement_id='cccccccc-0010-0000-0000-000000000010' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0010-0000-0000-000000000010' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0010-0000-0000-000000000010' AND group_code='GRUPO_3';
-- 11) Calzado
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0011-0000-0000-000000000011' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0011-0000-0000-000000000011' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0011-0000-0000-000000000011' AND group_code='GRUPO_3';
-- 12) Madera y Mueble
UPDATE professional_categories SET ss_contribution_group = 8 WHERE collective_agreement_id='cccccccc-0012-0000-0000-000000000012' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 8 WHERE collective_agreement_id='cccccccc-0012-0000-0000-000000000012' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 9 WHERE collective_agreement_id='cccccccc-0012-0000-0000-000000000012' AND group_code='GRUPO_3';
-- 13) Artes Gráficas
UPDATE professional_categories SET ss_contribution_group = 2 WHERE collective_agreement_id='cccccccc-0013-0000-0000-000000000013' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 8 WHERE collective_agreement_id='cccccccc-0013-0000-0000-000000000013' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0013-0000-0000-000000000013' AND group_code='GRUPO_3';
-- 14) Sanidad Privada
UPDATE professional_categories SET ss_contribution_group = 1 WHERE collective_agreement_id='cccccccc-0014-0000-0000-000000000014' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 2 WHERE collective_agreement_id='cccccccc-0014-0000-0000-000000000014' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0014-0000-0000-000000000014' AND group_code='GRUPO_3';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0014-0000-0000-000000000014' AND group_code='GRUPO_4';
-- 15) Enseñanza Concertada
UPDATE professional_categories SET ss_contribution_group = 1 WHERE collective_agreement_id='cccccccc-0015-0000-0000-000000000015' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 2 WHERE collective_agreement_id='cccccccc-0015-0000-0000-000000000015' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0015-0000-0000-000000000015' AND group_code='GRUPO_3';
-- 16) Enseñanza Privada
UPDATE professional_categories SET ss_contribution_group = 2 WHERE collective_agreement_id='cccccccc-0016-0000-0000-000000000016' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 5 WHERE collective_agreement_id='cccccccc-0016-0000-0000-000000000016' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0016-0000-0000-000000000016' AND group_code='GRUPO_3';
-- 17) Consultoría TIC
UPDATE professional_categories SET ss_contribution_group = 1 WHERE collective_agreement_id='cccccccc-0017-0000-0000-000000000017' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 2 WHERE collective_agreement_id='cccccccc-0017-0000-0000-000000000017' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 3 WHERE collective_agreement_id='cccccccc-0017-0000-0000-000000000017' AND group_code='GRUPO_3';
UPDATE professional_categories SET ss_contribution_group = 5 WHERE collective_agreement_id='cccccccc-0017-0000-0000-000000000017' AND group_code='GRUPO_4';
-- 18) Contact Center
UPDATE professional_categories SET ss_contribution_group = 3 WHERE collective_agreement_id='cccccccc-0018-0000-0000-000000000018' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0018-0000-0000-000000000018' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0018-0000-0000-000000000018' AND group_code='GRUPO_3';
-- 19) Estaciones de Servicio
UPDATE professional_categories SET ss_contribution_group = 3 WHERE collective_agreement_id='cccccccc-0019-0000-0000-000000000019' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 9 WHERE collective_agreement_id='cccccccc-0019-0000-0000-000000000019' AND group_code='GRUPO_2';
-- 20) Peluquerías y Estética
UPDATE professional_categories SET ss_contribution_group = 5  WHERE collective_agreement_id='cccccccc-0020-0000-0000-000000000020' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 8  WHERE collective_agreement_id='cccccccc-0020-0000-0000-000000000020' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0020-0000-0000-000000000020' AND group_code='GRUPO_3';
-- 21) Seguridad Privada
UPDATE professional_categories SET ss_contribution_group = 3  WHERE collective_agreement_id='cccccccc-0021-0000-0000-000000000021' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 9  WHERE collective_agreement_id='cccccccc-0021-0000-0000-000000000021' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0021-0000-0000-000000000021' AND group_code='GRUPO_3';
-- 22) Multiservicios
UPDATE professional_categories SET ss_contribution_group = 3  WHERE collective_agreement_id='cccccccc-0022-0000-0000-000000000022' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 9  WHERE collective_agreement_id='cccccccc-0022-0000-0000-000000000022' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0022-0000-0000-000000000022' AND group_code='GRUPO_3';
-- 23) Servicios Funerarios
UPDATE professional_categories SET ss_contribution_group = 5  WHERE collective_agreement_id='cccccccc-0023-0000-0000-000000000023' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 10 WHERE collective_agreement_id='cccccccc-0023-0000-0000-000000000023' AND group_code='GRUPO_2';
-- 24) Atención a Personas con Discapacidad
UPDATE professional_categories SET ss_contribution_group = 1 WHERE collective_agreement_id='cccccccc-0024-0000-0000-000000000024' AND group_code='GRUPO_1';
UPDATE professional_categories SET ss_contribution_group = 2 WHERE collective_agreement_id='cccccccc-0024-0000-0000-000000000024' AND group_code='GRUPO_2';
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0024-0000-0000-000000000024' AND group_code='GRUPO_3';
-- 25) Sin convenio / libre → auxiliar admin por defecto (editable)
UPDATE professional_categories SET ss_contribution_group = 7 WHERE collective_agreement_id='cccccccc-0025-0000-0000-000000000025' AND group_code='CUSTOM';
