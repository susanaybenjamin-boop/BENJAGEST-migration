# MODE-UP — Subir una cuenta de EMPRESARIO a ASESORIA sin perder nada

Diseno corto (cambio pequeno, 1-2 slices). Acordado con Benjamin el 2026-11-23.

---

## 1. Intencion y por que

Benjamin se dio de alta como **empresario** (`companies.company_type = 'CLIENT'`)
y hoy, para ver el modo asesoria, **entra con una segunda cuenta falsa**. Quiere
las dos cosas en **su misma cuenta**: su negocio de siempre + la cartera.

Contexto del backlog (lineas 5-6): BENJAGEST **no** se va a terminar para
VeriFactu ni factura electronica; cuando sean obligatorias se contrata un
programa certificado. BENJAGEST se queda para **llevar las cuentas por encima y
saber por donde van**. Esta subida de modo encaja ahi: no toca facturacion
oficial, solo da acceso al cockpit de asesoria (Fiscal/Modelos, Agenda, Equipo,
cartera) sobre los MISMOS datos.

Excepcion consciente a la nota del backlog "solo arreglos, sin funciones
nuevas": Benjamin la pide expresamente porque sustituye un apano (la cuenta
falsa).

## 2. Objetivos

- Un OWNER de una empresa `CLIENT` puede convertirla en `ADVISORY` **desde
  dentro de la app**, sin tocar la BD a mano.
- **Cero movimiento de datos**: mismo `company_id`, mismo NIF, mismas facturas,
  diario, empleados, clientes y certificados.
- Tras la subida, su gestion de siempre se abre por **"Mi gestion"** (pantallas
  completas) y aparecen los modulos de asesoria.
- La cuenta falsa deja de hacer falta.

## 3. No objetivos

- La vuelta atras (asesoria -> empresario). Nota al backlog y se hace el dia que
  haga falta.
- Mover el negocio a una empresa distinta (ver enfoque B, descartado).
- Tocar login, seleccion de membership o claims del JWT (CLAUDE.md 11.2).
- Cambiar el reparto de pantallas entre los dos modos (lo decidido el 2026-06-15
  sigue: el sidebar de asesoria es administracion, la operativa propia entra por
  "Mi gestion").

## 4. Anti-objetivos (esto seria un fracaso aunque "funcione")

- Que despues de subir falte algo que antes se veia (una factura, un asiento, un
  empleado, el logo, el certificado).
- Que se desactive ningun modulo que el empresario tuviera encendido.
- Que quede una empresa a medias: tipo cambiado pero sin self-link, o con el
  sidebar roto, o con modulos de asesoria sin activar.

## 5. Restricciones (verificadas en codigo, no supuestas)

| Hecho | Donde |
|---|---|
| El modo es `companies.company_type`: `CLIENT` vs `ADVISORY`; se fija en el alta | `RegisterService.java:79` |
| El tipo **no** viaja en el JWT; viene en las memberships del login/refresh, leidas de BD | `JwtService` (claims), `AuthService.refresh():149` |
| La UI deriva el modo del `companyType` de la membership | `BenjagestUiApplication.java:961` |
| La asesoria se gestiona a si misma via self-link en `advisory_invitations` (fila SELF), y `ensureSelfLink()` la crea sola | `AdvisoryService.java:131`, `V64` |
| "Mi gestion" es un acceso virtual que la UI inyecta en modo ADVISORY; no es un modulo de BD | `BenjagestUiApplication.java:1939` |
| Los modulos `advisory_only` (p.ej. `team`) solo se ofrecen a INTERNAL/ADVISORY; `V43` apaga los que esten mal activados | `ModuleRepository.findCompanyType`, `V43`, `V69` |
| En modo ADVISORY el sidebar filtra la operativa propia (`tax, labor, billing, purchases, accounting, self-employed`) | `BenjagestUiApplication.java:1918` |
| `hasCommLink` para una ADVISORY cuenta hijos `CLIENT` con `parent_company_id = yo`; para un CLIENT mira su propio `parent_company_id` | `ModuleRepository.hasCommLink` |
| Crear clientes exige `company_type = 'ADVISORY'` | `AdvisoryService.java:136` |

Consecuencia de la penultima fila: una empresa **vinculada a otra asesoria**
(`parent_company_id != NULL`) que se convirtiera en ADVISORY perderia el modulo
Comunicacion con su gestor (pasaria a contar hijos, y no tiene). Ver 7.3.

## 6. Enfoques considerados

**A — Subir de tipo la misma empresa. ELEGIDO por Benjamin.**
Un endpoint cambia `company_type` y activa los modulos que faltan. Cero datos
movidos, mismo NIF, un slice. Coste: la operativa propia queda un clic mas
adentro ("Mi gestion").

**B — Asesoria nueva y el negocio como cliente vinculado.**
Segunda empresa `ADVISORY` + `parent_company_id`. Tendria sentido si la gestoria
fuese otra sociedad, pero exige NIF distinto (hay unique por NIF), duplica
configuracion (series, certificados, logo, correo) y su negocio dejaria de ser
"Mi gestion". Descartado: no es el caso.

