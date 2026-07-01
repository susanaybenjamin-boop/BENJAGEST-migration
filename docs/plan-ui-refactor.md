# Plan — Troceado de la UI (bloque UIR)

> **Decidido Benjamin 2026-06-30.** Objetivo: desmontar el God Object
> `BenjagestUiApplication.java` (~44.125 líneas, ~145 métodos de pantalla) en
> clases de pantalla independientes, replicando el patrón **ya probado** en el
> proyecto (`screens/AccountingScreen.java`, `screens/ClientFinancialsScreen.java`).
> No es un rediseño: es mover código a su sitio sin cambiar comportamiento.

## Por qué
- El backend está bien modularizado (35 paquetes de dominio). La UI no: una sola
  clase concentra casi toda la interfaz.
- El 18% del archivo (~8.200 líneas) es solo i18n; 24 tipos van anidados; las
  pantallas leen campos privados directamente y se llaman entre sí (`showX()→showY()`).

## Contrato de extracción (el patrón que ya funciona)
Cada pantalla = una clase en `ui/screens/`:
- Constructor recibe `AppContext` (+ su `ApiClient` concreto).
- Expone `public Node buildView()` (o `build*Tab()`).
- El `showX()` del monolito queda como **wrapper fino** que instancia la clase y
  la monta con `setCenterAnimated(...)`. El dispatcher `showModule` no se toca.
- Los campos cacheados de esa pantalla (`TableView`, `ComboBox`, `Runnable refresh`)
  **se mudan dentro** de la clase nueva.

## Fases y slices

### 🟢 FASE 1 — Andamiaje (movimientos puros, sin cambiar comportamiento)
- **UIR-1** — Extraer i18n a `I18n` (`t(Language, key)`); el monolito conserva
  `private String t(String key) { return I18n.t(language, key); }`. Mueve `t` +
  los ~28 métodos `tXxxEn/Es`. Recorta ~8.200 líneas. Riesgo bajo (solo depende de
  `language`). **Preservar la fragmentación** de métodos (límite 64KB de bytecode).
- **UIR-2** — Sacar los 24 records/enums/interfaces anidados a ficheros propios
  (`model/`, `support/nav/`). Incluye `Language`, `AppMode`, `ModuleLink`, los
  `*Bundle`, `*Row`, interfaces funcionales. Hojas sin dependencias.
- **UIR-3** — Helpers **stateless** compartidos en `support/`: `Icons` (icon),
  `Formatters` (money/displayValue + DISPLAY_DATE/CURRENCY_FORMAT), `Dialogs`
  (error/info/toast). El monolito conserva métodos delegados (call-sites intactos).
  > **Decisión 2026-06-30:** se DESCARTA el `AppContext` god-object. El patrón ya
  > usado en el proyecto (`AccountingScreen(apiClient, this::t)`) inyecta dependencias
  > **concretas** por pantalla — es mejor diseño que un contexto-dios. Las pantallas
  > extraídas reciben su(s) ApiClient(s) + la función `t`, e **importan** los helpers
  > stateless directamente. Lo stateful que faltaba (navegación) se resuelve en UIR-4
  > (Router); el async (`start(Task)`) cada pantalla lo gestiona como ya hace
  > `AccountingScreen`.

### 🟡 FASE 2 — Router (cortar llamadas cruzadas)
- **UIR-4** — Interfaz `Router` (`navigateTo(module)`, `setCenter(node)`) que las
  pantallas extraídas reciben (junto a su ApiClient + `t`) para navegar en vez de
  `this.showY()`. El monolito implementa el `Router` durante la transición.

### 🟠 PRERREQUISITO descubierto (2026-06-30) — kit de soporte de pantallas
> Al empezar la Fase 3 se confirmó que **casi toda pantalla arrastra una red de
> helpers compartidos del shell** (`content()`, `errorPanel()`, `iconBubble()`,
> `shortIso()`, `blankToNullOrSelf()`, `installDialog()`, `sectionHeader()`,
> `formLabel()`, los comparadores `ISO_DATE_COMPARATOR`/`NUMERIC_STRING_COMPARATOR`)
> y, en algunas, **estado del shell** (`currentModule`, polling con Timeline,
> `setCenterSilent`). Antes de mover las pantallas medianas/grandes hay que
> extraer ese kit a `support/` + `ScreenBase` y exponer en el `Router` lo que
> falte (`setCenterSilent`, `currentModule()`/`isActive`). Es trabajo mecánico
> pero real: la Fase 3 es **multi-sesión**, no un repetir-y-listo. Hecho ya:
> `ScreenBase` (UIR-5b), `UiBuilders` (label/scroll), `Dialogs`, `Icons`,
> `Formatters`, `BackendErrors`. Pendiente del kit: content/errorPanel/iconBubble/
> shortIso/blankToNullOrSelf/installDialog/comparadores/setCenterSilent.

