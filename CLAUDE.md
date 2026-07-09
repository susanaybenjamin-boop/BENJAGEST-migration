# Instrucciones para Claude trabajando en BENJAGEST

Este archivo se lee automáticamente al iniciar cualquier sesión de
Claude en este repositorio. Contiene las convenciones del proyecto
y los patterns de trabajo que Benjamin ha establecido. Síguelas
salvo que él pida lo contrario.

---

## ⛔ AVISO PREVIO — VERIFICAR EN EJECUCIÓN (leer ANTES de trabajar)

> **Regla de Benjamin (2026-07-09, tras las releases rotas 0.1.19 y el
> gestor-navegador):** *"antes de crear algo que has dado por hecho que
> está correcto, lo vuelves a revisar."*

**"Compila" y "los tests pasan" NO significa "funciona".** Las dos
averías de hoy pasaron ambas puertas y aun así rompieron la app en
producción (login imposible). La pregunta obligatoria antes de
commitear y SIEMPRE antes de publicar una release es:

> **¿He VISTO esto funcionar, o solo creo que funciona?**

Detalle completo y checklist por tipo de cambio: **sección 10.ter**.
Dos reglas que no se saltan NUNCA:

1. **Ningún `gh release create` sin smoke test del camino tocado.**
   Si algo no se pudo verificar en ejecución, se le dice a Benjamin
   ANTES de publicar y él decide.
2. **Toda suposición sobre un componente de terceros se comprueba**
   (leyendo su código/docs o ejecutándolo). La 0.1.19 murió por asumir
   que la MariaDB embebida exigía credenciales; arrancaba con
   `--skip-grant-tables` (seguridad apagada de fábrica).

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

### 1.1. CONTENDO como fuente de verdad

**CONTENDO (legacy) está en `C:\Proyectos\CONTENDO GESTIONES`** —
es el sistema que BENJAGEST está reemplazando.

> **REGLA DE ORO**: cuando Benjamin no esté disponible para responder
> una decisión de producto o comportamiento, la respuesta por defecto
> es **"igual que en CONTENDO"**. Buscar la implementación equivalente
> en la ruta de arriba y portarla a Java fielmente.

Carpetas útiles dentro de CONTENDO:
- `backend/src/services/` — lógica de negocio JS, incluye parsers
  (OCR/calendario), servicios fiscales, etc.
- `backend/src/services/ocr/calendarioParser.v3.js` — parser fiel del
  calendario laboral que ya porteé como `HolidayPdfExtractor` (sesión
  2026-06-09).
- `backend/migrations/` — migraciones SQL legacy que documentan el
  schema histórico (las tablas `_180` que ves en `gap-analysis-contendo.md`).
- `app180-frontend/` — Next.js. Útil para ver UI flows aunque
  BENJAGEST usa JavaFX.

Patrón típico de port:
1. Localizar el archivo equivalente en CONTENDO (grep por keyword).
2. Leer el algoritmo completo antes de portar.
3. Portar fielmente a Java, manteniendo nombres de constantes /
   keywords / heurísticas idénticas para que sean fáciles de
   comparar lado a lado.
4. En el commit message, citar el archivo CONTENDO de origen +
   commit BENJAGEST de port.

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
- **i18n obligatorio (REGLA DURA — verificar en CADA ejecución, antes de
  cerrar el slice).** Toda string visible va por `t(key)` con par ES + EN.
  Nunca hardcodear español. Esto **NO es solo para los textos de la UI** —
  el bug que más se repite es olvidar la clave de un **VALOR de enum / estado
  / código / `source_type`** que el backend produce y la UI pinta con
  `t("prefijo." + valor)`. Checklist obligatorio:
    1. ¿He creado un **valor nuevo** que llega a la UI? (un `source_type` de
       asiento como `DUE_DATE_PAYMENT`, un `status`, un código de
       enum como `DEBIT`/`FIXED`, una categoría, un método de pago…).
       → Añadir su clave `t(...)` en **AMBOS** bloques del switch (ES **y** EN)
       de `BenjagestUiApplication`. Buscar el grupo existente
       (`accounting.source_type.*`, `duedates.method.*`, etc.) y añadir ahí.
    2. ¿He puesto algún literal en español en código JavaFX, en un combo, en
       una columna de tabla, en un `Alert`, o en un text-block HTML de PWA?
       → Pasarlo por `t(key)` con par ES+EN. (La PWA del empleado es ES por
       diseño; ahí sí puede ir literal, pero anótalo.)
    3. Antes de commitear: si he añadido una clave EN, ¿existe su gemela ES
       (y viceversa)? Si una falta, la UI muestra la **clave cruda**
       (`accounting.source_type.DU…`) — exactamente el bug a evitar.
  No cerrar un slice sin haber repasado este checklist. Cuesta 30 s; volver a
  corregirlo después cuesta la confianza de Benjamin.