**C — Flag "lleva cartera" sin cambiar el tipo.**
Descartado: mas de veinte sitios del backend preguntan por
`company_type IN ('INTERNAL','ADVISORY')` (scheduler, alertas BOE, modulos,
invitaciones, device tokens). Una segunda fuente de verdad = regresion segura.

## 7. El como decidido

### 7.1. Backend — `POST /api/advisory/upgrade` (MODE-UP-1)

- `@RequiresRole({"OWNER"})`. La empresa que se sube es
  `currentUser.activeCompanyId()`, **no** `tenantContext` (patron CLAUDE.md 5:
  el OWNER puede estar actuando como cliente).
- Todo en **una transaccion**:
  1. `UPDATE companies SET company_type='ADVISORY' WHERE id=? AND company_type='CLIENT'`.
     Si no actualiza fila -> 409 con motivo (ya es asesoria / tipo no elegible).
  2. Activar **de forma aditiva** (`INSERT IGNORE` / `ON DUPLICATE KEY UPDATE
     active=TRUE`) los modulos que lleva una asesoria de alta y le falten:
     `tax`, `notifications`, `team`. **No se desactiva nada** de lo que ya
     tuviera.
  3. Crear el self-link con la **misma SQL de `ensureSelfLink`** pero pasando el
     `companyId` **por parametro** (no depender de `TenantContext`), idempotente
     por el `COUNT` previo.
  4. Auditoria: evento propio (`COMPANY_UPGRADED_TO_ADVISORY`) con la empresa y
     el usuario.
- **Guardas** (todas devuelven 409 con mensaje claro, sin tocar nada):
  - No es OWNER -> lo corta `@RequiresRole`.
  - `company_type != 'CLIENT'` -> ya es asesoria o es un tipo que no se sube.
  - `parent_company_id IS NOT NULL` -> **bloqueado**: esta vinculada a otra
    asesoria y la conversion le romperia Comunicacion con su gestor (ver 5).
    Mensaje: desvincularse primero. (Decision mia; Benjamin no esta en ese caso.)
- Sin migracion Flyway: no hay columnas nuevas.

### 7.2. UI — boton en Configuracion -> Empresa (MODE-UP-2)

- Bloque al final de la pestana **Empresa** (`SettingsScreen`, `settings.tab.company`),
  clases existentes (`settings-section`, `settings-hint`, `primary-button`).
- Visible solo si `appMode == BUSINESS` y el usuario es OWNER.
- Boton -> **dialogo dimensionado** (`setPrefSize`) con **Cancelar**, que enumera
  en texto lo que va a cambiar: aparece "Mi gestion" y la cartera de clientes;
  Facturacion/Compras/Laboral/Fiscal pasan a abrirse dentro de "Mi gestion"; no
  se pierde ningun dato; hay que volver a entrar.
- Al confirmar y responder OK: aviso de "listo" y **cerrar la aplicacion** por el
  mismo camino ordenado que el boton "Salir" del header. Ojo, esto se comprobo en
  codigo: en esta app **"Salir" cierra el programa entero**, no vuelve al login
  (fix de Benjamin 2026-07-10: volver al login con la BD embebida ya apagada
  dejaba la app inutilizable). El modo se arma al arrancar el shell, asi que
  reabrir la app es lo unico fiable; recomponer la ventana a medias no.
- **i18n**: todas las claves nuevas con par **ES + EN** (boton, hint, titulo y
  cuerpo del dialogo, y los tres mensajes de error). Checklist CLAUDE.md 4.
- Auto-refresh: no aplica (se cierra sesion); no hace falta `RefreshBus`.

### 7.3. Lo que queda fuera, anotado en el backlog

- Vuelta atras asesoria -> empresario (y su guarda: solo sin clientes vinculados).
- Permitir el hibrido "soy cliente de otra asesoria y a la vez asesoria", que
  pide repensar `hasCommLink`.

## 8. Verificacion en ejecucion (CLAUDE.md 10.ter — sin esto no se cierra)

Smoke aislado (receta de memoria: MariaDB4j en 23307 + backend 18080):

1. Alta de una cuenta `CLIENT` por `POST /api/auth/register`; meterle algo
   reconocible (un cliente y una factura) para poder comprobar que sigue ahi.
2. `curl` del upgrade: happy path 200, y los tres rechazos (no OWNER, repetir la
   llamada, empresa con `parent_company_id`).
3. En BD: `company_type='ADVISORY'`, los modulos activados, la fila SELF en
   `advisory_invitations`, y el evento de auditoria.
4. `POST /api/auth/login` -> la membership viene con `companyType='ADVISORY'`.
5. **UI contra ese backend**: entrar, ver el sidebar de asesoria, abrir "Mi
   gestion" y **comprobar que el cliente y la factura del paso 1 siguen ahi**.
6. Tests: uno de servicio para el guard CLIENT->ADVISORY, la idempotencia y el
   bloqueo por `parent_company_id`.

**Antes de hacerlo en la cuenta de produccion de Benjamin**: copia de la BD
(la embebida del 13307), por si acaso. Es un UPDATE de una fila, pero es su
contabilidad.
