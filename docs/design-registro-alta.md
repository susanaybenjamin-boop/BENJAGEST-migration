# Diseño — Registro / Alta de cuenta (REG)

> Diseño escrito a petición de Benjamin (2026-06-25). **Sin código**: el registro
> toca auth (zona sensible, CLAUDE.md §11.2) y se construirá con Benjamin delante.
> Estado: propuesta para revisar.

## 1. Estado actual (verificado)

- **NO existe auto-registro.** `AuthController` solo tiene `/login`, `/refresh`,
  `/me`, `/switch-company`, `/logout`. La UI tiene `showLogin()` pero no pantalla
  de alta.
- Las cuentas se crean hoy por **seed** (V1-V8) o por **invitación de asesoría**
  (`AdvisoryInvitationService` — un asesor invita a un cliente).
- **Login** = email + password (BCrypt, `PasswordEncoder`).

### Modelo de datos (ya existe, no hay que migrar para el caso base)
- `user_accounts`: `id, email (UNI), password_hash, display_name, global_role
  (default USER), google_id, avatar_url, forced_password_change, active,
  session_pin_hash`.
- `company_memberships`: `id, company_id, user_id, role_name, active`. Un usuario
  puede pertenecer a varias empresas con un rol en cada una.
- `companies`: `legal_name, tax_identifier, company_type (INTERNAL | CLIENT |
  MANAGED_CLIENT), parent_company_id, …`.

> El esquema **ya soporta** el registro sin migración: alta = crear `user_account`
> + `company` + `company_membership` (rol OWNER) en una transacción.

## 2. ¿Quién se registra? (decisión clave)

El caso natural de auto-registro en BENJAGEST es **una ASESORÍA nueva** que se da
de alta (su empresa = `company_type = INTERNAL`, el usuario = OWNER). Los
**empresarios/clientes** NO se auto-registran: entran por **invitación** de su
asesoría (flujo TPB/advisory ya existente) — así la cartera queda vinculada y no
aparecen tenants huérfanos.

- **Propuesta:** auto-registro **solo para asesorías** (alta de la firma + su
  OWNER). Empresarios siguen por invitación. (Decisión 1 abajo.)

## 3. Flujo propuesto (REG-1: alta de asesoría)

Pantalla "Crear cuenta" enlazada desde el login:

1. **Formulario**: nombre de la asesoría (`legal_name`), NIF (`tax_identifier`),
   email del titular, nombre del titular (`display_name`), contraseña + repetir.
2. **POST `/api/auth/register`** (público, sin token). Validaciones:
   - email único (409 si existe), formato email.
   - password: mínimo 8, fuerza razonable (no la del PIN de sesión).
   - NIF: formato válido (reusar el validador de NIF que ya exista en backend).
   - legal_name no vacío.
3. **Transacción** (todo o nada):
   - `INSERT companies` (company_type=INTERNAL, legal_name, tax_identifier,
     parent_company_id=NULL, verifactu_modality=NO_VERIFACTU por defecto).
   - `INSERT user_accounts` (email, password_hash=BCrypt, display_name,
     global_role=USER, active=1).
   - `INSERT company_memberships` (company_id, user_id, role_name=OWNER).
   - Sembrar lo mínimo de la empresa: **plan contable PGC** + **ejercicio fiscal
     OPEN** del año en curso + **módulos por defecto** activos (billing,
     purchases, accounting, labor…). Reusar el mismo seeding que usan las
     empresas existentes (buscar dónde se siembra el PGC/fiscal_year al crear una
     company hoy — p.ej. al aceptar una invitación).
4. **Respuesta**: igual que `/login` (access+refresh token + memberships) → el
   usuario entra directo, sin segundo paso. (O exigir verificación de email
   antes — Decisión 3.)

## 4. Seguridad (obligatorio)

- **Rate limiting** del endpoint público `/register` (anti-abuso). Si no hay
  infra de rate-limit, al menos un límite por IP/email simple.
- **NO** permitir elegir `global_role` ni `company_type` desde el cliente: el
  server fija USER + INTERNAL + OWNER. Nada de escalada.
- `forced_password_change = 0` (la eligió el usuario).
- Auditoría: registrar el alta (`audit_events`) como hoy se audita el login.
- **Google OAuth** (la columna `google_id` ya existe): alta vía Google = crear
  user_account con `google_id`, `password_hash` NULL. Encaja, pero es un slice
  aparte (REG-2). Va en el plan de "login real + Google OAuth" de la memoria.

## 5. Slices propuestos

- **REG-1** — Backend `/api/auth/register` (alta asesoría) + seeding mínimo +
  auditoría + tests. **Con Benjamin** (toca auth).
- **REG-2** — UI: pantalla "Crear cuenta" enlazada desde login (i18n ES+EN).
- **REG-3** — (opcional) verificación de email antes de activar.
- **REG-4** — (opcional) Google OAuth (REG-2 de la memoria "login real + OAuth").

## 6. Decisiones abiertas (Benjamin)

1. **¿Auto-registro solo para asesorías** (recomendado) o también empresarios
   sueltos sin asesoría?
2. **¿Una asesoría = una empresa INTERNAL + OWNER** al registrarse (recomendado)?
   ¿O permitir registrar empresa sin ser asesoría?
3. **¿Verificación de email** obligatoria antes de poder operar, o entrada
   directa tras el alta? (Recomendado: directa al principio; verificación como
   REG-3 cuando haya envío de correo fiable.)
4. **Google OAuth**: ¿en el primer corte o lo dejamos para REG-4?
5. **NIF**: ¿obligatorio en el alta, o se puede completar luego en Configuración?
   (Recomendado: obligatorio — hace falta para facturar.)