- **Diálogos dimensionados**: todo `Dialog`/`Stage` nuevo lleva
  `setPrefSize(...)` (+ `setResizable(true)` si tiene tabla) y las tablas
  anchas usan `CONSTRAINED_RESIZE_POLICY` para que las columnas no se corten
  fuera de la ventana. No crear ventanas “a ojo”.
- **Controles/datos ARRIBA del listado, nunca debajo (patrón "Slice 3V").**
  Botones de acción, filtros, totales y paneles secundarios van **encima** de
  la `TableView`/`ListView`, y la tabla **crece** con
  `VBox.setVgrow(tabla, Priority.ALWAYS)` para llenar el espacio. Así el usuario
  no tiene que hacer **scroll vertical** para llegar a controles/datos del
  fondo (perdiendo de vista la lista). Si una pantalla tiene mucho contenido +
  una lista dentro de un `ScrollPane`, pon lo principal (KPIs + la lista) arriba
  y lo derivado abajo. La mayoría de pantallas ya lo cumplen; al crear o tocar
  una, verifícalo.
- **Auto-refresh (REGLA DURA — verificar en CADA acción que muta datos).**
  El usuario **NO** debe pulsar "Refrescar" para ver el resultado de una acción.
  Toda acción que cree/edite/borre/valide/pague algo tiene que **refrescar
  automáticamente** las vistas afectadas. Patrón del proyecto = `RefreshBus`:
    1. La acción, al terminar OK, hace `RefreshBus.emit(TOPIC_X)` del/los topic(s)
       afectados (`TOPIC_JOURNAL`, `TOPIC_SALES`, `TOPIC_PURCHASES`, `TOPIC_EMPLOYEES`…).
    2. **Toda pantalla que muestre datos que pueden cambiar** se **suscribe** a su
       topic con `RefreshBus.subscribe(TOPIC_X, this::loadX, ownerNode)` (se auto-baja
       al desmontar). Si construyes una vista nueva, suscríbela.
    3. Caso típico que se olvida: un **aviso/badge/contador** (p.ej. "X por validar"
       del cuadro de mando) debe desaparecer solo al resolverlo → la vista del aviso
       también se suscribe al topic de la acción. Bug real 2026-06-19: el cuadro de
       mando no se refrescaba al validar; arreglado suscribiéndolo a `TOPIC_JOURNAL`.
  No cerrar un slice sin preguntarte: *¿qué vistas quedan obsoletas tras esta acción,
  y se refrescan solas?* Si no, falta el `emit`/`subscribe`.
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
- Antes de commitear/release: **VERIFICAR EN EJECUCIÓN** (sección 10.ter) —
  ¿he VISTO esto funcionar, o solo creo que funciona? Sin smoke test del
  camino tocado NO hay release.
- Antes de tocar UI: **CSS reusable + i18n + diálogo dimensionado + no emoji**.
- **i18n en CADA slice**: ¿algún **valor nuevo** (source_type/estado/enum/
  método) llega a la UI? → añade su clave `t(...)` en **ES y EN** (sección 4,
  checklist). Si falta una de las dos, la UI muestra la clave cruda. Si añades
  un valor que se filtra en un combo (p.ej. el filtro de Origen del Diario),
  **añádelo también a la lista del filtro**.
- **Auto-refresh en CADA acción**: tras crear/editar/borrar/validar/pagar →
  `RefreshBus.emit(TOPIC)`; toda vista afectada (incl. avisos/contadores) se
  `subscribe`. El usuario nunca pulsa "Refrescar" (sección 4).
- Antes de tocar endpoint: **`@RequiresRole` con EMPLOYEE si es operacional**.
- Antes de parsear JSON anidado: **`splitTopLevelObjects`**, no
  `parseObjects`.
- Antes de cerrar el día: **commit + push + merge develop**.

Si dudas en algo de esto, abre `docs/agents-debug-pattern.md` o
pregunta a Benjamin directamente.

---

## 10.bis. NO ASUMIR — verificar siempre antes de tocar (lección dura 2026-06-10)

