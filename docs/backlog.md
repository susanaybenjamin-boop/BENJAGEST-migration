# Backlog operativo BENJAGEST

> Lista única ordenada de mayor a menor importancia con TODO lo que hay que hacer en BENJAGEST.
> Se va tachando conforme cada item se crea, prueba, commitea y mergea a `develop`.
>
> **Última revisión:** 2026-06-03 (sesión maratón — bloque VeriFactu cerrado funcionalmente en local: VF2 + VF-OFF-DEPRECATE + VF-EVENTS + F-STORAGE + bugfix núcleo + SUMMARY_6H + VF3-QR + F-EMAIL + VF-SIGN MVP + VF-ANOMALY + VF4 reintento + VF3-SOAP listo sin probar + selector de carpeta para almacenamiento + PDF mutante "Abrir/Guardar" + ComboBox tipo en editor + **proformas full flow** (PDF sin QR, conversión a normal con/sin validar, filtro de tipo en listado). Pendiente: ajuste XAdES-EPES estricto + parseo respuesta AEAT real cuando haya FNMT + alta SIF).
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
  - ⬜ **VF-EVENTS export real** — exportación de los eventos del SIF a CSV/JSON por período (hoy el endpoint solo emite el evento EXPORT_EVENTS sin entregar el fichero).
  - ⬜ \*\*VG Hay que hacer que las columnas del listado de facturas sean ordenadas al cliquear sobre el nombre de esta, y hacer esto en todos los listados que aparezcan en el proyecto. Comentario de Benjamin.

- ✅ **Anulación con vínculo** (2026-06-02, reforzado 2026-06-03) — `SalesInvoiceService.voidValidated()` emite **en una sola transacción** una factura RECTIFYING ya VALIDATED enlazada a la original mediante `original_invoice_id` + líneas con cantidad negativa, y la original queda VOIDED con `rectifying_invoice_id` apuntando a la nueva. Endpoint `POST /api/billing/invoices/{id}/void`. **Decisión 2026-06-03**: la rectificativa por anulación NO pasa por borrador editable — emitirla como DRAFT abriría una ventana para manipular cifras antes del acto legal. Refactor: `validate()` ahora delega en `validateInternal()` para reutilizarse desde `voidValidated()` sin romper proxy AOP (`@Transactional`). Cascada VOIDED + hash VeriFactu en la misma tx. `updateDraft` preserva `invoice_type` y `original_invoice_id` para defensa (aunque ya no hay borrador rect que editar). UI: botón "Anular" en el listado activo solo con VALIDATED, alerta con mensaje "acto legal — no se puede deshacer", al confirmar muestra el nº emitido (RECT-2026-0001) y refresca. Pendiente futuro: rectificativa parcial R1-R5 (flujo aparte que sí pasaría por borrador editable porque ahí el usuario debe revisar las líneas).
- ✅ Almacenamiento documental de facturas (cerrado por F-STORAGE, ver bloque VeriFactu arriba).
- ✅ Envío facturas por email (cerrado por F-EMAIL, ver bloque VeriFactu arriba).
- ⬜ Obligaciones de fabricante VeriFactu (auditoría propia del software, ver `VERIFACTU_OBLIGACIONES_FABRICANTE.md` de CONTENDO).
- ⬜ Todavia no tenemos la importacion del pdf en los modulos donde se van a usar esa funcion, ya que CONTENDO usa IA, nosotros lo vamos a hacer con dependencias OCR y Regex, sin usar IA, o como Claude me sugiera honestamente.

### RD 8/2019 (fichajes — obligación legal) comentario de benjamin(vamos a ver como Sesame lo hace para incorporar nuevas ideas, o cualquier otra app de fichaje)

- ⬜ Tabla `fichaje_correcciones` — corrección **solo por apunte vinculado**, no modificación de fichaje original.
- ⬜ Tabla `fichaje_verificaciones` — código CSV para verificación pública (art. 35.8 RD 8/2019).
- ⬜ **Geolocalización en clients** — clients con `lat`/`lng`/`radio_m`/`geo_policy` + verificación al fichar. [§11.A](gap-analysis-contendo.md).
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

### Asesoría — decisión tomada 2026-06-03

> **(A) confirmado**: BENJAGEST puede correr como asesoría (`company_type=INTERNAL`) o como cliente (`CLIENT`). El módulo `advisory` (categoría + sub-módulos) **solo** debe aparecer y activarse en empresas INTERNAL — equivalente funcional a CONTENDO. Cliente NO ve gestión de cartera de clientes.
>
> **Evolución abierta (C)**: cuando se ataque el slice de comunicación con la asesoría externa, el módulo `advisory` en una empresa CLIENT podría reusarse como "Mi asesoría" (compartir docs, recibir requerimientos, ver el calendario fiscal que mi gestoría me prepara). Es un cambio de semántica del slug — no necesariamente código nuevo, depende de qué endpoints exponga "Mi asesoría". Lo decidiremos cuando llegue.
>
> **Deuda menor abierta** (no urgente — observada 2026-06-03):
>
> - El backend `CompanyModulesService.list()` filtra el subárbol `core` pero NO filtra `advisory_only=TRUE` por `company_type`. Resultado: en la pestaña "Módulos" de Configuración, una empresa CLIENT (Marcos) SÍ ve "Asesoría" como activable, aunque el sidebar luego no lo muestra (otro mecanismo lo filtra). La incoherencia es visible y confunde al usuario. Cuando se cierre la decisión (A vs C), arreglar en un mismo slice junto con V21 que limpie cualquier CLIENT con advisory activo.
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
