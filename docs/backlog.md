# Backlog operativo BENJAGEST

> **Última actualización:** 2026-06-16 (cerrada la **COLA AUTÓNOMA** salvo lo no
> [ver bloque de estado verificado justo debajo, 2026-06-17]
> prioritario; **topes de cotización SS por grupo** cableados 1+2+3 con cifras
> oficiales 2026; **auditoría completa del ciclo de vida del empleado** con 4
> agentes — contabilidad confirmada correcta, bugs triados; **investigación legal
> del ascenso** → bloque **CONTRATO-VIGENCIAS** decidido. Lo pendiente del bloque
> nómina queda como **PRIORIDAD 1** abajo).
>
> **Forma de trabajo (junio 2026):** Benjamin lidera y decide. Pablo solo entra de uvas a peras desde 05-30. Todo el trabajo va por `feat/Benjamin` → prueba local → commit → merge `--no-ff` a `develop`. Cada item cerrado lleva commit hash + fecha. **Regla 10.bis de CLAUDE.md aplica siempre: verificar código antes de tocar.**
>
> **Fuentes complementarias:** [`gap-analysis-contendo.md`](gap-analysis-contendo.md), [`gap-analysis-config-ui.md`](gap-analysis-config-ui.md), [`migration-roadmap.md`](migration-roadmap.md), [`vf-chain-fix.md`](vf-chain-fix.md), [`agents-debug-pattern.md`](agents-debug-pattern.md).

---

## 📅 SESIÓN 2026-06-20 — FICHAJE-JORNADA + Portal empleado (MEMP-3). En curso.

> Benjamin: terminar el portal del empleado en orden FICHAJE-JORNADA → MEMP-3 →
> MEMP-4 → MEMP-5 y luego testear juntos. Trabajo en `feat/Benjamin` (pusheada);
> **merges a develop pospuestos hasta tras las pruebas** (decisión "testeamos al final").

**Cerrado hoy en `feat/Benjamin` (compila backend+ui):**
- ✅ **FJ-1/FJ-2** *(e61f7b4)* — `ScheduleFichajeService.suggestNextPunch` (resuelve
  plantilla vigente + bloques del día → transiciones IN/BREAK_START/BREAK_END/OUT →
  siguiente que toca con ventana ±15 min) + endpoint `GET /api/empleado/fichaje/sugerencia`.
- ✅ **FJ-3/FJ-4** *(c14663b)* — resaltado del botón que toca: escritorio (renderTimeClock,
  banner `.suggest-banner` + `.punch-suggested`, i18n `timeclock.suggest.*` ES+EN) y PWA
  (pantalla Fichar). No bloquea (solo sugiere).
- ✅ **FJ-5a** *(6267860)* — "Corregir…" accionable en Auditoría de fichajes: diálogo
  TIME_ADJUST/TYPE_CHANGE/VOID → `POST /api/timeclock/correction` (RD 8/2019, no altera
  el original). i18n `labor.audit.correct.*`.
- ✅ **MEMP-3** *(5b90f64)* — "Mi jornada" en la PWA: `GET /api/empleado/jornada` compone
  horario (JOR-2) + jornada real (JOR-1, WorkdayService) + festivo + qué toca (FJ).
- ✅ **MEMP-4** *(17c5f01)* — solicitudes de vacaciones/bajas desde el móvil + aprobación.
  V134 `employee_leave_requests` + `employee_leave_attachments` (BLOB). Tipos VACATION/
  SICK_LEAVE/PAID_LEAVE/OTHER (baja exige adjunto). PWA: pedir/listar/cancelar. Escritorio:
  sub-tab "Solicitudes" en Laboral>Ausencias (aprobar/rechazar + ver adjunto). Aprobar
  VACATION espeja a `employee_vacations` (finiquito). Decidido por Benjamin: 4 tipos +
  aprueban empresario y asesoría (OWNER/ADMIN/ACCOUNTANT).
- ✅ **MEMP-5** *(240e4ad)* — nóminas en la PWA: recibir/descargar PDF/confirmar recibí.
  `PayslipService.listForEmployeeApp/pdfForEmployee/acknowledgeOwn` (guarda de propiedad) +
  `EmployeePayslipController /api/empleado/nominas`. Reusa V116 (delivered_at/acknowledged_at).
  **→ Portal del empleado MEMP-2..5 COMPLETO.**

**Pendiente:**
- ⬜ **FJ-5b** — incidencia "schedule-aware" (esperado-por-jornada vs fichado por día
  cerrado). Toca semántica **legal-sensible** del flag → validar con Benjamin con caso real.
- **Pruebas en vivo pendientes** de TODO lo de hoy (escritorio + PWA) con Benjamin, y luego
  **merges `--no-ff` a develop** por bloque (FICHAJE-JORNADA, MEMP-3, MEMP-4, MEMP-5).
- Posibles mejoras menores tras probar: encadenar sugerencias FJ; que el empresario pueda
  "entregar"/publicar nóminas explícitamente; calendario semanal en "Mi jornada".

---

## 📅 SESIÓN 2026-06-19 — Autónoma (Benjamin fuera hasta 19:00). Bloques A + B (FIN)

> Benjamin dejó cola decidida: cerrar A, B, C, D + GESTOR-NAVEGADOR (E y F a otra
> sesión). Decisiones: bloque D = construir cálculo pero MARCAR para validar juntos;
> FORMATS-EXCHANGE = por especificación; profundidad (100% cerrado) > amplitud.
> Pruebas de flujo a las 19:00 juntos.

**Cerrado y mergeado a develop hoy (compila limpio; backend arranca limpio en puerto
aparte — context Spring + Flyway OK):**
- ✅ **ACC-TEMPLATES UI** *(edded5c → merge ff3c4fc)* — cierra el bloque Contabilidad.
  Nueva pestaña "Plantillas" en `AccountingScreen`: tabla + filtro archivadas; editor
  (cabecera + tabla editable de líneas FIXED/VARIABLE/FORMULA con pista de cuadre y
  validación); aplicar plantilla (fecha + concepto + contabilizar-ya + un campo por
  variable → genera asiento DRAFT/POSTED y refresca el Diario). `AccountingApiClient`
  list/create/update/archive/apply + `AccountingModels.EntryTemplate(+Line)`. i18n ES+EN.
- ✅ **FIN-1 cuadro de mando** *(3002195 → merge 8794dd3)* — `ClientFinancialsService`
  (tenant) reusa `SalesAndExpensesKpiService` + coste personal (64x) + ratios (margen %,
  gasto/ingreso %, personal/ingreso %) + tesorería de COBROS (sales_invoices) + aviso
  de drafts. Endpoint `/api/accounting/financials`. Pestaña "Cuadro de mando" con
  tarjetas KPI + rango de fechas.
- ✅ **FIN-2 evolución mensual** *(956691d → merge ec39dea)* — `monthlySeries(year)` (12
  meses, una query agrupada). Endpoint `/financials/monthly`. Tabla "Evolución mensual"
  bajo las tarjetas.
- ✅ **FIN-3 proyección de cierre + IS** *(7b3c7d3 → merge 88ef72f)* — `projectYearEnd`
  (extrapola YTD a 12 meses + IS 25%, orientativo, NO declaración). Endpoint
  `/financials/projection`. Sección "Proyección de cierre" con 4 tarjetas.
- ✅ **FIN-4 recomendaciones** *(fdb3013 → merge 1af1d7d)* — sección "Recomendaciones"
  con reglas sobre las cifras (vencidas, pérdida, coste personal >40%, margen ajustado,
  IVA a pagar/compensar, drafts). Calculadas en UI para pasar por i18n ES+EN.
- ✅ **FIN-5 informe PDF** *(0aedd89 → merge df560bb)* — `FinancialDashboardPdfService`
  (OpenPDF): resumen + proyección + recomendaciones + evolución mensual. Endpoint
  `/financials/export.pdf` + botón "Exportar PDF".
  **→ Bloque FIN-ANALYSIS (FIN-1..5) COMPLETO.**
- ✅ **REPORTS-PDF (Balance + PyG)** *(0d2e7ef → merge 4582d0d)* — `AccountingReportsPdfService`
  (OpenPDF): PDF del Balance de Situación y de PyG. Endpoints `/reports/{balance-sheet,
  profit-and-loss}/export.pdf` + botón "Exportar PDF" en ambas pestañas + helper `savePdf`.
  Cierra parcialmente "Export PDF de informes": **pendiente Mayor + Sumas y Saldos**.
- ✅ **ACC-TEMPLATES fix UX** *(c012964 → merge fb99233)* — feedback Benjamin: diálogo
  dimensionado (setPrefSize + resizable + CONSTRAINED_RESIZE columnas que no se cortan),
  **enums traducidos** (D/H = Debe/Haber, Tipo = Fijo/Variable/Fórmula vía codeLabelConverter),
  **cuenta = selector del PGC** (TplAccountCell, autocompletar + alta de tercero 4000/4300).
- ✅ **FIN-1b pendiente de pago a proveedores** *(83821d2 → merge ee7e66d)* — saldo acreedor
  400/410 del diario (medida robusta tras la reestructuración V45). Tarjeta + línea PDF.
- ✅ **FIN fix "por validar"** *(ade0d83 → merge bd2c386)* — el contador del cuadro de mando
  contaba TODOS los DRAFT en vez de solo los auto-propuestos (pestaña "Por validar"). Ahora
  coinciden. + atajo "Ir a Por validar" *(e2d6a1e)*.
- ✅ **PAGO-PROVEEDOR — VENCIMIENTOS (PV-1..4, núcleo funcional)** — plan en
  [`plan-pago-proveedor-vencimientos.md`](plan-pago-proveedor-vencimientos.md):
  · **PV-1** V133 `invoice_due_dates` (compras+ventas) · **PV-2** `PaymentScheduleService`
  (vencimientos + pagar contra tesorería 572/570 → asiento 400→572/570, unpay, replace)
  · **PV-3** `DueDateController` /api/due-dates · **PV-4** UI en Compras (botón
  "Vencimientos / Pago": tabla + Pagar banco/caja + **Pagar al contado** (ticket) +
  Deshacer + Editar cuadro). *(622492d, 1fc5192 → merge bbfced7, 11a51d8)*.
  ✅ **PV-5** *(3badeb4 → merge 58da3fd)* — la **conciliación bancaria marca el vencimiento
  como PAGADO** (settleByBankMovement, sin asiento nuevo). Unifica las dos vías de pago.
  ❌ **PV-6 DESCARTADO** (innecesario): el saldo acreedor 400/410 que ya usa FIN-1b **YA
  refleja los pagos** (pagar un vencimiento/conciliar reduce el saldo 400). Leer de los
  vencimientos infracontaría las compras sin vencimiento creado aún. Se queda el 400/410.
  ✅ **PV-7 COBRO POR PLAZOS** *(0c50de3 → merge 49d0110)* (decidido Benjamin: sí, cobrar
  a plazos). Hecho **con unificación** (no sistema paralelo): `syncSalesPaymentStatus`
  proyecta los vencimientos PAGADOS de venta al `payment_status`+`paid_amount` de la factura
  (los vencimientos son la fuente). UI: botón **"Vencimientos / Cobro"** en Ventas (VALIDATED)
  que reutiliza el diálogo de vencimientos con kind=SALES. *Mejora menor: labels del diálogo
  por kind ("Cobrar" vs "Pagar"). Edge case: multi-allocation no pasa por vencimientos.*
  **→ Bloque PAGO/COBRO POR VENCIMIENTOS (PV-1..7) COMPLETO** (PV-6 descartado a propósito).
- ✅ **MEMP-2 fichar desde el móvil** *(420d31b → merge 41f352b)* — `EmployeeFichajeController`
  /api/empleado/fichaje (rol EMPLOYEE, reusa TimeClockService) + pantalla de fichaje en la PWA
  (Entrada/Salida/Pausa/Vuelta + geo + estado + últimos). **Probado en vivo OK** por Benjamin
  (fichaje móvil de Marcos visible en Auditoría). Caso "empresa de servicios" cubierto.
- ✅ **Auditoría fichajes — fixes UX** *(tras feedback Benjamin)* — combo empleado con **"Todos"**
  visible en el desplegable (cellFactory/buttonCell) y seleccionado al abrir; tooltip en el
  resumen explicando que el click de fila filtra el detalle (vía de revisión de la incidencia).
- ✅ **Fix i18n source_type DUE_DATE_PAYMENT** + **CLAUDE.md §4/§10 regla dura de i18n**
  (valores de enum/estado/source_type que el backend genera necesitan clave ES+EN).
- ✅ **Fix filtro Origen (Diario)** *(b1f116e)* — listaba 13 de 19 source_types; faltaban
  nóminas/recurrente/DUE_DATE_PAYMENT/venta-PDF. Ahora completo (verificado contra BD).
- ✅ **Auto-refresh cuadro de mando** *(390fca0)* + **CLAUDE.md §4/§10 regla dura de auto-refresh**
  (toda acción → `RefreshBus.emit`; toda vista/aviso → `subscribe`; el usuario nunca refresca).
- ✅ **ASIENTO MANUAL INTUITIVO (ME-1/2/3)** *(f78bd81, e9da2fc → merge 124d4d0, 886cae2)* —
  plan en [`plan-asientos-manuales-intuitivos.md`](plan-asientos-manuales-intuitivos.md).
  **ME-1** Tab recorre la fila (cuenta→desc→debe→haber→sig. línea). **ME-2** al elegir cuenta
  de tercero (43x/40x) muestra sus facturas pendientes debajo. **ME-3** sugiere cuentas
  (histórico de co-ocurrencia + regla IVA) como botones; clic rellena línea. Backend
  `ManualEntryAssistService` + `/api/accounting/assist/*`. **Probado por Benjamin: mejor que
  CONTENDO.** ✅ **ME-2 fase 2** *(5fc376c)*: facturas pendientes CLICABLES → rellenan la línea
  del tercero + contrapartida tesorería (572) con el importe en el debe/haber correcto.
  Pendiente menor: encadenar sugerencias ME-3; clic en factura podría dejar elegir banco/caja.

> **Validación:** todo compila (backend+ui), el backend ARRANCA limpio (V133 migra OK),
> rutas nuevas 403 (mapeadas/protegidas), la PWA sirve el HTML nuevo. **MEMP-2 probado en vivo.**

**Pendiente del plan (orden sugerido):**
- ⭐ **PRÓXIMA SESIÓN (decidido Benjamin 2026-06-19): terminar el PORTAL DEL EMPLEADO (MEMP)** —
  **MEMP-3** calendario / jornada / plan del día (que el empleado vea SU horario JOR-2 + su
  jornada real JOR-1 + festivos) · **MEMP-4** vacaciones y bajas (pedir desde el móvil, con
  adjuntos) · **MEMP-5** nóminas (recibir/confirmar/firmar/descargar; falta backend de
  entrega/firma). MEMP-2 (fichar) ya está. *Sinergia: MEMP-3 comparte con FICHAJE-JORNADA la
  resolución del horario del empleado; conviene hacer FICHAJE-JORNADA antes o a la vez.*
- ⬜ **FICHAJE-JORNADA** *(pedido Benjamin 2026-06-19)* — botones de fichaje según el horario
  asignado (estilo CONTENDO: ±15 min, "solo el botón que toca"). Plan slice a slice (FJ-1..5,
  incluye la incidencia schedule-aware + acción de revisar/corregir = punto 2) en
  [`plan-fichaje-por-jornada.md`](plan-fichaje-por-jornada.md). **Feature grande → contexto fresco.**