> **Frase de Benjamin (2026-06-10 tarde):** *"no puedes dar por
> hecho algo sin haberlo consultado, entonces no asumas correcciones
> sin haber consultado el código. El código está para leerlo y
> comprobarlo. No tenemos que hacer las cosas rápido, tenemos que
> hacer las cosas bien."*

Esta regla viene de una tarde con 4 fallos consecutivos por asumir
sin verificar:

1. **V87 `ADD COLUMN ... AFTER pin_hash`** sobre `user_accounts` —
   asumí que `user_accounts` tenía `pin_hash`. **No lo tiene**.
   El PIN de vinculación vive en `employees.pin_hash` desde V3.
   Si hubiera leído V3 + V70 + el schema actual, lo habría visto.

2. **`UserSettingsService` consultando columnas dropeadas** — V87
   dropeó `language`, `ai_enabled`, `avatar_path`, `workday_template`
   de `user_settings` pero **no actualicé el service que las leía**.
   Tuvo que romperse en arranque para que me diera cuenta. La regla
   "DROP columna → busca todos los usos en código" no se aplicó.

3. **CompanyLogoService llamado desde InvoicePdfGenerator** —
   inventé que solo tocaba 1 caller; eran 3. No grepé. Mentí en el
   commit message.

4. **Editor de cliente "B" sin dirección** — propuse opción "B = 9
   campos originales" sin haberme parado a pensar que la dirección
   del cliente es **obligatoria** en la factura. Asunción a ciegas
   sobre lo que el usuario necesitaba.

### Reglas concretas para evitar esto

#### Antes de TOCAR una columna o tabla en BD:

```bash
# 1. ¿Qué columnas tiene HOY la tabla? (mira la última migración que
#    la toca, no solo el CREATE)
grep -rE "ALTER TABLE <tabla>|CREATE TABLE <tabla>" \
  backend-java/src/main/resources/db/migration/

# 2. ¿Quién lee/escribe esa columna?
grep -rn "<columna>" backend-java/src/main/java
grep -rn "<columna>" ui/src/main/java
```

Si vas a hacer DROP de una columna, los hits del segundo grep son la
**lista de cosas que debes refactorizar antes** del DROP, no después.

#### Antes de "porter X de CONTENDO":

```bash
# 1. ¿Qué columnas tiene la tabla origen?
grep -A30 "centros_trabajo_180\|emisor_180\|...etc" \
  "C:/Proyectos/CONTENDO GESTIONES/backend/migrations/*.sql"

# 2. ¿Qué endpoints expone?
ls "C:/Proyectos/CONTENDO GESTIONES/backend/src/routes/" | grep <slug>

# 3. ¿Qué hace el controller/service?
# leer 30-50 líneas, no resumen — el detalle se nos escapa
```

No vale fiarse del resumen de un agente Explore — el agente te da
estructura pero los detalles los tienes que confirmar tú con `Read`.

#### Antes de añadir una clase nueva al backend:

- ¿Existe ya algo similar? `find backend-java/src/main/java -name "*<slug>*"`
- ¿Inyecta `TenantContext` o `CurrentUserService`? ¿Por qué? ¿Lo
  necesita tu clase? Si SÍ, ¿el caller siempre estará dentro de un
  request scope? Si NO (cron, scheduled, hook PostConstruct), tienes
  que pasar `companyId` por parámetro.

#### Antes de cambiar la firma de un método público:

```bash
grep -rn "<nombreMetodo>(" backend-java/src/main/java ui/src/main/java
```

Cada hit es un caller que debes actualizar. Si el commit message dice
"toca 1 caller" y el grep da 5, el commit message miente.

#### Antes de cerrar un slice como "completo":

- ¿Compila? `mvn compile -q` desde la raíz. Sin output = OK.
- ¿Arranca? Para slices que tocan BD o schema: `mvn spring-boot:run`
  al menos una vez y mirar el log. Si fallaría en arranque, el
  usuario lo verá en su próximo arranque y dirá la frase de arriba
  otra vez.
- ¿Las pantallas afectadas se cargan? Si es UI nueva, ejecutar y
  abrirla mentalmente: ¿el SELECT que hace el service tiene todas
  las columnas que la BD tiene HOY?

#### Antes de proponer una decisión al usuario:

