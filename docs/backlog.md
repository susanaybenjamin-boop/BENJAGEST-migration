# Backlog operativo BENJAGEST

> Lista única ordenada de mayor a menor importancia con TODO lo que hay que hacer en BENJAGEST.
> Se va tachando conforme cada item se crea, prueba, commitea y mergea a `develop`.
>
> **Última revisión:** 2026-06-10 tarde — tras prueba en vivo de Benjamin y refinamiento. **18 slices cerrados en total en el día**: los 11 de la sesión autónoma de la mañana (FIX-T-LIMIT, CAL-A/B/C/D, EMP-1..4, SUG, PERFIL+LOCK, CLI, PORT-2 skeleton, TC-CAL) + 7 de la tarde tras feedback en vivo:
>
> - **V87 cleanup**: DROP `user_settings.language/ai_enabled/avatar_path/workday_template`, DELETE module_catalog para `profile`/`employee-portal`/`shifts` (retirados del sidebar tras prueba), ADD `companies.logo_path`, ADD `user_accounts.session_pin_hash`.
> - **UI cleanup**: KNOWN_VIEWS sin profile/employee-portal/shifts. El módulo "Mi perfil" se elimina entero (decisión Benjamin: idioma ya en header, AI no se hará, avatar = logo empresa, LOCK pasa a Configuración).
> - **CAL-A v2**: matching tolerante por palabra clave ("festiv"/"ajust"/"cierre"/"cerrad") con stripDiacritics para que los eventos volcados de PDF de calendario laboral con texto libre tomen color.
> - **PORT-4 CLI v2**: editor cliente revertido a UNA sección con 9 campos (lo nuevo seguía sin conectar; confundía). Los campos extendidos siguen en BD (V85) — el PUT los preserva.
> - **PORT-4 LOGO**: backend CompanyLogoService con auto-resize a 400px ancho + auto-compress a <2MB (PNG → fallback JPEG quality 0.8→0.4), guarda en `{storageRoot}/{companyId}/_brand/logo.{ext}`. Endpoint /api/settings/company/logo (POST multipart, GET bytes, DELETE). UI sección "Logo de empresa" en Configuración → Empresa con ImageView preview + FileChooser + botón eliminar. Aplicación en PDF factura queda como slice aparte (requiere cambiar firma + 3 callers).
> - **PORT-4 SESSION**: pestaña "Sesión" en Configuración con timeout 0-120 min + PIN de sesión (separado del PIN de vinculación de device_tokens; vive en user_accounts.session_pin_hash) + combo 4 salvapantallas (clock/logo/dark/carousel). SessionPinService con BCrypt + validación PIN 4-8 dígitos numéricos. Lock screen refactorizado: verifica contra /api/settings/session/pin/verify (NO contra pinLogin device), fondo cambia según salvapantallas elegido.
> - **PORT-2 reactivado**: V88 reinserta módulo "shifts" en catalog + activa en empresas con labor activo. Sub-pestaña "Partes/jornadas" dentro de Labor (no en sidebar). UI con DatePicker + tabla + diálogo crear parte manual.
> - **Centros de trabajo (port CONTENDO `centros_trabajo_180`)**: V89 tabla work_centers (geolocalización opcional lat/lng/radio_m + geo_policy CHECK IN none/info/soft/strict) + ALTER employees ADD work_center_id. Sub-pestaña "Centros" dentro de Labor con CRUD completo.
> - **VG-FULL-SCAN parcial**: comparators NUMERIC_STRING / ISO_DATE aplicados a las 5 omisiones de mayor impacto (RetaBaseChange + ContractTemplate.salary). 25 restantes documentadas pero pendientes (baja visibilidad o módulos retirados).
>
> Verificación final por agente Explore independiente: BUILD SUCCESS + tenant isolation OK en todos los services nuevos + KNOWN_VIEWS correcto + lock screen apuntando al endpoint correcto.
>
> **Última revisión:** 2026-06-10 mañana (autonomía). 11 slices: FIX-T-LIMIT (extracción de keys calendar.* a tCalendarEn/Es para desbloquear compile que estaba roto pre-sesión), PORT-5 CAL-A (badge color por event_type), PORT-5 CAL-B (botón "Quitar de Agenda" con DELETE inverso al volcado), PORT-5 CAL-C (botón "Cargar festivos nacionales" con Easter algoritmo Meeus/Jones/Butcher), PORT-1 EMP-1..4 (módulo Portal del empleado con 4 tabs: Mi calendario, Mis nóminas, Mis notificaciones, Mis trabajos), PORT-3 SUG (módulo Sugerencias con CRUD), PORT-3 PERFIL+LOCK (preferencias usuario + auto-bloqueo por inactividad con PIN), PORT-4 CLI (rediseño editor cliente con TabPane y +10 campos: dirección postal, código interno, modo defecto). Migraciones: V82 (employee-portal), V83 (suggestions), V84 (user_settings + profile), V85 (customers extended). Sin tocar nada que requiera decisión arquitectural de Benjamin. **PORT-2 (Jornadas/Turnos/Plannings/Partes-dia) NO atacado** — bloque grande con decisión estructural pendiente "work logs con billing embebido vs separados". Detalle abajo en la sección de sesión.
>
> **Última revisión 2026-06-09 noche** (Benjamin presente — cierre del día: revert + fix puerto MariaDB 3307 + bloque CAL-FIX 1-4 + humanizar event_type en Agenda). **Bloque RECURRENTES cerrado** con motor + UI + 7 kinds (SALES_INVOICE, PURCHASE, JOURNAL_ENTRY, ACCOUNTING_INCOME, ACCOUNTING_EXPENSE, TEMPLATE_APPLY, LOAN_AUTO_PAY) + transacciones REQUIRES_NEW aisladas + placeholders {MES}/{MES_MAY}/{AÑO}/{YY}/{M}/{D}/{T}/{MM}/{YYYY}/{DD}/{Q} + auto_proposed=TRUE + endpoint already-covers + from-recurring (check determinista via recurring_task_runs.generated_id) + candidatos detectables con banner + botón "Hacer recurrente" contextual en listados (cliente vinculado abre editor legal SALES_INVOICE, shadow abre ACCOUNTING_INCOME) + editor "Pago periódico sin factura" JOURNAL_ENTRY + autovinculación silenciosa asesoría (V64) + "Mi gestión" sidebar (IcoMoon fas-briefcase) + sub-tabs Recurrentes en empresario/cliente vinculado/cliente NO vinculado + badge "Creada por tu asesoría" + auto-resolver cuenta tercero por NIF + V65 ck_rt_kind con ACCOUNTING_INCOME/EXPENSE + commitSpinner fix + placeholder en cursor + purchase_invoice sintético al ejecutar ACCOUNTING_EXPENSE/JOURNAL_ENTRY para centralizar en listado Compras + auto-refresh sub-tabs vía RefreshBus.TOPIC_RECURRING + orígenes Diario unificados (solo "Venta"/"Gasto" con sufijo "(recurrente)" en concepto) + "Compra"→"Gasto" semánticamente correcto + acciones encima del listado (no scroll) + botón "Hacer recurrente" en buildClientBillingTab + buildClientSalesArchivedTab + sidebar asesoría renombrado "Facturacion clientes/Compras revisadas" → "Facturacion/Compras y Gastos" para igualar empresario.
>
> **Sesión 2026-06-05 (referencia previa):** asesoría↔cliente + exports legales. 15 slices: ciclo invitación (UNLINK-SYNC + REINVITE + POLLING-FIX `sendAsOwner` + INSTANT-REFRESH + DEHU-POLLING), sidebar dual `Mi empresa` vs `Mis clientes` con cierre de la deuda `advisory_only` (DUAL-SIDEBAR + V43), bug crítico fichajes `EMP-USER-MAP`, trilogía exports verificables — TC-EXPORT, AUDIT-EXPORT, AUDIT-CHAIN (V44 hash encadenado en `audit_events` con FIX collation MariaDB 11.4 + endpoint `/verify` + display_name humano).
>
> **Sesión 2026-06-06 (referencia previa):** bloque contabilidad completo. 35+ slices encadenados — V46 catálogo PGC PYMES (RD 1515/2007), asiento contable automático al validar (compras + ventas), cierre ejercicio + aplicación resultado, modelos AEAT 347/390/190, aprendizaje contable por feedback, asientos manuales con bloqueo periodo, Libro Diario + Mayor + Sumas y Saldos, cuentas bancarias + movimientos + cobros/pagos, importación Norma 43/CSV bancario + auto-conciliación, préstamos + cuadro amortización, inmovilizado + amortización, plantillas asiento, Balance situación + PyG, exportación contable Contasol/A3/Sage + importación inversa, motor recurrentes (cron) + UI primer corte, RefreshBus publish/subscribe central, V55 tercero_ref/aliases en accounting_accounts + TerceroAccountResolverService (port del CONTENDO `getOrCreateCuentaTercero`), ExpenseAccountClassifierService + IncomeAccountClassifierService espejo, refactor PurchaseJournalEntryService + SalesJournalEntryService con resolver+classifier, endpoint reclassify + botón UI "Reclasificar asientos", V56 companies.tercero_account_length/mode (BY_INDEX/BY_NIF) + endpoint config + UI, V57 sales_invoices.concept/purchase_invoices.concept con descripción por línea correcta, V59 relax UK tax_identifier para shadow companies + endpoint start-management + doble click en cliente no vinculado, V60 journal_entries.source_pdf_path + visor PDF reutilizable con PDFBox + multi-import gastos/ventas + seed fiscal_year + PGC en shadow company.
>
> **Sesión 2026-06-04 (referencia previa):** PDF-EXTRACT v2 con layout X/Y por span (PDFBox + LayoutCollector), regex inspirado en CONTENDO calendarParser.v3. PDF-TEMPLATES aprendizaje por NIF. PDF-AMAZON específico (NIF español prioritario sobre EU VAT, Número del documento con `del`, totales por signature). PDF-MULTI (Amazon multi-factura por PDF). TC-CFG tipos de evento configurables + TC-AUDIT sub-tab auditoría + EMP-GEO geolocalización opcional.
> **Fuentes:** [`gap-analysis-contendo.md`](gap-analysis-contendo.md), [`gap-analysis-config-ui.md`](gap-analysis-config-ui.md), [`next-sessions-plan.md`](next-sessions-plan.md), [`migration-roadmap.md`](migration-roadmap.md).
>
> **Forma de trabajo (junio 2026):** Pablo ya no participa de forma activa (solo entra de uvas a peras). Benjamin lidera y decide. Todo el trabajo se hace en la rama `feat/Benjamin` → se prueba localmente → se commitea → se mergea a `develop` con `--no-ff`. Cada item cerrado se marca `✅` aquí con el hash del commit.

## Cómo se usa

- `⬜` pendiente · `🔵` en curso · `✅` hecho (con commit) · `❓` decisión de alcance pendiente · `⏸` aplazado conscientemente.
- Cuando un item se cierra, se marca `✅` con el commit hash entre paréntesis y se mueve al final de su sección (orden visual: pendientes arriba, cerrados abajo).
- Reordenar items cuando cambia la prioridad. No borrar nada cerrado.

---

## Estado base ya cerrado (referencia)

Para no repetir en cada sección lo que ya está:

- ✅ V1-V3 esqueleto, V4 PGC + RETA tramos, V5 RETA extension, V6 issuers.is_default, V7 catálogo módulos, V8 auth seed, V9 `company_email_config`.
- ✅ Issuer módulo end-to-end (CRUD + emisor activo + indicador header). ⚠️ **Deprecado por V10-V12**: la tabla `issuers` se absorbe en `companies` (decisión 2026-06-01 — empresa = emisor por defecto).
- ✅ Infra modular: `module_catalog`, `company_modules`, `TenantContext`, `@RequiresModule`, interceptor 403.
- ✅ Slice C1: login real email/password con JWT, AuthSession con Bearer automático, selector de empresa, modo derivado de `company_type` (toggle eliminado).
- ✅ Slice C3: módulo Configuración MVP — V9 + Jasypt + 3 controllers `/api/settings/*` con `@RequiresRole`/`@RequiresModule` + UI con TabPane (Empresa/Email SMTP/Módulos) + sticky footer + batched save de módulos + A4 sidebar dinámico (`/api/modules-catalog/active`). i18n cerrado 2026-06-02.
- ✅ Fix de seguridad: `CustomerRepository.findById/findAllActive` filtran por `company_id` (era fuga pre-existente).

---

## 📅 Sesión 2026-06-10 (autonomía total — Benjamin fuera, sin Pablo)

Sesión más larga de autonomía hasta la fecha. Benjamin pidió:
"completar el backlog... mira CONTENDO GESTIONES como está cada
función, súmale los estilos que tenemos aquí, y cuando termines me
haces una pregunta — si no contesto al instante, sigue completando
incluso decisiones que debería tomar yo".

**Plan ejecutado**: 9 slices, todos compilan, todos mergeados a
`develop`. Patrón "un commit por slice + push + merge --no-ff" sin
romper la regla de oro.

**Cerrado autónomamente**:

1. ✅ **FIX-T-LIMIT** (`66ff009`): el método `t()` en
   `BenjagestUiApplication.java` ya superaba el límite JVM de 64KB
   por método ANTES de empezar (el commit `3e0a1c4` de docs no
   compilaba). Extraídas las 40 keys `calendar.*` a nuevos helpers
   `tCalendarEn/Es` siguiendo el patrón de `tNewModulesEs/En`,
   `tTeamEn/Es`, `tPinLoginEs/En` ya existentes. Refactor puro,
   sin keys nuevas. **Lección documentada**: si la app no compila
   al arrancar, el primer slice de la sesión TIENE que ser
   desbloqueo del compile. Esto va a volver a pasar — los helpers
   ya son 8, conforme se añadan módulos seguiremos necesitando
   extraer más bloques.

2. ✅ **PORT-5 CAL-A — Badge color por event_type en Agenda**
   (`66ff009` mismo commit que FIX-T-LIMIT): rojo para HOLIDAY,
   azul para WORK_ADJUSTMENT, gris para WORK_CLOSURE, morado para
   GENERAL. Aplicado en `dayEventCard()` como clase modificadora
   sobre `calendar-event-card-type`. 4 reglas CSS nuevas.

3. ✅ **PORT-5 CAL-B — Botón "Quitar de Agenda"** (`9da186d`):
   inversa de `dump-to-agenda`. Endpoint `DELETE
   /api/labor/work-calendars/{id}/dump-to-agenda` que borra solo
   los eventos con `source_type='WORK_CALENDAR'` + `source_id IN
   (SELECT id FROM holidays WHERE work_calendar_id=?)`. Idempotente
   y NO toca eventos manuales del usuario. UI: botón `fas-calendar-minus`
   en la top bar del calendario laboral con confirmación.

4. ✅ **PORT-5 CAL-C — Botón "Cargar festivos nacionales"**
   (`ebc8fa1`): sustituye al seed global que estaba en backlog.
   Self-service por calendario: el usuario pulsa el botón y se
   crean los 10 festivos nacionales fijos del año de ese calendario
   (idempotente — solo añade los que faltan). **Viernes Santo
   calculado con el algoritmo Meeus/Jones/Butcher** así funciona
   para cualquier año, no solo 2026. Cubre 01/01, 06/01, Viernes
   Santo, 01/05, 15/08, 12/10, 01/11, 06/12, 08/12, 25/12. Los
   autonómicos siguen llegando por PDF.