- ⬜ **A restante**: Export PDF de **Mayor + Sumas y Saldos** (Balance+PyG ya hechos) ·
  **FORMATS-EXCHANGE** (xDiario + SUENLACE export/import, por spec, marcar para validar).
- ⬜ **PV-5/6/7** (enhancements de pago proveedor, ver arriba).
- ⬜ **C resto**: MEMP-3 (calendario/jornada) · MEMP-4 (vacaciones/bajas) · MEMP-5 (nóminas) ·
  JOR-4 · partes de día · fichajes sospechosos.
- ⬜ **D entero** (decisión Benjamin: construir + MARCAR para validar): VIG-3 menor
  (guard `hasPayslips`) · VIG-4 atrasos · CV-5 excedencias/suspensiones · CV-8 cese empresa.
- ⬜ **GESTOR-NAVEGADOR (JCEF)** Fase 1 — integración pesada (binarios nativos Chromium);
  pendiente entera. Aviso: posible muro de entorno (descarga libs nativas).
- **Nota puertos:** dejé 8080 y 8090 libres tras validar; al probar a las 19:00 se
  arranca backend fresco con el código nuevo. MariaDB 3307 intacta.

---

## 📅 SESIÓN 2026-06-18 — Jornadas + Portal empleado (PWA) + UX. Estado y pendientes

**Cerrado y mergeado a develop hoy:**
- **PORT-2 / JORNADAS completo** (JOR-1 jornada real desde fichajes + JOR-2/3
  planificación de plantillas). Ver bloque "PORT-2 JORNADAS" en Decisiones.
- **MEMP-1** (portal del empleado, PWA): invitación + activación + login PIN +
  cascarón PWA instalable (iOS arreglado: storage separado → reutilizable +
  "Copiar código" en navegador). Conectividad = **Cloudflare Tunnel** (decidido).
  Ver bloque MEMP en "Decisiones bloqueantes".
- **Máscaras de entrada** (UX global): horas `HH:mm` y fechas `dd-MM-yyyy` con los
  separadores automáticos (`EditableCells.installTimeMask/installDateMask/
  enableDateMaskOnFocus`); conversor de fecha unificado a `dd-MM-yyyy`.
- **Fix `@PathVariable` sin nombre** en WorkScheduleService (rompía asignar/bloques).
- **Editor de bloques rediseñado** tipo CONTENDO (día + copiar a días).

**PENDIENTE — UX-DIMENSIONES (barrido de campos/etiquetas truncadas):**
Diagnóstico con 2 agentes Explore (convergieron). Causa raíz: `Label` en `HBox`
sin `setMinWidth(Region.USE_PREF_SIZE)` + combos/pickers sin `setMaxWidth(MAX)` y
`GridPane` sin `ColumnConstraints` Hgrow. Helper `formLabel(...)` ya creado y
aplicado a los diálogos de horarios (Asignar + bloques). **Falta aplicar el mismo
patrón en** (file:line de BenjagestUiApplication.java, aprox.):
  - Editor de empleado: combos sexo/estado civil/régimen SS (~22383).
  - Editor/wizard de contrato: convenio/categoría/grupo SS/estado (~23867, ~23002, ~23133).
  - Suspender/finiquitar empleado: combos tipo/devengo (~20370).
  - Editor RETA tramos (~36240), calcular nómina objetivo BRUTO/NETO (~21621).
  - Long tail: algún DatePicker dentro de `Dialog<>` que no pase por el helper
    puede necesitar máscara (revisar si aparece sin separadores al usar).

**PENDIENTE — MEMP-2…5** (funciones reales de la PWA del empleado): fichar,
mi jornada/calendario/plan, vacaciones/bajas, nóminas. **Siguiente: MEMP-2 (fichar)**.
Para probar en producción: arrancar backend + `cloudflared tunnel --url
http://localhost:8080` + `BENJAGEST_PUBLIC_BASE_URL`=URL del túnel.

**PENDIENTE — JORNADAS menor:** excepciones por fecha; comparación plan-vs-real (JOR-4).

---

## ✅ ESTADO VERIFICADO — auditoría 4 agentes + verificación manual (2026-06-17)

> Barrido del código (backend + UI) para reconciliar el backlog con la realidad.
> Veredicto: el backlog estaba ~85-90% fiel. Confirmado que la app es muy completa
> en lo on-premise. Trampa recurrente detectada: **endpoint backend ≠ UI**.

**Correcciones aplicadas (estaban marcadas como hechas pero NO lo estaban del todo):**
- ✅ **BANK-IMPORT** (Norma 43 / CSV): **UI hecha 2026-06-17** (sesión autónoma,
  botón "Importar extracto" en pestaña Bancos).
- ✅ **EXPORT-CONTABLE + EXT-IMPORT** (CSV/Contasol/JSON): **UI hecha 2026-06-17**
  (pestaña "Exportar/Importar" en Contabilidad). A3/Sage siguen pendientes en backend.
- ✅ **ACC-TEMPLATES**: **UI de gestión (CRUD) hecha 2026-06-19** (edded5c) — pestaña
  "Plantillas" con editor de líneas FIXED/VARIABLE/FORMULA + diálogo de aplicar con
  variables. Cierra el bloque Contabilidad.
- ✅ **ECPN** (cambios patrimonio neto): **UI hecha 2026-06-17** (pestaña ECPN).
- Matiz **Modelos AEAT 347/390/190**: backend OK pero **editor UI genérico** (JSON);
  solo 130/303 tienen editor específico. **Benjamin pidió editores específicos
  (greenlight)** — pendiente; requiere mapear campos exactos por modelo.
- Matiz **VeriFactu**: NO_VERIFACTU (offline) ✅ completo; envío AEAT (VERI*FACTU) +
  XAdES-EPES estricto 🔵 implementado pero **NO probado contra AEAT** (bloqueado FNMT).

**Confirmado ✅ COMPLETO (backend + UI), antes con dudas:**
- **REPORTS-UI** (Mayor, Sumas y Saldos, Balance de Situación, PyG) — hecho 2026-06-17.
- **REC-BANCARIA** (conciliación asistida) — tiene diálogo UI (verificado).
- Bloques Nómina/NOM, Contratos/CTR, RETA-0..4, VIG-0..3, CV-1..3, TPB, Comunicación,
  Equipo S1, AVISOS-1, Auth/JWT/PIN, Cierre de ejercicio, Calendario fiscal,
  Modelos 130/303, fichaje RD 8/2019 de escritorio + GEO.

**Sesión autónoma 2026-06-17 (Benjamin fuera hasta 15:00) — CERRADO:**
1. BANK-IMPORT UI (`1378858`) · 2. EXPORT-CONTABLE+EXT-IMPORT UI (`0df00e3`) ·
3. ECPN tab (`277cfa7`) · 4. VIG-3 menor / bloqueo fechas (`d6006c0`).
Greenlit por Benjamin pero NO empezados (parado en punto limpio, §11.3, para no
dejar UI compleja sin probar): **ACC-TEMPLATES** (CRUD con editor de líneas),
**editores AEAT específicos 347/390/190**, **FIN-1** (cuadro de mando), **export
PDF de informes**. Plan de cada uno en su sección. Todo compila y mergeado a develop.

**Gaps reales pendientes (no empezados o parciales), por prioridad:**
- 🔴 **N2** clamp BCCC/BCCP + tiempo parcial (ignora `weekly_hours`) + grupos 8-11 (legal; validar caso real).
- 🟠 **N5** incidencias de nómina · **PORT-2 jornadas** (skeleton, falta modelo plantilla) · **ACC-TEMPLATES UI** · **editores AEAT 347/390/190** (greenlit) · **FIN-1** (greenlit).
- 🟡 **FIN-ANALYSIS** FIN-2..5 · export PDF de informes contables · VIG-4 atrasos · régimen especial IVA · OCR · AVISOS-2 cross-cartera (verificar).
- 🔵 **Decisiones/planes sin código:** MOBILE-EMPLEADO (stack) · FICHAJE-MÓVIL/KIOSCO (FM-1..5) · GESTOR-NAVEGADOR (JCEF) · DEPLOY-PKG · CV-4..8 · EQUIPO S2.
- 🔒 **Bloqueado por certificado FNMT real:** VeriFactu estricto/envío AEAT, Modelos 100/180/200/411, conectores DEHú/SS RED/SILTRA.

---

## 🔴 PRIORIDAD 1 — CERRAR EL BLOQUE NÓMINA (2026-06-16)

> Benjamin: cerrar el bloque de nóminas con lo que quede pendiente, antes de
> seguir con el resto de la cola. Orden sugerido:

- **N1 · CONTRATO-VIGENCIAS** (ascenso + derivar grupo) → bloque detallado abajo.
  ✅ **VIG-0/1/2** hechos. ✅ **VIG-3 (UI ascenso)** *(2026-06-17, 4a6d226)*:
  diálogo "Ascender / cambiar condiciones" (fecha de efecto + motivo → /promote,
  nueva vigencia, antigüedad intacta). Sigue **VIG-4** (atrasos). *Pendiente menor
  VIG-3: bloquear edición destructiva de start_date/antigüedad en contratos CON
  nóminas (requiere check backend hasPayslips).*