No propongas opciones (A/B/C) sin haber verificado las implicaciones
de cada una. Si "B = 9 campos originales", piensa: **¿esos 9 cubren
lo que el usuario va a hacer con la pantalla?** Si la pantalla es
"cliente que se va a facturar" y la opción B no tiene dirección,
estás proponiendo algo que el usuario va a rechazar al ver el PDF.

### El reflejo correcto

Cuando vayas a hacer algo y la primera reacción sea *"asumo que…"* o
*"creo recordar que…"*, **PARA** y haz un `Read` / `Grep` para
confirmar. Cuesta 30 segundos. Pifiarla cuesta una migración rota,
un endpoint roto en producción, y la confianza de Benjamin.

> **No tenemos que hacer las cosas rápido, tenemos que hacer las
> cosas bien.**

---

## 10.ter. VERIFICAR EN EJECUCIÓN antes de commitear/release (lección dura 2026-07-09)

Dos averías el mismo día, ambas con "compila OK" y "tests OK":

1. **v0.1.19 — DB-LOCK rompió el arranque del backend.** Asumí que la
   MariaDB embebida exigía credenciales. FALSO: MariaDB4j arranca con
   `--skip-grant-tables` (seguridad apagada; por eso "funcionaba"
   cualquier usuario/contraseña). Mi blindaje chocó con ese modo, el
   backend murió al arrancar y Benjamin se quedó SIN PODER ENTRAR en
   la app. Ni un solo arranque embebido de prueba antes de publicar.
2. **Gestor-navegador roto desde el 30-jun** (commit 577a21f): se
   añadió `log_severity` sin `log_file` y se publicó sin abrir el
   navegador ni una vez. En CEF 127 eso aborta los subprocesos y la
   ventana se cierra sola.

El patrón común: **dar por hecho** en vez de **ver funcionar**.

### La regla

Toda pieza nueva o tocada se **ejercita por su camino real de
ejecución** antes de commitear, y OBLIGATORIAMENTE antes de una
release. Si no se pudo (falta entorno, credencial, hardware), se dice
explícitamente a Benjamin ANTES de publicar — decide él.

### Checklist por tipo de cambio (mínimos, no exhaustivo)

| Si el cambio toca... | Verificación mínima en ejecución |
|---|---|
| **Arranque / BD embebida / MariaDB4j** | Boot embebido COMPLETO con `-Duser.home=<dir de prueba>` **FUERA de Temp** (MariaDB4j borra data dirs bajo Temp) + `-Dbenjagest.db.embedded=true`. Esperar `Started BenjagestBackendApplication` en el log. Probar TAMBIÉN el segundo arranque (reinicio). |
| **Migración Flyway** | Arrancar el backend y ver la migración aplicada en el log (no solo que el SQL parsee). |
| **Endpoint nuevo/modificado** | `curl` real contra el backend levantado (mínimo el happy path + un caso de rechazo si es un guard). |
| **Login / auth / tenant / interceptores** | Probar los TRES caminos: login asesoría, actuar-como-cliente (X-Company-Id), portal empleado (PWA). Un fallo aquí bloquea la app entera. |
| **UI JavaFX** | Abrir la pantalla afectada y ejecutar la acción (no solo que compile el binding). |
| **Gestor-navegador / JCEF** | Lanzarlo y cargar una página real; verificar que NO se cierra solo. |
| **Config de terceros (MariaDB4j, CEF, jpackage, Flyway...)** | Leer el comportamiento REAL del componente (código fuente/docs) o probarlo aislado. Nunca asumir defaults "razonables". |

### Antes de `gh release create` (gate duro)

1. Lista escrita de los caminos tocados por la versión.
2. Para cada uno: **cómo se verificó en ejecución** (comando/log/captura).
3. Lo no verificado → contárselo a Benjamin ANTES de publicar.
4. Con el MSI: si el cambio afecta al arranque, instalar/arrancar al
   menos una vez en local (o el boot embebido de prueba de arriba)
   antes de subir la release.

### El reflejo correcto

Cuando estés a punto de commitear y pienses *"esto es trivial, no hace
falta probarlo"* — esa frase exacta es la señal de PARAR y probarlo.
Las dos averías de arriba eran "triviales".

---

## 11. Trabajo autónomo cuando Benjamin no está

Benjamin a veces deja la sesión arrancada y se va. Reglas para esos
intervalos (probadas en sesión 2026-06-09 tarde, lecciones del fix
del puerto MariaDB la noche del mismo día):

### 11.1. Qué SÍ hacer