5. ✅ **PORT-5 CAL-D — CAL-IMPORT-MODAL** (ya estaba): el modal
   side-by-side editable ya estaba implementado en sesiones
   previas (CAL-FIX block + sesión 2026-06-04 con
   `HolidayPdfExtractor`). Solo se confirma como cerrado en el
   backlog.

6. ✅ **PORT-1 EMP-1..4 — Portal del empleado con 4 tabs**
   (`9602e22`): un único módulo `employee-portal` con TabPane (Mi
   calendario / Mis nóminas / Mis notificaciones / Mis trabajos).
   Decisión implícita aceptada: misma JavaFX en modo empleado, no
   app aparte — el rol EMPLOYEE ya entra con PIN desde L4-4.
   - V82 module_catalog + activación en TODAS las companies
     (advisory_only=FALSE, display=50, icono fas-user-clock).
   - Backend paquete `portal/`: `EmployeePortalService` con
     `currentEmployeeIdOrNull` (no falla si OWNER no es empleado),
     `listCalendar` (combina `calendar_events` + `medical_leaves`
     del empleado en rango), `listPayslips`, `listNotifications`,
     `listJobs`. Defensivo: try/catch alrededor de payslips y
     advisory_notifications por si la tabla no existe.
   - 4 modelos UI nuevos. AltaApiClient con 4 métodos. Helpers
     `tEmployeePortalEn/Es` con ~50 keys ES+EN.
   - Tab "Mis trabajos" queda como placeholder hasta decisión PORT-2
     (work logs con billing embebido o separados).

7. ✅ **PORT-3 SUG — Módulo Sugerencias** (`74f42ae`): buzón de
   feedback hacia el equipo BENJAGEST. Per-tenant, cualquier rol
   puede sugerir, OWNER/ADMIN cierra/borra. Espejo de
   `sugerencias_180` de CONTENDO pero con nombres en inglés.
   - V83 tabla `suggestions` con índices + módulo "suggestions"
     (icono fas-lightbulb, display=880).
   - Categorías: general/improvement/module/bug/other. Estados:
     new/read/answered/closed.
   - `SuggestionService` con whitelist de categorías y estados.
     `SuggestionController` bajo `/api/suggestions`.
   - UI: módulo `showSuggestionsModule` con tabla + topbar (Nueva
     / Cerrar / Eliminar) + modal de formulario con combo categoría.
   - `humanizeSuggestionCategory` + `humanizeSuggestionStatus` para
     mostrar etiquetas traducidas sin cambiar códigos en BD.

8. ✅ **PORT-3 PERFIL + LOCK — Preferencias usuario + bloqueo PIN**
   (`0139e0a`): dos items del backlog cerrados en un bloque por
   compartir tabla.
   - V84 `user_settings` (user_id PK, language, pin_timeout_min,
     screensaver_style, ai_enabled, avatar_path, workday_template)
     con CASCADE al borrar user_account. Módulo "profile"
     (fas-user-circle, display=870).
   - `UserSettingsService` con `getCurrent` (devuelve defaults sin
     crear fila si no existe) y `save` (UPSERT con validación de
     rangos: timeout 0-120 min, language es|en).
   - **PORT-3 PERFIL**: `showProfileModule` con 4 secciones
     reutilizando `settings-section` (Idioma combo, Bloqueo
     inactividad spinner 0-120 min, IA Copilot reservada, Avatar
     con FileChooser local). Save aplica idioma inmediatamente.
   - **PORT-3 LOCK**: estado `lastInputAt` + `lockTimeoutMin` +
     `lockChecker` Timeline. Event filters globales en la Scene
     (MouseEvent.ANY + KeyEvent.ANY) actualizan `lastInputAt`.
     Timeline cada 30s, dispara `showLockStage` cuando elapsed >=
     timeout. Stage UNDECORATED + APPLICATION_MODAL con PIN
     PasswordField + botones Desbloquear / Salir. Verifica vía
     `authApiClient.pinLogin(deviceSecret, pin)`.
   - Helpers `tProfileLockEn/Es` con ~50 keys ES+EN.

9. ✅ **PORT-4 CLI — Rediseño editor cliente con TabPane** (`d5cfd05`):
   Benjamin pidió "la UI de crear clientes está obsoleta y faltan
   campos". CONTENDO `app/admin/clientes` tenía dirección postal
   completa + código interno + datos fiscales avanzados — todo eso
   faltaba en BENJAGEST. Cambios aditivos sin tocar el formulario
   genérico (otros módulos lo comparten).
   - V85 ALTER customers ADD COLUMN address, city, province,
     postal_code, country (default España), internal_code,
     default_mode, phone, email, website. Índice
     `ix_customers_internal_code` para búsquedas. Aditivo y
     nullable — clientes existentes con NULL hasta editar.
   - `CustomerExtendedController` bajo `/api/customers-extended`
     con GET/PUT por id. Independiente del CustomerController
     existente.
   - Modelo `CustomerExtendedEntry` (24 campos) +
     `getCustomerExtended/updateCustomerExtended` en AltaApiClient.
   - `editSelected("customers", ...)` ahora desvía a
     `showCustomerDetailDialog` en lugar del form genérico. Resto
     de módulos siguen igual.
   - `openCustomerDetailDialog`: TabPane con 3 tabs (Generales /
     Dirección postal / Facturación) y 24 campos repartidos. Combo
     de tipo (COMPANY/SELF_EMPLOYED/PUBLIC_ENTITY/OTHER). Validación
     numérica del IVA y retención. Helper `formGrid(pairs)`
     reutilizable.
   - Helpers `tCliEditorEn/Es` con ~33 keys ES+EN.

**Lo que NO se atacó** (decisiones conscientes — Benjamin tiene
que entrar):

- ⬜ **PORT-2 — Fichaje extensión (Jornadas/Turnos/Plannings/
  Partes-dia)** — bloque enorme (CONTENDO tiene `app/admin/turnos`
  + `app/admin/jornadas` con 7 componentes + `app/admin/plannings`
  358 líneas + `app/admin/partes-dia` 572 líneas). Bloqueo
  arquitectural: el backlog dice literal *"Decidir si work logs
  con billing embebido (modelo CONTENDO) o separados (modelo
  BENJAGEST actual)"*. Benjamin debe decidir antes de portar — si
  embebido, hay que refactorizar también la tabla `sales_invoices`
  para enlazar a work_logs; si separado, los work_logs viven
  aparte y la facturación pesca opcional. La decisión cambia la
  forma del schema, los endpoints, y todo el flujo. Lo dejo
  apuntado en la lista grande de abajo (sigue 🟠 ALTA).

- ⬜ **Backup local automático** — necesita decisión de Benjamin
  sobre dónde guardar (ruta) y cuándo (cron o manual).
- ⬜ **Dashboard widgets personalizables** — decisión de UX (drag
  drop, layout móvil).
- ⬜ **Reconciliación bancaria asistida con sugerencias ML** —
  toca el núcleo contable cerrado el 06-06, zona caliente sin
  Benjamin delante.
- ⬜ **VF-SIGN-XADES-AEAT estricto + VF3-SOAP afinado** —
  requieren FNMT real y testing en sede AEAT, no es atacable
  autónomamente.
- ⬜ **Conector DEHú real / SS RED / SILTRA real** — requieren
  credenciales reales.
- ⬜ **AI Copilot / PWA / Google Calendar OAuth / Email personal
  OAuth** — decisiones estructurales o requieren credenciales.

**Adendas durante la sesión (Benjamin contestó desde fuera)**:

10. ✅ **PORT-2 skeleton** (`c5bbbcb`): tras decisión Benjamin
    "embebido como CONTENDO", V86 creó 4 tablas (workday_templates,
    workday_template_blocks, work_shifts, work_logs con
    is_billable + billable_amount + billed_invoice_line_id FK
    opcional) + módulo "shifts" inactivo por defecto.
    WorkLogService con listForCompany/listMine/create. El tab "Mis
    trabajos" del Portal del empleado ahora lee los partes propios
    de los últimos 90 días. UX completa (plantillas, turnos,
    workflow validación, conversión work_log → línea factura)
    pendiente de slices siguientes.

11. ✅ **TC-CAL — warning amarillo en fichaje de festivo**: tras
    decisión Benjamin "warning amarillo" (no bloqueo).
    `TimeClockRepository.findTodaysHolidayForEmployee(employeeId)`
    cruza `employees.work_calendar_id` con `holidays.holiday_date =
    CURRENT_DATE`. `TimeClockService.punch` detecta y devuelve
    `HolidayWarning(holidayName, holidayType, scope)` dentro de
    `PunchResult`. UI: `TimeClockApiClient` parsea `holidayWarning`
    del JSON y devuelve `PunchOutcome(csv, holidayName,
    holidayType, holidayScope)`. El dialog tras fichar añade
    tarjeta amarilla con tipo humanizado (Festivo/Ajuste/Cierre) +
    nombre del festivo si aplica. **NO bloquea** — solo informa;
    la nómina ya podrá tratarlo como horas extra cuando se cierre
    ese flujo. 4 keys i18n nuevas (`tTcCalEn/Es`) + 4 reglas CSS
    `holiday-warning-*`.

**Pendiente real para Benjamin al volver**:

1. Probar los 11 slices nuevos:
   - Agenda → comprobar que los badges salen coloreados según tipo.
   - Calendario laboral → ver botones nuevos "Quitar de Agenda" y
     "Cargar festivos nacionales".
   - Sidebar → debería aparecer "Portal del empleado",
     "Sugerencias", "Mi perfil" como módulos nuevos.
   - Mi perfil → guardar idioma EN y comprobar que se aplica. Subir
     timeout a 1 min, esperar, ver el lock screen.
   - Customers → doble click en un cliente debe abrir el editor
     nuevo con tabs.

2. Decisión sobre **TC-CAL** (heredada de 06-09): warning amarillo
   o bloqueo duro al fichar en día festivo del calendario del
   empleado.

3. Decisión sobre **PORT-2 work logs**: embebido en factura
   (CONTENDO) o separado (actual BENJAGEST + enlace opcional).

---

## 📅 Sesión 2026-06-09 noche (Benjamin presente — cierre día)

Sesión de cierre tras la autónoma de la tarde. Benjamin volvió,
probó lo que dejó la sesión autónoma, y aparecieron 2 bugs serios
que pasaron a primera prioridad antes de cualquier feature nueva.

**Cerrado**:

1. **fix(workcal) — revert UI + backend al estado 71e0697**
   (commits `49e030d`, `410ccc3`): tras la sesión autónoma de la
   tarde, varios cambios "preventivos" (pickPrimaryMembership en
   AuthService, validación X-Company-Id en TenantInterceptor, V79/V80
   de holiday_type, retoques al modal) habían dejado al usuario
   empresario `empresario@benjagest.local` sin ver clientes, ventas,
   compras, auditoría ni calendarios — pese a que la BD los tenía
   correctos. Revert quirúrgico a 71e0697 (`git checkout 71e0697 --`
   por archivos, no `git revert` que habría arrastrado más cambios)
   para restaurar el comportamiento previo.

2. **fix(config) CRITICAL — backend conectado a MariaDB 3306 (Pablo)
   en vez de 3307** (commit `e98fbb1`): después del revert el bug
   seguía. Diagnóstico con 3 logs temporales (`[TENANT-DEBUG]`,
   `[CUSTOMERS-DEBUG]`, `[CUSTOMERS-DEBUG-2]` con contrapruebas
   countAll/countNoActive/countHardcoded). Resultado: el JdbcTemplate
   no devolvía datos NI con companyId hardcodeado y `countAll=3`.
   Aplicación arrancaba contra `MariaDB 12.2`, NO `11.4` del proyecto.
   `application.yml` tenía `localhost:3306` por defecto, que en la
   máquina de Benjamin es la instancia de Pablo con una BD `benjagest`
   casi vacía. Cambio: default a `3307` + comentario explicando por
   qué para futuras sesiones. Logs temporales eliminados en el mismo
   commit. **Lección**: si Hikari log dice `MariaDB 12.2` en arranque,
   estamos contra BD equivocada — la del proyecto es 11.4.

3. **feat(workcal) CAL-FIX — bloque calendario laboral completo**
   (commit `064b6df`): cierra las 4 peticiones de Benjamin tras el
   bug del puerto:
   - **CAL-FIX 1**: DatePicker → TextField flexible en modal
     CAL-IMPORT, estilo CONTENDO (`<input type=date>` nativo). Nuevo
     `EditableCells.flexibleDateTextField()`. Sin popup, sin
     VirtualFlow. Acepta dd/MM/yyyy, ISO, dd-MM-yyyy, d.M.yyyy. Si
     no parsea, borde rojo y mantiene texto. Elimina el bug
     "01/01/2026 dos veces" definitivamente.
   - **CAL-FIX 2**: botón "Eliminar calendario" movido del pie a la
     barra superior junto a "Importar PDF" + nuevo botón.
   - **CAL-FIX 3**: botón "Volcar a Agenda" + endpoint
     `POST /api/labor/work-calendars/{id}/dump-to-agenda`. Copia
     `holidays` a `calendar_events` con `source_type=WORK_CALENDAR`.
     Mapeo: FESTIVO→HOLIDAY, AJUSTE→WORK_ADJUSTMENT, CIERRE→
     WORK_CLOSURE. Idempotente.
   - **CAL-FIX 4**: V81 `employees.work_calendar_id` CHAR(36) NULL
     + FK ON DELETE SET NULL. EmployeeService lee/escribe el campo.
     ComboBox en form de empleado con opción "— Ninguno —" + todos
     los calendarios. Listo para que fichaje/nómina lo usen
     (lógica de fichaje extra/bloqueo queda como bloque futuro
     **TC-CAL**, decisión pendiente: ¿warning visual o bloqueo
     duro?). ✅ **Cerrado 2026-06-10**: Benjamin eligió warning
     amarillo. Ver sesión 2026-06-10 abajo.

4. **fix(agenda) — humanizar event_type en tarjetas** (commit
   `8077226`): la modal de Agenda mostraba el código crudo
   (`WORK_ADJUSTMENT`, `HOLIDAY`, `WORK_CLOSURE`) bajo el título.
   Nuevo helper `humanizeCalendarEventType()` con switch + fallback
   al valor original (back-compat con eventos antiguos cuyo tipo
   era texto libre tecleado). 4 keys i18n ES+EN nuevas.

**Pendiente / propuestas para próxima sesión**:

- **TC-CAL** — usar `employees.work_calendar_id` en fichaje: detectar
  si el día es FESTIVO/AJUSTE/CIERRE del calendario del empleado y
  o bien marcar el fichaje como extra, o bloquear. Decisión de
  comportamiento abierta (preguntar a Benjamin).
- **Agenda — limpieza de eventos volcados**: si el usuario hace
  `Volcar a Agenda` con un calendario de 2025 y luego con uno de
  2026, ambos siguen en la Agenda. Botón "Quitar de Agenda" o
  limpieza automática al cambiar calendario activo del año.
- **Badge de color** en lugar de texto plano para tipos de evento
  (rojo festivo, azul ajuste, gris cierre) — más visual.

---