- **N2 · NOM paso 4 (refinamientos del clamp por grupo)** *(de item #3)* — ⬜
  **PENDIENTE, a validar con caso real (legal-sensible, toca importes):**
  desglose **BCCC/BCCP** (mín del grupo solo en contingencias comunes; mín común
  1.424,40 en AT/EP, desempleo, FOGASA, FP); **tiempo parcial** (base por horas /
  base mínima horaria, leer `weekly_hours` — hoy se ignora, sobrecotiza parciales);
  **grupos 8-11 base diaria** (base diaria × días). Validar con caso real.
- **N3 · NO-CODE de nómina** *(principio Benjamin: nada legal hardcodeado)*:
  ✅ **N3(a)** *(2026-06-17, fdc4df8)*: quitados los fallbacks 2026 a fuego de
  `SsContributionRatesService` e `IrpfRetentionService` → lanzan 422 si la tabla
  está vacía (el fallback al último año ≤ pedido se mantiene). ✅ **N3(b)**
  *(2026-06-17, a2dc7a6)*: topes de **indemnización** (33/720/45/1260/20/360 días,
  exención 180.000€) a tabla no-code `severance_params` (V127, seed 2012 = valores
  actuales, behavior-preserving) + `SeveranceParamsService` + `TerminationService`
  la lee por el año del cese + pestaña "Indemnización" en Laboral. `REFORM_2012`
  (hito legal fijo) se queda en código.
- **N4 · Bugs menores del ciclo de vida** *(auditoría 4 agentes 2026-06-16)* —
  ✅ **CERRADO** *(2026-06-17, 0cdf0e4)*: validación **NIF** (formato laxo como
  CONTENDO + único por empresa) + vacaciones del finiquito a **/365** (criterio
  legal/estándar, barrido A3Nom/INEAF; coherente con la indemnización). El
  `professional_category` ya lo guardaba el wizard (verificado); `markPaid`
  re-pago ya estaba cerrado (0e6c566).
- **N5 · Incidencias de nómina** *(item #4 de la cola)* — portar de CONTENDO:
  horas extra, ausencias/bajas, complementos variables por periodo, que alimentan
  el cálculo. (Toca el cálculo → mismo cuidado legal.)

> Hecho ya del bloque nómina: tabla de bases por grupo (V121) + cifras oficiales
> 2026 (V122) + grupo en contrato (V123) + clamp por grupo + provisión/pago de
> pagas extra + fix IRPF (SS anual acotada). Ver item #3 abajo.

---

## 🔁 FORMATS-EXCHANGE — Export/Import contable por formato estándar (decidido Benjamin 2026-06-17)

> Benjamin: no poner nombres de programas competidores en el combo, y JSON no se
> conoce. Investigación (web): no hay un estándar único, pero **xDiario** (Sage 50/
> ContaPlus/ContaSol/Aplifisa), **SUENLACE** (A3 Wolters Kluwer), **Conta3** (Cegid)
> y **CSV/Excel** (universal) son los formatos de intercambio reales. Dato: A3 carga
> el saldo de apertura EN la cuenta, no como asiento.

**Decisión Benjamin: etiquetar por FORMATO estándar (no por programa) y soportar
CSV + xDiario + SUENLACE.**
- ✅ Interino 2026-06-17: combos traducidos ("CSV / Excel (universal)", "Contasol",
  "Copia BENJAGEST (interna)") + el combo "Datos" traducido.
- ⬜ **xDiario** export+import (backend) — cubre Sage/ContaPlus/ContaSol/Aplifisa.
- ⬜ **SUENLACE** export+import (backend) — A3. Ojo apertura→saldos de cuenta.
- ⬜ Reetiquetar el combo a "xDiario (Sage/ContaPlus/ContaSol)" y "SUENLACE (A3)"
  al tenerlos. El "Contasol" actual probablemente ya sea xDiario-compatible (verificar
  AccountingExportService al implementar). A3/SAGE/XML_ESPI hoy lanzan "no implementado".
- Fuentes: ayudacontasol.sdelsol.com (C662), es-kb.sage.com (Enlace A3), criterium.es.

## 🧾 AEAT-EDITORS — Editores específicos 347/390/190 (greenlit Benjamin 2026-06-17)

> Hoy 130/303 tienen editor campo-a-campo; 347/390/190 usan editor genérico (JSON).
> Benjamin: hacerlos visuales como CONTENDO (le gustan más). **Greenlit, es de lo
> siguiente que quiere.** Necesita: un 347/390/190 real (o de CONTENDO) para copiar
> las casillas fielmente. Backend ya calcula (AeatExtraModelsService).

---

## 📊 REPORTS-UI — Pantallas de informes contables — ✅ HECHO 2026-06-17

> Benjamin: en CONTENDO sí estaban y le gustaban; aquí faltaba la UI (el backend
> ya estaba). Construidas 4 pestañas nuevas en `AccountingScreen`.

- ✅ **Libro Mayor** — pestaña: combo de cuenta + rango → movimientos con saldo
  corriente + saldo apertura/final. `AccountingApiClient.ledger`.
- ✅ **Balance de Sumas y Saldos** — rango + filtro por grupo → debe/haber/saldo
  deudor/acreedor por cuenta + totales. `AccountingApiClient.trialBalance`.
- ✅ **Balance de Situación** — a fecha → Activo vs Patrimonio Neto y Pasivo por
  masas. `AccountingApiClient.balanceSheet`.
- ✅ **Pérdidas y Ganancias (PyG)** — rango → Ingresos / Gastos por masas +
  resultado. `AccountingApiClient.profitAndLoss`.
- ⬜ **ECPN** (`/reports/equity-changes`) — backend listo, UI no añadida (opcional).
- ⬜ **Export PDF** de estos informes — pendiente (mejora).
- Parseo JSON anidado (Balance/PyG) con `extractArrayField` + `splitJsonArray`
  (sin Jackson en UI). NOTA: ACC-BOOKS / REPORTS-CONTABLES estaban marcados ✅
  pero era solo backend; ahora la UI también está. **Pendiente: prueba visual de
  Benjamin.**

---

## 🐞 BUGS UX/NAV GLOBALES (reportados Benjamin 2026-06-16) — ✅ CERRADOS 2026-06-17

> Dos bugs globales de la capa de UI/navegación. Causa ya diagnosticada; fix en
> sesión enfocada (tocan muchos sitios → riesgo de regresión, §11.2). Hacerlos bien.
>
> **CERRADOS 2026-06-17** (feat/Benjamin): BUG-UX-2 en `7cc10fa`, BUG-NAV-1 en
> `6131984`. Pendiente: validación visual de Benjamin (toast nuevo + recarga en
> sitio) antes de mergear a develop. Hallazgo NAV-1: solo Labor (14 sitios) y
> Facturación (2 sitios, CRUD de series) tenían el bug. **Compras** ya refrescaba
> en sitio (`reloadPurchaseInvoices`) y **Contabilidad** usa instancias propias de
> `AccountingScreen`/`ClientFinancialsScreen` sin acceso al centro del padre → no
> tenían el bug. Fix con indirección `laborRefresh`/`billingRefresh` (patrón
> `reloadRetaProfiles`). Helpers nuevos reusables: `toast()`, `highlightMissing()`,
> `clearMissingOnChange()` + clases CSS `.toast`/`.field-error`.

- **BUG-NAV-1 · La acción de un sub-tab pierde los tabs generales.** En "Mi gestión"
  (y la ficha de cliente), que se montan con `buildClientDetailView` (vista con
  pestañas; Laboral = `buildClientLaborTab()`), al **validar una nómina** (y en
  general cualquier acción de los sub-tabs de Laboral) el handler llama a
  `showLaborModule()` → `setCenterAnimated(laborView standalone)`, que **reemplaza
  toda la vista con pestañas** y deja solo los tabs de personal; hay que volver a
  pulsar "Mi gestión". **Causa:** ~15 llamadas a `showLaborModule()` en los handlers
  de acción (grep `showLaborModule()`), pensadas para el módulo standalone del
  sidebar, NO para la vista embebida en ficha. **Fix:** refresco contextual — un
  `Runnable` de recarga que, embebido, refresca el holder del tab en su sitio (como
  ya se hizo con `reloadRetaProfiles` para RETA), y solo standalone use
  showLaborModule. Revisar también Facturación/Compras/Contabilidad por el mismo
  patrón (probablemente igual). Afecta a TODOS los sub-tabs según Benjamin.
- **BUG-UX-2 · Validar sin empleado cierra el diálogo y saca ventana de error.** Al
  calcular nómina sin empleado y pulsar Validar: sale un `Alert` de error Y se cierra
  el diálogo de calcular. **Correcto (Benjamin):** NO cerrar el diálogo, NO sacar
  ventana de error (no es un error, es un campo que falta); mostrar **globo de
  notificación (toast) no modal + sombrear el campo** que falta. **Fix:** en el
  diálogo de calcular nómina, `addEventFilter(ACTION)` en el botón Validar que
  `consume()` el evento si falta el empleado (evita el cierre) + helper `toast()`
  reusable + resaltar el campo. Es un patrón global (vale para todos los diálogos);
  empezar por el de calcular nómina y dejar el helper para reusar.

---

## 2026-06-16 — BLOQUE FICHAJE-MÓVIL/KIOSCO (pedido Benjamin) 📱 — ✅ MÓDULO CERRADO 2026-06-17

> **➡️ PLAN/ESTADO: [`plan-fichaje-movil-kiosco.md`](plan-fichaje-movil-kiosco.md)**.
> ✅ **Backend + frontend del fichaje kiosco/móvil COMPLETO** (2026-06-17):
> FM-1 V129 (tablas) · FM-2 KioskService+interceptor+API · FM-3/4 V130 + página web
> `/api/public/kiosk/app` (activar→PIN→fichar+foto+geo) · FM-admin pestaña "Kioscos"
> en Laboral (alta+código activación+empleados). Decisiones: PIN+QR, foto opcional
> no-facial (AEPD), geo, sin OTP. Verificado: compila, arranca, V129/V130 aplican,
> /app sirve la página, smoke OK.
> **Pendiente FUERA de este módulo:** FM-5 (fichaje→jornadas) = parte de PORT-2
> (jornadas, decisión de diseño pendiente); cola offline = fase 2; deshacer-60s =
> vía correcciones (mejora). Probar en vivo con una tablet/móvil en la LAN.
>
> Benjamin: falta el **fichaje MÓVIL y KIOSCO (tablet)** con **invitación**, igual
> que en CONTENDO. Por ley (RD-Ley 8/2019). **Bloque grande → contexto fresco.**
>
> ✅ **FM-0 explorado** (agente, 2026-06-16). Modelo CONTENDO + qué hay en BENJAGEST:
> - **Reusar (ya existe):** `time_clock_events` (V2, = `fichajes_180`) con geo
>   (GEO-FICHAR) + cadena hash + correcciones/verificaciones (V21, RD 8/2019);
>   `work_centers` con lat/lng + radio + `geo_policy` none/info/soft/strict (V89,
>   = `centros_trabajo_180` + `geoValidator.js`); `daily_work_reports` (V2, =
>   `jornadas_180`); `device_tokens` + `employees.pin_hash` (V70).
> - **Falta (crear):** tablas de KIOSCO + OTP + cola offline. CONTENDO:
>   `kiosk_devices_180` (device_token secreto + offline_pin), `kiosk_activation_tokens_180`
>   (token QR 30 min), OTP por email/SMS, offline sync.
> - **Fichaje** CONTENDO: `POST /api/fichaje` (tipo entrada|salida|descanso_inicio|
>   descanso_fin; subtipo pausa_corta|comida|trayecto; lat/lng/accuracy). Kiosco:
>   /activate, /config, /identify, /estado, /fichaje, /otp/request, /void (60s).
> - **Invitación (matiz):** en CONTENDO el kiosco se EMPAREJA con QR (token 30 min),
>   no hay invitación por email de empleados. Para BENJAGEST local: el OWNER habilita
>   al empleado (PIN, ya existe) + empareja la tablet con QR. ⚠️ Confirmar con Benjamin
>   si "invitación" = habilitar empleado + QR de tablet, o algo más (p.ej. enlace al móvil).
>
> Plan de implementación (fresco, slice a slice, compilar+verificar):
> - **FM-1**: migración kiosco (`kiosk_devices`, `kiosk_activation_tokens`,
>   `kiosk_employee_assignments`) + `otp_codes`. Additive, NO tocar AuthService core.
> - **FM-2**: `KioskController` (Java) + `KioskTokenInterceptor` (header `KioskToken`),
>   reusando `TimeClockService`/`time_clock_events` para crear el fichaje. OTP por email
>   (SES ya existe). Geo validada con `work_centers.geo_policy` (reusar).
> - **FM-3 (móvil web)**: página de fichaje servida por el backend, accesible desde el
>   móvil en la LAN (entrada/salida/pausas + geo). Responsive.
> - **FM-4 (kiosco)**: pantalla completa (idle→identificar→confirmar→OTP/PIN→éxito,
>   ventana de deshacer 60s); la misma web en modo kiosco o vista JavaFX. + cola offline.
> - **FM-5**: que lo fichado alimente jornadas/partes (PORT-2) y el calendario.
> Coherente con despliegue local ("todo es un puesto").

## 2026-06-16 — BLOQUE CONTRATO-VIGENCIAS (decidido por Benjamin) 🔵

> Decisión Benjamin tras barrido legal + competencia (A3Nom/Nóminasol/Factorial):
> el ascenso/cambio de condiciones se modela con **VIGENCIAS con fecha de efecto**
> sobre el MISMO contrato (no contrato nuevo; antigüedad intacta; variación SS no
> SEPE). Detalle en memoria `project_benjagest_ascenso_vigencias.md`. Bloque grande
> que toca el motor de nóminas → hacer con contexto fresco, slice a slice, validar.

- ✅ **VIG-0 (derivar grupo)** *(2026-06-16, commit 06c99d7)*: V124
  `professional_categories.ss_contribution_group` (1-11) + seed por categoría
  (~80 filas, defaults editables). `ContractCatalogService` expone el campo; el
  **asistente** de contrato deriva el grupo de cotización de la categoría elegida.
  *Pendiente menor: editor de catálogo de categorías en la UI para ajustar el
  grupo por categoría (hoy se ajusta por-contrato en el editor plano); revisar los
  defaults del seed por convenio.*
- ✅ **VIG-1 (tabla)** *(2026-06-16, ba3c754)*: `contract_vigencias` append-only +
  backfill (una vigencia inicial por contrato, effective_from = start_date).
- ✅ **VIG-2 (resolución en motor)** *(2026-06-16, 071639f)*:
  `PayslipService.resolveActiveContract` lee la vigencia vigente a la fecha del
  periodo (COALESCE con fallback al contrato). Behavior-preserving con 1 vigencia;
  query verificada contra BD. **Validar con caso real al ascender.**
- ✅ **VIG-3** *(backend a8bd3ab 2026-06-16; UI 4a6d226 2026-06-17)*: create()/update()
  sincronizan la vigencia (alta=crea inicial; editar=actualiza la última);
  `promote()` + endpoint `POST /contracts/{id}/promote` = ascenso con fecha de
  efecto (nueva vigencia, antigüedad intacta). **UI hecha**: botón "Ascender /
  cambiar condiciones" en el diálogo de contratos del empleado → editor en modo
  ascenso (fecha de efecto + motivo, bloquea tipo/SEPE/fechas/antigüedad/estado,
  valida la fecha con toast). `LaborApiClient.promoteContract`.
  ⬜ **Pendiente menor**: bloquear edición destructiva de start_date/antigüedad en
  contratos CON nóminas en el editor normal (requiere check backend hasPayslips).
  Distinguir cambio de categoría (variación SS) vs cambio de tipo de contrato
  (novación SEPE 100/200/300). + e2e real al ascender.
- **VIG-4 (atrasos de convenio)**: cálculo de atrasos comparando vigencias en el
  periodo afectado (caso de uso que justifica el histórico). Para más adelante.

## 2026-06-15 — PROPUESTA: GESTOR-NAVEGADOR (navegador embebido a AEAT/DEHÚ/SS RED/SILTRA) 🌐

> Idea de Benjamin: un tab por cliente (y para la propia asesoría) con un
> **navegador embebido con pestañas** a DEHÚ, AEAT, SS RED y SILTRA, logueado con
> el **certificado** ya importado del cliente, persistente hasta cerrar el
> programa. (CONTENDO lo intentó vía API/conexión directa y fue inviable.)

**Opinión crítica (Claude):** alto valor y diferencial, PERO el login con
certificado es el punto crítico:
- **JavaFX WebView NO sirve** (WebKit antiguo, sin TLS de cliente ni Autofirma →
  renderiza pero falla el login). Hay que embeber **Chromium real**: **JCEF**
  (gratis, integración pesada) o **JxBrowser** (de pago, soporta client-certs).
  Ambos suman ~150 MB al instalable.
- "Auto-login sin prompt inyectando el .p12" es lo más caro y sensible (cert en
  memoria, aislado por cliente). Sesiones AEAT/SS caducan en su servidor igual.
- **Fases:** Fase 1 = pestañas embebidas persistentes + el usuario elige el
  certificado una vez por sesión (ya enorme). Fase 2 = inyección automática del
  certificado. 
- **Cuándo:** tras cerrar la cola actual y tener el instalable (afecta peso/
  empaquetado). Fase 1 primero.
- ✅ **DECIDIDO (Benjamin 2026-06-16): usar JCEF** (gratis, siempre sin coste; NO
  JxBrowser de pago). Crear el tab en modo asesoría **por cliente** con navegador
  embebido con pestañas. **Tarea de última prioridad**: solo si se termina TODO el
  resto del backlog. Fase 1 (pestañas persistentes + el usuario elige certificado
  una vez por sesión) primero.

---

## 2026-06-15 — 🚀 COLA AUTÓNOMA (decisiones cerradas por Benjamin)

> Benjamin se va a trabajar y deja esta cola decidida para trabajo autónomo
> (CLAUDE.md §11: commit por slice + merge develop + compilar antes de commitear;
> reportar a la vuelta). Orden de ejecución y decisiones:

1. ✅ **CLIENT-CONFIG + fix no-vinculados** *(2026-06-15)* — #1 `ensure-operativa`
   (auto-activa módulos al entrar al cliente) + #2 tab "Configuración" (V119:
   cifras manuales anual/trimestral + datos de gestión: periodicidad/régimen/
   contacto/notas). Pendiente menor: toggles de módulos manuales en el tab (hoy
   auto-activados). [Spec original abajo.]
   **CLIENT-CONFIG + fix no-vinculados** — tab "Configuración" (2º lugar) en la
   ficha. **Decisión:** **auto-activar los módulos operativos** del cliente al
   gestionarlo desde la asesoría (no más error "módulo no activo") **+ toggles**
   de módulos por cliente en el tab Config. Secciones del tab: (a) datos
   fiscales/identidad (reusar `companies`/`customers` + ACT-CATALOG ya hecho);
   (b) cotización RETA manual (acceso directo a perfil); (c) **cifras manuales
   sin contabilidad: ANUAL obligatorio + desglose TRIMESTRAL opcional** (tabla
   nueva, alimenta RETA/KPIs/avisos); (d) preferencias (módulos activos, contacto,
   notas internas). Que cargar un no-vinculado NO dé error.
2. ✅ **AVISOS** *(2026-06-15)* — `PendingTasksService` (8 buckets) per-empresa +
   cartera; entrada "Tareas pendientes" en sidebar + panel con toggle Esta
   empresa/Cartera + tarjetas por severidad + "Abrir". Vale para empresario.
   Pendiente menor: badge total en la campana; añadir RETA/contratos como buckets.
   [Spec abajo.] **AVISOS** — per-empresa + cartera + empresario.
3. ✅ **Topes cotización TGSS + asiento pagas extra** *(2026-06-16)*. Pasos 1+2+3
   cableados (decisión Benjamin: hacerlo según la ley):
   - Tabla no-code de bases por GRUPO (V121) + cifras OFICIALES 2026 (V122, Orden
     PJC/297/2026) + pestaña "Bases por grupo" en Laboral.
   - **Paso 1**: V123 `employment_contracts.ss_contribution_group` (1-11, default
     7) + desplegable en el editor de contrato.
   - **Paso 2**: `PayslipService` acota la base al [mín del grupo, máx común]
     leído de la tabla por año (no-code); fallback al tope global si no hay grupo.
   - **Paso 3**: provisión MENSUAL de pagas extra no prorrateadas (640→465) +
     asiento de pago de la paga extra (465→4751/572, sin SS). Asientos DRAFT,
     try/catch, aditivos (las pagas extra no generaban asiento antes).
   - ⬜ Refinamientos PENDIENTES (paso 4, en memoria): desglose BCCC/BCCP (mín
     común para AT/EP en grupos 1-3 bajo mínimo), tiempo parcial (base horaria),
     grupos 8-11 base diaria. **A VALIDAR por Benjamin contra un caso real** los
     asientos de pagas extra antes de confiar.
4. **Incidencias de nómina** — **igual que CONTENDO** (localizar su modelo en
   `C:\Proyectos\CONTENDO GESTIONES` y portarlo): horas extra, ausencias/bajas,
   complementos variables por periodo, que alimentan el cálculo de la nómina.
5. **FIN-ANALYSIS completo** — FIN-1 (cuadro de mando: ingresos/gastos/margen/
   beneficio/coste personal %/tesorería/ratios) + FIN-2 (evolución mensual e
   interanual) + FIN-3 (proyección cierre + IS) + FIN-4 (recomendaciones para
   mejorar beneficio) + FIN-5 (informe PDF). Reusar `SalesAndExpensesKpiService`,
   `AdvisoryDashboardService`, year-close.
6. **JORNADAS UI (PORT-2)** — modelo **CONTENDO**: 1 plantilla = N bloques
   horarios, adjudicable a M empleados; partes reportados en solo-lectura hasta
   la app móvil. Backend skeleton ya en V86/V88.
7. **Asistente de ALTA de empleado completo** — wizard: datos → contrato
   (SEPE/convenio) → acceso app/PIN → perfil RETA si procede, con validaciones.
8. **Partes de día (work_logs) lado asesoría** — workflow DRAFT→APROBADO→
   FACTURADO + convertir parte aprobado en línea de `sales_invoice` al cobrar.
9. **OCR (Tess4J + Tesseract)** — integrar OCR para PDFs escaneados (importación
   facturas/calendario) + **anotar en DEPLOY-PKG** que el instalable Windows debe
   empaquetar el binario Tesseract.
10. **CENTROS-MAP** — ❌ NO por ahora (Benjamin: nos quedamos con el geocoder por
    texto).
11. ✅ **RETA-4** *(2026-06-15)* — V120 `companies.legal_form` + combo en tab
    Configuración; AUTONOMO → auto-perfil RETA de la empresa; ensure al abrir
    Perfiles. *Pendiente menor: combo también en Configuración→Empresa del
    empresario.* [Spec original:]
    **RETA-4 — forma jurídica + perfil RETA garantizado** *(decisión Benjamin
    2026-06-15)*. Añadir **forma jurídica** a la empresa (combo: AUTONOMO, S.L.,
    S.A., S.L.U., S.C., C.B., COOPERATIVA, OTRO) editable en el perfil de la
    empresa (empresario y asesoría, vinculado y no). Regla: si **AUTONOMO** → el
    propio cliente es el autónomo → auto-crear su perfil RETA con nombre/NIF de la
    empresa. Si es **sociedad** → exigir los datos del **titular OWNER** que
    cotiza RETA (company_owner ss_regime=RETA) → perfil desde el titular. Así
    SIEMPRE hay perfil RETA. (Extiende RETA-2.) Migración nueva (companies.legal_form).
12. 🔵 **FICHA-TABS — agrupar pestañas de la ficha** *(parcial 2026-06-15)*.
    ✅ **Contabilidad** agrupada en sub-tabs {Diario/Validar, Bancos, Préstamos,
    Inmovilizado}. ⬜ **Facturación** {Ventas, Clientes, Config, TPB} PENDIENTE:
    el TPB se añade/quita dinámicamente a la barra principal (onTpbActivated/
    onTpbRevoked insertan por índice y quitan por etiqueta); agruparla exige
    reescribir esa lógica para apuntar al sub-TabPane. Hacerlo con cuidado.

**AL TERMINAR LA COLA (pedido Benjamin 2026-06-15, con agentes/equipo):**
- **SEC-AUDIT** — barrido de seguridad del proyecto completo (inyección SQL,
  fuga multi-tenant, authz/`@RequiresRole`/`@RequiresModule`, secretos, cifrado
  Jasypt, validación de entrada, path traversal en ficheros, etc.) y **corregir**
  lo encontrado. Usar agentes Explore en paralelo (CLAUDE.md §2).
- **I18N-AUDIT** — verificar que TODO pasa por `t(key)` con par ES+EN y que no
  queda nada hardcodeado, **incluidos los listados/combos/enums**. *Matiz: el
  catálogo CNAE/IAE son términos legales oficiales en español (no se traducen);
  el resto de la UI sí.* Hacerlo en el mismo barrido de agentes que SEC-AUDIT.

**Bloqueado (no tocar hasta tener certificado FNMT real):** VeriFactu estricto
(XAdES/SOAP), obligaciones fabricante SIF, Modelos AEAT 100/180/200/411,
conectores DEHú y SS RED/SILTRA reales. **Para el final:** DEPLOY-PKG, CV-4..8.

---

## 2026-06-15 — CLIENT-CONFIG: tab "Configuración" en la ficha del cliente (plan)

> Decisión Benjamin: cada cliente de la asesoría tendrá un tab **"Configuración"
> en 2º lugar** (tras Resumen). Sirve para clientes **sin vínculo** (sin
> contabilidad en BENJAGEST de la que extraer datos) y para que no falle nada al
> cargar. **"Mi gestión" = solo la gestión de la propia asesoría**; lo
> cross-cartera va a notificaciones/banners (ver AVISOS).

**Contenido (las 4 secciones elegidas):**
- ⬜ **Datos fiscales/identidad**: NIF, régimen fiscal, epígrafe IAE/CNAE,
  dirección, periodicidad de modelos (mensual/trimestral). Parte ya existe en
  `companies`/`customers`; consolidar aquí.
  - ✅ **ACT-CATALOG** *(2026-06-15)* — catálogo OFICIAL CNAE-2009 (INE, 1010) +
    IAE (AEAT, 908) en `activity_catalog` (V118), descargado de las fuentes
    oficiales. Endpoint `/api/reta/activity-catalog?type=`. Editor RETA: combos
    CNAE/IAE **filtrables al teclear** (código+descripción) + custom; al elegir
    CNAE autocompleta la descripción. Reutilizable para el resto de la ficha.
- ⬜ **Cotización RETA del titular (manual)**: rendimiento neto previsto + base +
  cuota → alimenta la Revisión RETA en no vinculados (ya soportado vía
  `reta_profiles.expected_net_income`; aquí un acceso directo).
- ⬜ **Datos para extraer/estimar sin contabilidad**: cifras manuales de
  ventas/gastos/resultado por periodo para clientes que no llevan contabilidad
  aquí → alimentan avisos y KPIs. **Requiere tabla nueva** (p.ej.
  `client_manual_financials`).
- ⬜ **Preferencias de gestión**: módulos/avisos activos por cliente, vía de
  contacto, notas internas de la asesoría. **Requiere tabla/campos nuevos**.

**Notas de implementación:**
- El tab va en `buildClientDetailView`, posición 2 (tras Resumen), para TODOS los
  clientes (vinculados y no). Pensado para que cargar un no vinculado no dé error.
- Reusar lo que ya existe (NIF/IAE en companies/customers; RETA en
  reta_profiles) y añadir solo lo nuevo (financials manuales, prefs, notas).
- ⚠️ Benjamin reporta posible "error al cargar" clientes no vinculados — verificar
  el caso real (no encontrado aún en código; puede haberse resuelto con la
  Revisión RETA por-cliente).

---

## 2026-06-15 — AVISOS: centro de "Tareas pendientes" (plan, auditado por agente)

> Origen: Benjamin vio 2 asientos por validar y no se enteró ("si no entro no me
> entero"). Quiere un centro de avisos que mantenga informada a la asesoría (y
> al empresario) de TODO lo pendiente. Agente Explore auditó 35 estados
> accionables; Claude supervisó/curó el set v1.

**Arquitectura:** `PendingTasksService` agregador EN VIVO (estado actual, no
eventos) → buckets `{tipo, etiqueta, count, severidad, destino}`. Panel "Tareas
pendientes" + contador en la campana existente (`AdvisoryNotificationService` +
`buildAdvisoryNotificationsBell`). Dos ámbitos: **por empresa** (empresario / Mi
gestión / dentro de un cliente) y **cross-cliente** (asesoría sobre cartera,
reusar patrón `AdvisoryDashboardService`). Todas las tablas ya tienen índice
(company_id, status) → rápido.