### 🔴 FASE 3 — Pantalla por pantalla (estado real 2026-06-30)
**Hechos y validados en runtime:** ✅ UIR-5 Sugerencias · ✅ UIR-6 Equipo · ✅ UIR-7 Portal
empleado · ✅ UIR-8 DEHú (polling fiel) · ✅ UIR-9 **Fiscal** (reutilizable standalone+ficha)
· ✅ UIR-10 **RETA** (reutilizable + cross-tab reload). Monolito 44.126 → ~32.000 (−27,5%).

**Patrón "Screen reutilizable" establecido** para operativos incrustados en fichas de cliente:
una clase con `mountStandalone()`/`buildHolder()` o factoría en el shell; estado compartido
(year/refresh) encapsulado; cross-tab reload vía instancia guardada en el shell.

**Pendiente — son MEGA-BLOQUES entrelazados, no pantallas sueltas:**
- **🧾 BLOQUE FACTURACIÓN (XL)** — Facturación + Trabajos + Compras giran TODOS alrededor del
  **editor de factura** (campos `editor*`: `editorLinesTable`, `editorCustomerCombo`,
  `editor*Label`, `editorPendingWorkLogIds` + `recomputeEditorTotals`). Secuencia segura:
  1. **FAC-1**: diálogos auto-contenidos (Migración baselines, Declaración fabricante) →
     `BillingDialogsScreen`. 0 acoplamiento al editor. (Series/IVA: con callback de recarga.)
  2. **FAC-2 ✅ HECHO (2026-06-30, en develop).** Editor extraído a `InvoiceEditorScreen` (shell
     mantiene wrapper `showInvoiceEditor` + interfaz `Host`). Se movió `showImportWorkLogsToInvoice`;
     `showWorkLogBillingDialog` se quedó en el shell para FAC-3 (no tocaba el editor). En la misma
     sesión: bloque AGR (gate de acuerdo TPB para facturar/cobrar) + desglose de IVA por tipo en los
     asientos con 303/390 leídos desde la contabilidad (ver [[memoria facturación]]). Mapa histórico:
  2bis. (mapa original FAC-2) Es todo-o-nada (~1.200 líneas, sin
     sub-paso pequeño seguro; sus helpers son 90% exclusivos). **4 regiones a mover:**
     (a) campos `editor*` (11965-11982: `editorCustomerCombo/InvoiceTypeCombo/InvoiceDate/DueDate/
     NoDueDateChk/NotesArea/LinesTable/Subtotal/Vat/Retention/TotalLabel`, `editorDefaultVat/Retention`,
     `editorPendingWorkLogIds`); (b) núcleo `showInvoiceEditor`→`persistDraft` (12572-13649:
     invoiceEditorView, invoiceCard/CardWithActions/TotalsRow, configureCustomerCombo, previewNextNumber,
     formatDecimalForCell, decimalColumn, applyCustomerVatDefaults, recomputeEditorTotals, persistDraft);
     (c) `showWorkLogBillingDialog` (28558, puente Trabajos→editor); (d) `showImportWorkLogsToInvoice`
     (28755, interno del editor). **ApiClients:** billing+customer+labor+alta. **Contrato:** el shell
     mantiene `showInvoiceEditor(id)` wrapper (`recordNav` + `new InvoiceEditorScreen(...).show(id)`),
     así los **8 callers no cambian**; `showWorkLogBillingDialog` pasa a público y Trabajos lo llama por
     la instancia. **Helper compartido:** `localizedInvoiceTypeLabel` (2 usos fuera) → ScreenBase + copia.
  3. **FAC-3 ✅ RESUELTO POR FAC-2 (2026-06-30).** El acoplamiento Trabajos↔editor desapareció al
     mover `showImportWorkLogsToInvoice` dentro de `InvoiceEditorScreen`. `showWorkLogBillingDialog`
     (shell ~27581) NO toca el editor (solo `billWorkLogs` server-side) → pertenece a la futura
     extracción del módulo Trabajos, no a FAC. Sin trabajo standalone.
  ✅ **FAC-1 HECHO (2026-06-30):** `BillingDialogsScreen` (migración baselines + declaración
     fabricante); shell mantiene wrappers + Host del visor PDF.
  4. **FAC-4 (XL, ~1.800 líneas — SESIÓN DEDICADA, mapa abajo):** `showBilling`→`BillingScreen`.
     - `showBilling` (shell 9570) + `billingView` (9613).
     - `billingInvoicesTab` (9733-10553, ~820 líneas): listado + acciones. Acopla a (vía Host):
       `validateInvoiceFromList`, `voidInvoiceFromList`, `deleteDraftFromList`,
       `showMultiAllocationDialog`, `showBankReconciliationDialog`, `openDueDatesDialog`,
       `showInvoiceEditor`, `openRecurringEditorFromInvoice`, gate AGR-2 (`applyBillingGate`/
       `ensureBillingAllowed`), email/pdf.
     - `billingConfigTab` (10554-~11600, ~1.000 líneas): series CRUD, VeriFactu config,
       certificados, textos legales, `showMigrationBaselines`/`showManufacturerDeclaration`
       (ya en BillingDialogsScreen), `verifyVerifactuChain`. Candidato a `BillingConfigScreen` aparte.
     - `buildRecurringTab` (22176) es COMPARTIDO con compras → se queda o va a un helper común.
     Sugerencia de troceo: FAC-4a config tab → `BillingConfigScreen`; FAC-4b invoices tab → `BillingScreen`.
     - ✅ **Hecho 2026-06-30:** `VatRatesScreen` (tipos IVA/IRPF) + `SifAuditScreen` (auditoría SIF,
       solo lectura) extraídos del config tab vía `buildSection()`. Eran los 2 bloques "dedicados".
     - ✅ **Resto FAC-4a HECHO (2026-06-30):** `BillingConfigScreen` con interfaz `Host`
       (`refreshBillingConfig`/`showMigrationBaselines`/`showManufacturerDeclaration`). Movido tal cual
       `billingConfigTab`→`buildTab` + `showSeriesEditor` + `saveVerifactuConfig`(x2) + `vatRegimeBlock`
       + `saveInvoiceTexts` + migración (`applyMigration`/`importMigrationPdf`/`resolveMigrationSeries`/
       `buildMigrationTokenTagger`/`recomputeMigrationFromTokens`) + `chooseInvoiceStorageDir` +
       `localizedModality` + `verifyVerifactuChain` + `textArea`/`fillIfBlank`/`nzDash` + 23 campos. El
       shell conserva `billingConfigTab(...)` como wrapper (2 call sites intactos). −1.060 líneas del
       monolito. Helpers form copiados local. mvn -pl ui compile OK; merged a develop.
     - ✅ **FAC-4b HECHO (2026-06-30):** `BillingInvoicesScreen` con `Host` (clase anónima en el shell,
       sin cambiar visibilidad de nada). Movido tal cual `billingInvoicesTab`→`buildTab` + 4 campos
       filtros/tabla + `reloadInvoices` + acciones EXCLUSIVAS (`voidInvoiceFromList`/`deleteDraftFromList`/
       `sendInvoiceByEmail`/`convertProforma`/`openInvoicePdf`/`storeInvoicePdf`) + `localizedInvoiceStatus`/
       `localizedPaymentStatus`/`localizedInvoiceTypeLabel`/`mapAllOrValue`. Quedan en shell vía Host (compartidos
       o diálogos grandes): `showInvoiceEditor`, `validateInvoiceFromList` (lo usa también la ficha de cliente),
       `ensureBillingAllowed` (gate AGR-2), `showMultiAllocationDialog`, `showBankReconciliationDialog`,
       `openDueDatesDialog`, `openRecurringEditorFromInvoice`, `buildRecurringCandidatesBanner`,
       `importSalesPdfsMulti`, `showImportSalesButton`. Auto-refresh intacto (acciones emiten TOPIC_SALES →
       screen suscrito). −678 líneas. Helpers form copiados local. mvn -pl ui compile OK; merged a develop.
     - **FAC-4 COMPLETO**: dashboard se queda en el shell (trivial); `showBilling`/`billingView` son el shell
       que orquesta las 4 tabs (dashboard + facturas + recurrente + config) — no se extrae como `BillingScreen`
       aparte (el shell ES la pantalla). `buildRecurringTab` sigue compartido con compras.