## 📅 Sesión 2026-06-09 tarde (autonomía total — Benjamin fuera)

Segunda tanda de autonomía: Benjamin pidió continuar solo cerrando lo
que se pueda mientras él no está, con la regla "cuando creas que
solucionas algo, plantea una mejora extra". **5 commits, todos
compilan y mergeados a develop**. Pendiente que Benjamin lo pruebe
con UI al volver.

**Cerrado autónomamente**:

1. **fix(workcal) — DatePicker parsea varios formatos** (commit `1a43661`):
   Bug raíz del CAL-IMPORT-MODAL — el converter de JavaFX para es_ES
   usa `dd/MM/yy` (año 2 dígitos) y al teclear "31/12/2026" fallaba,
   dejando 4 filas con fecha 2026-01-01 (error duplicado). Fix:
   `EditableCells.parseFlexibleDate` acepta ISO + `dd/MM/yyyy` +
   `dd/MM/yy` + `dd-MM-yyyy` + `d.M.yyyy`. + `commitPendingDatePickerEdits(table)`
   que busca pickers abiertos vía `lookupAll(".date-picker")` y fuerza
   commit antes de validar. Aplicado al modal de import calendario.

2. **feat(labor) — UI Bajas (IT)** (commit `8fdcff1`): backend
   `MedicalLeaveService` estaba cerrado, UI pendiente. Pestaña nueva
   en módulo Labor con tabla (empleado, tipo, inicio, fin, estado,
   notas) + diálogo formulario para nueva/editar. Auto-status según
   endDate. 38 i18n keys ES + EN.

3. **feat(labor) — UI Cotizaciones SS** (commit `50dee71`): backend
   `SocialSecurityContributionService` estaba cerrado. Pestaña solo
   lectura (las cuotas las calcula el módulo de nóminas; editor
   manual no es prioritario). Filtros año/mes/empleado, botón
   Eliminar solo para DRAFT (backend rechaza !=DRAFT con 409).
   32 i18n keys.

4. **feat(labor/ss) — footer con totales** (commit `9aa61fb`):
   Mejora aditiva sobre el slice anterior siguiendo la regla
   Benjamin. Muestra `{n} cuotas · Total base: X € · Total cuota: Y €`
   en footer, recalcula al cambiar filtros. Verificación rápida
   contra TC1 sin sumar a mano.

5. **feat(ui) — installFlexibleConverter para DatePickers sueltos**
   (commit `4729eca`): defensa en profundidad sobre el bug del
   workcal. `EditableCells.installFlexibleConverter(DatePicker)`
   reemplaza el converter del DatePicker por uno flexible. Aplicado
   a 4 DatePickers de alto uso: editor factura emitida (invoiceDate +
   dueDate) y editor Bajas IT (start + end). Resto pendiente de
   añadir incrementalmente — el helper está disponible.

**Para probar al volver**:
- Reiniciar backend (las migraciones aplicarán) + UI.
- Modal CAL-IMPORT con tu PDF de Granada: las fechas tecleadas
  `31/12/2026`/`3/6/2026` deben quedar guardadas al volcar.
- Pestaña "Bajas (IT)" en Labor: añadir/editar/eliminar una baja.
- Pestaña "Cotizaciones SS" en Labor: filtros + footer con totales.
- Editor de factura: teclear fecha en formato 4 dígitos no debe
  perder el valor.

**Lo que NO se atacó** (decisiones conscientes):
- VG-FULL-SCAN (barrido sistemático de 159 cellValueFactory): muy
  largo para sesión autónoma, alto riesgo de tocar muchos sitios.
- UI advisory_documents (V78): el backend explicita "upload multipart
  real queda como sub-slice pendiente"; sin él la UI no rinde.
- Modelos AEAT 100/180/200/411: requieren WebSearch legal extenso +
  decisiones de Benjamin sobre flujo (similar a CTR-4).
- VF-SIGN-XADES-AEAT estricto + VF3-SOAP afinado: requieren FNMT real
  + alta SIF en sede AEAT (no aplicable autónomamente).

---

## 📅 Resumen sesión 2026-06-09 (autonomía)

Sesión de trabajo autónomo (Claude solo, con permisos delegados por
Benjamin) cerrando todo lo aditivo y defensivo del backlog. **17
commits, todos compilan y mergeados a develop**.

**Bloque L3 cerrado completo**:
- L3-2 backend `WorkCalendarService` + Controller (paquete `labor.workcal/`,
  endpoint `/api/labor/work-calendars`, tope legal 14 festivos/año Art.
  37.2 ET, lista CCAA ISO 3166-2:ES validada).
- L3-3 (retirado 2026-06-09): el seed `HolidaySeed2026` con festivos
  autonómicos hardcoded NO estaba verificado contra BOJA/BOPV/DOGC.
  Sustituido por flujo PDF: usuario importa el calendario laboral
  oficial de su CCAA descargado del boletín correspondiente, parser
  CONTENDO (`HolidayPdfExtractor`) extrae festivos + ajustes, modal
  side-by-side editable.
- L3-4 UI tab "Calendario laboral" dentro del módulo Labor con bootstrap
  rápido (CCAA combo + municipio + nombre), tabla calendarios, tabla
  festivos, CRUD inline. i18n ES+EN (45 keys).

**Empleados/Nóminas**:
- `MedicalLeave` backend completo (paquete `labor.leaves/`, endpoint
  `/api/labor/medical-leaves`, estados OPEN/CLOSED/DRAFT auto).
- `SocialSecurityContribution` backend completo (paquete `labor.ss/`,
  endpoint `/api/labor/social-security`, bloqueo DELETE si no es DRAFT
  por inalterabilidad).
- `LaborCostReportService` para reporte coste empresa por empleado
  (suma bruto + cuotas SS empresariales agregadas, endpoint
  `/api/labor/reports/cost`).

**Asesoría (backend completo, UI pendiente)**:
- V77 `advisory_messages` + `AdvisoryMessageService`. Timeline
  unificado A2C/C2A. `resolveParts()` detecta rol asesoría/cliente
  via `companies.parent_company_id`. Endpoints
  `/api/advisory/messages/threads/{otherCompanyId}/{send|mark-read}`.
- V78 `advisory_notifications` + `AdvisoryNotificationService`.
  Severity INFO/WARNING/URGENT, `entity_ref` para navegación
  contextual, read/dismiss separados. Endpoints
  `/api/advisory/notifications`.
- V78 `advisory_documents` + `AdvisoryDocumentService`. Status
  UPLOADED→REVIEWED→ACCEPTED|REJECTED con `note` obligatoria al
  rechazar, bloqueo DELETE si ACCEPTED. Endpoints
  `/api/advisory/documents`.
- `AdvisoryDashboardService` con vista panorámica (cartera +
  obligaciones próximas + workflow + workload por empleado).
  Endpoint `/api/advisory/dashboard`.

**Hooks (sistema de notificaciones operativo)**:
- `AdvisoryInvitationService` → emit en accept (INFO) y reject (WARNING).
- `ContractAlertService.scan()` → emit summary por (asesoría, cliente)
  con idempotencia diaria.
- `AnomalyDetectionScheduler` → emit URGENT cuando detecta cadena
  hash rota (facturas o eventos SIF) en cliente con asesoría.

**Polish técnico**:
- `parseObjects` ahora delega en `splitTopLevelObjects` — tolera
  sub-objetos anidados sin romper. Cierra deuda CLAUDE.md.

**Estado al cerrar la sesión**:
- Backend de Mensajes, Notificaciones, Documentos, Dashboard,
  Calendario Laboral, MedicalLeave, SocialSecurity, LaborCost listo
  y testeable vía API.
- UI pendiente para: bandeja notificaciones del asesor (badge en
  sidebar), chat de mensajes, file tree de documentos compartidos,
  dashboard cross-client, listados de MedicalLeave/SocialSecurity/
  LaborCost.
- 3 hooks reales emitiendo notificaciones al asesor; otros hooks
  (TaxFiling, message arrival) en próximas sesiones cuando Benjamin
  priorice.

---

## 🔴 CRÍTICA — siguiente bloque a atacar

Lo que toca **antes** de seguir con features funcionales. Cubre: legalidad, seguridad multi-tenant, y los dos slices que dejamos preparados.

### 🟢 PRIMERA TAREA AL VOLVER — continuar bloque CTR (CTR-4 PDF firmable)

**Bloque L4 cerrado al 100%** + **CTR-1 y CTR-2 cerrados** + Flyway out-of-order arreglado + dos fixes UX transversales (humanización errores HTTP en TODA la app + i18n sexo/estado civil). Próximo: **CTR-4 (PDF firmable)** porque el wizard ya tiene el selector `UNIFIED_2022 / BY_CODE` pero no genera nada todavía.

**Hecho 2026-06-08 tarde:**

