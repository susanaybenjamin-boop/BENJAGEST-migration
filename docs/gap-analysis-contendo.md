# Análisis de faltas: CONTENDO GESTIONES vs BENJAGEST migración

> **Documento de Benjamin para Pablo.** Recoge, módulo a módulo, qué tiene hoy `CONTENDO GESTIONES` (la aplicación original, en Next.js + Supabase) que todavía no está contemplado, o solo parcialmente contemplado, en la migración a Java/MariaDB.
>
> Este documento **NO** propone implementaciones ni decide alcance. Solo lista lo detectado para que Pablo pueda planificar.
>
> Fecha de inventario inicial: 2026-05-27.
> **Actualización (estado + hallazgos adicionales): 2026-05-31** — ver secciones 0 y 11 al final.
>
> Fuentes: `C:\Proyectos\CONTENDO GESTIONES\app180-frontend\app\*`, `C:\Proyectos\CONTENDO GESTIONES\backend\*`, proyecto Supabase `APP360` (id `qexnthgfdvtvwoeykgun`), y para el destino: `docs/domain-model.md`, `docs/legacy-schema-inventory.md`, `docs/migration-plan.md`, [`docs/migration-roadmap.md`](migration-roadmap.md), y código actual en `develop`.

---

## 0. Estado de avance — actualización 2026-05-31

Desde la fecha de inventario inicial (27/05) se han cerrado los siguientes items del documento. Para ver el detalle de cada commit ver [`migration-roadmap.md`](migration-roadmap.md).

| Sección | Item | Estado | Commits |
|---|---|---|---|
| 3.B | Emisores (`issuers`) — CRUD end-to-end + emisor activo (is_default) + indicador en header | ✅ | `17b251d`, `adf1766` |
| 3.E | Carga del PGC español (326 cuentas estándar sembradas para la empresa demo) | ✅ | `4874855` (V4) |
| 3.G | Schema RETA cerrado: tabla `self_employed_contribution_brackets` + `self_employed_base_changes` + `self_employed_preonboarding` | ✅ schema | `4874855` (V4), `a0340af` (V5) |
| Infra | Catálogo de módulos (`module_catalog` + `company_modules`) — 14 categorías, 38 sub-módulos, activación por empresa | ✅ | `83ac83b` (V7) |
| Infra | TenantContext + `@RequiresModule` + control de acceso 403. **Fix de seguridad**: `CustomerRepository.findById/findAllActive` ahora filtran por `company_id` (era fuga entre empresas pre-existente) | ✅ | `7c48684` |
| Infra | Login real email/password con JWT (8h access + 30d refresh) + dos usuarios demo (`admin@benjagest.local` y `empresario@benjagest.local`) + empresa Marcos Construcciones SL | ✅ | `bf511d7` (backend), `02f75e5` (UI) (V8) |
| Infra | UI: pantalla email/password, AuthSession con Bearer automático en todos los ApiClients, selector de empresa, modo derivado de `company_type`, toggle eliminado | ✅ | `02f75e5` |

**Decisiones arquitectónicas tomadas el 2026-05-30** que afectan al alcance de varios items pendientes (memoria/`project_benjagest_architecture`):

1. Modular: árbol de 2 niveles (categoría + sub-módulo) con activación por empresa y dependencias auto-activadas.
2. Modo asesoría/empresario **se deriva** de `company_type`, no se elige en la UI.
3. PIN coexiste con email/password (PIN solo para fichaje kiosko y desbloqueo de pantalla).
4. Cifrado de columnas sensibles en aplicación (Jasypt), no en BD.
5. "Borrado" en facturas/nóminas/fichajes = anonimizado (retención legal).

---

---

## 1. Resumen ejecutivo

CONTENDO GESTIONES es una aplicación funcionalmente muy amplia: ~190 tablas en Supabase, 4 portales (admin, asesor, empleado, kiosko), e integraciones con AEAT (VeriFactu, SII, modelos fiscales), Google Calendar, DEHú, RETA y sistema de fichajes con cumplimiento legal RD 8/2019.

La migración a BENJAGEST está hoy en estado **esqueleto técnico**:

- Backend Java tiene solo `customer` (CRUD básico), `workspace` (auth PIN, dashboard, módulos) y `health`.
- UI JavaFX espeja esa estructura mínima.
- Base de datos: arquitectura definida en `docs/domain-model.md` (~50 entidades planificadas en inglés), pero todavía sin implementación en `develop`. Hay ramas `feature/database-bootstrap` y `feature/database-domain-model` en preparación.

El `legacy-schema-inventory.md` de Pablo ya cubre ~65 tablas legacy mapeadas al modelo destino. **Buen punto de partida**, pero el inventario está hecho sobre el dump del proyecto original y no captura módulos completos que existen hoy en Supabase y en el frontend de CONTENDO. Este documento se centra en **lo que falta por mapear o no está cubierto**.

**Estimación de la magnitud de las faltas:**
- Áreas ya contempladas en `domain-model.md` pero pendientes de implementación: **~80% del alcance documentado**.
- Áreas **no contempladas** en `domain-model.md` y detectadas en CONTENDO: **al menos 12 módulos funcionales adicionales** (ver sección 4).

---

## 2. Estado actual de cada lado

### 2.1 BENJAGEST migración (lo que hay hoy en `develop`)

**Backend (`backend-java/src/main/java/com/benjagest/backend/`):**

| Paquete | Contenido | Estado |
|---|---|---|
| `customer/` | `CustomerController`, `CustomerService`, `CustomerRepository`, request/response DTOs | CRUD básico |
| `workspace/` | `AuthController` (PIN login), `DashboardController`, `ModuleController`, `WorkspaceRepository`, `DemoCompany` | Demo funcional |
| `health/` | `HealthController` | OK |
| `config/` | `BenjagestProperties` | OK |

**UI (`ui/src/main/java/com/benjagest/ui/`):**

| Paquete | Contenido |
|---|---|
| Raíz | `BenjagestUiApplication`, `AppBrand` |
| `model/` | DTOs de cliente, dashboard, módulo, sesión, estado |
| `service/` | `BackendStatusService`, `CustomerApiClient`, `WorkspaceApiClient` |