**v1 (curado):**
- 🔴 Asientos DRAFT por validar · Facturas vencidas sin cobrar · Declaraciones
  fiscales que vencen sin presentar · Asiento de cierre fiscal pendiente.
- 🟠 Nóminas del mes sin generar/pagar/entregar · Facturas PENDING_CLIENT_APPROVAL
  (TPB) · **RETA fuera de tramo (RETA-3)** · DEHú pendientes · Contratos por
  vencer (ContractAlertService) · VeriFactu en ERROR.
- 🟡 Movimientos bancarios sin conciliar · Docs/mensajes cliente sin leer ·
  Notificaciones URGENT · Certificados por caducar.
- Descartado v1 (ruido/ya cubierto): clientes sin email, importaciones históricas,
  asignaciones sin módulos, colaboraciones, candidatos recurrentes (ya tiene
  banner), BOE (ya tiene pantalla).

**Plan de construcción (incremental, por riesgo):**
- ⬜ **AVISOS-1** — `PendingTasksService` **por empresa** (tenant actual) con las
  queries v1 + panel "Tareas pendientes" + badge. Resuelve la pain directamente
  (en Mi gestión / empresario / dentro de un cliente). Bajo riesgo (sin cross-tenant).
- ⬜ **AVISOS-2** — roll-up **cross-cliente** para la asesoría (recorre cartera).
  Reusar el patrón de aislamiento de `AdvisoryDashboardService` (¡cuidado
  multi-tenant!). Incluye RETA-3 con P&L real por cliente.
- ⬜ **AVISOS-3** — replicar en modo empresario (su propia empresa) — en parte
  sale gratis de AVISOS-1 si se hace agnóstico del modo.
- Inventario completo (35 fuentes) documentado para ampliar después.

---

## 2026-06-15 — RETA: split operativa + alerta de regularización (plan)

> Decisión Benjamin: RETA tiene dos naturalezas → **operativa** del autónomo
> (en la ficha) y **vigilancia** cross-cliente (admin). Casi todos los clientes
> de la asesoría son autónomos (detrás de cada empresa hay un autónomo).

- ✅ **RETA-0** *(2026-06-15)* — tramos de cotización por **año en BD** (V117
  `reta_tramos` + seed 2026) en vez de hardcodeados en Java; `suggestTramo` los
  lee de BD; **editor no-code** en Laboral → "Tramos autónomo" (clonar año +
  editar). 2027 sin tocar código. *Ojo: el seed son valores 2025 placeholder;
  revisar/ajustar al publicarse el PGE 2026.*
- ✅ **RETA-1** *(2026-06-15)* — operativa RETA movida a la ficha (pestaña
  "Autónomos (RETA)" en Mi gestión + cada cliente, reutiliza `retaView`);
  quitada del sidebar del cockpit propio (filtro `activeModules`).
- ✅ **RETA-2** *(2026-06-15)* — `ensureOwnerProfiles(companyId)` crea perfiles
  RETA para titulares con `company_owners.ss_regime IN (RETA, AUTONOMO_SOCIETARIO)`
  que no lo tengan. Idempotente, sin falsos positivos en sociedades. El scan de
  RETA-3 lo ejecuta en toda la cartera (cobertura automática). Endpoint
  POST `/api/reta/ensure-profiles`.
- ✅ **RETA-3 (alerta de regularización, cross-cliente)** *(2026-06-15)* — regla
  Benjamin = **rendimiento REAL** (P&L). `scanRegularization(year)` recorre
  empresa propia + cartera (`parent_company_id`); por empresa calcula rendimiento
  neto real (7xx haber − 6xx debe, POSTED, por company_id) → tramo del año
  (`reta_tramos`) → compara base cotizada con [base mín, máx] → marca
  UNDER/OVER/NO_BASE. UI: pestaña "Revisión RETA" en Laboral (desde Mi gestión
  cubre la cartera). Endpoint POST `/api/reta/regularization/scan`.
  **Legal-sensible: validar la regla con Benjamin** (RD-Ley 13/2022; la TGSS
  regulariza al año siguiente). Solo aplica a clientes con contabilidad en BENJAGEST.
  *Pendiente futuro: integrarla como una fuente del centro AVISOS (badge en campana).*

---

## 2026-06-15 — Sesión con Benjamin: cierre, nómina, sidebar ✅

- ✅ **CONS-CIERRE** — pantalla de cierre de ejercicio cableada en Contabilidad
  (precalcular + preview regularización + cerrar con aplicación + reabrir).
- ✅ **fix cierre** — `sumIncome` usaba `sales_invoices.total_amount` (no existe);
  corregido a `total`. El "Precalcular" ya no da *bad SQL grammar*.
- ✅ **PAY-DELIVERY** — entrega de nómina con vía + acuse de recibo (V116).
- ✅ **VG-FULL-SCAN restante** — 7 comparadores de ordenación.
- ✅ **SIDEBAR-ADMIN** *(decisión Benjamin)* — el sidebar de la asesoría queda
  como **administración** (Clientes, Equipo, Informes, Agenda, Configuración,
  Asesoría) y la **operativa del propio negocio** (Fiscal/Laboral/Facturación/
  Compras/Contabilidad) se accede entrando en **"Mi gestión"**, que ahora muestra
  las **pantallas completas** (no versiones reducidas) para la empresa propia.
- ✅ **DEPLOY-PKG** anotado — instalable Windows autocontenido (MariaDB embebida,
  "todo es un puesto", dos versiones Asesoría/Empleado); se empaqueta al terminar.
- 🔵 **Pendiente nómina**: incidencias por periodo (horas extra/bajas/variables)
  — necesita decisión de modelo (se solapa con complementos por nómina).
- 🔵 **Pendiente legal-sensible**: topes de cotización TGSS + pagas extra
  cotizadas — construir y validar con Benjamin como el IRPF.

---

## 2026-06-14 — DEPLOY-LOCAL: ¿está listo para funcionar en local? ✅🖥️

> Pregunta de Benjamin (se fue a trabajar): *"este programa va a trabajar en
> local, ¿estamos preparándolo para eso? ¿Verifactu está preparado?"*.
> Investigado con **dos agentes Explore en paralelo** (preparación local +
> Verifactu). Veredicto y entregables abajo.

**Veredicto: SÍ, el código está local-ready (≈85-90%).** No hay acoplamiento a
la nube (ni S3, ni OAuth Google, ni subdominios SaaS). UI→backend configurable
por `BENJAGEST_API_BASE_URL`; BD y puerto por env vars; ficheros en filesystem
local configurable (`benjagest.invoices.storage-root`,
`benjagest.imported-pdfs.root`); multitenant por `company_id` encaja en una
asesoría local con N clientes. La UI es **JavaFX de escritorio (HttpClient)** →
**no hay problema de CORS** al apuntar a otra IP de la LAN. Servicios externos
(AEAT, BOE, email, geocoding) son **opcionales y degradan bien** sin internet.

**Verifactu en local:** funciona **100% offline en modalidad NO_VERIFACTU**
(huella SHA-256 encadenada + firma local + QR + eventos SIF, todo en el
servidor). La modalidad **VERI*FACTU** (envío a la AEAT) está implementada pero
**NO probada contra la AEAT**: requiere certificado FNMT registrado + ajustar el
XML al XSD oficial + firma XAdES-EPES (`AeatVerifactuClient` lo dice). El
scheduler de envío con reintentos ya existe. Para una asesoría on-premise, lo
correcto hoy es **NO_VERIFACTU**; el salto a VERI*FACTU es mejora futura con una
salida puntual a internet.

**Hecho en esta sesión (aditivo, sin tocar auth/seeds/AEAT):**
- ✅ `docs/despliegue-local.md` — guía oficina (1 servidor + N puestos por LAN).
- ✅ `start-local-server.ps1` (servidor: Docker + espera BD + backend) y
  `start-ui.ps1 -ServerIp <IP>` (puesto: fija API base + lanza UI).

### 🎯 DEPLOY-PKG — Instalable Windows (decisiones Benjamin 2026-06-15)

> **NO construir todavía.** Se empaqueta **al terminar la app**. Anotado aquí para
> no perder las decisiones de producto.