- **🏢 BLOQUE ASESORÍA (XL, EN CURSO)** — el composite que incrusta TODOS los operativos en las
  fichas de cliente (`buildClientDetailView` + ~15 tabs). Estrategia leaf-first: extraer las pestañas
  auto-contenidas a screens con Host para lo compartido; `buildClientDetailView` se queda de
  orquestador y se extrae el último. Slices (prefijo AS):
  - `[x]` **AS-1** `buildClientCustomersTab` → `ClientCustomersScreen` (Host: diálogos de cliente
    `openNewCustomerDialog`/`showCustomerDetailDialog`). HECHO 2026-06-30.
  - `[x]` **AS-2** `buildClientConfigTab` (+content/manualResult/localizedConfigCombo/
    showManualFinancialEditor) → `ClientConfigScreen` (Host: `reloadRetaProfiles`). HECHO 2026-06-30.
  - `[x]` **AS-3** `buildClientSummaryTab` → `ClientSummaryScreen` (Host funcional: `buildClientKpisBlock`,
    compartido con AS-4). HECHO 2026-06-30.
  - `[x]` **AS-4** `buildClientSalesArchivedTab` + sus 4 diálogos (duplicados/sin nº/descuadres/editar
    concepto) + `isLikelyRectifying` + `loadClientSalesArchived` → `ClientSalesArchivedScreen` (Host:
    `importSalesPdfsMulti`/`importPdfsAuto`/`openRecurringEditorFromInvoice`). HECHO 2026-06-30. NOTA: el
    orquestador `buildClientSalesAndExpensesTab` se quedó en el shell (embebe purchases/recurrentes/KPIs
    aún sin extraer); se extraerá cuando estén AS-6 y el KPIs block.
  - `[x]` **AS-5** `buildClientTpbAgreementTab` + `renderTpbState` + 7 `tpb*Action` + `showTpbProposeDialog`
    → `ClientTpbAgreementScreen` (TPB RD 1619/2012, magic-link+OTP/PIN, polling 5s). Sin Host (callbacks
    onActivated/onRevoked como params). `tpbDownloadSignedPdfAction`/`tpbRevokeAction` + `humanizeTpb*`
    se quedan/copian en shell (los usa también la vista TPB del cliente). HECHO 2026-06-30.
  - `[x]` **AS-6** `buildClientBillingTab` (+`loadClientBilling`/`isSalesInvoiceRectifying`) →
    `ClientBillingScreen` (Host: editor/validar/importar/recurrente/gate). `buildClientPurchasesTab` NO
    se extrajo: solo delega en `buildPurchasesListing` (módulo Compras compartido). HECHO 2026-06-30.
  - `[~]` **AS-7 — DESCARTADO (decisión Benjamin 2026-06-30):** `buildClientDetailView` es el
    ORQUESTADOR (cablea ~15 tabs + TPB dinámico + polling); sus ~25 dependencias son métodos del shell.
    Extraerlo = Host de ~25 métodos de puro reenvío (boilerplate, net-negativo, riesgo en la manipulación
    dinámica de tabs TPB). Igual que `showBilling` se quedó en el shell en FACTURACIÓN, el orquestador se
    queda. **BLOQUE ASESORÍA CERRADO** con AS-1..AS-6 (todo el contenido extraído a screens).