**Base de datos:** todavía no hay migraciones Flyway aplicadas en `develop` para el modelo de dominio completo. Las ramas `feature/database-bootstrap` y `feature/database-domain-model` están preparando esto (revisar `V2__create_domain_model.sql`, `V3__seed_demo_and_pin_access.sql` en esas ramas).

### 2.2 CONTENDO GESTIONES (origen)

**Frontend (Next.js, `app180-frontend/app/`):**

- **Rutas públicas/onboarding:** `login`, `registro`, `onboarding`, `activar`, `setup`, `verificar`, `cambiar-password`, `qr`, `kiosko`, `factura-demo`, `ayuda-instalacion`
- **Rutas legales:** `(legal)`, `privacidad`, `terminos`, `aviso-legal`, `cumplimiento-legal`
- **Portal admin** (28 secciones) — ver sección 3.
- **Portal asesor** (27 secciones) — ver sección 3.A.
- **Portal empleado** (calendario, dashboard, nominas, notificaciones, trabajos, etc.)
- **Portal kiosko** (activar, setup) — modo dispositivo de fichaje.

**Backend (`backend/`):** Node.js con 60+ migraciones SQL, especificaciones M111/M130/M303, tests Jest, y 6 documentos de Verifactu (AEAT, firma, eventos, obligaciones de fabricante).

**Supabase (`APP360`):** ~190 tablas. Las clasifico en la sección 3.

---

## 3. Faltas por área funcional

Cada apartado lleva:
- **Origen (CONTENDO):** lo que existe hoy.
- **Destino planificado (BENJAGEST):** lo que dice `domain-model.md`.
- **Faltas:** qué hay que cerrar.
- **Prioridad sugerida:** alta / media / baja, desde la óptica de paridad funcional con CONTENDO.

---

### 3.A. Modelo multi-empresa y rol asesoría

**Origen:**
- Tablas: `empresa_180`, `users_180`, `asesorias_180`, `asesoria_usuarios_180`, `asesoria_clientes_180`, `asesoria_mensajes_180`, `notificaciones_asesor_180`, `documentos_asesoria_180`, `empresa_config_180`, `perfil_180`, `invite_180`.
- Portal `asesor/` completo con 27 rutas: `mis-clientes`, `mi-equipo`, `auditoria`, `certificados-clientes`, `exportar`, `laboral`, `nominas`, `partes-dia`, `planings`, `reta`, `sii`, `worklogs`, etc.
- Un asesor accede a múltiples clientes; un cliente puede tener uno o varios asesores; comunicación bidireccional con mensajes y documentos.

**Destino planificado:**
- `companies`, `user_accounts`, `company_memberships` (con rol), `company_settings`.
- `asesorias_180` se mapea a "companies/memberships" en `legacy-schema-inventory.md`, pero el workflow asesor↔cliente, mensajes, notificaciones específicas de asesor, documentos compartidos y portal asesor **no están descritos**.

**Faltas:**
1. **Workflow de asesoría no modelado.** No hay entidades equivalentes a `asesoria_clientes`, `asesoria_mensajes`, `documentos_asesoria`, `notificaciones_asesor`.
2. **Permisos finos por sub-recurso.** En CONTENDO un asesor puede tener `configuracion:write` sobre un cliente concreto (ver tabla `credenciales_externas_180`). No hay tabla equivalente prevista en el modelo destino.
3. **Portal asesor (UI dedicada).** El destino solo prevé un único cliente JavaFX. Habría que decidir si el asesor usa la misma UI con cambio de contexto o si necesita una vista distinta.
4. **Tabla de invitaciones (`invite_180`)**: no aparece en el modelo destino. Es relevante para alta de usuarios y vinculación cliente↔asesor.

**Prioridad:** **ALTA**. Es uno de los modos de uso principales de CONTENDO. Benjamin lo identificó como "modo asesoría puro y duro".

---

### 3.B. Facturación + VeriFactu

**Origen:**
- Tablas: `factura_180` (17 facturas), `lineafactura_180`, `concepto_180`, `iva_180`, `emisor_180`, `configuracionsistema_180`, `registroverifactu_180` (12 registros), `registroverifactueventos_180` (**1.814 eventos**), `envios_email_180`, `auditoria_180` (auditoría de facturación), `factura_recurrente_180`.
- Rutas: `admin/facturacion/{crear, editar, listado, dashboard, conceptos, configuracion, pagos, proformas, almacenamiento, auditoria, informes}`.
- Documentación legal completa: `VERIFACTU_AEAT_GUIA.md`, `VERIFACTU_EVENTOS_Y_EXPORTACION.md`, `VERIFACTU_FIRMA_DIGITAL.md`, `VERIFACTU_OBLIGACIONES_FABRICANTE.md`.

**Destino planificado:**
- `invoice_series`, `sales_invoices`, `sales_invoice_lines`, `sales_invoice_payments`, `recurring_invoices`, `verifactu_records`, `verifactu_events`.

**Faltas:**
1. **Series de numeración (`invoice_series`)**: el destino lo tiene, pero la lógica de proformas, rectificativas y anulaciones no está descrita.
2. **Cadena de eventos VeriFactu y firma digital**: el destino tiene `verifactu_records` y `verifactu_events`, pero falta describir cómo se reproduce el sistema de **hash encadenado**, **firma XML** y **anulación con vínculo** (`factura_rectificada_link`, columnas `verifactu_xml_firmado`, `verifactu_anulacion_columns`, `verifactu_retry_columns` — todas en migraciones de CONTENDO de abril 2026).
3. **Envío de facturas por email** (`envios_email_180`, `empresa_email_config_180`): no aparece en el destino.
4. **Configuración de emisor por empresa** (`emisor_180`): el destino tiene `issuers` plural pero falta el flujo de "emisor activo" y configuración avanzada (`configuracionsistema_180`).
5. **Auditoría específica de facturación** (`auditoria_180` separada de `audit_log_180`): el destino solo prevé `audit_events` genérica.
6. **Almacenamiento documental de facturas** (ruta `facturacion/almacenamiento`, tabla `storage_180`): no aparece en destino.
7. **Verifactu compliance: obligaciones de fabricante.** Hay documento dedicado en CONTENDO. Esto implica que el fabricante del software (vosotros) tiene obligaciones de auditoría propias — no solo el contribuyente.

