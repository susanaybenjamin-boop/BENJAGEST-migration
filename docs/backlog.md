# Backlog operativo BENJAGEST

> Lista única ordenada de mayor a menor importancia con TODO lo que hay que hacer en BENJAGEST.
> Se va tachando conforme cada item se crea, prueba, commitea y mergea a `develop`.
>
> **Última revisión:** 2026-06-02 (F4 cerrado + decisión arquitectónica: serie elegida por server, usuario solo define STANDARD + deuda i18n abierta).
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

## 🔴 CRÍTICA — siguiente bloque a atacar

Lo que toca **antes** de seguir con features funcionales. Cubre: legalidad, seguridad multi-tenant, y los dos slices que dejamos preparados.

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
- ⬜ **C2** — Google Sign-In con OAuth2 (aplazado conscientemente hasta que Benjamin genere credenciales en Google Cloud Console). No olvidar.

### VeriFactu / Facturación (legal obligatoria)

> **F1 cerrado (2026-06-01):** dominio de facturas dedicado en `billing/invoices/` (SalesInvoice + InvoiceLine + Repository + Service + Controller). Antes vivía mezclado dentro de WorkspaceRepository genérico; ahora tiene su propio paquete con endpoints `/api/billing/invoices`, lógica de transición DRAFT→VALIDATED, cálculo de totales (subtotal, IVA, retención, total) con BigDecimal HALF_UP y enganche a SeriesService para emitir el número al validar.
>
> **F2/F3/F5 cerrado (2026-06-01, i18n 2026-06-02):** Pantalla "Facturación" en la UI con sub-tabs estilo CONTENDO (Dashboard/Facturas/Configuración), reutilizando `settings-tabs` y `settings-tab-body` sin tocar paletas. V13 + backend `billing/verifactu/` (VerifactuConfig + Service + Controller con GET/PUT `/api/billing/verifactu-config`, defensa PROD-sin-cert → 400). UI: header con icono + botón "Nueva factura" (placeholder hasta F4), tab Facturas con filtros (status/cobro) + tabla, tab Configuración con modo VeriFactu + selector certificado + pie + listado de series read-only. BillingApiClient en `service/`.
>
> **F5+ cerrado (2026-06-01, i18n 2026-06-02):** Ampliación pestaña Configuración paridad CONTENDO. V15 amplía `companies` con 6 textos legales (`invoice_text_exempt`, `_reverse_charge`, `_reduced_vat`, `_rectifying`, `_legal_terms`) + `invoice_show_iban` + `migration_acknowledged_at/_by_user_id`. Backend `billing/texts/InvoiceTexts*` con GET/PUT `/api/billing/invoice-texts`. **Bloqueo de serie por continuidad legal**: si una serie tiene ≥1 factura `VALIDATED` en el año actual, el PUT rechaza cambios de code/format/kind/numberingType con 409 (mensaje explícito; sólo cierre de año desbloquea). Endpoint `POST /api/billing/series/{id}/migrate` permite importar correlativo de otro programa con `{nextNumber, acknowledged: true}`; sin `acknowledged=true` → 400. UI: nuevas secciones "Migración desde otro programa" (ComboBox serie + campo número + checkbox responsabilidad + botón Aplicar) y "Textos legales en la factura" (6 TextArea + checkbox mostrar IBAN). Smoke verde: bloqueo F2026 (que tiene F-2026-0001 validada) → 409, migrate sin ack → 400, migrate con ack=true → next_number=99 aplicado.
>
> **F4 cerrado total (2026-06-02, i18n incluida):** Rediseño completo del editor estilo CONTENDO en `showInvoiceEditor`: header con back/título/badge "PRÓXIMO Nº AL VALIDAR" (formato real, monoespaciado), 3 tarjetas (Cabecera 3-col cliente+detalle/fechas/tipo-pill / Líneas con `decimalColumn`+`liveTextColumn` que comitean en cada pulsación + Subtotal/Total con `computedColumn` (IdentityHashMap-driven) que se actualiza sin `TableView.refresh()` para no perder foco / Totales+Observaciones con TOTAL en gradiente). Footer Cancelar/Guardar borrador/Validar y emitir. `previewNextNumber(SeriesEntry)` replica `SeriesService.formatNumber` (placeholders `{CODE}`/`{YYYY}`/`{0000+}`) en cliente. Manejo "sin precondiciones" cuando faltan clientes/series. **Decisión 2026-06-02**: usuario solo define la serie STANDARD; el editor no muestra combo de serie — el server pica la serie por `invoiceType` (V16 semilla `PROF`+`RECT` por empresa, `SeriesService.findActiveByKind`, `SalesInvoiceService.createDraft/updateDraft` override `seriesId`, `SeriesService.create/update/delete` rechazan kind≠STANDARD). Acciones desde el listado: Validar/Eliminar borrador/PDF placeholder (F4b). Atajos de navegación: botones laterales BACK/FORWARD del ratón con stack `navBack`/`navForward`. Listado limpia "(borrador)" sin id críptico. Pendientes hijas: PDF multipágina (F4b), anulación con vínculo, simplificadas (requiere ampliar enum `invoice_kind`).
>
> Los próximos items (hash, firma, anulación, almacenamiento, email) se enchufan sobre `SalesInvoiceService.validate` y futuros endpoints. F4b (PDF multipágina), F6 (dashboard real con KPIs y gráficos) pendientes.