- **💰 BLOQUE NÓMINA/LABORAL (XXL)** — el último. Mapeado 2026-07-01. El orquestador
  `laborView(bundle)` (5 categorías × 24 sub-tabs, reusado standalone `showLaborModule` +
  embebido `buildClientLaborTab`/`loadLaborIntoHolder`) **se queda en el shell** (igual que
  AS-7/`showBilling`: extraerlo = Host de ~24 reenvíos, net-negativo). Leaf-first, prefijo NOM,
  un Screen por tab (decisión Benjamin 2026-07-01), un commit por slice, `mvn -pl ui compile`:
  - `[x]` **NOM-1** categoría Params (SS rates + bases grupo + IRPF + severance) →
    `LaborParamsScreen`. 4 tablas no-code, cero acoplamiento. Wrappers `buildXxxTab()` en shell;
    `addCol`/`addColSorted` copiados local. −598 líneas (27.109→26.511). HECHO 2026-07-01.
  - `[x]` **NOM-2** `buildWorkCalendarTab`+3 diálogos+modal import PDF → `WorkCalendarScreen` (−811). HECHO 2026-07-01.
  - `[x]` **NOM-3** `buildWorkCentersTab`+editor+geocode → `WorkCentersScreen` (−243). HECHO 2026-07-01.
  - `[x]` **NOM-4** categoría Ausencias (leave requests + bajas IT + vacaciones) → `AbsencesScreen`
    (inyecta laborApiClient+altaApiClient; `root`→viewRoot) (−565). HECHO 2026-07-01.
  - `[x]` **NOM-5** Auditoría fichajes + Config tipos → `TimeClockAdminScreen` (recibe language +
    callback refreshLabor; `root`→viewRoot; copia localizedPunchType/humanizeFromKey). **`buildTimeClockTab`
    (fichar) NO se extrae**: reusa la maquinaria del fichaje personal (punch/reloadTimeClock/timeClockTable),
    zona RD 8/2019 → se queda en shell. (−567). HECHO 2026-07-01.
  - `[x]` **NOM-6** partes/jornadas + planificación + kioscos — troceado en 3 sub-slices (tabs independientes):
    `[x]` **6a** Kioscos → `KioskDevicesScreen` (−198); `[x]` **6b** Planificación jornada →
    `ScheduleTemplatesScreen` (−466); `[x]` **6c** Partes/Jornadas → `ShiftsScreen` (JOR-1/JOR-4/FICHA-REVIEW,
    inyecta labor+alta; −351). `openShiftCreateDialog` era código muerto → se queda en shell. HECHO 2026-07-01.
  - `[x]` **NOM-7** nómina core → `PayslipsScreen` + prerrequisito `SalaryComplementsEditor`:
    `[x]` **7a** `SalaryComplementsEditor` (inner class compartida con contratos) → `ui/support/` reutilizable
    (recibe `t`). `[x]` **7b** `PayslipsScreen`: listado + 10 acciones + calcular (mensual/extra/bonus/
    finiquito) + incidencias + genmonth/batch/extra/settlement + entrega/pagar/pdf/email/borrar. LaborBundle→
    record PayrollData; 2 callbacks (refreshLaborAndJournal + refreshLabor); `root`→viewRoot; copia
    localizedConverter/highlightMissing/clearMissingOnChange. −1.275. HECHO 2026-07-01.
  - `[ ]` **NOM-8** coste empresa + cotizaciones SS → `EmployerCostScreen`.
  - `[ ]` **NOM-9** plantillas de contrato + cláusulas → `ContractTemplatesScreen`.
  - `[ ]` **NOM-10** contratos globales + wizard + editor + docs → `ContractsScreen` (mega, ~2.500 líneas).
  - `[ ]` **NOM-11** empleados + editor + finiquito/despido + suspensiones → `EmployeesScreen` (mega, ~2.000 líneas).