**Prioridad:** **CRÍTICA**. Es funcionalidad legal obligatoria para facturar en España desde 2026.

---

### 3.C. SII (Suministro Inmediato de Información)

**Origen:**
- Tablas: `sii_config_180`, `sii_envios_180`, `sii_registros_180`.
- Ruta: `admin/.../sii` (también `asesor/sii`).
- Migraciones: `20260403_sii_framework.sql`, `20260403_sii_module.sql`.

**Destino planificado:**
- `sii_configurations`, `sii_submissions`.

**Faltas:**
1. Falta detalle del flujo SII (estados de envío, reintentos, reconciliación con AEAT). El destino solo lista dos tablas, CONTENDO tiene tres con un módulo framework completo.

**Prioridad:** **ALTA** si el cliente factura con SII obligatorio.

---

### 3.D. Compras, gastos y proveedores

**Origen:**
- Tablas: `purchases_180` (40 registros), `gastos_recurrentes_180`, `gastos_recurrentes_silenciados_180`.
- Rutas: `admin/gastos`, `admin/gastos/recurrentes`.

**Destino planificado:**
- `suppliers`, `purchase_invoices`, `purchase_invoice_lines`, `recurring_expenses`.

**Faltas:**
1. **Tabla `gastos_recurrentes_silenciados_180`**: control de gastos recurrentes desactivados/silenciados temporalmente. No aparece en destino.
2. **Falta separar proveedores como entidad**: en CONTENDO `purchases_180` parece tener proveedor inline; el destino normaliza esto bien con `suppliers`. ✅ Mejora del modelo destino.

**Prioridad:** MEDIA.

---

### 3.E. Contabilidad

**Origen:**
- Tablas: `ejercicios_contables_180`, `pgc_cuentas_180` (**668 cuentas — Plan General Contable español preargado**), `asientos_180` (74 asientos), `asiento_lineas_180` (181 líneas), `historial_cambios_asientos_180`.
- Rutas: `admin/contabilidad/{asientos, balance, cuentas, extracto, mayor, pyg}` (y duplicadas en `asesor/`).

**Destino planificado:**
- `accounting_accounts`, `fiscal_years`, `journal_entries`, `journal_entry_lines`, `fixed_assets`, `year_closings`.

**Faltas:**
1. **Carga inicial del Plan General Contable español**: 668 cuentas estándar. Hay que decidir si se semilla en migración Flyway, si se importa de fichero o si se ofrece como catálogo opcional por empresa.
2. **Historial de cambios de asientos** (`historial_cambios_asientos_180`): el destino tiene `audit_events` genérica pero la traza específica de asientos contables suele exigirse para auditoría fiscal.
3. **Vistas de reporting (balance, mayor, P&G, extracto, ejercicio)**: son consultas y reportes, no tablas. Hay que confirmar que se generan en backend Java a demanda.
4. **Cierre de ejercicio con aplicación de resultado** (`cierre_ejercicio_180`, `cierre_aplicacion_resultado`): el destino tiene `year_closings` pero faltaría documentar el proceso (cierre temporal vs definitivo, regularización, distribución resultado).
5. **Asientos revisados por usuario** (columna `revisado_usuario_asientos`): workflow de revisión contable, no aparece en destino.

**Prioridad:** **ALTA**.

---

### 3.F. Fiscal: modelos AEAT, renta, sociedades

**Origen:**
- Tablas: `fiscal_models_180`, `modelos_anuales_180`, `fiscal_reglas_180` (164 reglas — **se duplican cada enero al cambiar Hacienda los valores**), `fiscal_casilla_patterns_180` (69 patrones regex para extraer casillas de PDFs), `modelos_fiscales_180`, `renta_irpf_180`, `renta_historica_180`, `renta_datos_personales_180`, `impuesto_sociedades_180`, `aeat_consultas_180`, `aeat_discrepancias_180`, `aeat_campo_mapeo_180` (32 mapeos campo↔casilla), `epigrafes_iae_custom_180`, `calendario_fiscal_180` (18 eventos).
- Rutas: `admin/fiscal/{modelo100, modelo180, modelo190, modelo347, modelo390, reglas, renta, inmovilizado, cierre, configuracion}`.
- Migraciones: `20260403_modelos_anuales.sql`, `20260403_renta_sociedades.sql`, `20260411_aeat_consultas_discrepancias.sql`, `20260425_11_fiscal_models_aeat_csv.sql`, `20260425_15_regimenes_especiales_iva.sql`.

**Destino planificado:**
- `tax_models`, `tax_filings`, `aeat_consultations`, `aeat_discrepancies`.

**Faltas:**
1. **Reglas fiscales con histórico anual** (`fiscal_reglas_180`): mecanismo de duplicar reglas en enero ajustando valores que cambia Hacienda. **No contemplado** en destino.
2. **Casillas de modelos como patrones regex** (`fiscal_casilla_patterns_180`): retroalimentación con aciertos/fallos. Mecanismo único, no contemplado.
3. **Mapeo de campos AEAT** (`aeat_campo_mapeo_180`): 32 mapeos. No contemplado.
4. **Modelos específicos**: 100, 180, 190, 347, 390, renta, sociedades. El destino tiene una tabla genérica `tax_models`/`tax_filings`, falta documentar si los modelos específicos se modelan como tipos o como subtablas.
5. **Calendario fiscal** (`calendario_fiscal_180`): vencimientos fiscales. No contemplado.
6. **Epígrafes IAE personalizados** (`epigrafes_iae_custom_180`): personalización por empresa.
7. **Régimen especial de IVA** (migración `20260425_15`), **prorrata IVA** (`20260425_10`), **criterio de caja** (`20260425_13`): mecanismos fiscales españoles específicos. No contemplados.
8. **Inmovilizado** (`inmovilizado_180`, ruta `admin/fiscal/inmovilizado`): el destino tiene `fixed_assets` ✅ pero falta el cálculo de amortizaciones y vinculación con asientos.

**Prioridad:** **CRÍTICA** (es el corazón del valor de una gestoría).

---

### 3.G. RETA (autónomos)