- ✅ **fix Flyway outOfOrder** — `FlywayConfig.outOfOrderCustomizer` permite aplicar V71 (creada tras V72/V73). Sin esto el backend abortaba al arrancar con "Detected resolved migration not applied: 71".
- ✅ **fix UX errores humanos** — `showError` ahora pasa CUALQUIER mensaje por `humanizeBackendError()` que detecta el JSON Spring estándar y extrae solo `"message"`. Aplica a TODA la app sin retoque por call-site.
- ✅ **fix UX i18n sexo + estado civil** — combos `genderCombo` y `maritalCombo` con StringConverter usando `humanizeFromKey(key, fallback)`. Valores internos siguen siendo MALE/FEMALE/SINGLE/MARRIED/... (no se toca BD). UI muestra "Hombre/Mujer/Soltero/a/..." en ES, equivalentes en EN. 13 keys + helper `humanizeFromKey` reutilizable.
- ✅ **CTR-1 — V74 catálogos completos**. WebSearch legal previo. Tres tablas con seed: sepe_contract_types (28 códigos post-Reforma 2022 + 2 legacy), collective_agreements + professional_categories (25 convenios PYMEs con 3-6 categorías cada uno y salario mín 2026 + jornada máx + periodo prueba), contract_clause_templates (12 anexos built-in con texto legal completo + placeholders + legal_basis).
- ✅ **CTR-2 — backend ContractCatalog{Models,Service,Controller} + UI wizard 4 pasos**. 3 GET /api/contracts/catalog/*. UI showContractWizard reemplaza al editor plano: paso 1 SEPE filtrable por familia, paso 2 convenio→categoría cascada con info referencia, paso 3 datos auto-rellenados con warning amarillo si < mínimo, paso 4 resumen + combo modelo PDF (UNIFIED_2022 / BY_CODE) + checkboxes de las 12 cláusulas. WizardState mutable + validateStep + pre-rellenado al editar. 45+ keys i18n ES/EN.

**Decisiones Benjamin 2026-06-08 aplicadas al diseño:**
  - PDF model: ambos modelos al final del wizard (UNIFIED_2022 + BY_CODE).
  - XML contrat@: v1 (no posponer).
  - Anexos: TODOS los posibles + campos libres editables (4 marcados como prioritarios).
  - SEPE: catálogo completo (~28+).

**Hecho 2026-06-08 mañana:**

- ✅ **L4-4 a L4-7 + V70/V71/V72/V73** — bloque L4 al 100%. Detalles previos abajo.

**Hecho 2026-06-08 — bloque L4 completo:**

- ✅ **L4-4 — Alta empleado con Acceso a la app + PIN integrado**. EmployeeService.UpsertRequest amplía con appAccess (Boolean tri-state), pin (4-8 dígitos), roleInCompany (default EMPLOYEE). EmployeeView con appAccess/userId/hasPin. create/update detectan transición de app_access y aplican provisionAppAccess / revokeAppAccess. Reusa user_account existente (decisión Benjamin); si no existe lo crea con email sintético `pin-{empId}@local`. setEmployeePinChecked con verify bcrypt in-memory para evitar colisión. EMPLOYEE_APP_ACCESS_GRANTED/REVOKED/PIN_CHANGED en audit. UI showEmployeeEditor con sección "Acceso a la app (PIN)" — CheckBox + ComboBox rol con StringConverter + PasswordField PIN con prompts contextuales. i18n 9 keys ES/EN.
- ✅ **L4-5 — Refactor módulo Equipo → employees.app_access=TRUE**. ClientAssignmentService.listAdvisoryMembers cambia de company_memberships a employees JOIN user_accounts WHERE app_access=TRUE + UNION con OWNER vía company_memberships (caso transitorio asesorías pre-L4-4). Subquery wrap necesario en MariaDB para ORDER BY UNION. UI marca al usuario actual con " — tú" en columna nombre.
- ✅ **L4-6 — V71 advisory_collaborations + backend**. Tabla con id, advisory_company_id, partner_advisory_id, invited_email, status PENDING/ACCEPTED/REJECTED/REVOKED, invited_at/by, accepted_at/by, revoked_at/by, notes. partner_advisory_id NULL hasta aceptar. AdvisoryCollaboration record + Repository + Service (invite/accept/reject/revoke + listOutgoing/listIncoming/listActivePartners + listActivePartnerIds helper para L4-7) + Controller con 7 endpoints REST bajo /api/advisory/collaborations. Audit completo.
- ✅ **L4-7 — Tab Colaboradores en módulo Equipo**. CollabEntry UI record + AltaApiClient con 6 métodos. TeamBundle amplía con outgoing/incoming/active collabs (carga try/catch defensivo). 4ª tab "Colaboradores" (fas-handshake) con 3 secciones: Invitaciones recibidas (solo si las hay) + Aceptar/Rechazar, Colaboradoras activas + Revocar con confirmación, Invitaciones enviadas pendientes + Revocar. Botón "Invitar asesoría colaboradora" → diálogo email/notas con validación. humanizeCollabStatus + helpers (ThrowingRunnable, runCollabAction). i18n ~45 keys ES/EN.
- ✅ **Decisión 2026-06-08 — bloque CTR (Contratos)** acordado con Benjamin: volumen alto (20+ contratos/mes), todos los pain points marcados. 7 slices CTR-1 a CTR-7 planificados, con prioridad CTR-1 → CTR-2 → CTR-4 (PDF SEPE oficial con barrido legal completo) → CTR-3 → CTR-6 → CTR-7 → CTR-5 (XML contrat@).

**Pendiente — orden estricto para próxima sesión:**

1. ⬜ **CTR-1 — V74 catálogo SEPE + 25 convenios + tablas salariales**. Códigos SEPE oficiales (100/109/200/300/401/402/501/502 con descripción legal completa). 25 convenios PYMEs (Comercio General, Hostelería, Construcción, Oficinas y Despachos, Limpieza, Transporte por Carretera, Sanidad Privada, Enseñanza Privada, Industria Metalúrgica, etc.) con tabla salarial mínima 2024-2025 + categorías profesionales + grupos. Datos plausibles del BOE; el OWNER puede editar después desde Configuración.
2. ⬜ **CTR-2 — Wizard contrato 4 pasos**. Editor rediseñado: 1) Tipo+SEPE (combo con descripción + filtros Indefinido/Temporal/Prácticas), 2) Convenio + categoría profesional (combos en cascada), 3) Datos económicos (auto-rellenados con mínimos del convenio elegido + warnings amarillos si bajan), 4) Revisión + crear. Integrado con flujo alta empleado L4-4.
3. ⬜ **CTR-4 — PDF SEPE oficial firmable**. **IMPORTANTE: hacer WebSearch primero** sobre Estatuto de los Trabajadores (RDLeg 2/2015), reforma laboral 2022 (RD-Ley 32/2021), reformas 2024-2025, modelos SEPE oficiales — Benjamin pidió expresamente "barrido en internet para no dejarnos nada según la ley, barreras leyes desde hasta hoy". Plantillas BOE de clausulado por tipo SEPE con variables (nombre, NIF, salario, convenio, jornada, periodo de prueba...) + huecos firma. OpenPDF ya en pom.
4. ⬜ **CTR-3 — Plantillas reutilizables (contract_templates)**. Tabla con datos del wizard guardados. UI "Crear plantilla desde contrato" + "Aplicar plantilla en bloque" a empleados nuevos.
5. ⬜ **CTR-6 — Alertas vencimientos**. Cron diario en backend. Escribe en dehu_notifications (ya existe). Plazos por defecto (Benjamin 2026-06-08): periodo prueba 7 días antes, contrato temporal 30 días antes, cláusulas anuales 60 días antes, cumpleaños/aniversarios.
6. ⬜ **CTR-7 — Anexos**. Plantillas de cláusulas adicionales (confidencialidad, no competencia, exclusividad) que se concatenan al PDF principal según se marquen al crear.
7. ⬜ **CTR-5 — XML contrat@ alta SEPE oficial**. Generador del XML según esquema oficial del SEPE. Lo último por complejidad — beneficia del testing previo de CTR-1 a CTR-4.

**L3 pausado:** Slices L3-2 (WorkCalendarService + Controller), L3-3 (templates BOE 2026 + servicio import) y L3-4 (UI modal comparador calendario laboral). **Retomar después del bloque CTR** o mover a 🟠 ALTA si no es prioritario.

**Estado al cerrar 2026-06-08:** backend levanta limpio con V70 + V71 + V72 + V73 aplicadas. UI compila. Benjamin tiene login PIN 2406 funcional. Módulo Equipo con 4 tabs (Empleados, Asignaciones, Delegaciones, Colaboradores) cerrado y mergeado a develop. Editor empleado con casilla "Acceso a la app + PIN" funcional. Marcos sigue en Laboral con app_access=FALSE pero NO aparece en Equipo (filtrado correcto por L4-5). Próxima sesión: CTR-1.

### ⚠️ Deuda transversal — i18n (ES/EN)

La app se diseñó bilingüe desde C1 (botón EN/ES en el header → `language` field + helper `t(key)` en `BenjagestUiApplication`). Desde C3 y especialmente en F2/F3/F4/F5/F5+ **se habían hardcodeado strings en español** sin pasar por `t()`. Recorrida hecha 2026-06-02 en pasada con lupa:

- ✅ **F2/F3/F5 Facturación shell** — header, sub-tabs, botones, filtros, hints, dashboard placeholder con KPIs (sesión 2026-06-02).
- ✅ **F4 Editor de factura** — cabecera, badge "PRÓXIMO Nº", cards (cabecera/líneas/totales), botones, prompts, alertas de validación, mensajes de éxito.
- ✅ **F5+ Configuración facturación** — VeriFactu, series CRUD, showSeriesEditor (incluido reservadas + autolock + migración + textos legales).
- ✅ **F4 Listado de facturas** — columnas, filtros (con compat ES/EN del "(todos)/(all)"), botones acción, alertas validar/eliminar/PDF.
- ✅ **Command Palette + atajos** — título, placeholder, lista de acciones.
- ✅ **C3 Configuración (Empresa/Email/Módulos/Auditoría)** — todos los prompts, fields, secciones, botones, alertas, columnas de auditoría (con compat "(todos)/(all)" en filtro tipo evento).
- ✅ **Diálogos genéricos** — `errorPanel("Reintentar")`, `prerequisitePanel("Volver a Facturación")`.
- ⬜ **Calendario (event card "Editar"/"Eliminar")** — pendiente; pantalla previa al C3, fuera del scope de hoy pero detectada en la pasada.
- ⬜ **Otros módulos viejos (customers detail, dashboard CRUDs)** — auditar cuando se retomen.

**Regla a partir de ahora**: cada nueva pantalla / diálogo / alerta / botón **debe** pasar por `t("key")` con su par de traducciones ES + EN en el switch de `BenjagestUiApplication.t()`. Si un PR introduce strings hardcodeados, su slice queda con ⚠️ hasta resolver.

### Inmediato (siguiente bloque a atacar)

- ✅ **D1** — Unificación de tablas fiscales (decisión 2026-06-01). V10 amplía `companies` con address/iban/registry/legal_terms/invoice_footer + UPDATE JOIN desde `issuers` por defecto + quita FK `sales_invoices.issuer_id` + DROP TABLE issuers + borra slug `issuers` del catálogo. V11 hace lo mismo con `customer_billing_profiles` → `customers`. Backend: paquete `issuer/` borrado, `CompanyDataController/Service/Repository` ampliados con los 9 campos nuevos. UI: módulo "Emisores" eliminado del sidebar + línea "Facturando como:" del header eliminada + pestaña Empresa ampliada (Datos generales / Dirección postal / Datos de facturación) + refresh silencioso de `AuthSession.activeCompanyLegalName` + `SessionInfo.withCompanyName` tras guardar.

### Seguridad y trazabilidad

- ✅ **Refactor WorkspaceRepository** — los 27 usos de `DemoCompany.ID` migrados a `tenantContext.getCurrentCompanyId()` vía un helper `currentCompanyId()`. Verificado: admin BENJAGEST ve 3 clientes / 3 facturas / 3037€ facturado, empresario Marcos ve todo a 0 (su empresa está vacía). `DemoCompany.ID` solo queda como fallback en `RequestScopedTenantContext` para defensa en profundidad.
- ✅ **@RequiresRole + RoleInterceptor** — cerrado en C3 (`auth/RequiresRole.java` + `auth/RoleInterceptor.java`, registrado en `WebMvcConfig`). Aplicado a los 3 controllers de `settings/`.
- ✅ **Audit log activo** — paquete `audit/` (Event + Repository + Service + Controller) escribe en `audit_events` desde `AuthService` (LOGIN_OK / LOGIN_FAIL / COMPANY_SWITCHED), `CompanyModulesService` (MODULE_ENABLED / MODULE_DISABLED) y `CompanyDataService` (COMPANY_DATA_UPDATED). UI: 4ª pestaña "Auditoría" en Configuración con tabla filtrable por tipo. Pendiente futuro: vista global para LOGIN_FAIL pre-auth (companyId NULL).
- ✅ **Cifrado columnas sensibles con Jasypt** — paquete `certificates/` (Certificate + Repository + Service + Controller) con CRUD `/api/certificates`. `encrypted_password` y `certificate_data` se cifran con `StringEncryptor` antes de tocar BD y se descifran al leer. GET nunca expone los campos sensibles, solo `passwordConfigured` / `certificateDataPresent`. Verificado: subir un cert con password `SECRETO_DEMO_2026` deja en BD `Ps4/JNT0m+T9CG...` (ciphertext base64). Pendiente: misma técnica para futuras `credenciales_externas` (DEHú/SS/SILTRA) cuando lleguen.
- ✅ **Refresh token revocation** — V12 tabla `revoked_refresh_tokens` (jti + user_id + revoked_at). `JwtService.createRefreshToken` añade `jti` único. `AuthService.refresh` rechaza 401 si el jti está en la denylist. POST `/api/auth/logout` mete el jti en la denylist (idempotente). UI: `AuthApiClient.logout()` se llama desde el botón Logout antes del `clear()` local. Verificado: refresh OK antes de logout, 401 después.
- ⏸ **C2** — Google Sign-In con OAuth2 (aplazado conscientemente hasta que Benjamin genere credenciales en Google Cloud Console). No olvidar.

### VeriFactu / Facturación (legal obligatoria)

#### 📜 Base legal SIF/VeriFactu (confirmada 2026-06-03)

> **Marco normativo vigente** — todas las decisiones de este bloque se toman conforme a:
>
> - **[Real Decreto 1007/2023, de 5 de diciembre](https://www.boe.es/buscar/act.php?id=BOE-A-2023-24840)** — Reglamento de los Sistemas Informáticos de Facturación (SIF). Origen: Ley 11/2021 antifraude.
> - **[Orden HAC/1177/2024, de 17 de octubre](https://www.boe.es/diario_boe/txt.php?id=BOE-A-2024-22138)** — Especificaciones técnicas, funcionales y de contenido. En vigor 29/10/2024.
> - **FAQs AEAT**: [Registro de eventos](https://sede.agenciatributaria.gob.es/Sede/iva/sistemas-informaticos-facturacion-verifactu/preguntas-frecuentes/caracteristicas-requisitos-sif-registro-eventos_.html) · [Integridad e inalterabilidad](https://sede.agenciatributaria.gob.es/Sede/iva/sistemas-informaticos-facturacion-verifactu/preguntas-frecuentes/caracteristicas-requisitos-sif-integridad-inalterabilidad.html) · [Huella/hash](https://sede.agenciatributaria.gob.es/Sede/iva/sistemas-informaticos-facturacion-verifactu/preguntas-frecuentes/huella-hash.html).
>
> **Plazos** — contribuyentes obligados desde **01/07/2025**; fabricantes (nosotros) **9 meses desde 29/10/2024** (~29/07/2025). A junio 2026 BENJAGEST ya debería cumplir.
>
> **Dos modalidades legales** (no hay un "OFF" libre):
>
> | Modalidad        | Hash facturas | QR factura | Envío AEAT tiempo real | Registro de Eventos           |
> | ---------------- | ------------- | ---------- | ---------------------- | ----------------------------- |
> | **VeriFactu**    | Sí            | Sí         | Sí                     | **NO** (AEAT ya tiene todo)   |
> | **No VeriFactu** | Sí            | Sí         | No (a petición)        | **Sí, obligatorio + firmado** |
>
> El antiguo modo `OFF` que tenemos en `verifactu_config.mode` es **ilegal** salvo empresa exenta. Hay que retirarlo y dejar dos modalidades.
>
> **9 eventos obligatorios** en NO VeriFactu (Orden HAC/1177/2024, FAQ AEAT):
>
> 1. Inicio del sistema como "NO VERI\*FACTU".
> 2. Apagado del sistema como "NO VERI\*FACTU".
> 3. Lanzamiento del proceso de detección de anomalías en **registros de facturación**.
> 4. Detección de anomalías de integridad/inalterabilidad/trazabilidad en **registros de facturación**.
> 5. Lanzamiento del proceso de detección de anomalías en **registros de eventos**.
> 6. Detección de anomalías de integridad/inalterabilidad/trazabilidad en **registros de eventos**.
> 7. Restauración de copia de seguridad gestionada por el SIF.
> 8. Exportación de registros de facturación de un período.
> 9. Exportación de registros de eventos de un período.
>
> **Extra**: resumen cada 6 horas de operación + uno justo antes de apagar el sistema. Cada evento **firmado electrónicamente con hash encadenado idéntico al de facturas** (no se mezclan cadenas — una cadena para facturas, otra para eventos). Los eventos **no se envían a AEAT** salvo que los pida; se conservan en el SIF durante el período de prescripción tributaria.
>
> **Estado actual BENJAGEST vs ley** (junio 2026):
>
> - ✅ Hash facturas VF1/VF2 (implementado, cadena reproducible, /verify funcional).
> - ❌ `verifactu_config.mode='OFF'` como opción libre — **incumple**. Sustituir por `VeriFactu`/`NoVeriFactu` (slice VF-OFF-DEPRECATE).
> - ❌ Registro de Eventos del SIF — **no existe**. Es el slice VF-EVENTS. En CONTENDO estaba como pestaña Auditoría con hash encadenado, probado en TEST con AEAT (~25 facturas hasta que aceptaron el hash+QR). Migrar la lógica JS→Java para tener paridad probada.
> - ❌ QR oficial AEAT — hoy es placeholder. Pintar el QR real definido por la Orden (URL al servicio de validación AEAT con CIF emisor + nº factura + fecha + importe). Necesario en ambas modalidades (slice VF3-QR).
> - ❌ Detección de anomalías (eventos 3 y 5) — job que recorre las cadenas y emite alerta+evento si rompe (slice VF-ANOMALY).

> **F1 cerrado (2026-06-01):** dominio de facturas dedicado en `billing/invoices/` (SalesInvoice + InvoiceLine + Repository + Service + Controller). Antes vivía mezclado dentro de WorkspaceRepository genérico; ahora tiene su propio paquete con endpoints `/api/billing/invoices`, lógica de transición DRAFT→VALIDATED, cálculo de totales (subtotal, IVA, retención, total) con BigDecimal HALF_UP y enganche a SeriesService para emitir el número al validar.
>
> **F2/F3/F5 cerrado (2026-06-01, i18n 2026-06-02):** Pantalla "Facturación" en la UI con sub-tabs estilo CONTENDO (Dashboard/Facturas/Configuración), reutilizando `settings-tabs` y `settings-tab-body` sin tocar paletas. V13 + backend `billing/verifactu/` (VerifactuConfig + Service + Controller con GET/PUT `/api/billing/verifactu-config`, defensa PROD-sin-cert → 400). UI: header con icono + botón "Nueva factura" (placeholder hasta F4), tab Facturas con filtros (status/cobro) + tabla, tab Configuración con modo VeriFactu + selector certificado + pie + listado de series read-only. BillingApiClient en `service/`.
>
> **F5+ cerrado (2026-06-01, i18n 2026-06-02):** Ampliación pestaña Configuración paridad CONTENDO. V15 amplía `companies` con 6 textos legales (`invoice_text_exempt`, `_reverse_charge`, `_reduced_vat`, `_rectifying`, `_legal_terms`) + `invoice_show_iban` + `migration_acknowledged_at/_by_user_id`. Backend `billing/texts/InvoiceTexts*` con GET/PUT `/api/billing/invoice-texts`. **Bloqueo de serie por continuidad legal**: si una serie tiene ≥1 factura `VALIDATED` en el año actual, el PUT rechaza cambios de code/format/kind/numberingType con 409 (mensaje explícito; sólo cierre de año desbloquea). Endpoint `POST /api/billing/series/{id}/migrate` permite importar correlativo de otro programa con `{nextNumber, acknowledged: true}`; sin `acknowledged=true` → 400. UI: nuevas secciones "Migración desde otro programa" (ComboBox serie + campo número + checkbox responsabilidad + botón Aplicar) y "Textos legales en la factura" (6 TextArea + checkbox mostrar IBAN). Smoke verde: bloqueo F2026 (que tiene F-2026-0001 validada) → 409, migrate sin ack → 400, migrate con ack=true → next_number=99 aplicado.
>
> **F4 cerrado total (2026-06-02, i18n incluida):** Rediseño completo del editor estilo CONTENDO en `showInvoiceEditor`: header con back/título/badge "PRÓXIMO Nº AL VALIDAR" (formato real, monoespaciado), 3 tarjetas (Cabecera 3-col cliente+detalle/fechas/tipo-pill / Líneas con `decimalColumn`+`liveTextColumn` que comitean en cada pulsación + Subtotal/Total con `computedColumn` (IdentityHashMap-driven) que se actualiza sin `TableView.refresh()` para no perder foco / Totales+Observaciones con TOTAL en gradiente). Footer Cancelar/Guardar borrador/Validar y emitir. `previewNextNumber(SeriesEntry)` replica `SeriesService.formatNumber` (placeholders `{CODE}`/`{YYYY}`/`{0000+}`) en cliente. Manejo "sin precondiciones" cuando faltan clientes/series. **Decisión 2026-06-02**: usuario solo define la serie STANDARD; el editor no muestra combo de serie — el server pica la serie por `invoiceType` (V16 semilla `PROF`+`RECT` por empresa, `SeriesService.findActiveByKind`, `SalesInvoiceService.createDraft/updateDraft` override `seriesId`, `SeriesService.create/update/delete` rechazan kind≠STANDARD). Acciones desde el listado: Validar/Eliminar borrador/PDF placeholder (F4b). Atajos de navegación: botones laterales BACK/FORWARD del ratón con stack `navBack`/`navForward`. Listado limpia "(borrador)" sin id críptico. Pendientes hijas: PDF multipágina (F4b), anulación con vínculo, simplificadas (requiere ampliar enum `invoice_kind`).
>
> **F4b cerrado (2026-06-02):** generación de PDF multipágina. Backend `billing/pdf/InvoicePdfGenerator` (LibrePDF/OpenPDF 2.0.3, LGPL/MPL — compatible con software propietario) con layout: cabecera con título (FACTURA / FACTURA RECTIFICATIVA), número/fechas a la izquierda + datos fiscales empresa a la derecha; bloque cliente; tabla líneas (descripción/cantidad/precio/IVA/subtotal) con header navy + filas alternas; bloque totales agrupado por % IVA + TOTAL FACTURA destacado en azul; textos legales condicionales (rectificativa siempre si está definido + exempt si hay líneas con IVA 0% + reducedVat si hay 4/10% + legalTerms + IBAN si `showIban=TRUE`); pie de página del `companies.invoice_footer`/`InvoiceTexts.pie`. Endpoint `GET /api/billing/invoices/{id}/pdf` con `Content-Disposition: inline` y filename = invoiceNumber.pdf (o draft-shortId.pdf). UI: botón "Generar PDF" en el listado, habilitado para VALIDATED, abre `FileChooser`, descarga vía `BillingApiClient.downloadInvoicePdf(id)`, ofrece abrir con `Desktop.open()` tras guardar. UI module-info: `requires java.desktop` añadido. Pendientes hijas: QR + huella VeriFactu en el PDF (llega con VF3 cuando el cliente AEAT esté hecho), logo empresa (cuando se añada columna `companies.logo_path`).
>
> Los próximos items (firma, almacenamiento, email) se enchufan sobre `SalesInvoiceService.validate` y futuros endpoints. F6 (dashboard real con KPIs y gráficos) pendiente.

- ✅ Series de numeración: paquete `billing/series/` (Series record + Repository con `SELECT … FOR UPDATE` para emisión atómica + Service con reset BY_YEAR + Controller `/api/billing/series`). Tipos soportados: STANDARD, PROFORMA, RECTIFYING, TEST. Anulaciones quedan como evento sobre la factura existente (no es serie nueva — modelo VeriFactu). Smoke tests verdes: 3 claims secuenciales `PROF-2026-0001/2/3`, duplicate code → 409, locked → 409.
- ✅ **Bloque VeriFactu completo (sesión 2026-06-03)** — todo el cuerpo funcional del bloque VeriFactu queda implementado en local. Solo queda probarlo con un certificado FNMT real + ajustar el XSD AEAT para envío:
  - ✅ **VF1** Hash encadenado + registro local (V14, `VerifactuHashService` con fórmula AEAT, hook en `validate`, endpoint `GET /api/billing/verifactu-registry`).
  - ✅ **VF2** Verificabilidad de la cadena hash + huella en PDF (`531cd4f`): bugfix truncado `generation_time` a segundos para que `verify` sea reproducible. Endpoint `GET /verify`. PDF muestra 16 primeros chars del SHA-256. UI botón "Verificar integridad".
  - ✅ **VF-OFF-DEPRECATE** (`d404c08`): el modo OFF dejó de existir. Separación legal `verifactu_modality` (VERIFACTU/NO_VERIFACTU) del environment `verifactu_mode` (TEST/PROD). V17 migra. Hash siempre activo. UI combos separados.
  - ✅ **VF-EVENTS** (`ebd59dc`): Registro de Eventos del SIF encadenado. V18 `sif_event_registry`. Paquete `billing/sif/` con HashService + Service + Repository + Controller. 13 tipos de evento. Hooks `@PostConstruct` (SYSTEM_START) + `@PreDestroy` (SUMMARY_SHUTDOWN + SYSTEM_STOP) por empresa NO_VERIFACTU + hooks en `validate/void` (INVOICE_VALIDATED, INVOICE_VOIDED). Endpoint `/api/billing/sif-events` + `/verify` + `/export`. UI: bloque "Auditoría SIF" en Configuración Facturación con tabla filtrable + Verificar cadena.
  - ✅ **Bugfix núcleo** (`4966ce0`): blindar subárbol `core` contra desactivación. V20 repara empresas rotas + filtro en `list()` + rechazo en `setActive()`. Cierra ruta de bug que tumbaba acceso a Configuración irrecuperablemente.
  - ✅ **SUMMARY_6H** (`3c9369f`): `@Scheduled(fixedDelay=6h, initialDelay=10min)` emite SUMMARY_6H por empresa NO_VERIFACTU. `@EnableScheduling` activado.
  - ✅ **VF3-QR oficial AEAT** (`8ac8113`): dependencia zxing. `InvoiceQrService` con URL exacta de la Orden HAC/1177/2024 (endpoints `prewww2.aeat.es` TEST y `www2.agenciatributaria.es` PROD). 200×200 px → ~25 mm impreso. Etiqueta obligatoria bajo QR: "VERI\*FACTU" o "Factura verificable en la sede electrónica de la AEAT".
  - ✅ **F-STORAGE** (`5304d85`) + selector de carpeta (`f35923e`): V19 `companies.invoice_storage_root` + `sales_invoices.pdf_path`. `InvoiceStorageService` con estructura `{root}/{companyId}/{YYYY}/T{q}/{nº}.pdf`. PDF generado y guardado al validar (copia legalmente vinculante). Endpoint `/pdf` lee del disco. UI: TextField + botón "Examinar…" (DirectoryChooser SO que permite crear carpetas).
  - ✅ **F-EMAIL** (`1a9a3e1`): envío factura por email al cliente. `EmailSenderService` reutilizable + `InvoiceEmailService` con plantilla por defecto. Endpoint `POST /{id}/email`. UI botón "Enviar por email" en listado.
  - ✅ **VF-SIGN MVP** (`acb32a8`): firma XML-DSig de registros y eventos. `XmlSignerService` con Apache Santuario 4.0.2 + BouncyCastle 1.78.1. Carga .p12 vía CertificateRepository, firma enveloped SHA-256, persiste `signature_data` + `signed_at` + `status=SIGNED`. **MVP**: no es todavía XAdES-EPES estricto (sin `SignaturePolicyIdentifier` ni `SignedSignatureProperties`) — ver pendiente VF-SIGN-XADES-AEAT abajo.
  - ✅ **VF-ANOMALY** (`ab43879`): job `@Scheduled(12h)` itera (companyId, mode) en `verifactu_registry` + empresas NO_VERIFACTU y verifica las dos cadenas hash. Emite ANOMALY_DETECTION_INVOICES_RUN/HIT + ANOMALY_DETECTION_EVENTS_RUN/HIT con payload del id sospechoso. Métodos `*ForCompany(...)` sin TenantContext para uso desde scheduler.
  - ✅ **VF4 reintento firma** (`a0371c4`): job `@Scheduled(10min, batch=100)` recorre `PENDING` y reintenta firmar con `XmlSignerService.signForCompany(...)`. Útil cuando el .p12 se sube tarde, falla transitorio, contraseña corregida.
  - ✅ **PROFORMA-FLOW** (sesión 2026-06-03 tardía) — flujo completo de proforma. PDF generado para PROFORMA **no** lleva QR, ni etiqueta de cumplimiento, ni huella VeriFactu, ni placeholder QR (es documento comercial sin valor fiscal); el título cambia de "FACTURA" a "PROFORMA". Acción "A borrador" en el listado cambia el `invoice_type` PROFORMA→NORMAL y reasigna la serie STANDARD, dejando DRAFT para que el usuario revise. Acción "Convertir y validar" hace el cambio + valida en la misma transacción (emite número STANDARD + hash VeriFactu + QR + se almacena PDF). Endpoint `POST /api/billing/invoices/{id}/convert-to-standard?validate=true|false`. Filtro de tipo en el listado: ComboBox `billingTypeFilter` con [Todos, Normal, Proforma, Rectificativa]. Backend lista por invoice_type.
  - ✅ **VF3-SOAP** (`84c25e7`) — **listo pero sin probar contra AEAT real**. `AeatVerifactuClient` con SSL mutual (KeyManager del .p12 de la empresa) + envoltura SOAP con namespaces oficiales `sum:`/`sum1:` (SuministroLR/SuministroInformacion). Endpoints `prewww1.aeat.es` TEST y `www1.agenciatributaria.gob.es` PROD. Tercer paso en `SignatureRetryScheduler.retry()`: recorre SIGNED + modality=VERIFACTU, envía, mapea respuesta a SENT/ACKNOWLEDGED/ERROR con `retry_count<5`. **NO PROBADO** — requiere FNMT representante persona jurídica + alta SIF en sede AEAT.

  **Pendiente VeriFactu real** (cuando haya FNMT + sede):
  - ⬜ **VF-SIGN-XADES-AEAT** — ampliar `XmlSignerService` para producir XAdES-EPES estricto sobre XML canónico AEAT (XSD oficial), no nuestro XML interno. AEAT validará el XSD al recibir; hoy rechazaría. Incluye `SignaturePolicyIdentifier` + `SignedSignatureProperties` + `SigningCertificate`.
  - ⬜ **VF3-SOAP afinado** — parseo real de respuesta AEAT (Aceptado / AceptadoConErrores / Rechazado con códigos exactos), TrustManager con CAs del sistema (hoy permisivo provisional).
  - ✅ **VF-EVENTS-EXPORT** (2026-06-05) — cierra el hueco del controller que solo emitía EXPORT_EVENTS sin entregar fichero. `SifEventExportService` genera PDF A4 horizontal (cabecera empresa + columna por evento con generated_at, tipo, estado, hash_current/previous 16 chars, payload) y CSV con cadena completa para verificación externa. Endpoints `GET /api/billing/sif-events/export.{pdf|csv}` con `from`, `to`, opcional `eventType`. Auditoría DUAL: añade evento `EXPORT_EVENTS` en la propia cadena SIF (cumple Orden HAC/1177/2024 evento 9) + `SIF_EVENTS_EXPORTED` en `audit_events` con SHA-256 del documento para detectar manipulación posterior. UI: bloque inferior en Configuración Facturación → Auditoría SIF con DatePickers (default trimestre) + botones Descargar PDF/CSV. Tras descargar refresca el listado para mostrar el `EXPORT_EVENTS` recién encadenado.
  - 🔵 **VG ordenación columnas en todos los listados** — comentario de Benjamin. Estado parcial: existen `NUMERIC_STRING_COMPARATOR` y `ISO_DATE_COMPARATOR` reutilizables; las tablas de facturas, compras, fichajes, payslips, modelos AEAT, empleados, RETA y series ya los usan en sus columnas numéricas y de fecha. Aplicados además 2026-06-05 a `colSeq` y `colHash` de auditoría, a `colDate` de invitaciones (AUDIT-CHAIN UI fix) y a `sNext`/`sYear` de Series. **Pendiente VG-FULL-SCAN**: barrido sistemático de las 159 cellValueFactory restantes en `BenjagestUiApplication` para garantizar que CADA columna numérica/fecha tenga su comparator correcto. Sin el comparator, JavaFX ordena lexicográficamente y "10" queda antes de "2".

- ✅ **Anulación con vínculo** (2026-06-02, reforzado 2026-06-03) — `SalesInvoiceService.voidValidated()` emite **en una sola transacción** una factura RECTIFYING ya VALIDATED enlazada a la original mediante `original_invoice_id` + líneas con cantidad negativa, y la original queda VOIDED con `rectifying_invoice_id` apuntando a la nueva. Endpoint `POST /api/billing/invoices/{id}/void`. **Decisión 2026-06-03**: la rectificativa por anulación NO pasa por borrador editable — emitirla como DRAFT abriría una ventana para manipular cifras antes del acto legal. Refactor: `validate()` ahora delega en `validateInternal()` para reutilizarse desde `voidValidated()` sin romper proxy AOP (`@Transactional`). Cascada VOIDED + hash VeriFactu en la misma tx. `updateDraft` preserva `invoice_type` y `original_invoice_id` para defensa (aunque ya no hay borrador rect que editar). UI: botón "Anular" en el listado activo solo con VALIDATED, alerta con mensaje "acto legal — no se puede deshacer", al confirmar muestra el nº emitido (RECT-2026-0001) y refresca. Pendiente futuro: rectificativa parcial R1-R5 (flujo aparte que sí pasaría por borrador editable porque ahí el usuario debe revisar las líneas).
- ✅ Almacenamiento documental de facturas (cerrado por F-STORAGE, ver bloque VeriFactu arriba).
- ✅ Envío facturas por email (cerrado por F-EMAIL, ver bloque VeriFactu arriba).
- ⬜ **Obligaciones de fabricante VeriFactu** (auditoría propia del software, ver `VERIFACTU_OBLIGACIONES_FABRICANTE.md` de CONTENDO). Pendiente real — el slice cubre las **declaraciones responsables del fabricante** (no del usuario): registro como SIF en sede AEAT, documento de obligaciones, página pública de cumplimiento. Atacar antes de cualquier despliegue comercial.
- ✅ Importación PDF compras v1 (C3, 2026-06-04) — dependencia Apache PDFBox 3.0.3. `PdfTextExtractor.extract(bytes)` saca texto plano (PDFs con texto nativo, ~80% de facturas de software). `InvoiceFieldsExtractor` v1 con regex calibrados ES: NIF/CIF AEAT, fechas DD/MM/YYYY|YYYY-MM-DD, importes "1.234,56 €", IVA "21%". Devuelve `ExtractionResult{emitterNif, invoiceNumber, invoiceDate, baseAmount, vatPercent, vatAmount, totalAmount, allDetectedNifs, rawTextHead}`. Endpoint `POST /api/purchases/pdf-import` (multipart). UI: módulo Compras con botón "Importar PDF" → FileChooser → dialog con campos detectados.
- ✅ **PDF-EXTRACT v2** (2026-06-04, `aed5619` + `5263a2c`) — motor con preservación de layout X/Y por span (`PdfTextExtractor.extractLayout` extiende `PDFTextStripper`, captura cada `TextPosition` y agrupa por líneas Y con tolerancia). `LayoutDocument` con `LayoutPage > LayoutLine > LayoutSpan`. Heurísticas v2 estilo CONTENDO calendarParser.v3: NIF/CIF AEAT, NIF español labeled, VAT intracomunitario (LU/IE/FR/DE…), VAT con prefijo ES, OCR fixes (NBSP, em/en dash, comillas inteligentes), tabla de totales con cabecera "BASE IMPONIBLE % IVA CUOTA TOTAL", tabla Amazon "IVA % Precio total (IVA excluido) IVA", detector por _signature_ `21% 35,09 € 7,37 €` con cross-check `base+iva ≈ total ±0,10 €`, detector Solred "Total Factura en Euros 169,14 16,91 186,05", blacklist de cabeceras ("Dirección de correspondencia", "Domicilio fiscal", "Billing address"…), SHA-256 del PDF para dedup, validación cruzada de confianza (HIGH/MEDIUM/LOW). Tests: `InvoiceFieldsExtractorTotalsTest` (Bloques Los Llanos), `InvoiceFieldsExtractorAmazonTest` (transcripción ideal Amazon EU intracomunitario), `InvoiceFieldsExtractorAmazonEsTest` (transcripción literal del LayoutDocument del PDF real "LAPICES Y TENAZAS.pdf" — bloquea regresiones).
- ✅ **PDF-TEMPLATES** aprendizaje por proveedor (2026-06-04, `9a866f0` + `63107f7`) — V37 `supplier_extraction_templates` (company_id + supplier_nif UNIQUE + rules JSON + uses_count + last_used_at). `SupplierTemplateService.apply(base, template)` sobrescribe `supplierName`, `vatPercent`, `emitterNif` con los valores aprendidos. UI: diálogo de resultado con TextFields **editables** + botón "💾 Guardar plantilla para este proveedor". **Diseño clave**: la llave de búsqueda es el NIF detectado por el extractor (aunque sea el equivocado, p. ej. el LU de Amazon en vez del W español); el NIF corregido por el usuario se guarda como regla `emitterNif` y se muestra en próximas importaciones. Así la corrección sobrevive a un extractor que falla de forma consistente. Endpoints CRUD `/api/purchases/extraction-templates`.
- ✅ **PDF-AMAZON** específico (2026-06-04, `aed5619` + `5263a2c`) — `SPANISH_NIF_LABELED_PATTERN` + `SPANISH_VAT_PREFIX_PATTERN` aplican solo si coexiste un EU VAT (Amazon ES con LU+W → prefiere W; sin EU VAT no se aplica para no romper facturas nacionales con "NIF: 74668351R" del cliente). `INVOICE_NUMBER_PATTERN` ahora acepta "Número del documento" (contracción `del`). Detector de totales por _signature_ busca el "Total <importe único>" en ±10 líneas alrededor de la fila pct (Amazon ES a veces lo pone arriba, otras abajo) y descarta filas "Total 35,09 € 7,37 €" del subtotal IVA con regex estricta.
- ✅ **PDF-MULTI** facturas en un mismo PDF (2026-06-04, `aed5619`) — `InvoiceFieldsExtractor.extractAll(layout, bytes)` detecta marcadores "Página X de Y" en cada `LayoutPage`, agrupa por marcador "Página 1 de N" y devuelve `List<ExtractionResult>`. `PdfImportController` ahora devuelve siempre array JSON; aplica plantilla por NIF a cada factura. UI: `splitJsonArrayObjects` parte el array top-level (tracking de strings/escapes) y muestra un diálogo por factura en secuencia con sufijo `(i/N)` en el título. CONTENDO ya soportaba este caso de Amazon; queda replicado.
- ✅ **PDF-PURCHASES-PERSIST** (2026-06-05, commits `6866e6b` + `8c95aca` + `[CLEANUP]`) — flujo de gastos cerrado. Decisiones tomadas (ver `docs/legal-compras-gastos.md`):
  - NO se guarda el PDF binario (el usuario tiene el archivo). Solo `document_sha256` para dedup.
  - Tabla `purchase_invoices` reusa la creada en V2 (CREATE TABLE IF NOT EXISTS hizo no-op); V40 añade idempotentemente las columnas nuevas (`supplier_nif`, `base_amount`, `vat_percent`, `vat_amount`, `total_amount`, `document_sha256`, `invoice_index_in_pdf`, `status`, `journal_entry_id`, `notes`, `uploaded_by_*`) + UNIQUE de dedup + FKs.
  - `PurchaseJournalEntryService` crea asiento PGC simplificado al guardar (Debe 600 base / Debe 472 IVA / Haber 400 total) si la empresa tiene cuentas + fiscal_year OPEN. Si falta, la factura se guarda sin asiento.
  - Multi-factura PDF Amazon: una fila por factura individual con mismo SHA + distinto `invoice_index_in_pdf`.
  - Dedup: POST devuelve 409 + id de la factura existente al re-subir el mismo PDF.
  - **DELETE físico** (no VOID) — las facturas RECIBIDAS no entran en VeriFactu/SIF, así que no aplica la obligación de inalterabilidad. Audit_event `PURCHASE_INVOICE_DELETED` se registra ANTES del DELETE para conservar la traza con NIF/nº/total. Reversa el asiento si lo había. Mismo enfoque que A3/Sage/Contasol. Cuando llegue el slice de cierre fiscal, bloqueará DELETE en períodos LOCKED/CLOSED y ofrecerá rectificativa.
  - UI: módulo en sidebar renombrado a **"Compras y Gastos" / "Purchases & Expenses"**. Botón "💾 Guardar gasto" en el diálogo de extracción. Listado con filtros (año), columnas (fecha/proveedor/NIF/nº/base/IVA/total/✓asiento), botón "Eliminar" con confirmación reforzada.

  **Deuda técnica anotada**:
  - ⬜ **PURCHASES-CLEANUP-V2** — la tabla `purchase_invoices` arrastra columnas obsoletas de V2 (`supplier_id`, `category`, `subtotal`, `vat_total`, `retention_total`, `total`, `payment_status`, `document_hash`, `document_file_id`, `active`) más la duplicación con las nuevas (`supplier_name` existe en ambas). NO se pueden eliminar HOY porque `WorkspaceRepository.createPurchase`/`updatePurchase` (sistema viejo genérico que alimenta el dashboard) todavía las usa. Cuando se refactorice WorkspaceRepository (migrar el dashboard a usar `PurchaseInvoiceService`), se puede hacer un V41 que DROP las columnas obsoletas + DROP TABLE `purchase_invoice_lines` y `recurring_expenses` (también de V2, nadie las usa).
  - ⬜ **PURCHASES-CIERRE-FISCAL** — al cerrar el slice de cierre de ejercicio, bloquear DELETE de purchase_invoices cuyo `invoice_date` caiga en un `fiscal_year` LOCKED/CLOSED. Sustituir por flujo de rectificativa: nueva fila con signo negativo vinculada por `rectified_purchase_invoice_id`. Mismo patrón que `sales_invoices.rectifying_invoice_id`.

- ⬜ **OCR para PDFs escaneados** (Tess4J + Tesseract — requiere binario nativo, decisión a tomar). Hoy si el PDF es imagen, devuelve 422 con mensaje claro.
- ✅ **AUDIT-EXPORT** (2026-06-05) — exportación PDF/CSV verificable del registro de `audit_events` por rango de fechas, con opcional `eventTypePrefix`. PDF en A4 horizontal (cabecera empresa con NIF + periodo + contador + nota legal). CSV con todos los campos canónicos incluido hash y prev_hash para verificación externa. Endpoint `GET /api/settings/audit-events/export.{pdf|csv}` bajo `@RequiresRole(OWNER,ADMIN)`. Cada export queda auditado como `AUDIT_EXPORTED` con el SHA-256 del documento. UI: bloque "Exportar para Inspección / Hacienda" con DatePickers (default trimestre actual) en la pestaña Auditoría de Configuración. i18n nuevo helper `tExportsAndChainEn/Es`.
- ✅ **AUDIT-CHAIN** (2026-06-05, V44 + collation fix) — hash encadenado por empresa en `audit_events`. Nuevas columnas `sequence_number`, `prev_event_hash`, `event_hash` (SHA-256 de `prev|seq|company|user|type|entityType|entityId|result|details|createdAtIso`). Backfill cronológico vía procedure con `COLLATE utf8mb4_unicode_ci` explícito (V44 inicial reventaba con "Illegal mix of collations" porque las DECLARE heredaban el `utf8mb4_uca1400_ai_ci` default de MariaDB 11.4). `AuditChainService.computeNext` con `FOR UPDATE` serializado por empresa para concurrencia. `AuditEventRepository.insert` ahora `@Transactional(REQUIRES_NEW)` para que el FOR UPDATE bloquee hasta commit sin afectar la transacción del flujo de negocio. Endpoint `GET .../verify` recorre y recalcula la cadena. `AuditExportService` y el listado UI muestran nombre humano (JOIN con `user_accounts.display_name`, fallback a UUID si el user fue borrado), columna `Seq` y columna `Hash` (12 chars). Botón "Verificar cadena" en la pestaña Auditoría con dialog OK/ROTA.

### Contabilidad (cerrada 2026-06-06 / 2026-06-07)

> Bloque completo cerrado en una sola sesión maratón + post-mortem de recurrentes en 2026-06-07. Esto era una deuda crítica histórica del backlog (la asesoría no puede funcionar sin libros). Resumen de lo que **ya está** y se considera estable:
>
> - V46 **PGC PYMES** completo (RD 1515/2007) sembrado por empresa al alta + shadow companies.
> - **Asiento automático al validar**: factura emitida (SalesJournalEntryService) y compra (PurchaseJournalEntryService) generan asiento contable inmediato con cuentas resueltas por classifier+resolver.
> - **TerceroAccountResolverService** (port del CONTENDO `getOrCreateCuentaTercero`) con dos modos: BY_INDEX (430.001, 430.002…) o BY_NIF (430.B12345678) configurable por empresa (V56).
> - **ExpenseAccountClassifierService + IncomeAccountClassifierService** que clasifican gastos e ingresos por descripción (port del CONTENDO `detectarCuentaPorDescripcion`).
> - **AccountingLearningService** con histórico de cuenta por proveedor + endpoint `/reclassify` y botón UI "Reclasificar asientos" que re-resuelve cuentas tercero y categorías.
> - **V57 sales_invoices.concept + purchase_invoices.concept** + descripción correcta por línea en el asiento.
> - **Asientos manuales libres + bloqueo periodo** (ACC-MANUAL): editor con N líneas, validación cuadre debe=haber, bloqueo si fiscal_year LOCKED/CLOSED.
> - **Libro Diario + Mayor + Sumas y Saldos** (ACC-BOOKS) en módulo Contabilidad UI con filtros (búsqueda + origen + año).
> - **Cuentas bancarias + movimientos + cobros/pagos** (BANK-ACCOUNTS).
> - **Importación Norma 43 + CSV bancario + auto-conciliación** (BANK-IMPORT) con detector de duplicados.
> - **Préstamos + cuadro amortización + cuotas** (LOANS) con asiento automático mensual.
> - **Inmovilizado** (ASSETS-ENTRIES) con asiento de amortización mensual.
> - **Plantillas de asiento recurrentes** (ACC-TEMPLATES) — distinto del motor de recurrentes (esto son plantillas para asientos manuales).
> - **Balance situación + PyG** (REPORTS-CONTABLES) con corte por fecha + comparativa.
> - **Aprendizaje contable UI** (ACC-LEARN-UI) con tabla de feedback y override por cuenta.
> - **Exportación contable a Contasol/A3/Sage** (EXPORT-CONTABLE) + **EXT-IMPORT** inversa.
> - **Motor de tareas recurrentes (cron contable)** (RECURRING + RECURRING-COMPLETO + RECURRING-FINAL + slices 3A-3R) — soporta PURCHASE/SALES_INVOICE/JOURNAL_ENTRY/TEMPLATE_APPLY/LOAN_AUTO_PAY con placeholders extendidos, candidatos detectables, badge "Creada por tu asesoría" cuando el creador no es del tenant.
> - **Numeración entry_number al validar** (DRAFT no lleva número, evita huecos) + eliminación física de gasto borra asiento.
> - **RefreshBus** publish/subscribe central para auto-refresh entre componentes (compras ↔ ventas ↔ diario ↔ recurrentes).
> - **Visor PDF reutilizable** con PDFBox + multi-import gastos/ventas con vista previa.
> - **V59 relax UK tax_identifier para shadow companies** + endpoint start-management + doble click en cliente no vinculado abre la gestión.
> - **Auto-vinculación silenciosa asesoría↔sí misma** (V64) para que el asesor vea "Mi gestión" en el sidebar como primer item.

**Lo que NO entra en este bloque y queda pendiente real**:

- ⬜ **CONS-CIERRE** — confirmación de cierre por etapas con previsualización del asiento de regularización antes de generarlo. Hoy YEAR-CLOSE lo hace en un solo click.
- ⬜ **Consolidación de empresas asociadas** (cuando una asesoría gestione un grupo de PYMES con eliminación de operaciones intragrupo). No es urgente.
- ⬜ **Conciliación bancaria asistida con sugerencias ML** — hoy es por importe+fecha exactos. Sugerir matches "casi-iguales" requiere un poco de heurística adicional.

### RD 8/2019 (fichajes — obligación legal) comentario de benjamin(vamos a ver como Sesame lo hace para incorporar nuevas ideas, o cualquier otra app de fichaje)

- ✅ Arranque C4 (2026-06-04, `fc627ee` y siguientes) — V21 `time_clock_corrections` (art. 34.9 inalterabilidad: correcciones como apuntes vinculados, no modificación del original) + `time_clock_verifications` (art. 35.8 CSV verificación pública). Backend `timeclock/` con TimeClockService.punch (emite CSV automáticamente al fichar) + requestCorrection + verifyByCsv. Endpoints REST: POST `/api/timeclock/punch`, GET `/api/timeclock/employee/{id}/recent`, POST `/api/timeclock/correction`, GET público `/api/public/timeclock/verify?csv=...` (sin auth — RD 8/2019 exige verificación accesible a Inspección de Trabajo). SecurityConfig actualizado para permitir la ruta pública. CSV de 16 chars en alfabeto 32 (~80 bits entropía, evita confusiones humanas: sin I/L/O/0/1). UI: módulo "Fichajes" con botones grandes IN/OUT/BREAK_START/BREAK_END + tabla de últimos 50 + dialog con CSV copiable tras fichar. i18n ES+EN.
- ⬜ **Geolocalización en clients** — clients con `lat`/`lng`/`radio_m`/`geo_policy` + verificación al fichar. [§11.A](gap-analysis-contendo.md). (Fuera del arranque — sesión específica.)
- ⬜ Sincronización offline batches (kioskos sin red). (Fuera del arranque — sesión específica.)
- ✅ **EMP-USER-MAP** (2026-06-05) — bug crítico cerrado. `TimeClockRepository.findEmployeeByUserAndCompany(userId, companyId)` + `TimeClockService.resolveCurrentEmployee()` con `CurrentUserService + TenantContext`. Si no hay ficha, 404 con mensaje legible. Endpoint `GET /api/timeclock/me/employee`. UI: `showTimeClock` lanza `me()` primero; si OK renderiza pantalla normal con employeeId resuelto, si `NotEnrolledException` pinta pantalla amigable con icono ⚠ e instrucciones ("Pide al administrador que te dé de alta en Personal > Empleados"). Multi-empresa: hoy se asume 1 employee por user_id+company_id; selector de N queda anotado para futuro si aparece el caso.
- ✅ **TC-EXPORT** (2026-06-05) — exportación verificable RD 8/2019. `TimeClockExportService` genera PDF (OpenPDF) con cabecera empresa + lista con CSV verificación de cada fichaje + nota legal sobre el endpoint público `/api/public/timeclock/verify`. CSV simple separado por `;`. SHA-256 del documento registrado como `TIMECLOCK_EXPORTED` en `audit_events` para detectar manipulación posterior. Endpoints `GET /api/timeclock/export.{pdf|csv}` con `from`, `to`, opcional `employeeId`. UI: bloque "Exportar para Inspección" con DatePickers (default trimestre fiscal actual) en la pantalla Fichajes. Cierra TC-EXPORT y TC-INSP-API del backlog histórico.

---

## 🟠 ALTA — el corazón del valor de una gestoría

Cuando lo crítico esté cerrado.

### Fiscal y contabilidad

- ✅ **Carga del PGC completo** (PGC-PYMES V46, sesión 2026-06-06) — catálogo PGC PYMES RD 1515/2007 sembrado por empresa al alta + seed en shadow companies. Decisión: no PGC normal (668) por ahora, el PYMES cubre el 95% de los casos.
- ✅ **Reglas fiscales con histórico anual** (F1, ya cerrado en slice "F1: Reglas fiscales con histórico anual") — `fiscal_rules` con histórico por año, mecanismo de copia al año siguiente con ajuste manual de valores AEAT.
- ⬜ **Modelos AEAT específicos pendientes**: 100, 180, 200, 411. ✅ Cerrados: 130 (UI ALTA 2026-06-04), 303 (UI ALTA 2026-06-04), 347 + 390 + 190 (AEAT-EXTRAS 2026-06-06).
- ⬜ Patrones casillas regex (`fiscal_casilla_patterns_180`, 69 patrones). Atado a los modelos pendientes.
- ⬜ Mapeo AEAT (`aeat_campo_mapeo_180`, 32 mapeos). Atado a los modelos pendientes.
- ⬜ Calendario fiscal con vencimientos (`calendario_fiscal_180`). Hoy está integrado en el módulo Modelos AEAT como calendario; pendiente el seed con vencimientos oficiales del año fiscal y las alertas automáticas.
- ✅ **Inmovilizado: cálculo de amortizaciones + vínculo a asientos** (ASSETS-ENTRIES, sesión 2026-06-06) — entity Asset + tabla amortización + asiento contable mensual generado automáticamente.
- ✅ **Cierre de ejercicio con aplicación de resultado** (YEAR-CLOSE, sesión 2026-06-06) — flujo completo: asientos de regularización, asiento de cierre, aplicación de resultado (debe/haber a reservas/PyG), bloqueo fiscal_year status=CLOSED.
- ⬜ Régimen especial de IVA, prorrata, criterio de caja. Pendiente — el catálogo de cuentas lo soporta pero no hay UI para activarlo por empresa ni lógica de prorrata en el cálculo del 303.
- ✅ **TAX-MGR / Gestor de tipos de IVA** (V23, ya cerrado en slice "ALTA: Consolidar config + Gestor tipos IVA + Titulares + PGC") — tabla `vat_rates` configurable por empresa con `kind` (VAT/WITHHOLDING), `code`, `label`, `percent`, `is_default`, `active`, UNIQUE(company_id, kind, code). Seed por empresa con los tipos estándar AEAT (IVA 21/10/4/0 + IRPF 15/7/19). Backend `billing/taxes/` con `VatRate` + Repository + Service + Controller `/api/billing/vat-rates`. UI: bloque CRUD en Configuración → Facturación → Configuración (`vatRatesAuditBlock`) con tabla + crear/editar/borrar. Backlog histórico tenía la deuda apuntada por error; ya estaba implementado desde V23.
- ✅ **OWNERS / Titulares de empresa** (V24, ya cerrado en slice "UI ALTA: Titulares en Configuración → Empresa") — tabla `company_owners` con full_name, NIF, % participación, rol (administrador/socio), régimen SS. Backend `settings/owners/` con `CompanyOwner` + Repository + Service. UI: pestaña Empresa de Configuración con tabla + CRUD. Imprescindible para Modelo 200 cuando se ataque su editor. Backlog histórico tenía la deuda apuntada por error.

### RETA (autónomos)

- ✅ **Backend RETA completo** (L2, sesión 2026-06-04) — Repository + Service + Controller sobre las 7 tablas: perfiles, estimaciones, tramos, cambios base, alertas.
- ✅ **UI RETA** (UI RETA + DEHú, sesión 2026-06-04) — pestañas perfil, estimación, tramos, cambios base, alertas, pre-onboarding.

### Empleados / Nóminas

- ✅ **Backend Employees + Contracts** (L1, sesión 2026-06-04) — employees + contracts.
- ✅ **UI Empleados** (L1 UI, sesión 2026-06-04) — módulo labor con CRUD empleados + contratos.
- ⬜ **Payrolls backend** (PayslipService ya existe desde L1). Tablas existen, falta integrar con asiento contable mensual + cálculo automático desde contrato. Pendiente UI dedicada al ciclo de nómina.
- ✅ **MedicalLeave backend** (sesión 2026-06-09) — paquete `labor.leaves/` con record + Repository + Service+Controller embebido en `/api/labor/medical-leaves`. Tipos típicos: COMMON_DISEASE / WORK_ACCIDENT / MATERNITY / PATERNITY. Estados OPEN/CLOSED/DRAFT, auto-cierra al rellenar endDate. Validación endDate ≥ startDate. UI dedicada queda como pendiente.
- ✅ **SocialSecurityContribution backend** (sesión 2026-06-09) — paquete `labor.ss/` con record + Service+Controller embebido en `/api/labor/social-security`. Filtros ?year/?month/?employeeId. Tipos típicos EMPLOYEE_COMMON, EMPLOYER_COMMON, EMPLOYER_AT_EP, EMPLOYER_FOGASA, EMPLOYER_TRAINING, EMPLOYER/EMPLOYEE_UNEMPLOYMENT, MEI (Ley 21/2021). Estados DRAFT/FILED/PAID con bloqueo de DELETE si !=DRAFT (inalterabilidad). UI dedicada queda como pendiente.
- ⬜ Entrega de nóminas (firma trabajador, fecha, vía).
- ⬜ Incidencias de nómina.
- ✅ Centros de trabajo (`centros_trabajo_180`) — port CONTENDO (sesión 2026-06-10 tarde). V89 tabla `work_centers` + `employees.work_center_id`. Backend CRUD + sub-pestaña "Centros" en Labor.
- ⬜ Reporte coste empresa por empleado.
- ⬜ Le daremos una vuelta a los contratos y a todo lo referente del alta del empleado.

### Asesoría / multi-cliente

- ✅ **Modelo asesoría↔cliente decidido (2026-06-03)** — `parent_company_id` simple 1:N + lectura cruzada en tiempo real con switch de TenantContext (opción A1). Decisión informada por la sesión de contabilidad: NO materializamos asientos en la asesoría, son del cliente.
- ✅ **Mensajes asesoría↔cliente** (sesión 2026-06-09, V77) — `advisory_messages` con direction A2C/C2A para timeline unificado, `from_user_id` NULL si lo crea el sistema, `attachment_path` para PDFs, `read_at` por dirección con índices para badge en sidebar. `AdvisoryMessageService` con `resolveParts()` que detecta rol asesoría/cliente vía `companies.parent_company_id` y calcula la dirección automáticamente. Endpoints `/api/advisory/messages/threads/{otherCompanyId}/{send|mark-read}`. UI pendiente.
- ✅ **Documentos compartidos** (sesión 2026-06-09, V78) — `advisory_documents` con direction A2C/C2A, status UPLOADED→REVIEWED→ACCEPTED|REJECTED con `reviewed_by_user_id` + note + reviewed_at. `AdvisoryDocumentService` con `register/review/delete`, bloqueo de DELETE si status=ACCEPTED por trazabilidad. Endpoints `/api/advisory/documents`. UI pendiente. Upload multipart real queda como sub-slice (hookear con StorageService de PDFs).
- ✅ **Notificaciones específicas de asesor** (sesión 2026-06-09, V78) — `advisory_notifications` con severity INFO/WARNING/URGENT + entity_ref para navegación contextual + read_at/dismissed_at separados. `AdvisoryNotificationService` con `emit()` para que otros services (TaxFiling, Contract, Invitation, AnomalyDetection) creen notis + `listInbox` + `countUnread` + `markRead/dismiss/markAllRead`. Endpoints `/api/advisory/notifications`. UI badge en sidebar pendiente.
- ⬜ Permisos finos por sub-recurso (ej. `configuracion:write` sobre un cliente concreto). Pendiente — hoy el asesor entra como dueño del tenant del cliente.
- ⬜ Vista panorámica de asesoría (cross-client dashboard, vencimientos agregados, operaciones en lote). Pendiente — el listado actual de "Mis clientes" es por cliente, no agrega KPIs ni vencimientos.
- ⬜ **EQUIPO / Reparto de clientes** (decisión Benjamin 2026-06-07). En asesorías con varios empleados, cada empleado lleva su cartera de clientes. Hoy todos los miembros de la asesoría ven todos los clientes vía `company_memberships`. Propuesta — nueva tabla `client_assignments` (asesoria_id, employee_user_id, client_company_id, role_in_client, delegated_to_user_id, delegated_until). En "Mis clientes" cada empleado ve solo los asignados; el OWNER ve todos. Nuevo módulo "Equipo" en el sidebar del OWNER: invita empleados (reusa flujo INVITATION asesoría↔cliente adaptado), asigna/reasigna clientes, marca delegaciones temporales por vacaciones/bajas. Email "Te han asignado al cliente X" usando EmailSenderService (ya implementado en Slice 3 F-EMAIL + 4A presets). Auditoría firma cada acción con user_id concreto del empleado — los `audit_events` ya están encadenados (AUDIT-CHAIN), solo cambia el actor visible. **NO es "Sucursales"** (eso implica oficinas físicas distintas — modelo aparte si llega a hacer falta). Conecta con la Vista panorámica de arriba: el OWNER necesita filtro "Por empleado" para ver carga de trabajo y KPIs por persona. **Diferencial real vs Holded/Quipu** (no llevan asesorías con equipo) y vs A3/Sage (el reparto allí es Excel aparte). Plan: **Slice S1** modelo simple (asignación 1:N empleado→clientes, sin permisos por módulo) primero; **S2** permisos finos por (empleado, cliente, módulo) si la necesidad real aparece después.
- ✅ **Invitaciones asesoría↔empresario** (2026-06-05, V41+V42, varios slices encadenados) — sistema completo de comunicación por token:
  - V41 tabla `advisory_invitations` con token base62 32 chars (~190 bits entropía), caducidad 7 días, estados PENDING/ACCEPTED/REJECTED/EXPIRED/REVOKED.
  - V42 añade estado `UNLINKED` + columna `unlinked_at`. Cuando el empresario desvincula su asesoría, la invitación ACCEPTED original pasa a UNLINKED automáticamente (no se queda colgada como ACCEPTED para siempre).
  - `AdvisoryInvitationService` con `create/listForCurrentAdvisory/revoke` (lado asesoría) + `listPendingForCurrentClient/accept/reject/unlinkCurrentAdvisory/findCurrentLinkedAdvisory` (lado empresario).
  - Aceptar marca `companies.parent_company_id` + auto-sync customer en cartera de la asesoría (`syncClientIntoAdvisoryCustomerPortfolio`) para que pueda facturarle. Cobertura idempotente del UNIQUE global pre-existente en `customers.tax_identifier`.
  - UI: invitación desde "Mis clientes" con modal pre-rellenado, banner Home empresario + pestaña "Mi asesoría", botón "Copiar token" + caja para pegar token manual cuando el email no llega.
  - Botón contextual "Invitar / Reinvitar / Reenviar invitación" según `isLinked()`/`wasUnlinked()`/sin estado.
  - **POLLING-FIX** clave: `AuthSession.authorizeAsOwner` + `AltaApiClient.sendAsOwner` evitan que el polling en modo cliente (`actingForCompanyId` seteado) se envíe con X-Company-Id del cliente y dé 403 silencioso. Si el cliente activo se desvincula durante la sesión, `pollAdvisoryClients` detecta y dispara `exitClientMode()` + showDashboard + toast.
  - **INSTANT-REFRESH**: cada accept/reject/unlink/create/revoke dispara `poll*()` inmediato en el lado que actúa además del refresh local; el polling de 5s sigue siendo red de seguridad para el otro lado. Polling bajado de 30s → 5s en invitaciones y portfolio; DEHú a 15s con helper `setCenterSilent` sin animación.
  - Auditoría de los 5 eventos correspondientes (CREATED/ACCEPTED/REJECTED/REVOKED + UNLINKED).

### Documentos / integraciones externas

- ✅ **Credenciales externas cifradas** (ALTA-5, sesión 2026-06-04) — `credenciales_externas` cifrado Jasypt para DEHú, SS RED, SILTRA. **Valor diferencial cubierto**.
- ✅ **Notificaciones DEHú** (N1 + DEHU-POLLING, sesión 2026-06-05) — backend N1 con `dehu_notifications` + bandeja UI con polling 15s y helper `setCenterSilent` sin animación.
- ✅ **Log de uso de certificados** (ALTA-5 + CERT-IMPORT 2, sesiones 2026-06-04) — `certificate_usage_log` con trazabilidad obligatoria + auditoría al inspect del .p12.
- ✅ **Gestión visual del certificado `.p12`** (CERT-IMPORT 3 + CERT-FIX, sesión 2026-06-04) — pestaña Certificado en Configuración con carga local, inspect (NIF/CN extraídos con LdapName), fechas validez. UI-SCROLL aplicado al diálogo largo.

**Pendiente real**:

- ⬜ **Conector DEHú real** — hoy la bandeja muestra notificaciones, falta el job que descarga del servicio AEAT vía SOAP/REST con el certificado de la asesoría.
- ⬜ **Conector SS RED / SILTRA real** — credenciales guardadas, falta el envío real para nóminas (AFI/CRA/DELT@/CRETA).

### SII (Suministro Inmediato AEAT)

- ⬜ Framework SII completo (estados de envío, reintentos, reconciliación con AEAT).

---

## 🟡 MEDIA — MVP completers + nice-to-have

### UI / UX features que CONTENDO tiene

- ✅ **Lock screen + PIN por inactividad** — PORT-3 LOCK (sesión 2026-06-10, `0139e0a`). Timeline cada 30s + event filters globales en Scene. Stage UNDECORATED modal con PIN PasswordField. Configurable desde "Mi perfil" (0-120 min). [§2.3 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- 🔵 **Command Palette `Ctrl+K`** — buscador global rápido. Implementado el palette + atajos (Ctrl+K abrir, Ctrl+N nueva factura, Ctrl+F facturación, Ctrl+H inicio, F5 refresh, mouse BACK/FORWARD) en sesión 2026-06-02 (i18n incluida). Pendiente: ampliar lista de acciones según vayan saliendo módulos. [§2.2 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Dashboard widgets personalizables** — por usuario, activar/desactivar/reordenar. Layout escritorio vs móvil. [§2.1 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ✅ **Preferencias por usuario** — PORT-3 PERFIL (sesión 2026-06-10, `0139e0a`). V84 tabla `user_settings` (language, pin_timeout_min, screensaver_style, ai_enabled, avatar_path, workday_template). Módulo "profile" con 4 secciones (Idioma, Bloqueo inactividad, IA Copilot reservada, Avatar). [§1 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Backup local automático** — equivalente JavaFX a la File System Access API de CONTENDO. [§3 `gap-analysis-config-ui`](gap-analysis-config-ui.md).

- ✅ **Ui para crear clientes** — PORT-4 CLI (sesión 2026-06-10, `d5cfd05`). V85 ALTER customers + 10 campos nuevos (address, city, province, postal_code, country, internal_code, default_mode, phone, email, website). `CustomerExtendedController` independiente bajo `/api/customers-extended`. `showCustomerDetailDialog` con TabPane (Generales / Dirección postal / Facturación) y 24 campos. Combo de tipo. Validación numérica IVA + retención.

### Compras / pagos / banco

- ⬜ Reconciliación bancaria (`bank_transactions_180`).
- ⬜ Gastos recurrentes silenciados (`gastos_recurrentes_silenciados_180`).
- ⬜ **Multi-allocation de pagos** — un pago se distribuye en partes a varias facturas o trabajos. [§11.E](gap-analysis-contendo.md).

### Workflow trabajos

- ⬜ **Partes de día con validación admin** — empleado crea, admin valida, entonces facturable. [§11.C](gap-analysis-contendo.md). *(Ver bloque PORT-2 abajo en "Fichajes extensión".)*
- ⬜ **Decidir work logs con billing embebido vs separados** [§11.B](gap-analysis-contendo.md) — **CRÍTICO para desbloquear bloque PORT-2 entero**. Sesión 2026-06-10 lo dejó marcado como decisión bloqueante.

### Portal empleado

- ✅ Decisión arquitectura UI — misma JavaFX en modo empleado (no app aparte). Asumida en sesión 2026-06-10 PORT-1 dado que el rol EMPLOYEE ya entra con PIN desde L4-4.
- ✅ Vista calendario empleado — PORT-1 EMP-1 (sesión 2026-06-10, `9602e22`). Tab "Mi calendario" del módulo Portal del empleado. Combina `calendar_events` + `medical_leaves` propios en rango configurable.
- ✅ Nóminas descargables — PORT-1 EMP-2 (sesión 2026-06-10, `9602e22`). Tab "Mis nóminas" lee `payslips` del empleado (read-only). Descarga PDF pendiente (slice futuro EMP-PAY-PDF cuando se cierre el bloque nóminas).
- ✅ Notificaciones empleado — PORT-1 EMP-3 (sesión 2026-06-10, `9602e22`). Tab "Mis notificaciones" lee `advisory_notifications` con target_user_id NULL o = currentUser.
- ✅ Lista trabajos asignados — PORT-1 EMP-4 (sesión 2026-06-10, `9602e22`). Tab "Mis trabajos" placeholder hasta decisión PORT-2 (work logs embebidos vs separados).

### Pendientes 2026-06-10 tarde (tras prueba en vivo)

- ⬜ **JORNADAS — port completo del módulo Jornadas de CONTENDO**. La sub-pestaña "Jornadas" en Labor ahora muestra placeholder. Falta portar `app/admin/jornadas` con 7 componentes: PlantillasPanel (plantillas de jornada por tipo de trabajo), BloquesEditor (bloques horarios dentro de cada plantilla), AsignacionPanel (asignar plantillas a empleados), CopyDiasModal, DeletePlantillaModal, Modal. Las tablas backend ya existen (V86: workday_templates + workday_template_blocks + work_shifts). Falta: services + controllers + UI completa.
- ⬜ **REC-IGNORE — Acción "Ignorar candidato recurrente"**. En el diálogo de candidatos recurrentes solo hay "Crear recurrente". Benjamin pidió poder ignorar uno para que no vuelva a aparecer ("ejecutar / no hacerla recurrente / ignorar"). Requiere: V91 tabla `recurring_candidates_ignored(company_id, kind, party_nif, party_name, amount)` con UK + filtro en `RecurringCandidateService.findCandidates` para excluir los ignorados + endpoint POST + UI botón en cada fila del diálogo.
- ⬜ **CENTROS-MAP — Mapa interactivo para lat/lng de centros de trabajo**. CONTENDO usa GeoPicker (Leaflet + Nominatim). En BENJAGEST (JavaFX) requiere: WebView que cargue HTML embebido con Leaflet + OpenStreetMap tiles + búsqueda Nominatim. Al seleccionar dirección, rellenar TextField lat/lng/ciudad/CP automáticamente. Hoy se introducen a mano.
- ⬜ **MOBILE-EMPLEADO — App móvil/tablet del empleado**. Decisión Benjamin: los partes de día y los datos del Portal del empleado vivirán en una app móvil que el empresario instala en el dispositivo del empleado. El empleado fichará y subirá partes; el empresario los verá en BENJAGEST. Pendiente diseño técnico.

### Fichajes (extensión más allá del legal mínimo) — bloque PORT-2

- 🔵 **PORT-2 — Skeleton CERRADO en sesión 2026-06-10** (`c5bbbcb`). Decisión Benjamin: **embebido como CONTENDO**. V86 creó las 4 tablas (`workday_templates`, `workday_template_blocks`, `work_shifts`, `work_logs` con `is_billable` + `billable_amount` + `billed_invoice_line_id` FK opcional) + módulo "shifts" (INACTIVO por defecto, fas-business-time, display=145). `WorkLogService` con `listForCompany` + `listMine` + `create`. Endpoints `/api/work-logs` (OWNER/ADMIN/ACCOUNTANT) y `/api/work-logs/mine` (EMPLOYEE). `EmployeePortalService.listJobs` ahora lee los partes propios de los últimos 90 días — cierra el placeholder de PORT-1 EMP-4. Sub-items aún pendientes (todos requieren diseño UX de Benjamin):
  - ⬜ UI módulo "shifts" con TabPane (Plantillas / Turnos / Partes / Facturación).
  - ⬜ Plantillas de jornada complejas (días tipo, bloques, excepciones). CONTENDO `app/admin/jornadas` con 7 componentes (PlantillasPanel, BloquesEditor, AsignacionPanel, CentrosTrabajoPanel, CopyDiasModal, DeletePlantillaModal, Modal).
  - ⬜ Asignación plantillas a empleados.
  - ⬜ Turnos rotativos (`turnos_180`, `turno_bloques_180`). CONTENDO `app/admin/turnos` (CrearTurnoForm + page).
  - ⬜ Plannings — asignación masiva (ruta `admin/planings`, 358 líneas en CONTENDO).
  - ⬜ Partes de día con validación admin (CONTENDO 572 líneas). Workflow DRAFT → SUBMITTED → APPROVED → BILLED.
  - ⬜ Conversión work_log → línea de factura. Al cobrar, generar línea de `sales_invoices` con descripción del log + amount = `billable_amount`, setar `billed_invoice_line_id`.
  - ⬜ Fichajes sospechosos (detección de patrones).

### Calendario

- ✅ Festivos nacionales seed — PORT-5 CAL-C (sesión 2026-06-10, `ebc8fa1`). Botón "Cargar festivos nacionales" en cada calendario laboral. 10 festivos fijos + Viernes Santo dinámico (Meeus/Jones/Butcher). Los autonómicos siguen importándose por PDF.
- ⬜ Calendario laboral por empresa (mezcla festivos + cierres propios).
- ⬜ Integración Google Calendar bidireccional (webhooks + mapeo + log sync).
- ⬜ Importación masiva de calendarios.
- ✅ **CAL-IMPORT-MODAL** — ya cerrado en sesiones previas (CAL-FIX block + sesión 2026-06-04). Confirmado como PORT-5 CAL-D en sesión 2026-06-10. Modal side-by-side editable con `HolidayPdfExtractor`. Resto del texto original abajo para referencia histórica de qué requisitos cubría:
- *(Histórico)* — modal de comparación al importar calendario laboral (replicar CONTENDO). Tras detectar eventos desde un PDF/Excel del calendario, abrir un **modal lado-a-lado** con: (a) lista de eventos detectados con tipo (festivo nacional, festivo autonómico, festivo local, cierre empresa, día vacacional, día partido…), fecha, descripción, badge de confianza; (b) lista actual ya en el sistema para ese año. El usuario puede: corregir el tipo/fecha/descripción inline, **añadir** días que el extractor no detectó, **eliminar** falsos positivos, y al pulsar "Volcar" solo se persisten los eventos validados. Mismo extractor de layout/regex que usamos para PDFs de compras (con parsers específicos por formato — calendario laboral CCAA suele venir como PDF tabular o como BOE). Diseño similar a importación de bancos en CONTENDO: previsualización editable antes del commit a BD. Fuera del scope hasta que el módulo Calendario laboral exista; cuando se ataque, abrirlo como slice CAL-IMPORT que reuse `PdfTextExtractor` + un `CalendarParser` específico.

---

## 🟢 BAJA — para más adelante

- ⬜ Alertas de seguridad (`security_alerts_180`) — intentos login, accesos sospechosos.
- ✅ Sugerencias (`sugerencias_180`) — PORT-3 SUG (sesión 2026-06-10, `74f42ae`). V83 tabla `suggestions` + módulo "suggestions". Categorías general/improvement/module/bug/other. Estados new/read/answered/closed. CRUD + modal de alta + confirmaciones.
- ⬜ Análisis BOE (`boe_analysis_180`).
- ⬜ **Acceso PWA / móvil** — el cliente JavaFX deja fuera el caso móvil. ¿Cómo accederán los clientes desde el móvil? [§2.4 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Email personal via Google OAuth2** — a nivel de usuario, distinto del SMTP empresa. [§1 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **AI Copilot flotante** — si IA entra en scope, componente global accesible desde cualquier pantalla. [§2.5 `gap-analysis-config-ui`](gap-analysis-config-ui.md).

---

## ❓ Decisión de alcance — Benjamin decide al llegar el momento

> No hay nadie a quien consultar. Cuando un slice toque uno de estos puntos, Claude propone 2-3 opciones con pros/contras y Benjamin elige. Sólo se le manda un WhatsApp a Pablo si la decisión es irreversible y muy estructural (ej. modelo SaaS vs on-premise).

### Asesoría — decisión tomada 2026-06-03

> **(A) confirmado**: BENJAGEST puede correr como asesoría (`company_type=INTERNAL`) o como cliente (`CLIENT`). El módulo `advisory` (categoría + sub-módulos) **solo** debe aparecer y activarse en empresas INTERNAL — equivalente funcional a CONTENDO. Cliente NO ve gestión de cartera de clientes.
>
> **Evolución abierta (C)**: cuando se ataque el slice de comunicación con la asesoría externa, el módulo `advisory` en una empresa CLIENT podría reusarse como "Mi asesoría" (compartir docs, recibir requerimientos, ver el calendario fiscal que mi gestoría me prepara). Es un cambio de semántica del slug — no necesariamente código nuevo, depende de qué endpoints exponga "Mi asesoría". Lo decidiremos cuando llegue.
>
> **Deuda menor cerrada 2026-06-05 (DUAL-SIDEBAR + V43)**:
>
> - ~~El backend `CompanyModulesService.list()` filtra el subárbol `core` pero NO filtra `advisory_only=TRUE` por `company_type`~~. **Cerrada**: `ModuleAccessService.filterByCompanyType` aplica el filtro en `listActiveForCurrentCompany` y `listCatalog`. Defensa adicional en `CompanyModulesService.setActive` (403 si CLIENT intenta activar advisory_only por POST manual). V43 `UPDATE company_modules SET active = FALSE WHERE advisory_only = TRUE AND company.company_type NOT IN ('INTERNAL','ADVISORY')` limpia restos históricos sin borrar la fila (conserva traza).
> - **Sidebar dual**: cuando `appMode == ADVISORY` y `!actingForClient`, el sidebar se divide en dos secciones — "Mi empresa" (módulos empresariales propios: Personal, Mi facturación, Mis compras, Configuración) y "Mis clientes" (módulos advisory_only: Cartera, Modelos AEAT delegados, Informes asesoría). Al entrar en un cliente, la sección "Mis clientes" desaparece — está actuando como ese cliente y debe ver SU sidebar empresarial; el banner ámbar permite salir. El empresario ve sidebar plano. Hoy solo `advisory` está marcado `advisory_only=TRUE`; si se quieren más slugs separados (Modelos AEAT clientes, Nóminas clientes…), añadir a la columna `advisory_only` del catálogo en futuro slice CATALOG-EXTEND.
> - Sub-módulos de billing (verifactu, sales-invoices, sales-payments…) hoy no se respetan individualmente: los controllers solo comprueban `@RequiresModule("billing")`. Si el usuario apaga `verifactu` como sub-módulo, los endpoints siguen funcionando. Aceptable por ahora — los sub-módulos son útiles principalmente como elementos de organización visual del sidebar; si en el futuro se quiere granularidad real, tocar `@RequiresModule` y `ModuleAccessService` para cascadear (sub-módulo OFF → billing global cuenta como OFF para ese endpoint).

- ❓ Módulo construcción (`cons_*`, 50 tablas). [§11.H](gap-analysis-contendo.md). Es prácticamente otra aplicación dentro de la misma BD.
- ❓ FERRAPP (`ferrapp_proyectos`, `ferrapp_etiquetas_custom`).
- ❓ MCP / IA con quotas (`mcp_ai_*`, `contendo_memory_180`, `conocimiento_180`).
- ❓ Planes y SaaS (`plans_180`). ¿BENJAGEST es SaaS multi-tenant u on-premise? **(Decisión estructural — confirmar con Pablo antes de tocar.)**
- ❓ Páginas legales públicas (privacidad / términos / aviso legal / cumplimiento legal).
- ❓ Onboarding y flujo de alta público (`registro`, `activar`, `verificar`).
- ❓ Migración de datos históricos desde Supabase → MariaDB.
- ❓ Legacy pre-`_180` (`trabajos` 248 filas, `pagos` 39, `categorias` 51, etc.) — ¿valor histórico recuperable? [§11.F](gap-analysis-contendo.md).
- ❓ Tablas `pj_*` (P2P payments FERRAPP). [§11.G](gap-analysis-contendo.md).

---

## Reglas de manejo del backlog

1. **Trabajo siempre desde `feat/Benjamin`**. Se prueba localmente (backend + UI levantados, flujo manual) antes de commitear.
2. **Un commit cierra como mucho un item del backlog** (a veces uno cierra varios; está bien si están relacionados — anotar todos los items cerrados en el mensaje del commit).
3. Cuando un item se cierre, **marcar `✅` con el hash del commit** entre paréntesis (ej. `✅ Issuer CRUD end-to-end (commits 17b251d, adf1766)`) y moverlo al final de su sección.
4. Tras commitear y mergear a `develop` (con `git merge --no-ff`), `git push` a ambas ramas.
5. Si aparece algo nuevo durante el trabajo: **añadirlo aquí en su cubo de prioridad**, no en el código solo.
6. Antes de empezar una sesión: leer este fichero. Antes de cerrar una sesión: actualizar este fichero con lo cerrado y mover items si la prioridad cambió.
7. La sección **"Estado base ya cerrado"** del principio es resumen — no detallar item por item, solo bloques cerrados.