- **Sueltos menores:** Perfil (lock/screensaver Timeline), Calendario (helpers de dashboard),
  Configuración/Settings, Login/Registro (crítico).

## Reglas de seguridad (no negociables)
1. **Un dominio por commit** (revertible).
2. **Cero cambios de comportamiento**: mover, no reescribir. No tocar CSS ni claves
   i18n (reglas duras `CLAUDE.md` §4).
3. Tras cada slice: `mvn compile -q` + **arrancar y abrir la pantalla afectada**
   (no hay tests automáticos → verificación manual es la red).
4. **Aditivo**: `showX()` se queda como wrapper; navegación y dispatcher intactos.
5. Push a `feat/Benjamin` + merge `--no-ff` a `develop` al cerrar cada slice.

## Alcance
Bloque largo (~15 slices, varias sesiones). Cada slice deja el proyecto compilando
y funcionando, así que se puede parar en cualquier punto. La Fase 1 sola ya ordena
mucho. **La subida de versión (`UpdateService.APP_VERSION` + release) se hace una sola
vez, cuando TODO el bloque (hasta UIR-15) esté terminado**, para que llegue como una
única actualización vía auto-update. Hasta entonces, cada slice solo va a `develop`.

## Estado
- [x] UIR-1 (i18n→I18n, −8.684 líneas) · [x] UIR-2 (tipos transversales; bundles diferidos a Fase 3)
  · [x] UIR-3 (helpers stateless Icons/Formatters/Dialogs; AppContext god-object descartado)
  · [x] UIR-4 (Router: navigateTo/setCenter/runTask)
- FASE 3: [x] UIR-5 **plantilla** (SuggestionsScreen; + kit `UiBuilders`/`BackendErrors`) ·
  [ ] UIR-6 RETA/DEHú · [ ] UIR-7 Portal empleado · [ ] UIR-8 Sugerencias/Perfil/Equipo ·
  [ ] UIR-9 Fiscal · [ ] UIR-10 Calendario · [ ] UIR-11 Facturación · [ ] UIR-12 Config ·
  [ ] UIR-13 Asesoría · [ ] UIR-14 Trabajos · [ ] UIR-15 Laboral/Nómina (bloque NOM: [x] NOM-1 · NOM-2..11 pend.)
