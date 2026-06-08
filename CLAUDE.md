# Instrucciones para Claude trabajando en BENJAGEST

Este archivo se lee automáticamente al iniciar cualquier sesión de
Claude en este repositorio. Contiene las convenciones del proyecto
y los patterns de trabajo que Benjamin ha establecido. Síguelas
salvo que él pida lo contrario.

---

## 1. Contexto del proyecto

- **BENJAGEST**: migración de CONTENDO (legacy) a stack moderno.
- **Stack**: Java 21 + Maven multi-módulo (`backend-java`, `ui`) +
  Spring Boot 3.3.5 + JdbcTemplate + Flyway 10.10.0 + MariaDB 11.4
  (port 3307) + JavaFX 21 + OpenPDF + PDFBox 3.0.3.
- **Dominio**: asesoría fiscal/laboral española multi-tenant.
- Repo en GitHub de Benjamin (susanaybenjamin-boop/BENJAGEST-migration).

Para más contexto del dominio y arquitectura: `docs/architecture.md`,
`docs/domain-model.md`, `docs/database-model.md`.

---

## 2. Pattern obligatorio: diagnóstico con agentes paralelos

Para **bugs no triviales** (toca >3 archivos, ya hiciste fixes que no
funcionaron, los síntomas no apuntan a la causa) **lanza dos agentes
Explore en paralelo** antes de aplicar fix.

Receta completa en **`docs/agents-debug-pattern.md`** — léelo antes de
usarla por primera vez.

Resumen:
- Dos prompts **independientes**, no complementarios.
- Cada uno autosuficiente (contexto, stack, archivos a mirar, formato
  de salida).
- "NO escribas código. Solo lee y reporta."
- Busca **convergencia**: si ambos llegan al mismo `archivo:línea` por
  caminos distintos, el fix es sólido.

Cuando uses esta receta exitosamente, **menciónalo en el commit**:
> "Diagnóstico hecho con agentes Explore en paralelo. Los dos
> coincidieron en [archivo:línea]."

---

## 3. Workflow de Git (no negociable)

Benjamin trabaja en branch `feat/Benjamin`. Tras cada cambio:

```bash
git add -A
git commit -m "...mensaje descriptivo..."
git push origin feat/Benjamin
git checkout develop
git pull --ff-only
git merge --no-ff feat/Benjamin -m "merge: ..."
git push origin develop
git checkout feat/Benjamin
```

- **Commits pequeños** "por si hay que revertir" (sus palabras).
- **NUNCA** uses `--no-verify`, `--no-gpg-sign`, ni `--amend` en
  commits ya pusheados.
- **NUNCA** `git push --force` salvo que Benjamin lo pida explícito.
- Mensaje de commit en español + co-author Claude.

---

## 4. UI: convenciones críticas

- **No tocar estilos**: reusar clases CSS existentes (`module-detail-title`,
  `settings-section`, `data-table`, `primary-button`, `settings-hint`,
  etc.). CSS nuevo solo si imitando paleta y patrones de `app.css`.
- **i18n obligatorio**: toda string visible va por `t(key)` con par
  ES + EN. Nunca hardcodear español.
- **Botón Cancelar/Cerrar** en todos los wizards y diálogos largos.
- **Visor PDF**: usar `PdfViewer` interno (PDFBox), no `Desktop.open()`
  (no requiere visor del SO).
- **Sin emojis** en código salvo que el usuario los pida.

---

## 5. Backend: convenciones críticas

- **`@RequiresRole`**: para endpoints operacionales incluir
  `{"OWNER","ADMIN","ACCOUNTANT","EMPLOYEE"}`. Solo cerrar más para
  administración interna de la asesoría (team, settings, certificates,
  invitations).
- **`@RequiresModule`**: si la pantalla del UI requiere un módulo
  activo, marca el controller con el slug correspondiente.
- **TenantContext vs activeCompanyId**: cuando el frontend está
  "actuando como cliente" (`X-Company-Id` = cliente), `tenantContext`
  apunta al cliente. Para checks de "soy OWNER de mi asesoría" usa
  `currentUser.activeCompanyId()` (estable, del JWT). Patrón ya
  aplicado en `ClientAssignmentService`.
- **Flyway**: nuevas migraciones van como `V{N}__{descripción}.sql`.
  El siguiente número libre se obtiene listando `db/migration/`.
  outOfOrder=true está activo en `FlywayConfig`.
- **NUNCA renumerar `entry_number`** en BD (solo visual UI).

---

## 6. Parser JSON UI: nota de deuda técnica

`AltaApiClient.parseObjects(json, discriminator, mapper)` usa el
regex `\\{[^{}]*\\}` que **solo matchea objetos planos**. Falla
silenciosamente devolviendo `[]` cuando hay anidados.

Ya ha mordido 3 veces (convenios CTR-2, asignaciones EMP-SCOPE,
y antes). Cuando llames un endpoint que devuelva objetos con
sub-objetos, usa `splitTopLevelObjects()` que balancea llaves.

Pendiente: migrar todos los `parseObjects` a `splitTopLevelObjects` o
introducir Jackson en UI.

---

## 7. Cómo se nombran los slices

Benjamin usa prefijos cortos por bloque. Ejemplos vistos:
`CTR-3`, `EMP-SCOPED-UI`, `VF-CHAIN-FIX`, `L4-7`, `ALTA-5`.
Cuando abras una tarea grande, dale prefijo y enumera slices
(`CTR-1`, `CTR-2`, …). Al cerrar bloque, marca todos como
completados.

---

## 8. Rol y comunicación con Benjamin

- Benjamin es **principiante en Java** pero **muy claro con el dominio**.
  Asume que él decide el QUÉ, tú propones el CÓMO.
- Pablo (mentor) ya no está activo en estas sesiones desde 2026-05-30.
- Email del proyecto: `susanaybenjamin@gmail.com`.
- Email Benjamin en BD: `admin@benjagest.local`. PIN OWNER: `2406`.
- Cuando ofrezcas opciones, marca la recomendada con "(Recomendado)".
- Cuando un cambio sea grande o irreversible, **pregunta antes** con
  `AskUserQuestion`.

---

## 9. Para cierre de bloques grandes

Al cerrar un bloque (3+ slices completados):

1. Commit por slice (no commits gigantes).
2. Cada commit con co-author Claude.
3. Push a `feat/Benjamin`, luego merge `--no-ff` a `develop`.
4. Marca todas las tareas del bloque como `completed`.
5. Resume al usuario: qué hace cada slice, qué falta de la lista
   inicial, próximo bloque sugerido.

---

## 10. No olvides

- Antes de un fix complejo: **agentes paralelos** (sección 2).
- Antes de tocar UI: **CSS reusable + i18n + no emoji**.
- Antes de tocar endpoint: **`@RequiresRole` con EMPLOYEE si es operacional**.
- Antes de parsear JSON anidado: **`splitTopLevelObjects`**, no
  `parseObjects`.
- Antes de cerrar el día: **commit + push + merge develop**.

Si dudas en algo de esto, abre `docs/agents-debug-pattern.md` o
pregunta a Benjamin directamente.