- ✅ Series de numeración: paquete `billing/series/` (Series record + Repository con `SELECT … FOR UPDATE` para emisión atómica + Service con reset BY_YEAR + Controller `/api/billing/series`). Tipos soportados: STANDARD, PROFORMA, RECTIFYING, TEST. Anulaciones quedan como evento sobre la factura existente (no es serie nueva — modelo VeriFactu). Smoke tests verdes: 3 claims secuenciales `PROF-2026-0001/2/3`, duplicate code → 409, locked → 409.
- 🔵 Hash encadenado + firma XML + reintentos (parcial):
  - ✅ **VF1** Hash encadenado + registro local — V14 tabla `verifactu_registry` (UNIQUE invoice_id+mode, status PENDING/SENT/ACKNOWLEDGED/ERROR), `VerifactuHashService` reproduce fórmula AEAT (IDEmisor&NumSerie&Fecha&TipoFactura=F1&Cuota&Importe&Huella&FechaHoraHuso, SHA-256 hex MAYÚSCULAS, hora Europe/Madrid), Repository + Service idempotente, hook en `SalesInvoiceService.validate` que registra cuando mode≠OFF. Endpoint GET `/api/billing/verifactu-registry` con filtros mode/status. Smoke verde: 3 facturas en TEST encadenan correctamente.
  - ⬜ **VF2** Firma XAdES-EPES con certificado real (Apache Santuario + BouncyCastle).
  - ⬜ **VF3** Cliente SOAP a AEAT (Apache CXF, modos TEST y PROD).
  - ⬜ **VF4** Job @Scheduled de reintentos con backoff + middleware compliance (tipos F1/F2/R1-R5, regímenes IVA, deadlines).
- ✅ **Anulación con vínculo** (2026-06-02) — `SalesInvoiceService.voidValidated()` crea borrador RECTIFYING con `original_invoice_id` + líneas con cantidad negativa. Endpoint `POST /api/billing/invoices/{id}/void`. Al validar el borrador, cascada en `validate()`: la original pasa a VOIDED y se rellena `rectifying_invoice_id`. `updateDraft` preserva `invoice_type` y `original_invoice_id` para que no se pueda esquivar la cascada editando el tipo. UI: botón "Anular" en el listado activo solo con VALIDATED, alerta de confirmación + alerta con id del borrador creado. Editor reconoce RECTIFYING y pinta pill "Rectificativa de [shortId original]". Badge "PROXIMO Nº" del editor lee la serie RECT (kind=RECTIFYING) en vez de la STANDARD cuando se edita un borrador rectificativo. Pendiente futuro: vista "factura rectificada por X" en el detalle de la original (link bidireccional).
- ⬜ Almacenamiento documental de facturas (ruta `facturacion/almacenamiento`).
- ⬜ Envío facturas por email (`envios_email_180` + `empresa_email_config_180`).
- ⬜ Obligaciones de fabricante VeriFactu (auditoría propia del software, ver `VERIFACTU_OBLIGACIONES_FABRICANTE.md` de CONTENDO).
- ⬜ Configuración fina VeriFactu (modo TEST/PROD, correlativo inicial, certificados firma). [§3 `gap-analysis-config-ui`](gap-analysis-config-ui.md).

### RD 8/2019 (fichajes — obligación legal)