- **Cerrar bugs que él haya descrito por escrito** y se puedan
  diagnosticar con los datos que ya están (logs, código, BD).
- **Continuar slices previamente acordados** (los que él haya
  enumerado en su último mensaje antes de irse). Si hay un plan
  escrito en un comentario o en `docs/`, ese es el camino.
- **Mejoras aditivas evidentes** al cerrar un slice: ordenación que
  faltaba, i18n en un botón, footer con totales que se calculan
  trivialmente. La regla de Benjamin: *"cuando creas que solucionas
  algo, plantea una mejora extra"*. Aditivo, nunca destructivo.
- **Commits pequeños por slice**, con co-author Claude.
- **Push a `feat/Benjamin` + merge `--no-ff` a `develop`** al final
  de cada slice, NO al final del día. Así si algo va mal, solo se
  pierde un slice, no el día entero.
- **Compilar antes de commitear** (`mvn compile -q` desde la raíz).
  Si no compila, NO commitear — arreglarlo o revertir el WIP.

### 11.2. Qué NO hacer (lecciones duras 2026-06-09)

- **NO añadir validaciones "preventivas"** a código que no es el
  objetivo del slice. En la tarde añadí validación de `X-Company-Id`
  contra memberships al `TenantInterceptor` "por si acaso" y rompió
  el acceso del empresario a sus propios datos. La regla:
  > Si no es estrictamente necesario para el slice escrito, NO se
  > toca. Las mejoras "por si acaso" son fuente nº1 de regresiones
  > silenciosas.

- **NO cambiar AuthService.login() / selección de membership / JWT
  claims** sin que Benjamin esté delante. Esos cambios afectan a
  TODOS los usuarios y un fallo no se ve hasta que alguien recarga
  la UI. Si crees que hay un bug ahí, deja una nota en el backlog y
  espera.

- **NO crear migraciones que toquen seeds existentes**. Si necesitas
  un V{N} nuevo, que sea ALTER TABLE / ADD COLUMN aditivo. Cambiar
  `module_catalog`, `companies`, `user_accounts` o cualquier tabla
  con seed en V1-V8 sin Benjamin es zona caliente.

- **NO usar `git revert`** cuando el commit a revertir mezcla varios
  archivos. Usa `git checkout {sha} -- {file}` por archivo —
  quirúrgico, conservas lo que estaba bien.

- **NO usar `git push --force` ni `git commit --amend`** sobre
  commits ya pusheados. Benjamin lo dice explícito en sección 3.

### 11.3. Cuándo PARAR y dejar nota

Si pasas más de **2 rounds de "fix → no funciona"** en el mismo bug,
PARA. Lo más probable es que el síntoma no apunte a la causa. Deja
una nota en `docs/backlog.md` al inicio de la sección de la sesión
y espera a que Benjamin vuelva. Adivinar más solo añade ruido al
git log y dificulta el diagnóstico real cuando él regrese.

Síntomas típicos de "voy por mal camino":
- El fix toca un archivo distinto cada vez.
- Necesitas revertir el fix anterior antes de probar el siguiente.
- El error cambia de forma pero no desaparece.
- Empiezas a tocar capas (BD → backend → UI → BD…) sin un modelo
  claro de qué hace cada una.

### 11.4. Comprobaciones rápidas antes de diagnosticar BD

Si los datos están en BD pero no aparecen en UI:
1. Mira el log de arranque del backend: `Database: jdbc:mariadb://...`
   ¿Coincide con el puerto donde tú miras los datos? **Benjamin usa
   3307** (MariaDB 11.4 del proyecto). El 3306 es de Pablo, con BD
   distinta. Si el log dice `MariaDB 12.2`, estás contra la BD
   equivocada.
2. Añade un log `System.out.println(...)` en el repositorio justo
   tras el `query()` con `companyId=` y `rows=`. Si rows=0 pero la
   misma SQL ejecutada manualmente devuelve N, el JDBC está contra
   otra BD/schema o el parámetro no se vincula bien.
3. **NO** asumas que el bug está en TenantContext/AuthService antes
   de validar (1) y (2).

### 11.5. Reportar al volver

Cuando Benjamin regrese, primer mensaje debe ser:
- Lista numerada de slices cerrados con commit hash.
- Cosas que NO toqué (y por qué).
- Cualquier nota nueva en `docs/backlog.md`.
- 1-3 preguntas pendientes de decisión suya, si hay alguna.

NO dar paseo guiado por el código a no ser que él lo pida.
