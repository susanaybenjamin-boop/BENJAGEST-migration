# Backlog operativo BENJAGEST

> **Última actualización:** 2026-06-10 noche (reescritura completa con verificación contra código por agente Explore + grep). Se identificó EQUIPO S1 marcado como ⬜ pero realmente cerrado en V66; se incorpora correctamente como ✅.
>
> **Forma de trabajo (junio 2026):** Benjamin lidera y decide. Pablo solo entra de uvas a peras desde 05-30. Todo el trabajo va por `feat/Benjamin` → prueba local → commit → merge `--no-ff` a `develop`. Cada item cerrado lleva commit hash + fecha. **Regla 10.bis de CLAUDE.md aplica siempre: verificar código antes de tocar.**
>
> **Fuentes complementarias:** [`gap-analysis-contendo.md`](gap-analysis-contendo.md), [`gap-analysis-config-ui.md`](gap-analysis-config-ui.md), [`migration-roadmap.md`](migration-roadmap.md), [`vf-chain-fix.md`](vf-chain-fix.md), [`agents-debug-pattern.md`](agents-debug-pattern.md).

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

- **Cerrado a fecha de hoy**: bloque VeriFactu / Facturación + Contabilidad PGC PYMES completo + RD 8/2019 fichaje legal + Asesoría↔cliente con sidebar dual + **EQUIPO S1** + exports verificables (audit + SIF + fichajes) + L4 contratos hasta CTR-2 + PORT-1..5 de junio 10.
- **🔴 Crítico abierto**: CTR-4 (PDF contrato SEPE firmable), JORNADAS UI completa, MOBILE-EMPLEADO (decisión estructural).
- **🟠 Alta abierta**: Modelos AEAT 100/180/200/411, UI asesoría↔cliente (mensajes/docs/notif), Payrolls UI dedicada, Conector DEHú/SS RED reales.
- **🟡 Media abierta**: Reconciliación bancaria asistida, Multi-allocation pagos, CENTROS-MAP, REC-IGNORE, Dashboard widgets, Backup local, OCR PDFs.
- **🟢 Baja abierta**: Análisis BOE, Email personal OAuth.

---

# ✅ HECHO — orden cronológico inverso (más reciente arriba)

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
- ⚖️ Libro Diario + Mayor + Sumas y Saldos (ACC-BOOKS).
- Cuentas bancarias + movimientos + cobros/pagos (BANK-ACCOUNTS).
- Importación Norma 43 + CSV bancario + auto-conciliación (BANK-IMPORT).
- Préstamos + cuadro amortización (LOANS).
- Inmovilizado + amortización (ASSETS-ENTRIES).
- ✅ **Plantillas asiento manual (ACC-TEMPLATES)**.
- ⚖️ Balance situación + PyG (REPORTS-CONTABLES).
- Aprendizaje contable UI (ACC-LEARN-UI).
- Exportación contable Contasol/A3/Sage (EXPORT-CONTABLE) + EXT-IMPORT inversa.
- 💰 Motor recurrentes (cron) con 7 kinds.
- RefreshBus publish/subscribe central.
- V59 relax UK tax_identifier para shadow companies + start-management.
- V60 `journal_entries.source_pdf_path` + visor PDF reutilizable PDFBox.
- ⚖️ Modelos AEAT **347 + 390 + 190** (AEAT-EXTRAS).
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

- ⭐ ⬜ ⚖️ **CTR-4 — PDF SEPE oficial firmable**. El wizard de CTR-2 tiene combo `UNIFIED_2022 / BY_CODE` pero no genera nada. **WebSearch legal previo obligatorio** sobre Estatuto Trabajadores (RDLeg 2/2015), reforma 2022 (RD-Ley 32/2021), reformas 2024-2025, modelos SEPE oficiales. OpenPDF ya en `pom.xml`.
- ⬜ ⚖️ **VF-SIGN-XADES-AEAT estricto** — ampliar `XmlSignerService` para producir XAdES-EPES estricto sobre XML canónico AEAT (XSD oficial). Incluye `SignaturePolicyIdentifier` + `SignedSignatureProperties` + `SigningCertificate`. Requiere FNMT real.
- ⬜ ⚖️ **VF3-SOAP afinado** — parseo real respuesta AEAT (Aceptado / AceptadoConErrores / Rechazado). Requiere FNMT real + alta SIF en sede AEAT.
- ⬜ ⚖️ **Obligaciones fabricante VeriFactu** — registro como SIF en sede AEAT + documento declaraciones responsables + página pública de cumplimiento. Atacar antes de despliegue comercial.
- ⬜ ⚖️ **Modelos AEAT 100 / 180 / 200 / 411** — WebSearch legal extenso + patrones casillas regex (`fiscal_casilla_patterns_180`, 69 patrones) + mapeo (`aeat_campo_mapeo_180`, 32 mapeos).

## 🔴 Decisiones bloqueantes

- ❓ **MOBILE-EMPLEADO — App móvil/tablet del empleado**. Los partes de día + Portal del empleado vivirán en una app que el empresario instala en el dispositivo del empleado. **Sin diseño técnico aún**: ¿Capacitor/Tauri? ¿React-native? ¿PWA? ¿Compartirá BD vía API REST?
- ❓ 💰 **PORT-2 JORNADAS — UI completa** *(decisión arquitectura ya tomada: embebido CONTENDO)*. Backend skeleton ya en V86+V88. **Necesito tu diseño UX**: ¿TabPane interno o sub-pestañas? ¿1 plantilla = N bloques + adjudicada a M empleados como CONTENDO?

