# Plan de ataque — Módulo MÓVIL / PDA / KIOSCO (fichaje)

> Preparado en sesión autónoma 2026-06-17 tras leer `docs/` + el código de CONTENDO
> (regla de oro). Objetivo de Benjamin: **fichaje móvil y kiosco (tablet/PDA) con
> invitación**, igual que CONTENDO, por ley (RD-Ley 8/2019). Este documento deja el
> bloque listo para ejecutar slice a slice cuando Benjamin confirme las 3 decisiones
> abiertas (sección final).

## 1. Enfoque (decidido en FM-0, coherente con despliegue local)

**NO es una app nativa.** Es **web servida por el backend Spring** (páginas HTML
responsive), accesible desde el móvil/tablet en la **LAN** — el mismo patrón que ya
funciona en las páginas públicas del **Magic Link de TPB** (`PublicTpbController` +
HTML servido por Spring). Así:
- El móvil del empleado abre `http://<ip-servidor>:8080/fichar` (responsive).
- El kiosco (tablet) abre la misma web en modo pantalla completa, emparejada con QR.
- Evita por completo la decisión pendiente de "stack nativo" (MOBILE-EMPLEADO):
  esa decisión solo aplica al *portal del empleado completo* futuro, NO al fichaje.

## 2. Qué se REUTILIZA de BENJAGEST (ya existe — NO reinventar)

- `time_clock_events` (V2, = `fichajes_180`): fichajes + geo (GEO-FICHAR) + cadena
  hash + correcciones/verificaciones (V21, RD 8/2019). **El fichaje se crea aquí.**
- `TimeClockService.punch(...)`: ya valida geo contra `work_centers.geo_policy`
  (none/info/soft/strict) y escribe el evento con su hash. **El kiosco/móvil llama
  a este servicio**, no duplica lógica.
- `work_centers` (V89, lat/lng/radio_m + geo_policy): centros de trabajo.
- `employees.pin_hash` (V70/V3): PIN del empleado (identificación en kiosco).
- `device_tokens` (V70): tokens de dispositivo (referencia para el patrón de token).
- `EmailSenderService` (SES/SMTP): para OTP por email.
- Patrón **página pública servida por Spring** (Magic Link TPB): HTML + endpoints
  públicos con su propio interceptor/token. **Copiar este patrón para el kiosco.**
- `daily_work_reports` (V2, = `jornadas_180`): partes diarios (destino de FM-5).

## 3. Superficie API a PORTAR (fiel a CONTENDO)

Fuente de verdad: `C:\Proyectos\CONTENDO GESTIONES\backend\src\controllers\kioskController.js`,
`kioskEmployeeController.js`, `otpService.js`, `fichajeEngine.js`, `routes/kioskRoutes.js`.

**Admin (auth OWNER/ADMIN):**
- `POST /api/kiosk/register` — alta de dispositivo kiosco.
- `GET /api/kiosk/devices` — listar.
- `PATCH/PUT /api/kiosk/devices/{id}` — editar.
- `DELETE /api/kiosk/devices/{id}` — borrar/desactivar.
- `POST /api/kiosk/devices/{id}/activation-token` — generar **token QR** (30 min).
- `GET /api/kiosk/devices/{id}/employees` · `POST` (asignar) · `DELETE` (quitar).

**Público:**
- `POST /api/kiosk/activate` — el dispositivo canjea el token QR → recibe su
  `KioskToken` secreto persistente.

**Kiosco (header `KioskToken`, vía `KioskTokenInterceptor`):**
- `GET /api/kiosk/config` — config del kiosco (centro, empleados asignados, logo…).
- `POST /api/kiosk/identify` — identificar empleado (PIN).
- `POST /api/kiosk/estado` — estado actual del empleado (dentro/fuera, última acción).
- `POST /api/kiosk/fichaje` — crear fichaje (delega en `TimeClockService.punch`).
  Tipo: entrada|salida|descanso_inicio|descanso_fin; subtipo: pausa_corta|comida|trayecto.
- `POST /api/kiosk/otp/request` — pedir OTP (email).
- `POST /api/kiosk/verify-offline-pin` — verificar PIN offline.
- `POST /api/kiosk/void` — deshacer último fichaje (ventana 60 s).
- `POST /api/kiosk/sync-offline` — sincronizar cola offline.

## 4. Tablas nuevas (FM-1) — additive, NO tocar seeds ni AuthService

Diseño según CONTENDO (`kiosk_devices_180`, `kiosk_activation_tokens_180`, OTP).
**Confirmar columnas exactas leyendo el schema de CONTENDO al implementar.**
- `kiosk_devices`: id, company_id, name, work_center_id, device_token_hash (secreto),
  offline_pin_hash, active, last_seen_at, created_at.
- `kiosk_activation_tokens`: id, company_id, kiosk_device_id, token_hash, expires_at
  (30 min), used_at.
