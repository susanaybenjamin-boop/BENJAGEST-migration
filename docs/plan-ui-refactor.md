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
     - ⬜ **FAC-4b (invoices tab):** `billingInvoicesTab` (~820 líneas) → `BillingInvoicesScreen` con
       Host para las ~12 acciones (`validateInvoiceFromList`, `voidInvoiceFromList`,
       `deleteDraftFromList`, `showMultiAllocationDialog`, `showBankReconciliationDialog`,
       `openDueDatesDialog`, `showInvoiceEditor`, `openRecurringEditorFromInvoice`, gate AGR-2, email/pdf).
- **🏢 BLOQUE ASESORÍA (XL)** — el composite que incrusta TODOS los operativos en las fichas
  de cliente (`buildClientDetailView` + tabs). Va después de tener los operativos extraídos.
- **💰 BLOQUE NÓMINA (XXL)** — el último.
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
  [ ] UIR-13 Asesoría · [ ] UIR-14 Trabajos · [ ] UIR-15 Laboral/Nómina
