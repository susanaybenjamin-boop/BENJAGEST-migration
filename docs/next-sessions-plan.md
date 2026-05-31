# Plan de las próximas sesiones — Slices C1, C2, C3

> Documento compartido entre Benjamin y Claude.
> Plan acordado al final de la sesión del **2026-05-30**.
> Decisiones cerradas — no se re-debaten en cada sesión.
>
> **Versión gemela en memoria de Claude** (auto-recall):
> `memory/project_benjagest_next_session.md` — se borra cuando los 3 slices estén cerrados.

---

## Estado actual

Sesión 2026-05-30 cerró en `59411a4`. Sesión 2026-05-31 cierra el **Slice C1 entero** en `cff800f` (develop y feat/Benjamin alineadas).

- Backend Slice C1: `bf511d7` — V8 + Spring Security + JWT + auth/AuthController + PinAuthController renombrado.
- UI Slice C1: `02f75e5` — login email/password + AuthSession con Bearer automático + selector de empresa + modo derivado de company_type + título dice "Asesoría"/"Empresario", toggle eliminado.

**Demos validados manualmente**: login `admin@benjagest.local` y `empresario@benjagest.local`, contraseña `Benjamin123456$`. Sidebar, dashboard y emisores funcionan con Bearer.

## Pendiente al volver

- **Slice C3** — Configuración (MVP). Recomendado primero, no depende de nada externo.
- **Slice C2** — Google Sign-In. Requiere que Benjamin cree credenciales en Google Cloud Console primero.

---

## Slice C1 — Login real email/password + JWT + V8 seed  ✅ HECHO 2026-05-31

### Backend

- Dependencias: `spring-boot-starter-security` + `jjwt-api/impl/jackson` (0.12.x).
- `POST /api/auth/login` (email + password) → JWT con claims: user_id, email, display_name, active_company_id, role_in_active_company, exp.
- `POST /api/auth/refresh` (refresh token → nuevo access token).
- `GET /api/auth/me` (usuario logueado + lista de memberships con company_type).
- `POST /api/auth/switch-company/{id}` (cambia active_company_id, devuelve nuevo JWT).
- BCrypt para passwords (Spring Security lo trae por defecto).
- TenantInterceptor: lee company_id del claim del JWT. Mantiene `X-Company-Id` como override para testing.

Pendiente de añadir en slices posteriores:
- `@RequiresRole("OWNER", "ADMIN")` aplicado a Settings/Módulos.
- Audit log empezando a escribir en `audit_events`: LOGIN_OK, LOGIN_FAIL, COMPANY_SWITCHED.

### V8 (Flyway migration)

- UPDATE admin demo: password BCrypt("Benjamin123456$"), display_name "Benjamin Asesor".
- INSERT user_account empresario: email `empresario@benjagest.local`, display_name "Marcos Lopez", password BCrypt("Benjamin123456$").
- INSERT company: "Marcos Construcciones SL", NIF B09990500, company_type CLIENT.
- INSERT company_membership: Marcos OWNER en Marcos Construcciones SL.
- Activar para Marcos Construcciones SL los módulos mínimos (core, billing, purchases, accounting, tax, calendar, notifications) — excluir advisory, kiosk, labor, time-clock, self-employed, documents.

### UI

- Pantalla de login pasa a `email + password`.
- "Iniciar sesión con Google" sale pero con tooltip "Pendiente de configurar" (Slice C2).
- PIN se mantiene como "desbloqueo de pantalla tras inactividad" (timeout configurable; default 5 min, futuro). Decisión 6 de architecture.
- ApiClients (Customer / Issuer / Workspace): añadir `Authorization: Bearer <jwt>` automático desde `AuthSession.authorize(builder)`.
- Tras login: si 1 membership, entrar directo. Si varias, selector de empresa con tarjetas (nombre + role + companyType).
- Header del shell: título dice **"Asesoría"** o **"Empresario"** según `company_type` derivado.

