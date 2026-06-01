# Backlog operativo BENJAGEST

> Lista única ordenada de mayor a menor importancia con TODO lo que hay que hacer en BENJAGEST.
> Se va tachando conforme cada item se crea, prueba, commitea y mergea a `develop`.
>
> **Última revisión:** 2026-06-01 (Slice C3 cerrado + decisión arquitectónica: empresa = emisor).
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
- ✅ Slice C3: módulo Configuración MVP — V9 + Jasypt + 3 controllers `/api/settings/*` con `@RequiresRole`/`@RequiresModule` + UI con TabPane (Empresa/Email SMTP/Módulos) + sticky footer + batched save de módulos + A4 sidebar dinámico (`/api/modules-catalog/active`).
- ✅ Fix de seguridad: `CustomerRepository.findById/findAllActive` filtran por `company_id` (era fuga pre-existente).

---

## 🔴 CRÍTICA — siguiente bloque a atacar

Lo que toca **antes** de seguir con features funcionales. Cubre: legalidad, seguridad multi-tenant, y los dos slices que dejamos preparados.

### Inmediato (siguiente bloque a atacar)

- ✅ **D1** — Unificación de tablas fiscales (decisión 2026-06-01). V10 amplía `companies` con address/iban/registry/legal_terms/invoice_footer + UPDATE JOIN desde `issuers` por defecto + quita FK `sales_invoices.issuer_id` + DROP TABLE issuers + borra slug `issuers` del catálogo. V11 hace lo mismo con `customer_billing_profiles` → `customers`. Backend: paquete `issuer/` borrado, `CompanyDataController/Service/Repository` ampliados con los 9 campos nuevos. UI: módulo "Emisores" eliminado del sidebar + línea "Facturando como:" del header eliminada + pestaña Empresa ampliada (Datos generales / Dirección postal / Datos de facturación) + refresh silencioso de `AuthSession.activeCompanyLegalName` + `SessionInfo.withCompanyName` tras guardar.

### Seguridad y trazabilidad

- ⬜ **Refactor WorkspaceRepository** — 25 usos de `DemoCompany.ID` pendientes de migrar a `tenantContext.getCurrentCompanyId()`. Código heredado de Pablo: hacerlo con tests manuales antes/después y commit pequeño y aislado.
- ✅ **@RequiresRole + RoleInterceptor** — cerrado en C3 (`auth/RequiresRole.java` + `auth/RoleInterceptor.java`, registrado en `WebMvcConfig`). Aplicado a los 3 controllers de `settings/`.
- ✅ **Audit log activo** — paquete `audit/` (Event + Repository + Service + Controller) escribe en `audit_events` desde `AuthService` (LOGIN_OK / LOGIN_FAIL / COMPANY_SWITCHED), `CompanyModulesService` (MODULE_ENABLED / MODULE_DISABLED) y `CompanyDataService` (COMPANY_DATA_UPDATED). UI: 4ª pestaña "Auditoría" en Configuración con tabla filtrable por tipo. Pendiente futuro: vista global para LOGIN_FAIL pre-auth (companyId NULL).
- ⬜ **Cifrado columnas sensibles con Jasypt** — el bean `StringEncryptor` ya está (C3). Aplicar a `digital_certificates` (existe vacía, V2) y a futuras `credenciales_externas` (DEHú/SS/SILTRA). Decisión 7 architecture.
- ⬜ **Refresh token revocation** — denylist o rotación al logout (hoy el refresh sigue válido aunque el usuario cierre sesión).
- ⬜ **C2** — Google Sign-In con OAuth2 (aplazado conscientemente hasta que Benjamin genere credenciales en Google Cloud Console). No olvidar.

### VeriFactu / Facturación (legal obligatoria)

- ⬜ Series de numeración: proformas, rectificativas, anulaciones con vínculo (`factura_rectificada_link`).
- ⬜ Hash encadenado + firma XML + reintentos.
- ⬜ Anulación con vínculo (`verifactu_anulacion_columns`).
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

- ⬜ Decidir si `parent_company_id` + `MANAGED_CLIENT` es el link asesoría↔cliente, o si hace falta crear `advisory_client_links` (tabla N:M). Decisión de Benjamin al empezar el slice de asesoría.
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
- ⬜ **Command Palette `Ctrl+K`** — buscador global rápido. [§2.2 `gap-analysis-config-ui`](gap-analysis-config-ui.md).
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