- `kiosk_employee_assignments`: id, company_id, kiosk_device_id, employee_id.
- `otp_codes`: id, company_id, employee_id, code_hash, channel (EMAIL), purpose,
  expires_at, consumed_at, attempts.
Todas con `company_id` (multi-tenant) e índices por (company_id, …).

## 5. Plan slice a slice

- **FM-1** — Migración additive (las 4 tablas) + entidades/repos. Sin tocar auth core.
- ✅ **FM-2 HECHO** (2026-06-17) — `KioskService` + `KioskTokenInterceptor` +
  controllers admin (`/api/kiosk/devices...`: alta/listar/editar/borrar + QR de
  activación + asignar empleados) y público (`/api/public/kiosk/activate` +
  sesión config/identify/estado/fichaje). `punch(...,actorUserId)` añadido para
  fichar sin JWT; reutiliza geo + cadena hash. SecurityConfig (ruta pública) +
  WebMvcConfig (interceptor). Sin OTP (decisión Benjamin). Verificado: arranca +
  smoke (activate→410 token malo, config sin token→401). **Foto = en FM-4 (captura
  en la UI web).**
- ✅ **FM-3/FM-4 HECHO** (2026-06-17) — página web responsive servida por Spring
  (`GET /api/public/kiosk/app`): activar dispositivo (canjea QR→KioskToken en
  localStorage) → elegir empleado → PIN → fichar con los tipos de evento
  configurados → estado del día → **foto opcional** (cámara) + **geo** → éxito.
  Backend de foto: V130 `kiosk_punch_photos` + `KioskPhotoStorageService` + fichaje
  acepta `photoBase64`. Verificado: V130 aplica, `/app` sirve la página (200).
- ✅ **FM-admin HECHO** (2026-06-17) — pestaña "Kioscos / PDA" en Laboral (Tiempo y
  jornada): lista, alta (foto opcional con aviso legal), borrar, **código de
  activación** (con la URL del dispositivo) y asignar empleados. JavaFX +
  LaborApiClient.
- ⬜ **FM-5 / offline / deshacer-60s — PENDIENTE, fuera del módulo de fichaje:**
  - **FM-5 (fichaje → jornadas/calendario)** pertenece a **PORT-2 (jornadas)**, que
    es un módulo aparte con decisión de diseño pendiente de Benjamin ("1 plantilla =
    N bloques"). Hoy `worklogs`/`daily_work_reports` NO leen `time_clock_events`.
    Se aborda al retomar PORT-2.
  - **Cola offline** (kiosco sin red): fase 2 (decidido).
  - **Deshacer 60 s**: vía `requestCorrection` (mecanismo RD 8/2019 ya existe);
    su UI/endpoint de kiosco queda como mejora (no se anula el original — corrección).

## 6. DECISIONES CERRADAS (Benjamin 2026-06-17)

1. **Invitación = (A)**: OWNER habilita al empleado por **PIN** (ya existe) + empareja
   la tablet con **QR** (token 30 min). Sin enlace al móvil personal, sin email de invitación.
2. **Seguridad / OTP**: **NO OTP por email** (el PIN se reenvía y no prueba presencia).
   En su lugar, **foto OPCIONAL al fichar** (configurable por dispositivo `require_photo`,
   apagada por defecto) — **foto-evidencia, NUNCA reconocimiento facial** (AEPD nov-2023:
   el facial es categoría especial art. 9 y casi nunca pasa el test de proporcionalidad;
   sanciones hasta 200.000 €). La foto requiere aviso al empleado + retención limitada
   (`photo_retention_days`). Presencia: **geo** (ya existe) en el móvil; en el kiosco la
   tablet ya está físicamente en el centro.
3. **Offline = fase 2.** Online primero (FM-1..FM-3); cola offline después.

✅ **FM-1 HECHO** (2026-06-17): V129 `kiosk_devices` + `kiosk_activation_tokens` +
`kiosk_employee_assignments` (con `require_photo`/`photo_retention_days`, secretos
hasheados). Aplicada y backend arranca OK.

## 7. Notas / riesgos

- Reutilizar `TimeClockService.punch` es clave: NO duplicar la cadena hash ni la
  validación geo (ya cumplen RD 8/2019).
- El `KioskToken` es un secreto de dispositivo: guardar solo el **hash** en BD
  (como los PIN). El interceptor resuelve company sin pasar por el JWT de usuario.
- Multi-tenant: cada kiosco pertenece a una `company_id`; el interceptor debe fijar
  el `TenantContext` desde el device, no desde un JWT.
- Empezar por FM-1 (migración) solo tras confirmar la decisión 1 (afecta a
  `kiosk_employee_assignments` y al flujo de identificación).

> Cuando Benjamin confirme las 3 decisiones, ejecutar FM-1 → FM-5 con commit por
> slice + merge a develop, compilando y (para FM-1/FM-2) arrancando el backend una vez.