**Visión de producto:**
- El programa tendrá **dos versiones**: **Asesoría** y **Empleado**.
- **Modelo por defecto = "todo es un puesto"**: en una sola máquina se instala
  **UI + backend + MariaDB embebida**, autocontenido. Un **empresario con un solo
  PC no necesita un segundo ordenador** ni configurar nada de red.
- **Asesoría con varios empleados (caso opcional/avanzado):** el PC del **OWNER
  hace de servidor** (tiene la BD + backend) y los **empleados son puestos** que
  apuntan a su IP por la LAN. Reusa el modo LAN ya documentado en
  `docs/despliegue-local.md`. Pero **no es obligatorio**: si la asesoría es de una
  persona, también funciona como puesto único.
- Implicación técnica clave: la app debe poder arrancar en **modo embebido**
  (todo local en una máquina) como caso primario; el modo cliente→servidor LAN es
  configuración (apuntar `BENJAGEST_API_BASE_URL` a otra IP), ya soportado.

**Decisiones cerradas:**
- ✅ **MariaDB embebida/portable** dentro del instalador (sin Docker). Se empaqueta
  al final.
- ✅ Runtime de **Java incluido** vía `jpackage`/`jlink` (verificado: ambas
  herramientas están en el JDK Temurin 21 de la máquina; la UI ya es modular con
  `module-info.java`).

**Pendientes DEPLOY-PKG (al terminar la app, no arquitectura):**
- ⬜ **jpackage** del puesto (UI) + backend embebido + MariaDB portable → un
  instalable que arranque todo automático en una máquina ("todo es un puesto").
- ⬜ Backend (+ MariaDB) como **servicio de Windows** con auto-arranque (necesario
  sobre todo en el rol servidor/OWNER; en puesto único puede arrancar al abrir la app).
- ⬜ Instalar **WiX Toolset v3** en la máquina de build para generar `.msi`/`.exe`
  nativo (servicio + accesos directos + desinstalador). Sin WiX solo "app-image".
- ⬜ Variante de instalador/branding por **versión (Asesoría / Empleado)**.
- ⬜ Revisar/abrir puerto 8080 en firewall solo en el rol servidor (LAN).

**Pendiente Verifactu real (independiente del empaquetado):**
- ⬜ **VF-SIGN-XADES** (cuando se quiera VERI*FACTU real): cert FNMT + XSD AEAT
  oficial + XAdES-EPES + parseo de respuesta AEAT. Probar contra preproducción.

---

## 2026-06-14 — PROPUESTA: Ciclo de vida laboral y societario (CICLO-VIDA) ⚖️💰

> Identificado por Benjamin: hoy solo gestionamos **altas** (empleados,
> contratos, RETA autónomos, empresa). Falta toda la **salida/cese**, que
> conlleva nóminas, pagos, indemnizaciones y documentos legales. Propuesta
> de bloque a abordar tras validar el IRPF. Orden por dependencia/valor.

> **Avance 2026-06-14 (todo en develop):** el bloque CV se ha rediseñado como
> flujo AUTOMÁTICO (decisión Benjamin): la acción "Despedir / finiquito" sobre
> el empleado extrae todo solo (no se teclea cese/días). Cerrados:
> CV-VAC (registro de vacaciones, V114), CV-1 (finiquito SETTLEMENT),
> CV-2 (indemnización por tipo), CV-ORQ (orquestador baja/despido,
> TerminationService), CV-3 (jubilación = tipo RETIREMENT), CV-DOC (carta de
> despido + certificado de empresa, TerminationDocsService) y PAY-RECURRENT
> ("Generar mes": nómina mensual de todos los activos de una vez, idempotente).
> **Bloque CV probado por Benjamin (va todo bien).** Ajustes tras pruebas:
> antigüedad en años/meses/días; **fecha de antigüedad reconocida** en el
> contrato (V115) para indemnización con contratos sucesivos; tipo de trabajo
> localizado (combo Jornada completa/parcial); al despedir, el empleado pasa a
> baja si no le queda contrato activo; pool **Hikari** resiliente a conexiones
> muertas (suspensión del equipo / reinicio de MariaDB).
> Pendientes (futuro): CV-4 baja RED real, CV-5 excedencias, CV-6/7 autónomos
> (cese de actividad / jubilación RETA), CV-8 cese de empresa.

**Empleados (laboral):**
- ✅ ⚖️ **CV-1 Finiquito / liquidación** *(2026-06-14)* — al
  terminar cualquier contrato (baja voluntaria, fin de contrato, despido,
  jubilación): salario de los días trabajados + vacaciones no disfrutadas +
  prorrata de pagas extras devengadas no cobradas + pluses pendientes. Genera
  **recibo de finiquito** (PDF). Reusa el motor de nómina (tipo `SETTLEMENT`).
  Cotiza/tributa por conceptos; la **indemnización va aparte** (campo propio,
  exenta de IRPF hasta el límite legal; el detalle de tipos de despido es CV-2).
- ✅ ⚖️ **CV-2 Despido + indemnización** *(2026-06-14, motor)* — improcedente
  33 d/año (tope 24 mens; tramo 45 d hasta 2012-02-12, tope 42 mens), objetivo
  20 d/año (tope 12 mens), disciplinario 0, fin temporal 12 d/año. Salario
  diario=anual/365, antigüedad por fechas. Exenta IRPF hasta 180.000 €.
  (La **carta de despido** es CV-DOC, pendiente.)
- ✅ ⚖️ **CV-DOC Documentos de baja** *(2026-06-14)* — carta de despido (por
  tipo) + certificado de empresa. Se descargan tras la baja (TerminationDocsService).
- ✅ ⚙️ **PAY-RECURRENT** *(2026-06-14)* — "Generar mes": nómina mensual de todos
  los empleados activos de una vez, salta las ya hechas. Recurrente como ventas/gastos.
- ✅ ⚖️ **CV-3 Jubilación del empleado** *(2026-06-14)* — tipo RETIREMENT del
  orquestador: finiquito sin indemnización + cierre de contrato. (Baja RED +
  certificado de empresa: CV-DOC / CV-4.)
- ⬜ ⚖️ **CV-4 Baja en Sistema RED / certificado de empresa** — al cesar
  cualquier empleado: comunicación de baja (RED) + certificado de empresa
  (datos de cotización para la prestación). Hoy solo está el alta (contrat@).
- ⬜ **CV-5 Excedencias / suspensiones / reducción de jornada** — afectan
  cotización y nómina (suspensión sin sueldo, reducción por guarda legal…).

**Autónomos (RETA):**
- ⬜ ⚖️ **CV-6 Cese de actividad autónomo** — baja en RETA + AEAT (036/037) +
  prestación por cese de actividad ("paro del autónomo"). Liquidación de cuotas.
- ⬜ ⚖️ **CV-7 Jubilación del autónomo** — baja en RETA, compatibilidad
  jubilación activa, cálculo de la base reguladora.

**Empresa (societario):**
- ⬜ ⚖️ **CV-8 Cese / disolución de empresa** — baja de todos los empleados +
  finiquitos, despido colectivo (ERE) si aplica, baja de la empresa en SS y
  AEAT, liquidación. Implica N finiquitos + documentación.

**Transversal:** todos estos generan **documentos** (finiquito, carta despido,
certificado empresa) + **cálculos** (indemnización exenta/sujeta) + **pagos** +
**comunicaciones** (RED/AEAT). Decisiones pendientes de Benjamin: alcance de
cada uno (¿solo cálculo+PDF, o también el envío telemático real?).

---

## 2026-06-13 tarde — Bloque NOM-FLUJO (nómina profesional) ⚖️💰

Sobre el bloque NOM, construido el flujo completo estilo A3/Nomio:

- ✅ **NOM-6 Coste empresa/empleado** — pestaña Labor "Coste empresa".
- ✅ **PDF recibo modelo oficial** (Orden ESS/2098/2014, estilo Nomio): cabecera
  rejilla + tabla conceptos CLAVE/DEVENGOS/DEDUCCIONES + bases cotización
  (remuneración+prorrata) + 2 tablas aportación trabajador|empresa + firmas.
- ✅ **Fix base cotización = anual/12 SIEMPRE** (incluye prorrata, art.147 LGSS).
  Casilla prorrateo invertida corregida; default 14 pagas (art.31 ET).
- ✅ **DEV-DESGLOSE** (V107): `contract_salary_items` (salario base + complementos
  libres con cotiza/tributa) + `payslip_lines`. Editor de complementos en el
  editor de contrato. Nómina con una línea de devengo por concepto.
- ✅ **Complementos por nómina** (dietas/km/asistencia) en el diálogo de calcular.
- ✅ **PARAM-YEAR** (V108): `ss_contribution_rates` global por año (seed 2026).
  El cálculo lee los tipos de la tabla (no a fuego). Pestaña "Tipos cotización".
- ✅ **PREVIEW**: `compute()` puro + `preview()` + endpoint `/preview`. Botón
  Previsualizar + resumen en vivo + botón **Validar** en el diálogo.
- ✅ **OBJETIVO**: `solveTarget()` + endpoint `/solve-target`. "Llegar a objetivo"
  (bruto=resta / neto=modelo lineal con %IRPF contrato) → propone Mejora voluntaria.
- ✅ **REPLICAR**: botón "Lote a objetivo" → genera nóminas a un sueldo objetivo
  para varios empleados (mismo bruto=mismo plus; mismo neto=plus distinto por
  situación familiar).

**Pendiente futuro:** complementos en el asistente de alta.
(✅ topes cotización TGSS — V109; ✅ pagas extra EXTRA_* sin cotización propia;
✅ inverso NET por bisección con tipo IRPF real — 2026-06-14.)

**Pendiente NOM (refinamiento complementos, 2026-06-14):**
- ⬜ ❓ **Reparto del objetivo entre varios complementos con min/max** — hoy
  "Proponer plus" añade/actualiza UNA "Mejora voluntaria" (idempotente). Benjamin
  quiere poder repartir el objetivo entre varios complementos, cada uno con un
  mínimo/máximo, de forma idempotente. **Decisión pendiente**: definir el modelo
  (¿qué complementos son "ajustables", su orden y topes?). No es estándar A3
  (A3 usa un único concepto "a cuenta convenio"/mejora para cuadrar).
- ⬜ **IRPF: regularización intra-anual** (recalcular al cambiar datos a mitad de
  año) y **límite art. 85.3 afinado** por meses restantes.
- ⬜ **Reducciones algoritmo no modeladas**: pensionista (600), desempleado que
  acepta puesto (1.200), anualidades por alimentos (+1.980 en cuota2). Hoy no se
  modela situación pensionista/desempleado del perceptor.
- ⬜ **Aviso BOE de cambios de parámetros** — afinar BOE-RSS para alertar cuando
  se publique la norma de retenciones/cotización del nuevo año (recordatorio de
  actualizar las tablas por año).

---

## 2026-06-14 — PROPUESTA: Análisis financiero del cliente (FIN-ANALYSIS) 📊💰

> Idea de Benjamin: que la asesoría pueda sacar un **análisis financiero
> instantáneo** de cualquier cliente, con KPIs, y proponer cómo mejorar el
> beneficio. **Pendiente** (no arrancado). Exploración hecha 2026-06-14.

**Base que YA existe (reutilizar, NO duplicar):**
- `SalesAndExpensesKpiService` — P&L por empresa desde el diario: ventas (7xx
  haber), gastos (6xx debe), IVA repercutido (477) / soportado (472), **modelo
  303 estimado**, asientos DRAFT. Por rango de fechas, <200 ms.
- `AdvisoryDashboardService` — nivel cartera (cross-cliente): facturado,
  pendiente de cobro, vencidas, obligaciones, workflow.
- Labor `EmployerCostRow` (coste empresa/empleado por año); `fiscal`/`tax`
  (modelos + `tax_filings`); `accounting` cierre de ejercicio (precalcula
  resultado); `purchases` + banco (compras, conciliación).

**Plan por slices (orden por valor/dependencia):**
- ⬜ **FIN-1 Cuadro de mando del cliente (KPIs nivel 1)** — servicio
  `ClientFinancialsService(companyId, periodo)` que reúne: ingresos, gastos,
  **margen y beneficio**; coste de personal y su % sobre ingresos; carga fiscal
  (303 estimado ya está + IRPF/retenciones); tesorería (cobros/pagos
  pendientes desde `sales_invoices`/`purchases`); ratios (margen %, gasto/ingreso,
  ticket medio, DSO morosidad). UI: pantalla por cliente con tarjetas KPI.
- ⬜ **FIN-2 Evolución y comparativa** — serie mensual ingresos/gastos/beneficio
  del año + comparativa interanual (gráfica).
- ⬜ **FIN-3 Proyección de cierre** — extrapola tendencia + estima beneficio e
  IS de fin de año (reusa `year-close precalculate`). Aviso de tesorería futura.
- ⬜ **FIN-4 Recomendaciones (prescriptivo)** — reglas: IVA soportado sin
  deducir, gastos atípicos/recurrentes altos, amortizaciones pendientes, tipo
  IRPF del autónomo / pagos fraccionados, morosos a reclamar. Presentadas como
  "sugerencias a revisar por el asesor". Opcional: narrativa con IA (el cálculo
  va sobre datos reales, no inventado).
- ⬜ **FIN-5 Informe PDF** del cuadro de mando + recomendaciones.

**Límites honestos:** la proyección es tan buena como el histórico; sin
benchmarks de sector no se puede comparar "con empresas similares"; las
recomendaciones fiscales se marcan como sugerencias (las decide el asesor).
Empezar por **FIN-1 + FIN-2** (sólido y rápido); FIN-3/4/5 incrementales.

---

## 2026-06-14 tarde — Cierre y validación del bloque NOM ✅⚖️

- ✅ **Complementos del contrato MENSUALES** (€/mes, se guardan ×12). Base /N pagas,
  complementos /12; en pagas extra solo el salario base.
- ✅ **Objetivo → mejora al contrato (recurrente)**: "Proponer complemento" guarda
  la mejora como complemento mensual del contrato (anualiza en SS e IRPF).
  `solveTarget` reescrito por **bisección** (NETO no lineal). `recurringConcepts`
  reemplazan al concepto del contrato del mismo nombre (sin doble conteo). Lote idem.
  Endpoint POST `/api/labor/contracts/recurring-complement`.
- ✅ **Prorrateo por contrato** (V112): `extras_prorated`; casilla en el editor de
  contrato; en calcular nómina la casilla se ajusta al contrato del empleado.
- ✅ **Recibo de paga extra**: sin cuotas SS ni prorrata (solo MONTHLY genera TC);
  devengo "Paga extra de verano/Navidad"; período lo indica; nombre del PDF por
  tipo (`nomina-extra-verano-…`). FECHA ALTA cae a inicio del contrato si falta
  hire_date. Filas de relleno del recibo sin borde; zona conceptos mín. 14 filas.
- ✅ **Persistencia de sub-tab Labor**: tras cualquier acción se vuelve a la pestaña
  activa (no salta a Empleados).
- ✅ **IRPF VALIDADO contra calculadora AEAT 2026** (algoritmo oficial 26-12-2025).
  Caso Marcos (sit.3, 3 hijos, hipoteca) clava 8,62 %. Correcciones (V113):
  reducción **+2 descendientes 600 €** (el desfase), RNT art.20 = retrib − cotiz.,
  3er tramo art.20, truncado del tipo y de la minoración (como AEAT).
- ✅ **Editor UI de mínimos/reducciones IRPF por año** (pestaña Parámetros IRPF →
  "Mínimos y reducciones"). Con clonar año + editar escala, el sistema queda
  **100 % no-code para 2027**.