**Origen:**
- Tablas: `reta_tramos_180` (30 tramos), `reta_autonomo_perfil_180`, `reta_estimaciones_180` (9), `reta_cambios_base_180`, `reta_eventos_180`, `reta_pre_onboarding_180`, `reta_alertas_180` (10).
- Rutas: `asesor/reta/{clientes, pre-onboarding}`.

**Destino planificado:**
- `self_employed_profiles`, `self_employed_estimates`, `self_employed_events`, `self_employed_alerts`.

**Faltas:**
1. **Tramos de cotización RETA** (`reta_tramos_180`): tabla maestra con 30 tramos. **No contemplado** explícitamente, aunque podría caer dentro de `self_employed_estimates`.
2. **Cambios de base de cotización** (`reta_cambios_base_180`): trazabilidad de cambios de tramo del autónomo. No contemplado.
3. **Pre-onboarding** (`reta_pre_onboarding_180`): flujo previo al alta como cliente RETA. No contemplado.

**Prioridad:** ALTA si la gestoría trabaja con autónomos.

---

### 3.H. Empleados, contratos, nóminas, bajas

**Origen:**
- Tablas: `employees_180` (2 empleados), `contratos_180`, `nominas_180`, `nomina_entregas_180`, `nomina_incidencias_180`, `bajas_laborales_180`, `cotizaciones_ss_180`, `centros_trabajo_180`.
- Rutas: `admin/empleados/[id]/`, `admin/empleados/nuevo`, `admin/empleados/reportes`, `admin/nominas/{coste-empresa, entregas}`.

**Destino planificado:**
- `employees`, `employment_contracts`, `payrolls`, `medical_leaves`, `social_security_contributions`.

**Faltas:**
1. **Entrega de nóminas** (`nomina_entregas_180`): registro de entregas de nómina (firma del trabajador, fecha, vía). No contemplado.
2. **Incidencias de nómina** (`nomina_incidencias_180`): retrasos, errores, retenciones especiales. No contemplado.
3. **Centros de trabajo** (`centros_trabajo_180`): empresa puede tener varios centros, relevante para fichajes y SS. No contemplado.
4. **Coste empresa por empleado** (ruta `admin/nominas/coste-empresa`): vista/reporte. Falta calcular en backend.

**Prioridad:** ALTA.

---

### 3.I. Fichajes (CON cumplimiento legal RD 8/2019)

**Origen:**
- Tablas: `fichajes_180`, `fichaje_correcciones_180` (*correcciones solo mediante nuevo apunte vinculado, RD 8/2019*), `fichaje_verificaciones_180` (*códigos CSV para verificación pública, art. 35.8 RD 8/2019*), `ausencias_180`, `ausencias_adjuntos_180`, `jornadas_180`, `turnos_180`, `turno_bloques_180`, `plantillas_jornada_180`, `plantilla_dias_180`, `plantilla_bloques_180`, `empleado_plantillas_180`, `plantilla_excepciones_180`, `plantilla_excepcion_bloques_180`, `asignaciones_plantilla_jornada_180`.
- Rutas: `admin/fichajes/{correcciones, offline-pendientes, sospechosos}`, `admin/jornadas/`, `admin/turnos/`, `admin/planings`.
- Migraciones: `006_extend_work_logs.sql`, `20260218_extend_work_logs_v2.sql`.

**Destino planificado:**
- `time_clock_events`, `daily_work_reports`, `absences`.

**Faltas:**
1. **Cumplimiento legal RD 8/2019**: las tablas `fichaje_correcciones_180` y `fichaje_verificaciones_180` están marcadas explícitamente con la normativa. **No contempladas** en destino. Esto es **obligación legal**: no se pueden modificar fichajes, solo añadir apuntes correctores vinculados.
2. **Plantillas de jornada complejas** (`plantillas_jornada_180`, `plantilla_dias_180`, `plantilla_bloques_180`, `plantilla_excepciones_180`): sistema de plantillas reutilizables con días tipo, bloques horarios y excepciones. No contemplado.
3. **Asignación de plantillas a empleados** (`asignaciones_plantilla_jornada_180`, `empleado_plantillas_180`): no contemplado.
4. **Turnos** (`turnos_180`, `turno_bloques_180`): no contemplado.
5. **Plannings** (ruta `admin/planings`): no contemplado.
6. **Fichajes sospechosos** (ruta `admin/fichajes/sospechosos`): detección de patrones extraños. No contemplado.
7. **Sincronización offline** (`offline_sync_batches_180`, ruta `admin/fichajes/offline-pendientes`): kioskos sin red sincronizan en lote. **Crítico** para uso real.

**Prioridad:** **CRÍTICA**. La parte de RD 8/2019 es obligación legal.

---

### 3.J. Calendario, festivos e integración Google

**Origen:**
- Tablas: `festivos_es_180` (58 festivos España), `calendario_empresa_180` (38), `empresa_calendar_config_180`, `calendar_event_mapping_180` (38 mapeos), `calendar_sync_log_180` (68 syncs), `calendar_webhook_180`, `calendario_importacion_180`, `calendario_importacion_item_180` (24).
- Rutas: `admin/calendario`, `admin/configuracion/calendario`, `asesor/calendario`, `empleado/calendario`.

**Destino planificado:**
- `calendar_integrations`, `calendar_events`, `calendar_sync_logs`.

**Faltas:**
1. **Festivos nacionales/CCAA pre-cargados** (`festivos_es_180`): seed obligatorio.
2. **Calendario laboral por empresa** (`calendario_empresa_180`): mezcla festivos + cierres propios. No contemplado nominalmente.
3. **Integración Google Calendar bidireccional**: mapeo (`calendar_event_mapping_180`), webhooks (`calendar_webhook_180`), histórico de sync (`calendar_sync_log_180`). El destino menciona "calendar_integrations" pero falta detalle del flujo bidireccional.
4. **Importación masiva de calendarios** (`calendario_importacion_180`): de fichero o de otro sistema.

**Prioridad:** MEDIA. Crítica si los clientes ya dependen de Google Calendar.

---

### 3.K. Kiosko (modo dispositivo de fichaje)