- ⬜ Tabla `fichaje_correcciones` — corrección **solo por apunte vinculado**, no modificación de fichaje original.
- ⬜ Tabla `fichaje_verificaciones` — código CSV para verificación pública (art. 35.8 RD 8/2019).
- ⬜ **Geolocalización en clients/obras** — clients con `lat`/`lng`/`radio_m`/`geo_policy` + verificación al fichar. [§11.A](gap-analysis-contendo.md).
- ⬜ Sincronización offline batches (kioskos sin red).

---

## 🟠 ALTA — el corazón del valor de una gestoría

Cuando lo crítico esté cerrado.

### Fiscal y contabilidad

- ⬜ Carga del PGC completo (668 cuentas) por empresa al alta (hoy hay 326 sembradas solo para demo).
- ⬜ Reglas fiscales con histórico anual (`fiscal_reglas_180`, mecanismo de duplicar en enero ajustando valores AEAT).
- ⬜ Modelos AEAT específicos: 100, 130, 180, 190, 200, 303, 347, 390, 411.
- ⬜ Patrones casillas regex (`fiscal_casilla_patterns_180`, 69 patrones).
- ⬜ Mapeo AEAT (`aeat_campo_mapeo_180`, 32 mapeos).
- ⬜ Calendario fiscal con vencimientos (`calendario_fiscal_180`).
- ⬜ Inmovilizado: cálculo de amortizaciones + vínculo a asientos.
- ⬜ Cierre de ejercicio con aplicación de resultado.
- ⬜ Régimen especial de IVA, prorrata, criterio de caja.
- ⬜ **Gestor de tipos de IVA** — tabla `taxes` configurable (no solo el hardcode). [§3 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Titulares de empresa** (`titulares_empresa_180`) — administradores, % participación, régimen SS. Imprescindible para modelo 200 e informes SS. [§11.D](gap-analysis-contendo.md).

### RETA (autónomos)

- ⬜ Backend RETA completo (Repository + Service + Controller) sobre las 7 tablas que tenemos en schema.
- ⬜ UI RETA: perfiles, estimaciones, tramos, cambios de base, alertas, pre-onboarding.

### Empleados / Nóminas

- ⬜ Backend + UI: employees, contracts, payrolls, medical_leaves, social_security_contributions.
- ⬜ Entrega de nóminas (firma trabajador, fecha, vía).
- ⬜ Incidencias de nómina.
- ⬜ Centros de trabajo (`centros_trabajo_180`).
- ⬜ Reporte coste empresa por empleado.

### Asesoría / multi-cliente

- ⬜ Decidir si `parent_company_id` + `MANAGED_CLIENT` es el link asesoría↔cliente, o si hace falta crear `advisory_client_links` (tabla N:M). Decisión de Benjamin al empezar el slice de asesoría. **Pre-análisis 2026-06-02**: hoy la asesoría sólo cambia el sidebar (`ADVISORY_MODULES`) — el backend no diferencia, TenantContext filtra por la empresa activa, no hay tabla de asientos contables. Cuando abramos este slice, dos decisiones pendientes con recomendación: (A) `parent_company_id` simple 1:N **[recomendado]** vs (B) tabla N:M; y (1) lectura cruzada en tiempo real **[recomendado para arrancar]** vs (2) asientos materializados en `accounting_entries`. ASE0 propuesto: seed de empresa ADVISORY + reasignar 1111 a MANAGED_CLIENT + `AdvisoryService.listManagedClients` + endpoint `/api/advisory/clients/{id}/...` + UI "Mis clientes" con switch temporal de TenantContext. Aplazar hasta tener delante el slice de contabilidad (libros 303/347) — entonces la decisión sobre asientos materializados estará informada por necesidad real.
- ⬜ Mensajes asesoría↔cliente (`asesoria_mensajes_180`).
- ⬜ Documentos compartidos (`documentos_asesoria_180`).
- ⬜ Notificaciones específicas de asesor (`notificaciones_asesor_180`).
- ⬜ Permisos finos por sub-recurso (ej. `configuracion:write` sobre un cliente concreto).
- ⬜ Invitaciones (`invite_180`).
- ⬜ Vista panorámica de asesoría (cross-client dashboard, vencimientos agregados, operaciones en lote).

### Documentos / integraciones externas

- ⬜ Credenciales externas cifradas (`credenciales_externas_180`) — DEHú, SS RED, SILTRA. **Valor diferencial**.
- ⬜ Notificaciones DEHú (`notificaciones_dehu_180`) — recepción automatizada.
- ⬜ Log de uso de certificados (`certificados_uso_log_180`) — trazabilidad obligatoria.
- ⬜ **Gestión visual del certificado `.p12`** — keystore Java + carga local + desencriptado con `subject` y fechas. [§3 `gap-analysis-config-ui`](gap-analysis-config-ui.md).

### SII (Suministro Inmediato AEAT)

- ⬜ Framework SII completo (estados de envío, reintentos, reconciliación con AEAT).

---

## 🟡 MEDIA — MVP completers + nice-to-have

### UI / UX features que CONTENDO tiene

- ⬜ **Lock screen + PIN por inactividad** — desbloqueo de pantalla con `pin_timeout_minutes` y `screensaver_style`. Decisión 6 architecture. [§2.3 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- 🔵 **Command Palette `Ctrl+K`** — buscador global rápido. Implementado el palette + atajos (Ctrl+K abrir, Ctrl+N nueva factura, Ctrl+F facturación, Ctrl+H inicio, F5 refresh, mouse BACK/FORWARD) en sesión 2026-06-02 (i18n incluida). Pendiente: ampliar lista de acciones según vayan saliendo módulos. [§2.2 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Dashboard widgets personalizables** — por usuario, activar/desactivar/reordenar. Layout escritorio vs móvil. [§2.1 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Preferencias por usuario** — tabla `user_settings` o ampliación de `user_accounts` (avatar, `ai_enabled`, plantilla jornada, etc.). [§1 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Backup local automático** — equivalente JavaFX a la File System Access API de CONTENDO. [§3 `gap-analysis-config-ui`](gap-analysis-config-ui.md).

### Compras / pagos / banco

- ⬜ Reconciliación bancaria (`bank_transactions_180`).
- ⬜ Gastos recurrentes silenciados (`gastos_recurrentes_silenciados_180`).
- ⬜ **Multi-allocation de pagos** — un pago se distribuye en partes a varias facturas o trabajos. [§11.E](gap-analysis-contendo.md).

### Workflow trabajos

- ⬜ **Partes de día con validación admin** — empleado crea, admin valida, entonces facturable. [§11.C](gap-analysis-contendo.md).
- ⬜ Decidir si **work logs con billing embebido** (modelo CONTENDO) o separados (modelo BENJAGEST actual). [§11.B](gap-analysis-contendo.md).

### Portal empleado

- ⬜ Decisión arquitectura UI: ¿misma JavaFX en modo empleado o app aparte?
- ⬜ Vista calendario empleado.
- ⬜ Nóminas descargables.
- ⬜ Notificaciones empleado.
- ⬜ Lista trabajos asignados.

### Fichajes (extensión más allá del legal mínimo)

- ⬜ Plantillas de jornada complejas (días tipo, bloques, excepciones).
- ⬜ Asignación plantillas a empleados.
- ⬜ Turnos (`turnos_180`, `turno_bloques_180`).
- ⬜ Plannings (ruta `admin/planings`).
- ⬜ Fichajes sospechosos (detección de patrones).

### Calendario

- ⬜ Festivos nacionales/CCAA seed (`festivos_es_180`, 58 filas).
- ⬜ Calendario laboral por empresa (mezcla festivos + cierres propios).
- ⬜ Integración Google Calendar bidireccional (webhooks + mapeo + log sync).
- ⬜ Importación masiva de calendarios.

---

## 🟢 BAJA — para más adelante

- ⬜ Alertas de seguridad (`security_alerts_180`) — intentos login, accesos sospechosos.
- ⬜ Sugerencias (`sugerencias_180`).
- ⬜ Análisis BOE (`boe_analysis_180`).
- ⬜ **Acceso PWA / móvil** — el cliente JavaFX deja fuera el caso móvil. ¿Cómo accederán los clientes desde el móvil? [§2.4 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **Email personal via Google OAuth2** — a nivel de usuario, distinto del SMTP empresa. [§1 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
- ⬜ **AI Copilot flotante** — si IA entra en scope, componente global accesible desde cualquier pantalla. [§2.5 `gap-analysis-config-ui`](gap-analysis-config-ui.md).

---

## ❓ Decisión de alcance — Benjamin decide al llegar el momento

> No hay nadie a quien consultar. Cuando un slice toque uno de estos puntos, Claude propone 2-3 opciones con pros/contras y Benjamin elige. Sólo se le manda un WhatsApp a Pablo si la decisión es irreversible y muy estructural (ej. modelo SaaS vs on-premise).

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