---

## 2026-06-13 — Bloque NOM (ciclo mensual de nómina) ⚖️

Retomadas las decisiones aparcadas de la tarea #43 (Payrolls UI). Análisis
conjunto Benjamin + Claude barriendo internet (Orden PJC/297/2026, ejemplos
de asiento de Sage/Cegid/Wolters Kluwer/Billin).

**Decisiones cerradas (2026-06-13):**
1. **SS a cargo de la empresa** → se lee enlazando la tabla de cuotas TC
   (`social_security_contributions`); la nómina la alimenta (es su propósito
   documentado).
2. **Asiento contable** → dos asientos: devengo (al calcular) + pago (al
   marcar pagada). Estructura PGC 640/642 → 476/4751/465 y 465 → 572.
3. **AT/EP (accidentes de trabajo)** → tipo **por contrato** (varía por CNAE).
   Columna `at_ep_percent` en `employment_contracts`, default 1,50 %.

**Implementado:**
- ✅ **NOM-1** — `PayslipService.calculate()` calcula el desglose SS 2026 y
  hace upsert de las filas TC (EMPLOYEE_* / EMPLOYER_*) del periodo. Solo
  toca filas DRAFT (respeta FILED/PAID). Solo nóminas MONTHLY.
- ✅ **NOM-2** — fix SS trabajador **6,35 % → 6,50 %** (faltaba el MEI 2026).
- ✅ **NOM-3** — `PayslipJournalEntryService` (clon del de ventas): asiento de
  devengo, leyendo la SS empresa (642) y el acreedor TGSS (476) de las cuotas
  TC. Idempotente ante recálculos (borra el DRAFT previo).
- ✅ **NOM-4** — asiento de pago (465 → 572) al marcar pagada; reversión de
  ambos asientos al borrar la nómina.
- ✅ **NOM-5** — `at_ep_percent` cableado de punta a punta (migración V106 +
  EmploymentContractService + ContractEntry/LaborApiClient + ambos editores
  de contrato en la UI, con i18n ES/EN).

**Limitaciones honestas (documentadas en el javadoc):**
- Base de cotización = bruto (sin topes mín/máx por categoría TGSS).
- Desempleo/AT a tipos de indefinido; las pagas extra (EXTRA_*) no generan
  asiento todavía (cotizan prorrateadas — slice futuro).
- "Otras deducciones" (embargos/anticipos) quedan fuera del asiento MVP.

**Pendiente / próximo:** mostrar SS empresa + enlace al asiento en la pestaña
Nóminas; afinar topes de cotización; pagas extra.

---

## 🗺️ Leyenda visual

| Marcador | Significado |
|---|---|
| ✅ | Hecho — cerrado, commiteado, mergeado |
| 🔵 | Parcial / skeleton — funciona pero falta UX o features |
| ⬜ | Pendiente — atacable |
| ⏸ | Aplazado conscientemente |
| ❓ | Decisión de Benjamin pendiente |
| ❌ | Descartado por Benjamin |
| 🔴 | Crítico (legalidad, seguridad, bloqueo) |
| 🟠 | Alta prioridad |
| 🟡 | Media prioridad |
| 🟢 | Baja prioridad |
| ⚖️ | Obligación legal |
| 💰 | Diferencial de valor vs competencia |
| ⭐ | Próximo en pelear |

---

## 📊 Resumen ejecutivo

- **Cerrado a fecha de hoy**: bloque VeriFactu / Facturación + Contabilidad PGC PYMES completo + RD 8/2019 fichaje legal + Asesoría↔cliente con sidebar dual y módulo Comunicación + **EQUIPO S1** + exports verificables (audit + SIF + fichajes) + bloque CTR contratos completo (CTR-1..7) + PORT-1..5 + bloque TPB (facturación por tercero + Magic Link/revocación) + UIs autónomas (PANORAMA, BOE, Backup, Multi-allocation, Rec. bancaria, Cal. fiscal) + **bloque NOM (ciclo mensual de nómina con asientos)**.
- **🔴 Crítico abierto**: Modelos AEAT 100/180/200/411; VeriFactu estricto (XAdES + SOAP + alta SIF en sede AEAT) — todo **bloqueado por certificado FNMT real**; decisiones estructurales MOBILE-EMPLEADO + JORNADAS UI.
- **🟠 Alta abierta**: Reporte coste empresa/empleado, entrega nóminas con firma + incidencias, conectores DEHú/SS RED reales (necesitan certificado).
- **🟡 Media abierta**: Reconciliación ML "casi-iguales", régimen especial IVA/prorrata/criterio caja, dashboard widgets, CENTROS-MAP interactivo, OCR PDFs escaneados, VG-FULL-SCAN restante, workflow partes de día (ligado a app móvil).
- **🟢 Baja abierta**: Alertas de seguridad, Email personal OAuth, PWA (cubierto por MOBILE-EMPLEADO).

---

# ✅ HECHO — orden cronológico inverso (más reciente arriba)

## 📅 2026-06-13 — TPB-CLIENT-SETUP + navegación + i18n + pulido (sesión autónoma)

> Todo en `develop`, compila limpio. Pendiente prueba de Benjamin.

| Slice | Commits | Qué hace |
|---|---|---|
| ✅ **I18N-ENUMS** | `07e501b` | ~20 valores enum en bruto (COMPANY, RETA, MONTHLY, BANK_TRANSFER…) traducidos. Helpers `localizedEnum` + `localizeEnumCombo` + ~70 keys ES/EN. Auditado con 2 agentes Explore. |
| ✅ **TPB-CLIENT-SETUP F1** | `ddc515d` | El editor de factura del cliente sin receptores ofrece "Crear cliente" (alta receptor bajo la shadow company). Backend POST /api/customers-extended. |
| ✅ **TPB-CLIENT-SETUP F2** | `97e4347` | Sub-pestaña "Clientes" en la ficha del titular: crear/editar/listar su cartera de receptores. |
| ✅ **TPB-CLIENT-SETUP F3** | `5432f1a` | Sub-pestaña "Config. facturación" del titular: VERIFACTU/NO_VERIFACTU + series + textos + certificado bajo su tenant. |
| ✅ **NAV-CLIENT-BACK** | `3c886bd` | En modo cliente, "Nueva factura" ya no deja atrapado: "Volver"/"Cancelar"/tras emitir reconstruyen la pantalla del cliente con sus tabs. Auditado: era el único editor que reemplazaba el centro desde modo cliente. |
| ✅ **VG-FULL-SCAN-2** | `9b0b0a1` | Comparadores de ordenación añadidos en columnas numéricas/fecha restantes (contabilidad, facturación cliente, empleados, AEAT, portal nóminas, contratos, partes, calendario fiscal). Helper `addColSorted`. |

### 🔎 Hallazgos de la sesión — resueltos en NOM (2026-06-13)