**Origen:**
- Tablas: `kiosk_devices_180`, `kiosk_empleados_180`, `kiosk_activation_tokens_180`, `qr_sessions_180` (**840 sesiones**), `otp_codes_180`, `offline_sync_batches_180`, `employee_devices_180`.
- Rutas: `kiosko/activar`, `kiosko/setup`, `admin/kioscos`.

**Destino planificado:**
- Nada. **No contemplado** en `domain-model.md`.

**Faltas:**
- **Todo el módulo kiosko es un hueco**. Es un caso de uso distinto al de empleado/admin: un dispositivo compartido en oficina donde los empleados fichan con OTP/QR. Requiere su propio flujo de activación, vinculación a empresa, modo offline y sincronización por lotes.

**Prioridad:** ALTA si los clientes lo usan en producción. Cuestión: ¿la UI JavaFX cubre este caso de uso o necesita una app aparte?

---

### 3.L. Portal empleado

**Origen:**
- Rutas: `empleado/{calendario, dashboard, nominas, notificaciones, trabajos, drawer, instalar, debug, diagnostico}`.
- Tablas: `employee_devices_180`, `employee_daily_report_180`, `empleado_clientes_180`, `empleado_plantillas_180`.

**Destino planificado:**
- Nada explícito.

**Faltas:**
- El portal empleado es un **rol de usuario** distinto al admin. Tiene su propia vista de calendario, nóminas para descargar, notificaciones, lista de trabajos asignados.
- La UI JavaFX hoy solo prevé un cliente para admin/gestor. Hay que decidir: ¿la UI cambia de modo según rol o se separa en otra aplicación?

**Prioridad:** MEDIA. Bloqueante si los empleados acceden hoy a CONTENDO.

---

### 3.M. Documentos, certificados digitales e integraciones externas

**Origen:**
- Tablas: `certificados_digitales_180`, `certificados_uso_log_180`, `certificados_empresa_180`, `credenciales_externas_180` (**cifradas por empresa**, para DEHú, SS RED, SILTRA), `notificaciones_dehu_180`, `documentos_180`, `documentos_asesoria_180`, `documentos_postulacion`, `storage_180`.
- Rutas: `admin/.../configuracion`, `asesor/certificados`, `asesor/certificados-clientes`.

**Destino planificado:**
- `digital_certificates`, `document_files`.

**Faltas:**
1. **Credenciales externas cifradas** (`credenciales_externas_180`): DEHú, SS RED, SILTRA — cada empresa configura las suyas, asesor puede gestionarlas con permiso. **No contemplado**.
2. **Log de uso de certificados** (`certificados_uso_log_180`): trazabilidad obligatoria. No contemplado.
3. **Notificaciones DEHú** (`notificaciones_dehu_180`): recepción automatizada de notificaciones administrativas. No contemplado.
4. **Storage propio** (`storage_180`): el destino no menciona estrategia de almacenamiento de ficheros (¿filesystem? ¿S3-compat? ¿base de datos?).

**Prioridad:** ALTA. La integración con DEHú/SS/SILTRA es valor diferencial.

---

### 3.N. Notificaciones y alertas

**Origen:**
- Tablas: `notificaciones_180`, `notificaciones_asesor_180`, `notificaciones_dehu_180`, `security_alerts_180` (40), `reta_alertas_180`.
- Ruta: `empleado/notificaciones`.

**Destino planificado:**
- `notifications` (genérica).

**Faltas:**
1. **Tipología de notificaciones** (alertas legales/deadlines/seguridad/DEHú/asesor): el destino simplifica con una sola tabla. Habría que decidir si añadir tipo/canal o si cubre todo.
2. **Alertas de seguridad** (`security_alerts_180`): intentos de login, accesos sospechosos. No contemplado.

**Prioridad:** MEDIA.

---

### 3.O. Auditoría

**Origen:**
- Tablas: `audit_log_180` (31), `auditoria_180` (31 — *auditoría específica del módulo de facturación*), `historial_cambios_asientos_180`, `calendar_sync_log_180`, `verifactu_events`, `certificados_uso_log_180`.

**Destino planificado:**
- `audit_events` (única, genérica).

**Faltas:**
- Decisión arquitectónica: **¿una sola tabla de auditoría o varias específicas?** CONTENDO tiene varias. El destino simplifica. Si se simplifica, hay que asegurarse de cumplir requisitos de Verifactu (que exige auditoría específica) y RD 8/2019 (fichajes).

**Prioridad:** ALTA (cumplimiento).

---

### 3.P. Configuración (multinivel)

**Origen:**
- Tablas: `app_config_180` (8 — *configuración global editable por el fabricante*), `empresa_config_180`, `empresa_email_config_180`, `empresa_calendar_config_180`, `configuracionsistema_180`, `parte_configuraciones_180`, `cons_app_settings`.
- Rutas: `admin/app-config`, `admin/configuracion`, `admin/fabricante`, `admin/facturacion/configuracion`.

**Destino planificado:**
- `company_settings`.

**Faltas:**
1. **Configuración global (fabricante)** vs **configuración por empresa**: CONTENDO separa estos dos niveles. El destino solo prevé `company_settings`. Falta nivel global.
2. **Configuraciones específicas por módulo** (email, calendario, partes, facturación): el destino podría unificarlas en `company_settings` con campos JSON, o crear tablas específicas.

**Prioridad:** MEDIA.

---

## 4. Áreas funcionales no contempladas en `domain-model.md`

Estas son áreas detectadas en CONTENDO que **no aparecen en absoluto** en el modelo destino actual. Cada una requiere una decisión de Pablo: **¿entra en alcance, sale del alcance, o se aplaza?**