### Decisiones tomadas (NO re-debatir)

- Passwords demo: `Benjamin123456$` para ambos.
- JWT: 8h access token, 30 días refresh token.
- Modo asesoría/empresario: SE DERIVA del `company_type` de la empresa activa, NO hay toggle.
- Email/password coexiste con PIN. Decisión 6 architecture.

---

## Slice C2 — Google Sign-In

### Backend

- Dependencia: `spring-boot-starter-oauth2-client`.
- `application.yml` lee `GOOGLE_CLIENT_ID` y `GOOGLE_CLIENT_SECRET` de env vars.
- `.env.example` con placeholders, `.env` ignorado.
- Endpoint `/oauth2/authorization/google` iniciado por la UI; callback nuestro consume el id_token y emite JWT propio.
- Si email del Google coincide con un `user_account` existente → login OK, set `google_id` si era NULL. Si no coincide → error "Cuenta no registrada".

### UI (JavaFX desktop OAuth)

- Botón "Iniciar sesión con Google" abre navegador del sistema con URL del backend.
- Backend tras el callback de Google sirve un HTML pequeño que postea el JWT a `localhost:<puerto-libre>` donde la UI tiene un mini-servidor escuchando.
- UI recoge el JWT, lo guarda en `AuthSession`, cierra el servidor, sigue al dashboard.

### Bloqueo: necesita acción manual de Benjamin

Crear credenciales en Google Cloud Console **antes** de iniciar este slice:

1. https://console.cloud.google.com → nuevo proyecto "BENJAGEST".
2. APIs & Services → OAuth consent screen → External → rellenar nombre, email.
3. Credentials → Create credentials → OAuth 2.0 Client ID → **Desktop application** (o Web application si Desktop da problemas).
4. Pasar a Claude los valores `client_id` y `client_secret`. Irán en `.env`, no en el repo.

---

## Slice C3 — Módulo Configuración (MVP)

### V9 (Flyway)

- `CREATE TABLE company_email_config` (company_id, smtp_host, smtp_port, smtp_user, smtp_password_encrypted, from_address, reply_to, tls_enabled).
  - `password_encrypted` con Jasypt (Decisión 7 architecture: cifrado en aplicación).

### Backend

- Paquete `settings/` con:
  - `CompanyDataController` (datos de empresa, lee/escribe `companies`).
  - `CompanyEmailConfigController` (CRUD email config + endpoint de test "Enviar email de prueba").
  - `CompanyModulesController` (activar/desactivar módulos, llama a `ModuleAccessService` extendido).
- Todos con `@RequiresRole("OWNER", "ADMIN")`.
- `@RequiresModule("settings")`.

### UI

- Pantalla "Configuración" con `TabPane`:
  - **Pestaña 1 — Empresa**: formulario con datos de `companies`.
  - **Pestaña 2 — Email**: SMTP form + botón "Enviar email de prueba".
  - **Pestaña 3 — Módulos**: lista en árbol del catálogo con switches on/off por categoría y sub-módulo, respetando dependencias.
- Accesible desde el sidebar (módulo `settings` en `core`).

Otros módulos de configuración de CONTENDO (calendario, sistema, partes, fabricante, construcción) se añaden cuando lleguen sus fases. No en este slice.

---

## Orden recomendado

1. **C1** ~~login real~~ ✅ hecho 2026-05-31.
2. **C3** después (configuración MVP, no depende de Google).
3. **C2** al final (Google, depende de credenciales de Benjamin).

---

## Documentos hermanos

- [`migration-roadmap.md`](migration-roadmap.md) — visión por fases funcionales del proyecto entero.
- [`gap-analysis-contendo.md`](gap-analysis-contendo.md) — comparativa CONTENDO vs BENJAGEST (fuente del scope).
- [`git-chuleta-personal.md`](git-chuleta-personal.md) — comandos de Git y de arranque local.