- ✅ **Payrolls UI / asiento de nómina (#43)**: CERRADO en bloque NOM (ver arriba). Decisiones tomadas con Benjamin: SS empresa vía cuotas TC, 2 asientos (devengo+pago), AT/EP por contrato.
- 🟠 **Reporte coste empresa/empleado**: AHORA FACTIBLE — el bloque NOM ya escribe la SS empresa en `social_security_contributions` por empleado/periodo. Falta solo construir el informe (coste = bruto + Σ EMPLOYER_* cuotas TC, agrupado por empleado). Pendiente en Alta prioridad.
- ✅ **Backlog desactualizado**: pasada de marcado ✅ hecha en esta misma sesión (06-13). Mensajes/Documentos/Notificaciones, PANORAMA, CTR-3/5/6/7, Backup, Multi-allocation, Rec. bancaria, Cal. fiscal, BOE, GEO-FICHAR, REC-IGNORE → todos cerrados.

---

## 📅 2026-06-11 / 06-12 — Bloque TPB completo + UIs autónomas + Magic Link + i18n enums

> Sesión larga de 2 días. Migraciones nuevas: V96–V105. Todo en `develop`.

### Bloque TPB (facturación por tercero, RD 1619/2012 art. 5)

| Slice | Commits | Qué hace |
|---|---|---|
| ✅ **TPB-1** Acuerdo previo | `V96` | Tabla `third_party_billing_agreements`. Propuesta + estados PROPOSED/ACTIVE/REVOKED. Scope ventas/compras/modelos. PDF del acuerdo. |
| ✅ **TPB-2** Serie por tercero | `V97` | `invoice_series.expedited_by_company_id`. Serie TPB separada (art. 6.1.b). Auto-reparación en `findCurrent`. Endpoint `preview-next` para que el editor muestre la serie correcta en el banner. |
| ✅ **TPB-3** Aceptación factura-a-factura | `V98` | Estado `PENDING_CLIENT_APPROVAL`. El cliente vinculado aprueba/rechaza. Doble clic abre el editor para revisión/corrección. |
| ✅ **TPB-4** Marca AEAT Verifactu | `V99` | Campos `issued_by_third_party` en verifactu_registry. |
| ✅ **TPB firma con PIN** | varios | Modal "Define tu PIN" si el empresario no lo tiene antes de firmar. |
| ✅ **TPB offline-PDF BLOQUEADO** | `332bd69` | Flujo de subir PDF sin verificar → HTTP 410. Se prestaba a fraude (asesoría activaba sin firma real del cliente). Memoria guardada. |
| ✅ **TPB Magic Link + OTP** | `V104`, `9c2abb4` | Cliente sin cuenta firma desde el navegador: enlace por email + OTP de 6 dígitos. Página HTML pública servida por Spring. Evidencia legal (IP/UA/hora). eIDAS art. 25. |
| ✅ **TPB revocación cliente** | `V105`, `0f83a06` | Cliente sin cuenta revoca igual que firmó: email con enlace permanente + OTP al entrar. Protección de evidencia (no revoca si hay facturas sin PDF guardado). |
| ✅ **TPB live polling** | `bb81539` | Tab acuerdo se auto-actualiza cada 5s. Al firmar aparece el tab Facturación + KPIs en caliente; al revocar desaparece. |

### UIs autónomas sobre backend ya existente

| Slice | Commit | Qué hace |
|---|---|---|
| ✅ **PANORAMA-ASESORIA** | `546792c` | Dashboard asesoría: 5 KPIs cruzados de cartera. |
| ✅ **UI-BOE-ALERTS** | `bf2d644` | Pestaña Configuración → Alertas BOE con barrido + abrir PDF oficial. |
| ✅ **UI-BACKUP-LOCAL** | `44d1be3` | Pestaña Copias de seguridad: tabla + hacer ahora + abrir carpeta. |
| ✅ **UI-MULTI-ALLOCATION** | `6f57cdb` | Modal "Cobrar varias": un pago reparte entre N facturas. |
| ✅ **UI-REC-BANCARIA** | `a7cb076` | Diálogo conciliación bancaria asistida (Levenshtein). |

### Fixes en vivo

| Fix | Commit | Qué hace |
|---|---|---|
| ✅ Iconos tabs Comunicación invisibles | — | Color inline #1e293b (CSS .font-icon los pisaba). |
| ✅ Banner TPB con nombre asesoría | `037137f` | Antes decía "Tu asesoría", ahora el nombre real. |
| ✅ Tab "Mi acuerdo facturación" empresario | `037137f` | Configuración del cliente para gestionar su TPB. |
| ✅ Botones barra facturación truncados | `3bc16aa` | Textos acortados + minWidth para que no se corten con "...". |
| ✅ Estado PENDING_CLIENT_APPROVAL traducido | `3bc16aa` | Faltaba en localizedInvoiceStatus. |
| ✅ Email cliente desde `customers.email` | `4200245` | El portfolio leía solo customer_contacts legacy → magic link iba al email viejo. |
| ✅ Magic link SMTP de la asesoría | `d7f8f81` | Buscaba SMTP del cliente (tenant header) en vez del de la asesoría. |
| ✅ Página magic link 500 + IP LAN | `b28a24f`, `6b592b0` | CSS con `%` rompía String.format; enlace usaba localhost (no accesible desde móvil) → IP de red local. |
| ✅ **I18N-ENUMS** | `07e501b` | Barrido con 2 agentes Explore: ~20 valores enum mostrados en bruto (COMPANY, RETA, MONTHLY, BANK_TRANSFER...) ahora traducidos. Helpers `localizedEnum` + `localizeEnumCombo` + ~70 keys ES/EN. |

---

## 📅 2026-06-10 noche — Sprint A+B SIF + fixes editor factura

| 🔴 Slice | Commit | Qué hace |
|---|---|---|
| **Editor factura — dirección cliente debajo del combo** | `a168c32` | El editor mostraba solo NIF/email/teléfono. Ampliado `CustomerResponse` y `CustomerSummary` con address/city/province/postalCode/country. SELECTs con COALESCE y email/phone con doble fallback a `customer_contacts` legacy. `refreshClientDetail` pinta dirección + país. |
| 🔴 **Sprint A — Reset cadena SIF legacy** | `1a63d3c` | Endpoint `DELETE /api/billing/sif-events/legacy-chain` bloqueado para empresas en VERIFACTU (Orden HAC/1177/2024). UI bloque en Configuración → Auditoría. |
| 🔴 **Sprint B — SIF-SCHEDULER-LOCKS** | `1a63d3c` | `SifEventService.record/recordForCompany` ahora con `@Transactional(REQUIRES_NEW)`. Reduce contención con `AuditChainService` FOR UPDATE. |

## 📅 2026-06-10 tarde — feedback en vivo + refinamientos (10 slices)

| Slice | Commit | Qué hace |
|---|---|---|
| Combo Política i18n + CAL-A v2 keyword matching | `8d98643` | StringConverter en geo_policy + matching tolerante por keyword en event_type Agenda. |
| Importar PDFs solo asesoría-actuando-por-cliente | `8d98643` | `importSalesPdfsBtn` oculto si appMode != ADVISORY o !actingForClient. |
| Revisar candidatos recurrentes con Button visible | `8d98643` | Hyperlink → Button con icono `fas-search` + `button-primary`. Banner se oculta si no hay candidatos. |
| Sub-tab renombrada "Jornadas" | `8d98643` | Quitado "Nuevo parte" (vendrá de app móvil). Sección "Plantillas — próximamente" + "Partes reportados" solo lectura. |
| **Fix V90 work_logs ALTER aditivo** | `eae26d1` | V2 ya tenía `work_logs` con esquema viejo. V86 CREATE IF NOT EXISTS se la saltó. V90 ADD COLUMN log_date/minutes_worked/is_billable/billable_amount/status + sincronización registros viejos. |
| **Fix UserSettingsService columnas dropeadas** | `eae26d1` | V87 dropeó language/ai_enabled/avatar_path/workday_template pero service seguía haciendo SELECT. Simplificado a 2 campos (pin_timeout_min + screensaver_style). |
| **Fix editor cliente: combo Tipo i18n + dirección postal + labels sin truncar** | `eae26d1` | StringConverter con `t("cli.type.*")`. ColumnConstraints minWidth=140 + Hgrow.ALWAYS. Sección "Dirección postal" añadida. |
| **Fix PDF factura — bloque cliente con dirección** | `eae26d1` | `InvoicePdfGenerator` inyecta `JdbcTemplate` + `loadCustomerView(customerId)`. Pinta NIF + dirección + email/teléfono. |
| Fix V87 — quitar AFTER pin_hash + IF NOT EXISTS | `f993397`/`fecc723` | `user_accounts` no tenía `pin_hash`. ALTER idempotente. |
| 🔴 **CLAUDE.md sección 10.bis — NO ASUMIR** | `6358b1d` | Regla persistente tras 4 fallos por asumir sin verificar. |

## 📅 2026-06-10 mañana — autonomía total (18 slices)

| Slice | Commit | Qué hace |
|---|---|---|
| ⚖️ **TC-CAL — warning amarillo fichaje en festivo** | `89ea42c` | `findTodaysHolidayForEmployee` JOIN employees↔holidays. `PunchResult` con `HolidayWarning(name, type, scope)`. UI tarjeta amarilla. |
| 🔵 **PORT-2 skeleton work_logs embebido** | `c5bbbcb` | V86 (corregida por V90) 4 tablas + `WorkLogService` básico. |
| **PORT-4 CLI — rediseño editor cliente con TabPane** | `d5cfd05` | V85 ADD COLUMN address/city/province/postal_code/country/internal_code/default_mode/phone/email/website. `CustomerExtendedController` `/api/customers-extended`. |
| **PORT-3 PERFIL+LOCK — preferencias usuario + bloqueo PIN** | `0139e0a` | V84 `user_settings`. Lock screen con Timeline + Stage UNDECORATED. |
| **PORT-3 SUG — Módulo Sugerencias** | `74f42ae` | V83 tabla `suggestions` + módulo `suggestions` catalog. CRUD + modal alta. |
| **PORT-1 EMP-1..4 — Portal del empleado 4 tabs** | `9602e22` | V82 módulo `employee-portal`. *(Más tarde retirado del sidebar — futura app móvil.)* |
| **PORT-5 CAL-D — CAL-IMPORT-MODAL** | preexistente | Modal side-by-side editable con `HolidayPdfExtractor`. |
| **PORT-5 CAL-C — Cargar festivos nacionales** | `ebc8fa1` | Botón con Easter Meeus/Jones/Butcher. 10 festivos fijos. |
| **PORT-5 CAL-B — Quitar de Agenda** | `9da186d` | DELETE inverso a dump-to-agenda. Idempotente. |
| **PORT-5 CAL-A — Badge color event types** | `66ff009` | Variantes CSS `calendar-event-card-type--{holiday,work-adjustment,work-closure,general}`. |
| **FIX-T-LIMIT — extraer keys calendar.* a helper** | `66ff009` | `t()` excedía 64KB. |
| **V88 — reactivar módulo `shifts` para sub-tab Labor** | tarde | Reinsert + activación condicionada a labor activo. |
| **V89 — work_centers (port CONTENDO `centros_trabajo_180`)** | tarde | Tabla con lat/lng/radio_m + geo_policy CHECK + `employees.work_center_id`. Sub-tab "Centros". |
| **V87 — DROP columnas obsoletas + módulos + ADD logo_path + session_pin_hash** | `1493ed3` | Cleanup tras prueba en vivo. |
| **PORT-4 LOGO — logo empresa upload + storage + preview** | `aaa7e5a` | `CompanyLogoService` con auto-resize 400px + auto-compress <2MB. Guardado en `{root}/{companyId}/_brand/`. |
| **PORT-4 SESSION — pestaña Sesión + PIN + salvapantallas** | `6cc8ace` | `SessionPinService` BCrypt sobre `user_accounts.session_pin_hash`. Lock screen verifica con `/api/settings/session/pin/verify`. 4 salvapantallas (clock/logo/dark/carousel). |
| **VG-FULL-SCAN parcial — comparators** | `85c3388` | RetaBaseChangeEntry + ContractTemplate.salary. 25+ omisiones restantes documentadas. |

## 📅 2026-06-09 noche — cierre día (Benjamin presente)

| Slice | Commit | Qué hace |
|---|---|---|
| fix(workcal) — revert UI+backend a 71e0697 | `49e030d`/`410ccc3` | Revert quirúrgico por archivos tras bug que dejaba al empresario sin ver datos. |
| 🔴 **fix(config) — MariaDB 3307 (no 3306 de Pablo)** | `e98fbb1` | application.yml apuntaba a 3306 por defecto (BD de Pablo casi vacía). |
| ⚖️ **CAL-FIX 1-4 — Calendario laboral** | `064b6df` | DatePicker → TextField flexible CAL-IMPORT. Botones top bar. "Volcar a Agenda" + endpoint. V81 `employees.work_calendar_id`. |
| Humanizar event_type en Agenda | `8077226` | `humanizeCalendarEventType()` + back-compat. |

## 📅 2026-06-09 tarde — autonomía Benjamin fuera

| Slice | Commit | Qué hace |
|---|---|---|
| DatePicker parsea varios formatos | `1a43661` | `parseFlexibleDate` ISO + dd/MM/yyyy + dd/MM/yy + dd-MM-yyyy + d.M.yyyy. |
| ⚖️ **UI Bajas IT (MedicalLeave)** | `8fdcff1` | Pestaña Labor con tabla + diálogo. 38 keys i18n. |
| ⚖️ **UI Cotizaciones SS** | `50dee71` | Pestaña solo lectura con filtros. 32 keys i18n. |
| footer Cotizaciones SS con totales | `9aa61fb` | Mejora aditiva. |
| installFlexibleConverter para DatePickers sueltos | `4729eca` | Helper aplicado a 4 DatePickers de alto uso. |

## 📅 2026-06-09 mañana — autonomía total (17 commits)

- ✅ Bloque L3 work calendars cerrado: L3-2 service + controller, L3-4 UI tab "Calendario laboral".
- ✅ HolidaySeed2026 retirado, sustituido por flujo import PDF (BOJA/BOPV/DOGC).
- ✅ Modal CAL-IMPORT-MODAL con `HolidayPdfExtractor` (port `calendarioParser.v3.js` CONTENDO).

## 📅 2026-06-08 — Bloque CTR (Contratos) primeros 2 slices

| Slice | Qué hace |
|---|---|
| ⚖️ **CTR-1 — V74 catálogos SEPE + 25 convenios + tablas salariales** | WebSearch legal previo. 28 SEPE codes + 25 convenios PYMEs + 12 anexos built-in. |
| ⚖️ **CTR-2 — backend ContractCatalog + UI wizard 4 pasos** | Wizard SEPE/Convenio/Datos/Resumen + combo modelo PDF + 12 cláusulas. 45+ keys i18n. |
| **L4-4 Alta empleado con Acceso a la app + PIN** | `provisionAppAccess` / `revokeAppAccess`. PIN bcrypt. |
| **L4-5 Refactor Equipo → app_access=TRUE** | Filtro correcto sin OWNER huérfano. |
| **L4-6 V71 advisory_collaborations** | Asesoría↔asesoría con invite/accept/reject/revoke. |
| **L4-7 Tab Colaboradores en Equipo** | 4ª tab del módulo Equipo. |
| fix Flyway outOfOrder + UX errores humanos | `outOfOrderCustomizer` + `humanizeBackendError()`. |

## 📅 2026-06-07 — EQUIPO S1 + decisiones arquitectura

- ✅ 💰 **EQUIPO S1 — Reparto de clientes** *(antes marcado ⬜, confirmado HECHO en V66 + `ClientAssignment{Service,Controller,Repository}` + `showTeamModule` 4 tabs)*. Decisión: empleado → cartera de clientes (1:N). Email "Te han asignado al cliente X". **Diferencial real vs Holded/Quipu/A3/Sage**. S2 con permisos finos por módulo queda como deuda futura si se necesita.

## 📅 2026-06-06 — Bloque Contabilidad completo (35+ slices)

✅ **TODO cerrado en una sesión maratón + post-mortem 06-07**:

- ⚖️ V46 catálogo PGC PYMES (RD 1515/2007) sembrado por empresa.
- Asiento automático al validar (compras + ventas).
- `TerceroAccountResolverService` (port CONTENDO) BY_INDEX/BY_NIF.
- `ExpenseAccountClassifierService` + `IncomeAccountClassifierService`.
- `AccountingLearningService` + endpoint `/reclassify`.
- V56 `companies.tercero_account_length/mode` + UI.
- V57 `sales_invoices.concept/purchase_invoices.concept`.
- Asientos manuales con bloqueo periodo (ACC-MANUAL).
- ✅ Libro Diario + Mayor + Sumas y Saldos (ACC-BOOKS) — **UI COMPLETA 2026-06-17**
  (Diario ya la tenía; Mayor/Sumas y Saldos añadidos en REPORTS-UI).
- Cuentas bancarias + movimientos + cobros/pagos (BANK-ACCOUNTS).
- 🔶 Importación Norma 43 + CSV bancario (BANK-IMPORT) — **solo BACKEND**
  (`BankImportService` + endpoint); **falta UI** para elegir y subir el fichero
  (verificado 2026-06-17: sin botón ni método en AccountingApiClient). La
  auto-conciliación (REC-BANCARIA) sí tiene UI.
- Préstamos + cuadro amortización (LOANS).
- Inmovilizado + amortización (ASSETS-ENTRIES).
- 🔶 **Plantillas asiento manual (ACC-TEMPLATES)** — backend con endpoints, pero
  **falta UI de gestión (CRUD)** de plantillas (verificado 2026-06-17).
- ✅ Balance situación + PyG (REPORTS-CONTABLES) — **UI COMPLETA 2026-06-17**
  (REPORTS-UI: pestañas Balance de Situación y PyG en AccountingScreen). ECPN
  (`/reports/equity-changes`) sigue 🔶 solo-backend (opcional).
- Aprendizaje contable UI (ACC-LEARN-UI).
- 🔶 Exportación contable Contasol/A3/Sage (EXPORT-CONTABLE) + EXT-IMPORT inversa —
  **solo BACKEND** (`AccountingExportService`); **falta UI** (selector de formato +
  descarga). Verificado 2026-06-17.
- 💰 Motor recurrentes (cron) con 7 kinds.
- RefreshBus publish/subscribe central.
- V59 relax UK tax_identifier para shadow companies + start-management.
- V60 `journal_entries.source_pdf_path` + visor PDF reutilizable PDFBox.
- ⚖️ Modelos AEAT **347 + 390 + 190** (AEAT-EXTRAS) — backend completo; UI con
  **editor genérico (JSON)** salvo 130/303 que tienen editor específico (matiz
  verificado 2026-06-17). Editores específicos 347/390/190 = mejora pendiente.
- ✅ ⚖️ **YEAR-CLOSE — Cierre ejercicio con aplicación resultado**.

## 📅 2026-06-05 — Asesoría↔cliente + exports legales (15 slices)

- ✅ V41+V42 advisory_invitations con token base62 32 chars + estados.
- ✅ UNLINK-SYNC + REINVITE + POLLING-FIX + INSTANT-REFRESH + DEHU-POLLING.
- ✅ 💰 DUAL-SIDEBAR + V43 — "Mi empresa" vs "Mis clientes" en ADVISORY.
- ✅ EMP-USER-MAP — TimeClockService.resolveCurrentEmployee.
- ✅ ⚖️ TC-EXPORT — PDF/CSV verificable fichajes RD 8/2019.
- ✅ ⚖️ AUDIT-EXPORT — PDF/CSV verificable audit_events.
- ✅ ⚖️ AUDIT-CHAIN — V44 hash encadenado audit_events + collation fix MariaDB 11.4.

## 📅 2026-06-04 — PDF-EXTRACT v2 + RETA + ALTA

- ✅ PDF-EXTRACT v2 layout X/Y por span. Port `calendarioParser.v3.js`.
- ✅ PDF-TEMPLATES aprendizaje por NIF (V37 `supplier_extraction_templates`).
- ✅ PDF-AMAZON específico + PDF-MULTI multi-factura.
- ✅ TC-CFG tipos evento configurables + TC-AUDIT sub-tab + EMP-GEO opcional.
- ✅ Bloque L1 Employees + Contracts backend + UI.
- ✅ Bloque L2 RETA backend + UI completo.
- ✅ ⚖️ Modelo 130 IRPF + Modelo 303 IVA UI.
- ✅ ⚖️ Bloque C4 RD 8/2019: V21 + TimeClockService.punch + CSV publico + UI.
- ✅ ALTA-5 credenciales externas cifradas Jasypt (DEHú, SS RED, SILTRA).
- ✅ CERT-IMPORT certificado .p12 (V19) + UI.

## 📅 2026-06-03 — VeriFactu completo (cuerpo legal)

✅ **Bloque entero cerrado en una sesión** — cumple Orden HAC/1177/2024:

- ⚖️ V14 verifactu_registry + VerifactuHashService + hook en `validate` + `/verify`.
- ⚖️ V17 modality separada de mode (TEST/PROD).
- ⚖️ V18 `sif_event_registry` + 13 tipos evento + hooks SYSTEM_START/STOP/INVOICE_VALIDATED/VOIDED/SUMMARY_6H/ANOMALY_DETECTION.
- V19 `companies.invoice_storage_root` + InvoiceStorageService.
- ⚖️ VF3-QR oficial AEAT (zxing) + endpoints AEAT TEST y PROD.
- F-EMAIL (factura por email) + EmailSenderService.
- ⚖️ VF-SIGN MVP firma XML-DSig (Apache Santuario + BouncyCastle).
- ⚖️ VF-ANOMALY job 12h.
- VF4 reintento firma 10min batch=100.
- PROFORMA-FLOW (PDF sin QR ni huella).
- VF3-SOAP cliente AEAT (no probado contra AEAT real).
- ⚖️ VF-EVENTS-EXPORT (PDF/CSV verificable).

## 📅 2026-06-02 — F4 editor + F4b PDF + i18n

- ✅ F4 Editor de factura estilo CONTENDO.
- ✅ F4b PDF multipágina con OpenPDF.
- ✅ F5+ Configuración facturación.
- ✅ Command Palette Ctrl+K + atajos + navegación mouse BACK/FORWARD.
- ✅ ⚖️ Anulación con vínculo (`voidValidated` atómico).
- ✅ i18n pasada con lupa.

## 📅 2026-06-01 — F1 dominio facturas + F2/F3/F5 + seguridad

- ✅ F1 paquete dedicado `billing/invoices/`.
- ✅ V13 + verifactu/ shell.
- ✅ V15 + 6 textos legales + invoice_show_iban + migration_acknowledged_at.
- ✅ D1 V10/V11 unificación issuers→companies + customer_billing_profiles→customers.
- ✅ 🔴 Refactor WorkspaceRepository → `tenantContext.getCurrentCompanyId()`.
- ✅ 🔴 @RequiresRole + RoleInterceptor.
- ✅ ⚖️ Audit log activo.
- ✅ 🔴 Cifrado columnas sensibles con Jasypt.
- ✅ 🔴 Refresh token revocation V12.

## 📅 Sesiones C1-C4 (mayo–junio 06-01)

- ✅ C1 login real email/password con JWT + AuthSession + selector empresa.
- ✅ C3 Configuración MVP — V9 + Jasypt + 3 controllers `/api/settings/*` + UI TabPane.
- ✅ Issuer módulo (deprecado por D1).
- ✅ Infra modular: `module_catalog`, `company_modules`, `TenantContext`, `@RequiresModule`, interceptor 403.
- ✅ 🔴 Fix seguridad `CustomerRepository` filtra por `company_id`.

---

# 🔴 PENDIENTE — CRÍTICO

## ⚖️ Legal obligatorio

- ✅ ⚖️ **CTR-4 — PDF SEPE oficial firmable** *(cerrado — `ContractPdfGenerator` con modelo UNIFIED_2022 / BY_CODE)*.
- ⬜ ⚖️ **VF-SIGN-XADES-AEAT estricto** — ampliar `XmlSignerService` para producir XAdES-EPES estricto sobre XML canónico AEAT (XSD oficial). Incluye `SignaturePolicyIdentifier` + `SignedSignatureProperties` + `SigningCertificate`. Requiere FNMT real.
- ⬜ ⚖️ **VF3-SOAP afinado** — parseo real respuesta AEAT (Aceptado / AceptadoConErrores / Rechazado). Requiere FNMT real + alta SIF en sede AEAT.
- ⬜ ⚖️ **Obligaciones fabricante VeriFactu** — registro como SIF en sede AEAT + documento declaraciones responsables + página pública de cumplimiento. Atacar antes de despliegue comercial.
- ⬜ ⚖️ **Modelos AEAT 100 / 180 / 200 / 411** — WebSearch legal extenso + patrones casillas regex (`fiscal_casilla_patterns_180`, 69 patrones) + mapeo (`aeat_campo_mapeo_180`, 32 mapeos).

## 🔴 Decisiones bloqueantes

- 🟡 **MEMP — Portal del empleado (móvil)**. Decisiones Benjamin 2026-06-18:
  - **Tecnología = PWA servida por Spring** (HTML/JS + manifest + service worker,
    mismo patrón que la página del kiosko). Igual que CONTENDO (Next.js + manifest.ts
    + /activar + activate-install). NO nativa, NO Next.js.
  - **Alcance = completo**: fichar, vacaciones/bajas (con adjuntos), nóminas
    (recibir/confirmar/firmar/descargar), calendario/jornada/plan del día.
  - **Caso de uso clave (Benjamin 2026-06-18)**: EMPRESA DE SERVICIOS cuyos empleados
    fichan en VARIOS clientes y lugares distintos. Aquí el kiosko NO sirve (no hay
    centro fijo) → fichaje por PWA en el móvil con GEO obligatorio. El modelo YA lo
    soporta: `time_clock_events.customer_id` + `latitude/longitude` y el `punch(...)`
    los aceptan; la geo se captura como EVIDENCIA (geo_policy `info`), no contra radio
    fijo (`strict` es solo para centros físicos/kiosko).
  - **Conectividad = CLOUDFLARE TUNNEL** (decidido Benjamin 2026-06-18). Acceso
    externo obligatorio (el empleado ficha fuera del WiFi de la oficina); túnel
    saliente del equipo on-premise → URL HTTPS sin abrir puertos del router. Gratis.
    Solo-LAN queda para clientes con un único centro físico (kiosko). **NO bloquea
    construir**: la PWA es el mismo código; el túnel se configura al empaquetar.
    Se construye LAN-first para desarrollo.
  - **Plan slices**:
    - ✅ **MEMP-1 HECHO** (2026-06-18) — invitación + activación + login + cascarón PWA.
      MEMP-1a: V132 `employee_app_invitations` + `EmployeeAppService` (admin invita /
      público activa) + `DeviceTokenService.pairEmployeeDevice` (additive, reusa modelo
      PIN). MEMP-1b: PWA servida por Spring (`/api/public/empleado/app` + manifest + sw
      + icon): activar→PIN→home con stubs. MEMP-1c: botón "Invitar al móvil" en el
      editor de empleado (enlace + código copiables). El empleado entra por
      `/api/auth/pin-login` (JWT EMPLOYEE). Verificado: V132 aplica, smoke OK, PWA sirve.
    - ⬜ **MEMP-2** fichar desde la PWA (reusa `TimeClockService.punch` + geo + customer_id).
    - ⬜ **MEMP-3** calendario/jornada/plan (horario JOR-2 + jornada real JOR-1).
    - ⬜ **MEMP-4** vacaciones/bajas (pedir + adjuntos).
    - ⬜ **MEMP-5** nóminas (recibir/confirmar/firmar/descargar; falta backend entrega/firma).
    - Fuente CONTENDO: empleado*Controller.js, nominaEntregasController.js,
      app180-frontend/app/empleado + /activar.
  - **Siguiente**: MEMP-2 (fichar desde el móvil).
- ✅ **PORT-2 JORNADAS — CERRADO 2026-06-18** (decisión Benjamin: real + planificación,
  modelo 1 plantilla = N bloques → M empleados, CONTENDO). Entregado:
  - **JOR-1** jornada REAL desde fichajes: `WorkdayService` calcula horas
    trabajadas/pausas por empleado-día agregando `time_clock_events` (flag
    `is_work_time`), `GET /api/labor/workdays`. UI: sección "Jornadas fichadas"
    en la pestaña Jornadas. **Esto cierra FM-5 (fichaje→jornada).**
  - **JOR-2** planificación: V131 `work_schedule_templates`+`work_schedule_blocks`
    +`work_schedule_assignments`; `WorkScheduleService` (CRUD plantillas, reemplazo
    de bloques con validación fin>inicio y sin solapes, asignaciones con vigencia);
    `/api/labor/schedule-templates`.
  - **JOR-3** UI: pestaña "Planificación" (Tiempo y jornada) con CRUD plantillas +
    editor de bloques por día + asignación a empleados.
  - **Pendiente menor (no bloqueante):** excepciones por fecha (CONTENDO
    `plantilla_excepciones_180`); comparación planificado-vs-real (JOR-4).

---

# 🟠 PENDIENTE — ALTA PRIORIDAD

## 💰 UI asesoría↔cliente

- ✅ 💰 **Mensajes / Documentos / Notificaciones** — cerrado en módulo **Comunicación** (COMM-MOD/COMM-LINK, V77/V78). Timeline + upload multipart + badge no leídos. Solo visible si hay vínculo asesoría↔empresario.
- ✅ 💰 **Vista panorámica asesoría** — cerrado (PANORAMA-ASESORIA, `546792c`): 5 KPIs cruzados de cartera.

## Empleados / Nóminas

- ✅ ⚖️ **Payrolls — ciclo mensual** — cerrado en bloque NOM (calcular/pagar/PDF/email + asientos devengo/pago + SS empresa vía cuotas TC).
- ✅ 💰 **Reporte coste empresa por empleado** — cerrado (NOM-6, `aa61627`): pestaña "Coste empresa" en Labor con bruto anual + SS empresa + coste total por empleado y totales al pie.
- ✅ **Entrega de nóminas con firma trabajador** *(PAY-DELIVERY, 2026-06-15)* — V116 (delivered_at, delivery_method, acknowledged_at). Pestaña Nóminas: columna "Entrega" (Pendiente/Entregada/Firmada) + botón "Entrega / acuse" (fecha + vía HAND/EMAIL/PORTAL/POSTAL + acuse del trabajador). ET art. 29.
- ⬜ **Incidencias de nómina** — horas extra, bajas, complementos variables por periodo.
- ⬜ **Topes de cotización TGSS + pagas extra cotizadas** — afinar el cálculo NOM (hoy base = bruto sin topes; EXTRA_* sin asiento).
- ⬜ Revisión completa contratos + flujo alta del empleado.

## ⚖️ RD 8/2019 fichajes extensión

- ✅ **Geolocalización al fichar** — cerrado (GEO-FICHAR): verificación en `TimeClockService.punch` contra `work_centers` (lat/lng/radio_m + geo_policy).
- ⬜ **Sincronización offline batches** (kioskos sin red) — para cuando exista app móvil.

## ⚖️ Conectores externos reales

- ⬜ **Conector DEHú real** — falta job que descarga del servicio AEAT vía SOAP/REST con certificado.
- ⬜ **Conector SS RED / SILTRA real** — credenciales guardadas, falta envío real (AFI/CRA/DELT@/CRETA).

## CTR bloque restante

- ✅ **CTR-3 — Plantillas reutilizables** (`contract_templates`) — cerrado.
- ✅ **CTR-6 — Alertas vencimientos** — cerrado (cron + plazos prueba/temporal/anuales).
- ✅ **CTR-7 — Anexos** — cerrado (confidencialidad/no competencia/exclusividad).
- ✅ ⚖️ **CTR-5 — XML contrat@ SEPE oficial** — cerrado (`ContractXmlGenerator`).

---

# 🟡 PENDIENTE — MEDIA PRIORIDAD

## Compras / pagos / banco

- ✅ **Reconciliación bancaria asistida** — cerrado (REC-BANCARIA, Levenshtein "casi-iguales"). *Refinamiento ML adicional queda como mejora futura.*
- ✅ **Gastos recurrentes silenciados** — cerrado (REC-IGNORE, V91).
- ✅ **Multi-allocation pagos** — cerrado (un pago reparte entre N facturas).

## Fiscal afinado

- ✅ ⚖️ **Calendario fiscal con vencimientos** — cerrado (CAL-FISCAL, seed 303/130/111/190/347/390/200 + tabla próximos vencimientos).
- ⬜ ⚖️ **Régimen especial IVA, prorrata, criterio caja** — catálogo cuentas lo soporta pero no hay UI.
- ✅ **CONS-CIERRE** *(2026-06-15)* — nueva pestaña **"Cierre de ejercicio"** en el módulo Contabilidad (`AccountingScreen`): precalcular (ingresos/gastos/resultado + IS 25%), **previsualizar el asiento de regularización** (6x/7x→129) sin crear asiento, **cerrar** con aplicación del resultado (reservas/dividendos/pérdidas, cuadre en vivo + confirmación) y **reabrir**. Resuelve el hallazgo previo: el backend del cierre existía pero la UI no lo invocaba (los métodos year-close estaban muertos en `LaborApiClient`; movidos a `AccountingApiClient`). *Nota: "previsualizar regularización" requiere fila en `fiscal_years` (si falta, 404 manejado); precalcular/cerrar no.* Compila limpio.
- ⬜ **Consolidación empresas asociadas** — eliminación operaciones intragrupo. No urgente.

## UI/UX

- ⬜ **Dashboard widgets personalizables** — por usuario, activar/desactivar/reordenar.
- ✅ **Backup local automático** — cerrado (BACKUP-LOCAL semanal lunes 03:00 + panel Configuración).
- ⬜ **CENTROS-MAP** — mapa interactivo Leaflet+Nominatim en WebView para seleccionar lat/lng. *(El botón "Buscar coordenadas" con Nominatim ya está hecho — CENTROS-GEOCODE; falta solo el mapa visual.)*
- ✅ **REC-IGNORE** — cerrado (botón "Ignorar candidato recurrente", V91).
- ✅ Editor calendario event card "Editar"/"Eliminar" *(ya implementado — verificado 2026-06-15: `dayEventCard` tiene botones Editar (`showFormDialog("calendar", …)`) y Eliminar (`deleteCalendarEvent` → DELETE `/calendar/{id}`); backlog estaba desactualizado).
- ⬜ Auditar otros módulos viejos (customers detail, dashboard CRUDs).
- ✅ **VG-FULL-SCAN restante** *(2026-06-15)* — auditado con agente Explore (294 columnas). Añadido comparador a las 7 columnas numéricas/fecha que faltaban (TPB total, validez cert., multi-asignación fecha+importe, recurrentes importe+fecha). Las de tamaño de archivo (humanSize, unidades mezcladas KB/MB) se excluyen a propósito.
- ⬜ ❓ **OCR para PDFs escaneados** (Tess4J + Tesseract) — necesito decisión: instalar binario nativo.

## Workflow trabajos / Derivados PORT-2

- ⬜ **Partes de día con validación admin** — DRAFT → SUBMITTED → APPROVED → BILLED.
- ⬜ 💰 **Conversión work_log → línea sales_invoice** automática al cobrar. Setar `billed_invoice_line_id`.
- ⬜ **Fichajes sospechosos** — detección patrones anómalos.

## Calendario

- ⬜ Calendario laboral por empresa completo.
- ⬜ ❓ Integración Google Calendar bidireccional — necesito credenciales OAuth Google Cloud Console.
- ⬜ Importación masiva calendarios.

## Asesoría / multi-cliente

- ⬜ Permisos finos por sub-recurso (ej. `configuracion:write` sobre cliente concreto).
- ⬜ **EQUIPO S2 — permisos finos por (empleado, cliente, módulo)** — si la necesidad real aparece.

---

# 🟢 PENDIENTE — BAJA PRIORIDAD

- ⬜ Alertas de seguridad (`security_alerts_180`) — intentos login, accesos sospechosos.
- ✅ Análisis / Alertas BOE — cerrado (BOE-RSS diario + pantalla dedicada con apertura de PDF oficial).
- ⬜ Acceso PWA / móvil *(posiblemente cubierto por MOBILE-EMPLEADO)*.
- ⬜ Email personal via Google OAuth2 — a nivel usuario.

---

# ❌ DESCARTADOS POR BENJAMIN

- ❌ **AI Copilot** — descartado 06-10 tarde. NO se hará en BENJAGEST.
- ❌ **Mi perfil** (módulo) — eliminado V87. Consolidado en Configuración → Sesión.
- ❌ **Portal del empleado desktop** — eliminado V87. Migra a app móvil futura (MOBILE-EMPLEADO).
- ❌ **L3-3 seed festivos autonómicos hardcoded** — retirado 2026-06-09. Sustituido por flujo PDF (parser CONTENDO).
- ❌ **Sugerencias_180** — ya cerrado como módulo SUG (06-10 mañana, `74f42ae`).

---

# ❓ DECISIONES TUYAS PENDIENTES

| Decisión | Bloquea | Mi recomendación si decido yo |
|---|---|---|
| 🔴 App móvil empleado: stack técnico | MOBILE-EMPLEADO + PORT-2 partes reales | Capacitor (compartir Java backend vía REST) |
| 🔴 JORNADAS: modelo plantilla-bloques-asignación | PORT-2 UI completa | 1 plantilla = N bloques + adjudicada a M empleados (CONTENDO) |
| 🟡 OCR Tesseract | ⬜ OCR PDFs escaneados | Sí, instalar binario nativo. Hoy PDFs imagen rechazan con 422. |
| 🟡 CENTROS-MAP | ⬜ Mapa lat/lng | WebView + Leaflet + Nominatim (offline-friendly) |
| 🟡 Régimen especial IVA / prorrata / criterio caja | UI fiscal afinado | Modelar tras un caso real de cliente que lo necesite |
| ✅ Hechas | — | — |
| Nómina: SS empresa + asiento (NOM) | SS vía cuotas TC, 2 asientos, AT/EP por contrato | |
| Backup local: ruta + cron | semanal lunes 03:00 | |
| TC-CAL fichaje en festivo | warning amarillo, no bloqueo | |
| work_logs embebido vs separado | embebido CONTENDO | |
| Avatar usuario | = logo empresa | |
| AI Copilot | descartado | |
| Idioma | botón ES/EN en header | |

---

# 📚 Reglas de manejo del backlog

1. **Trabajo siempre desde `feat/Benjamin`**. Prueba local antes de commitear.
2. **Un commit cierra como mucho un item** (si cierra varios relacionados, listar todos en el mensaje).
3. **Marcar `✅` con hash + fecha** al cerrar. Nunca borrar lo cerrado.
4. **Tras commit + merge `--no-ff` a `develop`**: push a ambas ramas.
5. **Si aparece algo nuevo durante el trabajo**: añadirlo aquí en su cubo de prioridad.
6. **Antes de empezar sesión**: leer este fichero. Antes de cerrar sesión: actualizar.
7. **Regla 10.bis (CLAUDE.md)**: verificar código antes de tocar. No asumir.
8. **Tabla ✅ HECHO** se reordena por fecha (más reciente arriba). Tabla pendiente por prioridad.