| # | Área | Tablas representativas | Rutas | Comentario |
|---|---|---|---|---|
| 1 | **Asesoría (workflow completo)** | `asesoria_*_180`, `notificaciones_asesor_180`, `documentos_asesoria_180`, `invite_180` | `asesor/*` (27 rutas) | Detallado en 3.A. |
| 2 | **Kiosko fichaje** | `kiosk_*_180`, `qr_sessions_180`, `otp_codes_180`, `offline_sync_batches_180` | `kiosko/*`, `admin/kioscos` | Detallado en 3.K. |
| 3 | **Portal empleado** | `employee_*_180`, `empleado_*_180` | `empleado/*` | Detallado en 3.L. |
| 4 | **Módulo construcción (cons_\*)** | 50+ tablas con prefijo `cons_` (`cons_projects`, `cons_workers`, `cons_subcontractors`, `cons_materials`, `cons_budgets`, `cons_chapters`, `cons_certifications`, `cons_measurements`, `cons_work_logs`, `cons_equipment_catalog`, `cons_mailbox_*`, `cons_ai_*`, etc.) | (No vistos en frontend `app180-frontend`. Posiblemente otra app o módulo aparte.) | **Decisión clave**: ¿este módulo se migra a BENJAGEST o queda fuera? Tiene IA, presupuestos, mediciones, certificaciones — es prácticamente otra aplicación. |
| 5 | **FERRAPP (proyectos/etiquetas)** | `ferrapp_proyectos`, `ferrapp_etiquetas_custom` | No localizado | Sub-app/integración externa. Confirmar con Pablo. |
| 6 | **MCP / IA con quotas** | `mcp_ai_consumption`, `mcp_ai_quotas`, `mcp_ai_pricing`, `mcp_ai_provider_credits`, `mcp_ai_user_quotas`, `contendo_memory_180`, `conocimiento_180` | `admin/mcp` | Sistema de IA integrado con control de costes. No contemplado. |
| 7 | **Sugerencias** | `sugerencias_180` | `admin/sugerencias` | Feedback de usuarios. |
| 8 | **Planes y suscripciones (SaaS)** | `plans_180` | — | Modelo de negocio: ¿BENJAGEST es SaaS o on-premise? Decisión estratégica. |
| 9 | **BOE analysis** | `boe_analysis_180` | — | Análisis automático de novedades fiscales del BOE. |
| 10 | **Bank transactions** | `bank_transactions_180` | — | Conciliación bancaria. No contemplado. |
| 11 | **Páginas legales públicas** | — | `(legal)`, `privacidad`, `terminos`, `aviso-legal`, `cumplimiento-legal` | Si JavaFX es solo escritorio, esto no aplica. Si hay web pública, sí. |
| 12 | **Onboarding y flujo de alta** | `invite_180`, `otp_codes_180`, `qr_sessions_180` | `onboarding`, `registro`, `activar`, `setup`, `verificar`, `ayuda-instalacion`, `cambiar-password`, `factura-demo` | Flujo de captación y onboarding de nuevos clientes y usuarios. No contemplado. |

---

## 5. Recomendación de orden (sobre el plan actual de Pablo)

`migration-plan.md` ya define fases técnicas (1 base, 2 backend, 3 UI, 3b BD, 4 empaquetado, 5 limpieza). El **orden funcional** sugerido para cerrar paridad con CONTENDO, sin contradecir su plan:

1. **Cerrar modelo de dominio en MariaDB** con lo de `domain-model.md` + ampliaciones de la sección 4 que Pablo decida aceptar.
2. **Clientes + Catálogo + Tarifas + Series + Facturación + VeriFactu** (orden de `legacy-schema-inventory.md`, fase ya planteada).
3. **Auditoría + Certificados digitales + Credenciales externas + Storage**: imprescindible para el siguiente paso.
4. **SII y modelos fiscales AEAT** (modelos 100/180/190/347/390 + renta + sociedades + cierre + inmovilizado).
5. **Compras + gastos recurrentes + bank reconciliation**.
6. **Contabilidad + cierre de ejercicio + PGC seed**.
7. **Empleados + contratos + nóminas + cotizaciones SS + bajas laborales**.
8. **Fichajes (con cumplimiento RD 8/2019) + jornadas + turnos + plannings + ausencias**.
9. **Calendario + festivos + integración Google**.
10. **RETA completo (perfil + tramos + estimaciones + alertas)**.
11. **Asesoría (workflow + portal asesor)**.
12. **Notificaciones + alertas de seguridad + DEHú**.
13. **Portal empleado** (decidir arquitectura UI).
14. **Kiosko de fichaje** (decidir arquitectura).
15. **Módulos a decidir si entran en alcance**: construcción (cons_*), MCP/IA, BOE analysis, FERRAPP, planes SaaS, páginas legales públicas.

---

## 6. Dudas concretas para Pablo

Estas son las preguntas cuya respuesta cambia mucho el alcance:

1. **¿Entra en alcance el módulo `cons_*` (construcción)?** Es prácticamente otra app. Si entra, ¿se integra dentro de BENJAGEST o como módulo Maven adicional?
2. **¿Habrá portal asesor y portal empleado en JavaFX, o solo el portal admin?** Si JavaFX cubre solo admin, ¿qué pasa con empleados/asesores que hoy usan la web de CONTENDO?
3. **¿Se mantiene el kiosko de fichaje?** Si sí, ¿es app Android/iOS aparte, o se reutiliza JavaFX en modo kiosko?
4. **¿BENJAGEST es SaaS (multi-tenant en cloud) u on-premise (una instalación por gestoría)?** Cambia drásticamente la importancia de `plans_180`, RLS, aislamiento.
5. **¿Se migran los datos históricos de Supabase a MariaDB, o se arranca con BD vacía?** Si se migran, los UUIDs de PostgreSQL pasan a `CHAR(36)` según `legacy-schema-inventory.md`.
6. **¿Se mantiene la integración con Google Calendar?** Es trabajo bidireccional con webhooks.
7. **¿La firma digital de VeriFactu se hace en backend Java o se delega a una librería externa?** Hay un documento `VERIFACTU_FIRMA_DIGITAL.md` en CONTENDO que conviene revisar.
8. **¿La IA (MCP) sigue siendo parte del producto?** Si sí, ¿se migra el sistema de quotas y consumo?
9. **¿Quién es el "fabricante" del software a efectos VeriFactu y cómo se cumplen sus obligaciones?** Implica trazabilidad obligatoria propia.
10. **¿Las páginas legales y de onboarding (`registro`, `activar`, etc.) tienen equivalente en JavaFX, o se queda solo el flujo de PIN login que ya existe?**

---

## 7. Notas técnicas sueltas