---

# 🟠 PENDIENTE — ALTA PRIORIDAD

## 💰 UI asesoría↔cliente (backend listo, falta UI)

- ⬜ 💰 **Mensajes asesoría↔cliente UI** — backend V77 `advisory_messages` LISTO. Falta: pestaña sidebar "Mensajes" con timeline + badge no leídos.
- ⬜ 💰 **Documentos compartidos UI** — backend V78 `advisory_documents` LISTO. Falta: pestaña con upload multipart real + lista por estado (UPLOADED/REVIEWED/ACCEPTED/REJECTED).
- ⬜ 💰 **Notificaciones asesor UI badge sidebar** — backend V78 `advisory_notifications` LISTO. Falta: badge `countUnread` + dropdown + marcar leído.
- ⬜ 💰 **Vista panorámica asesoría** — cross-client dashboard con KPIs agregados + vencimientos por cliente.

## Empleados / Nóminas

- ⬜ ⚖️ **Payrolls UI dedicada** — `PayslipService` + `PayslipPdfGenerator` existen. Falta módulo OWNER con ciclo mensual: generar borrador → ver detalle → validar → asiento contable mensual + integración SS RED.
- ⬜ **Entrega de nóminas con firma trabajador** — fecha + vía.
- ⬜ **Incidencias de nómina**.
- ⬜ **Reporte coste empresa por empleado**.
- ⬜ Revisión completa contratos + flujo alta del empleado.

## ⚖️ RD 8/2019 fichajes extensión

- ⬜ **Geolocalización al fichar** — `work_centers` ya tiene lat/lng/radio_m + geo_policy. Falta verificación en `TimeClockService.punch`.
- ⬜ **Sincronización offline batches** (kioskos sin red) — para cuando exista app móvil.

## ⚖️ Conectores externos reales

- ⬜ **Conector DEHú real** — falta job que descarga del servicio AEAT vía SOAP/REST con certificado.
- ⬜ **Conector SS RED / SILTRA real** — credenciales guardadas, falta envío real (AFI/CRA/DELT@/CRETA).

## CTR bloque restante

- ⬜ **CTR-3 — Plantillas reutilizables** (`contract_templates`) — UI "Crear plantilla desde contrato" + "Aplicar plantilla en bloque".
- ⬜ **CTR-6 — Alertas vencimientos** — cron diario en `dehu_notifications`. Plazos: prueba 7d antes, temporal 30d, anuales 60d.
- ⬜ **CTR-7 — Anexos** — confidencialidad/no competencia/exclusividad.
- ⬜ ⚖️ **CTR-5 — XML contrat@ SEPE oficial** — generador XML para alta SEPE.

---

# 🟡 PENDIENTE — MEDIA PRIORIDAD

## Compras / pagos / banco

- ⬜ **Reconciliación bancaria asistida con sugerencias ML** — hoy es por importe+fecha exactos. Sugerir matches "casi-iguales".
- ⬜ **Gastos recurrentes silenciados** — marcar temporalmente por vacaciones/baja.
- ⬜ **Multi-allocation pagos** — distribuir 1 pago en varias facturas/trabajos.

## Fiscal afinado

- ⬜ ⚖️ **Calendario fiscal con vencimientos** — seed oficial AEAT (303/130/347/390…) + alertas automáticas.
- ⬜ ⚖️ **Régimen especial IVA, prorrata, criterio caja** — catálogo cuentas lo soporta pero no hay UI.
- ⬜ **CONS-CIERRE** — previsualización del asiento de regularización antes del cierre. Hoy YEAR-CLOSE lo hace en un solo click.
- ⬜ **Consolidación empresas asociadas** — eliminación operaciones intragrupo. No urgente.

## UI/UX

- ⬜ **Dashboard widgets personalizables** — por usuario, activar/desactivar/reordenar.
- ⬜ ❓ **Backup local automático** — equivalente JavaFX a File System Access API. Necesito decisión: ruta + cron.
- ⬜ **CENTROS-MAP** — mapa interactivo Leaflet+Nominatim en WebView para seleccionar lat/lng.
- ⬜ **REC-IGNORE** — botón "Ignorar candidato recurrente". V91 tabla + filtro + UI.
- ⬜ Editor calendario event card "Editar"/"Eliminar".
- ⬜ Auditar otros módulos viejos (customers detail, dashboard CRUDs).
- ⬜ **VG-FULL-SCAN restante** — ~25 omisiones de NUMERIC_STRING_COMPARATOR / ISO_DATE_COMPARATOR.
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
- ⬜ Análisis BOE (`boe_analysis_180`).
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
| 🟡 Backup local: ruta + cron | ⬜ Backup local | `{userHome}/BENJAGEST-backup/{YYYY-MM-DD}.zip` semanal |
| 🟡 OCR Tesseract | ⬜ OCR PDFs escaneados | Sí, instalar binario nativo. Hoy PDFs imagen rechazan con 422. |
| 🟡 CENTROS-MAP | ⬜ Mapa lat/lng | WebView + Leaflet + Nominatim (offline-friendly) |
| ✅ Hechas | — | — |
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