- **Una tabla en Supabase sin RLS** (advisor de Supabase): `public.schema_migrations_180`. Es la tabla de control de migraciones, podría ser normal, pero conviene confirmar con Pablo.
- **El archivo `BUILD` en la raíz del repo** parece ser un log de una ejecución fallida de Maven. No es código del proyecto.
- **Las ramas `feature/database-bootstrap` y `feature/database-domain-model`** todavía no están fusionadas a `develop`. Conviene revisar qué traen antes de planificar más, porque pueden adelantar parte del trabajo listado aquí.

---

## 11. Hallazgos adicionales — Claude 2026-05-31

Pasada hecha cruzando el inventario completo de Supabase (192 tablas, todas las del schema `public` al 31/05/2026) con las áreas que las secciones 3 y 4 del documento original cubren. Lista lo que **no estaba contemplado** o estaba **infra-detallado**.

### 11.A. `clients_180` no es "clientes" — son OBRAS / CENTROS DE TRABAJO con geolocalización

Esto es el hallazgo más importante. `clients_180` tiene **45 columnas** que cuentan una historia muy distinta a "lista de clientes":

| Concepto | Columnas |
|---|---|
| Identidad fiscal | `razon_social`, `nif_cif`, `tipo_fiscal`, `pais`, `provincia`, `municipio`, `codigo_postal`, `direccion_fiscal` |
| Contacto facturación | `email_factura`, `telefono_factura`, `persona_contacto` |
| **Geolocalización para fichaje** | `lat`, `lng`, `radio_m`, `requiere_geo`, `geo_policy` |
| Configuración de facturación | `iva_defecto`, `exento_iva`, `forma_pago`, `iban`, `aplicar_retencion`, `retencion_tipo` |
| Tarifas por defecto | `precio_default_hora`, `precio_default_dia`, `precio_default_mes`, `precio_default_trabajo` |
| Modo de trabajo | `modo_defecto` (hora / día / mes / trabajo) |
| Vinculación | `vinculado_empresa_id` (link a `empresa_180`) |
| Período | `fecha_inicio`, `fecha_fin` |

**Implicación**: el "cliente" en CONTENDO es a la vez:
1. Un cliente fiscal (datos para facturar).
2. **Un centro de trabajo con coordenadas GPS y radio de tolerancia**, contra el que se verifica que el empleado realmente está allí al fichar.
3. Una "obra" o "proyecto" con fechas de inicio y fin.
4. Una configuración de tarifa y modo de cobro.

El destino BENJAGEST tiene `customers` (datos fiscales), pero **no tiene nada de geolocalización para fichajes**, ni el concepto de "obra/centro" con coordenadas. Esto es un hueco grande de cara a:
- Fichajes con verificación GPS (decision 5 del RD 8/2019 no lo exige pero los clientes lo piden).
- Cumplimiento por obra (muy común en construcción).
- Modos de facturación por defecto del cliente.

**Prioridad: ALTA si los clientes de CONTENDO usan fichaje con geolocalización.**

### 11.B. Work logs con facturación embebida (`work_logs_180`, 37 filas)

A diferencia del destino BENJAGEST (`work_logs` separado de billing), aquí cada parte de trabajo lleva su **propio estado de cobro y vínculo a factura**:

| Campo | Para qué |
|---|---|
| `valor` | Importe que vale ese parte de trabajo |
| `pagado` | Importe ya cobrado |
| `estado_pago` | Estado individual del cobro |
| `tipo_facturacion` | hora / día / mes / trabajo |
| `factura_id` | A qué factura ha ido (NULL si aún no facturado) |
| `parte_config_id` | Configuración del parte |
| `metodo_pago_directo` | Si se cobró directo sin pasar por factura |
| `pagado_at` | Cuándo se cobró |
| `campos_extra` (JSONB) | Flexibilidad para campos específicos por cliente |

Esto cambia el modelo: en CONTENDO **cada trabajo es facturable individualmente** y puede cobrarse fuera de una factura. En BENJAGEST hoy work_logs no tiene billing.

### 11.C. `partes_dia_180` (37 filas) — workflow de validación admin

El empleado crea un parte por día (empleado + cliente + fecha + horas_trabajadas + resumen). Luego el admin lo valida (`validado`, `validado_at`, `nota_admin`) antes de que entre en la facturación. **Workflow no contemplado** en BENJAGEST.

### 11.D. `titulares_empresa_180` (2 filas) — governance de empresa

Tabla de titulares/administradores de la empresa para fines fiscales y SS:

| Campo | Para qué |
|---|---|
| `employee_id` | Link al empleado (algunos titulares son trabajadores) |
| `nombre`, `nif` | Identidad |
| `porcentaje_participacion` | Para Sociedades (modelo 200) |
| `es_administrador` | Booleano legal |
| `regimen_ss`, `fecha_alta_ss`, `fecha_baja_ss` | Para informes laborales |

**No contemplado** en BENJAGEST. Imprescindible para:
- Modelo 200 (Impuesto de Sociedades): porcentajes de participación.
- Informe de SS: régimen de los administradores.
- Cumplimiento legal de "representantes" de la sociedad.

**Prioridad: ALTA** cuando se aborde la Fase 6 (fiscal) y 8 (laboral).

### 11.E. `payment_allocations_180` (30 filas) — un pago a varios destinos

Tabla intermedia: un pago concreto se distribuye en partes hacia múltiples destinos. Columnas: `payment_id`, **`work_log_id`** o **`invoice_id`** o **`factura_id`**, `importe`.

Un pago de 500 € puede repartirse así:
- 200 € → factura 1
- 200 € → factura 2
- 100 € → trabajo suelto (sin factura)

El destino tiene `sales_invoice_payments` pero **no permite multi-allocation** desde un mismo pago. Hueco no documentado.

### 11.F. Tablas legacy pre-CONTENDO (~17 tablas sin sufijo `_180`)

Restos del proyecto anterior (probablemente FERRAPP). Identificables porque los `id` son `bigint`/`integer` en vez de `uuid`:

| Tabla | Filas | Concepto |
|---|---|---|
| `categorias` | 51 | Categorías de trabajos |
| `categorias_pendientes`, `categorias_solicitud`, `categorias_usuario` | 0 | Solicitud de nuevas categorías |
| `clientes` | 10 | Clientes en esquema antiguo |
| `documentos` | 0 | Documentos antiguos |
| `materiales` | 2 | Materiales |
| `mensajes` | 0 | Mensajes |
| `notificaciones` | 0 | Notificaciones antiguas |
| `pagos` | 39 | Pagos en esquema antiguo (id bigint) |
| `postulaciones` | 0 | Postulaciones de trabajadores |
| `resumen_mensual` | 14 | Cache mensual de totales |
| `reseñas` | 0 | Reseñas |
| `solicitudes` | 0 | Solicitudes de trabajo |
| `trabajos` | **248** | Trabajos en esquema antiguo |
| `usuarios` | 2 | Usuarios antiguos |
| `zonas_trabajo` | 0 | Zonas geográficas |

**Recomendación**: **fuera de scope** salvo que Pablo confirme que el dato actual de `trabajos` (248 filas) o `pagos` (39 filas) tiene valor histórico recuperable. Si lo tiene, migrar como una sola tabla `legacy_imports` y luego reconciliar.

### 11.G. `pj_pagos`, `pj_transacciones` — sistema P2P (probablemente FERRAPP)

Pagos entre usuarios: `solicitud_id`, `pagador_id`, `receptor_id`, `monto`, `moneda`, `estado`, `metodo_pago`. Tablas vacías hoy. Apuntan al ecosistema de "solicitudes/postulaciones" del FERRAPP original. **Fuera de scope** salvo decisión expresa.

### 11.H. Detalle del módulo construcción (`cons_*`, 50 tablas)

El doc lo mencionaba como bloque. Lo desgloso para que Pablo decida con más finura:

**Organización / multi-empresa de obras** (~6 tablas): `cons_organizations`, `cons_organization_members`, `cons_branch_links`, `cons_branch_invitations`, `cons_branch_project_visibility`, `cons_users`.

**Proyectos** (~3): `cons_projects`, `cons_project_files`, `cons_project_expenses`.

**Planos** (~3): `cons_plan_annotations`, `cons_plan_calibrations`, `cons_plan_extractions`. Detección automática vía OCR/IA.

**Presupuestos** (~8): `cons_budgets`, `cons_budget_items`, `cons_budget_item_templates`, `cons_budget_chapter_templates`, `cons_budget_comparison_groups`, `cons_budget_comparison_group_items`, `cons_budget_comparison_exclusions`, `cons_saved_partidas` (627 filas).

**Capítulos / librería** (~2): `cons_chapters`, `cons_library_chapters`.

**Mediciones** (~1): `cons_measurements`.

**Certificaciones** (~3): `cons_certifications`, `cons_certification_items`, `cons_certification_work_log_links`.

**Materiales + proveedores** (~6): `cons_materials`, `cons_material_categories`, `cons_material_price_history`, `cons_suppliers`, `cons_supplier_materials`, `cons_price_breakdown`.

**Equipos** (~2): `cons_equipment_catalog`, `cons_equipment_materials`.

**Subcontratistas** (~2): `cons_subcontractors`, `cons_subcontractor_documents`.

**Trabajadores** (~1): `cons_workers`.

**Partes de trabajo** (~5): `cons_work_logs`, `cons_work_log_budget_links`, `cons_work_log_equipment`, `cons_work_log_labor`, `cons_work_log_materials`.

**Buzón interno** (~4): `cons_mailbox_messages`, `cons_mailbox_attachments`, `cons_mailbox_contacts`, `cons_mailbox_shared_access`.

**IA específica** (~3): `cons_ai_consumption`, `cons_ai_pricing`, `cons_ai_provider_credits`.

**Notificaciones** (~1): `cons_notifications`.

**Settings** (~1): `cons_app_settings` (143 filas).

Es **un producto entero dentro del producto**. Decisión arquitectónica para Pablo: ¿módulo separado de BENJAGEST con su propio backend Spring + UI? ¿Aplicación independiente que comparte autenticación? ¿Fuera de scope?

### 11.I. Otras tablas no contempladas y de prioridad baja

| Tabla | Filas | Para qué |
|---|---|---|
| `client_fiscal_data_180` | 6 | Datos fiscales por cliente (probablemente separable de clients_180) |
| `client_tariffs_180` | 3 | Tarifas por cliente (overlap con destino `customer_tariffs`) |
| `cliente_seq_180` | 3 | Secuencia de numeración por cliente |
| `time_logs_180` | 0 | Time tracking simple (empleado, proyecto, inicio, fin, duración). Vacía. Posible candidato a reemplazo futuro. |
| `work_items_180` | 3 | Items de trabajo facturables. Overlap con destino `work_items`. |
| `invoices_180` | 0 | Tabla nueva vacía. Posible reemplazo planificado de `factura_180`. |
| `fiscal_rules_180` | 0 | Tabla nueva vacía. Posible reemplazo de `fiscal_reglas_180`. |
| `modelos_fiscales_180` | 0 | Vacía. Posible candidata a deprecación. |
| `cierre_ejercicio_180` | 2 | Cierres anuales. Mencionada de paso en 3.E. |
| `cierre_ejercicio_log_180` | 0 | Log de cierres. |
| `test_reports_180` | 1 | Sin uso aparente. |

### 11.J. Resumen de prioridades adicionales (mi visión)

Ordenadas por impacto en paridad funcional:

1. **CRÍTICA** — Geolocalización en clients/obras (11.A) para fichaje. Sin esto el módulo de fichajes no es competitivo.
2. **ALTA** — Titulares de empresa (11.D) para modelo 200 y SS.
3. **ALTA** — Workflow de validación de partes (11.C).
4. **MEDIA-ALTA** — Multi-allocation de pagos (11.E).
5. **MEDIA** — Work logs con billing embebido (11.B) si la decisión es replicar el modelo de CONTENDO; **BAJA** si el destino prefiere separar billing como entidad propia.
6. **BAJA-MEDIA** — Detalle de `cons_*` (11.H) si y solo si construcción entra en alcance.
7. **BAJA** — Legacy pre-`_180` (11.F), `pj_*` (11.G), y resto de tablas vacías (11.I).

---

*Sección 11 añadida por Claude el 2026-05-31 tras pasada de cruce con inventario completo de Supabase. La sección 0 al inicio refleja el avance desde 27/05.*
