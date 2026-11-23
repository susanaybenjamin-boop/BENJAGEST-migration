# MODE-UP — Plan de implementacion: subir de EMPRESARIO a ASESORIA

> **Para agentes:** usar `superpowers:subagent-driven-development` o
> `superpowers:executing-plans` para ejecutarlo tarea a tarea. Los pasos llevan
> casilla (`- [ ]`) para ir marcando.

**Objetivo:** que el OWNER de una empresa `CLIENT` la convierta en `ADVISORY`
desde Configuracion -> Empresa, sin mover ni perder ningun dato.

**Arquitectura:** un servicio en el paquete `advisory` hace, en una transaccion,
el cambio de `company_type`, la activacion aditiva de los modulos que le faltan
y el self-link de `advisory_invitations`. Un endpoint OWNER lo expone y la
pestana Empresa de Configuracion lo ofrece con un dialogo de confirmacion. No
hay migracion Flyway: no hay columnas nuevas.

**Stack:** Java 21, Spring Boot 3.3.5, JdbcTemplate, JUnit 5 + MariaDB4j
embebida para el test de integracion, JavaFX 21 en la UI.

**Spec:** `docs/design-subida-empresario-a-asesoria.md` (leerlo antes de empezar).

## Restricciones globales

- Rama `feat/Benjamin`; commit por tarea, en espanol, con co-author Claude.
  Push + merge `--no-ff` a `develop` al cerrar el bloque (CLAUDE.md 3 y 9).
- `mvn compile -q` desde la raiz antes de cada commit. **No compilar con la UI
  abierta ni durante los tests** (memoria del proyecto).
- i18n: toda clave nueva, par **ES + EN** en `I18n.java`. Si falta una, la UI
  pinta la clave cruda (CLAUDE.md 4).
- UI: reusar clases CSS existentes (`settings-section-title`, `settings-hint`,
  `primary-button`), dialogos con `setPrefSize`, boton Cancelar, sin emojis.
- `@RequestParam`/`@PathVariable` **siempre con el nombre escrito** (el build no
  usa `-parameters`; sin el nombre da 500 en ejecucion aunque compile).
- La empresa que se sube es `currentUser.activeCompanyId()`, **nunca**
  `tenantContext` (CLAUDE.md 5).
- Nada de `--no-verify`, `--amend` sobre lo pusheado ni `push --force`.

## Puntos de revision (lo que el diseno implica y hay que probar)

1. **El OWNER actuando como cliente**: si pulsa el boton con `X-Company-Id` de un
   cliente, se debe subir SU empresa (`activeCompanyId`), no la del cliente.
   -> test en Task 1.
2. **Nada se apaga**: un modulo que el empresario tenia activo (p.ej.
   `time-clock`) sigue activo despues de subir. -> test en Task 1.
3. **Los datos siguen ahi**: clientes/facturas de la empresa intactos tras el
   cambio de tipo. -> test en Task 1 (fila de `customers`) y paso 5 del smoke.
4. **Repetir la llamada** no deja la empresa a medias ni duplica el self-link ni
   filas de `company_modules`. -> test en Task 1.
5. **Solo OWNER**: un ADMIN/ACCOUNTANT recibe 403 y no cambia nada. Lo corta
   `@RequiresRole`, que es un interceptor y no se ve en el test de servicio
   -> se comprueba con `curl` en la Task 3.

---

### Task 1: Backend — servicio, endpoint y tests

**Ficheros:**
- Crear: `backend-java/src/main/java/com/benjagest/backend/advisory/AdvisoryUpgradeService.java`
- Crear: `backend-java/src/main/java/com/benjagest/backend/advisory/AdvisoryUpgradeController.java`
- Test: `backend-java/src/test/java/com/benjagest/backend/advisory/AdvisoryUpgradeTest.java`
- Referencia de patrones: `AdvisoryService.java:131` (`ensureSelfLink`, la SQL a
  reutilizar), `CobroDobleIntegrationTest.java` (montaje MariaDB4j + Flyway +
  `TenantContext` fijo), `AuditService.recordGeneric`.

**Interfaces:**
- Consume: `CurrentUserService.require()` -> `AuthenticatedUser` (tiene
  `userId()` y `activeCompanyId()`), `JdbcTemplate`, `AuditService`.
- Produce:
  ```java
  public record UpgradeResult(String companyId, String companyType,
                              List<String> activatedModules) {}
  public UpgradeResult upgradeActiveCompany()   // en AdvisoryUpgradeService
  ```
  Codigos de rechazo (motivo del `ResponseStatusException`, los lee la UI):
  `ALREADY_ADVISORY`, `LINKED_TO_ADVISORY`, `COMPANY_NOT_FOUND`.

- [ ] **Paso 1: escribir el test que falla**

`AdvisoryUpgradeTest` con MariaDB4j + todas las migraciones (copiar el montaje
de `CobroDobleIntegrationTest`), `CurrentUserService` mockeado devolviendo un
`AuthenticatedUser` con `activeCompanyId = COMPANY` y un `TenantContext` fijo que
devuelve **otro** id (asi el test cubre el punto de revision 1). Fixture: una
company `CLIENT` con `time-clock` activo y una fila en `customers`.

```java
@Test void subeLaEmpresaAAdvisoryYActivaLosModulosQueLeFaltan()
// company_type='ADVISORY'; tax, notifications y team activos en company_modules
@Test void noApagaNingunModuloQueYaEstabaActivo()
// time-clock sigue active=TRUE
@Test void dejaLosDatosDeLaEmpresaIntactos()
// la fila de customers sigue ahi y con el mismo company_id
@Test void creaElSelfLinkDeLaAsesoria()
// 1 fila en advisory_invitations con advisory_company_id = invited_company_id
//   = COMPANY, status 'ACCEPTED', token 'SELF:<id>'
@Test void subeLaEmpresaDelUsuarioNoLaDelTenantContext()
// company_type de COMPANY cambia; la empresa del tenantContext sigue CLIENT
@Test void laSegundaLlamadaRechazaConAlreadyAdvisoryYNoDuplicaNada()
// 409 ALREADY_ADVISORY; sigue habiendo 1 self-link y 1 fila por modulo
@Test void rechazaSiLaEmpresaEstaVinculadaAOtraAsesoria()
// parent_company_id != NULL -> 409 LINKED_TO_ADVISORY y company_type sigue CLIENT
```

- [ ] **Paso 2: ejecutar el test y ver que falla**

`mvn -pl backend-java test -Dtest=AdvisoryUpgradeTest`
Esperado: FALLA por clase inexistente (`AdvisoryUpgradeService`).

- [ ] **Paso 3: implementar `AdvisoryUpgradeService.upgradeActiveCompany()`**

`@Service`, metodo `@Transactional`. Orden:
1. Leer `company_type` y `parent_company_id` de `companies` por
   `activeCompanyId` -> vacio: 404 `COMPANY_NOT_FOUND`.
2. `parent_company_id != null` -> 409 `LINKED_TO_ADVISORY`.
3. `UPDATE companies SET company_type='ADVISORY' WHERE id=? AND company_type='CLIENT'`;
   si no actualiza fila -> 409 `ALREADY_ADVISORY`.
4. Activar `tax`, `notifications`, `team` con
   `INSERT INTO company_modules ... SELECT ... FROM module_catalog WHERE slug IN (...)
   ON DUPLICATE KEY UPDATE active=TRUE, deactivated_at=NULL, deactivated_by=NULL`.
   Nunca un `UPDATE ... SET active=FALSE`.
5. Self-link: misma SQL que `AdvisoryService.ensureSelfLink` **con el companyId
   por parametro** (no leer `TenantContext`), precedida del `COUNT` que la hace
   idempotente.
6. `auditService.recordGeneric(companyId, userId, "COMPANY_UPGRADED_TO_ADVISORY",
   "company", companyId, "OK", null)`.

- [ ] **Paso 4: ejecutar los tests y ver que pasan**

`mvn -pl backend-java test -Dtest=AdvisoryUpgradeTest` -> PASA.

- [ ] **Paso 5: el endpoint**

`AdvisoryUpgradeController`: `@RestController`,
`@RequestMapping("/api/advisory/upgrade")`, `@RequiresRole({"OWNER"})`,
**sin** `@RequiresModule` (el empresario no tiene el modulo `advisory`; por eso
este controller es aparte del de invitaciones). Un solo `@PostMapping` que
devuelve `UpgradeResult`.

- [ ] **Paso 6: suite completa y commit**

`mvn test -q` (suite entera, no solo la clase nueva).

```bash
git add backend-java/src/main/java/com/benjagest/backend/advisory/AdvisoryUpgrade*.java \
        backend-java/src/test/java/com/benjagest/backend/advisory/AdvisoryUpgradeTest.java
git commit -m "feat(modos): endpoint para subir una empresa de empresario a asesoria (MODE-UP-1)"
```

---

### Task 2: UI — boton en Configuracion -> Empresa

**Ficheros:**
- Modificar: `ui/src/main/java/com/benjagest/ui/service/SettingsApiClient.java`
  (metodo nuevo junto a `updateCompany`, mismo patron `sendAuthorized` + `ensureOk`)
- Modificar: `ui/src/main/java/com/benjagest/ui/screens/SettingsScreen.java`
  (`settingsCompanyTab`, el `VBox body` de la linea ~714, justo tras `typeNote`;
  y la `interface Host` de la linea 45)
- Modificar: `ui/src/main/java/com/benjagest/ui/BenjagestUiApplication.java`
  (implementar el metodo nuevo del `Host`, reutilizando el camino del boton
  "Salir" de la linea ~1745)
- Modificar: `ui/src/main/java/com/benjagest/ui/i18n/I18n.java` (bloque EN de
  `settings.company.*` ~linea 1178, bloque ES ~linea 2484)

**Interfaces:**
- Consume: `POST /api/advisory/upgrade` de la Task 1 y sus tres codigos.
- Produce: `SettingsApiClient.upgradeToAdvisory()` (sin parametros, `void`,
  `throws IOException, InterruptedException`) y `Host.logoutAndExit()`.

- [ ] **Paso 1: el cliente API**

`public void upgradeToAdvisory() throws IOException, InterruptedException` —
`POST` a `baseUrl + "/advisory/upgrade"` con cuerpo vacio
(`BodyPublishers.noBody()`), `ensureOk(response)`. El `IOException` que lanza
`ensureOk` ya trae `"HTTP 409: {body}"`, que es de donde la pantalla saca el
codigo.

- [ ] **Paso 2: el bloque en la pestana Empresa**

Visible solo si `appMode == AppMode.BUSINESS`, `"CLIENT".equalsIgnoreCase(company.companyType())`
y `AuthSession.get().roleInActiveCompany()` es `OWNER`. Contenido: titulo
(`settings-section-title`), hint (`settings-hint`) y boton (`primary-button`).
Va en el `VBox body` detras de `typeNote`, precedido de `new Separator()`.

- [ ] **Paso 3: el dialogo y la llamada**

Boton -> `Alert` de confirmacion (`setPrefSize`, Cancelar) cuyo cuerpo enumera:
aparece "Mi gestion" y la cartera de clientes; Facturacion, Compras, Laboral y
Fiscal pasan a abrirse dentro de "Mi gestion"; no se pierde ningun dato; al
terminar hay que cerrar y volver a abrir BENJAGEST. Al aceptar, `Task` + `start(task, "advisory-upgrade")`
(mismo patron que `saveCompany`).
- `setOnSucceeded`: alerta de "listo" y, al cerrarla, `host.logoutAndExit()`.
- `setOnFailed`: `showError` con el mensaje segun el codigo que venga en la
  excepcion — `LINKED_TO_ADVISORY` -> clave `...error.linked`,
  `ALREADY_ADVISORY` -> `...error.already`, cualquier otro -> `...error.generic`.

- [ ] **Paso 4: `Host.logoutAndExit()`**

Declararlo en `interface Host` e implementarlo en `BenjagestUiApplication`
extrayendo a un metodo privado el cuerpo del `setOnAction` del boton "Salir"
(linea ~1745: paran los pollings, `authApiClient.logout()`, limpiar
`AuthSession`, `WindowGeometry.save`, `Platform.exit()` + `System.exit(0)`) y
llamandolo desde los dos sitios. **No cambiar lo que hace "Salir"**: cierra la
app entera a proposito (fix 2026-07-10).

- [ ] **Paso 5: i18n ES + EN**

Las once claves, en los **dos** bloques: `settings.company.upgrade.section`,
`.hint`, `.button`, `.confirm.title`, `.confirm.body`, `.done.title`,
`.done.body`, `.error.title`, `.error.linked`, `.error.already`,
`.error.generic`.

- [ ] **Paso 6: compilar y commit**

`mvn compile -q` desde la raiz (con la UI cerrada). Sin salida = OK.

```bash
git add ui/src/main/java/com/benjagest/ui/service/SettingsApiClient.java \
        ui/src/main/java/com/benjagest/ui/screens/SettingsScreen.java \
        ui/src/main/java/com/benjagest/ui/BenjagestUiApplication.java \
        ui/src/main/java/com/benjagest/ui/i18n/I18n.java
git commit -m "feat(modos): boton para subir a asesoria en Configuracion -> Empresa (MODE-UP-2)"
```

---

### Task 3: Verificacion en ejecucion y cierre

**Ficheros:**
- Modificar: `docs/backlog.md` (entrada de la sesion + lo que queda fuera: la
  vuelta atras y el hibrido cliente-y-asesoria)

- [ ] **Paso 1: smoke aislado**

MariaDB4j en 23307 + backend en 18080 (receta del proyecto). Alta de una cuenta
`CLIENT` por `POST /api/auth/register` y meterle un cliente y una factura.

- [ ] **Paso 2: `curl` del endpoint**

Happy path 200 con `companyType: "ADVISORY"`; luego los rechazos: repetir la
llamada (409 `ALREADY_ADVISORY`), con un usuario ADMIN (403, punto de revision
5) y con una empresa con `parent_company_id` (409 `LINKED_TO_ADVISORY`).

- [ ] **Paso 3: comprobar la BD**

`company_type`, los tres modulos activos, la fila SELF en
`advisory_invitations` y el evento `COMPANY_UPGRADED_TO_ADVISORY` en
`audit_events`.

- [ ] **Paso 4: login**

`POST /api/auth/login` -> la membership viene con `companyType: "ADVISORY"`.

- [ ] **Paso 5: la UI contra ese backend**

Entrar, ver el sidebar de asesoria, abrir "Mi gestion" y **comprobar que el
cliente y la factura del paso 1 siguen ahi**. Sin este paso no se cierra el
bloque (CLAUDE.md 10.ter).

- [ ] **Paso 6: backlog, commit y merge**

```bash
git add docs/backlog.md
git commit -m "docs(backlog): MODE-UP verificado en ejecucion y lo que queda fuera"
git push origin feat/Benjamin
git checkout develop && git pull --ff-only
git merge --no-ff feat/Benjamin -m "merge: MODE-UP subida de empresario a asesoria"
git push origin develop && git checkout feat/Benjamin
```

- [ ] **Paso 7: decirle a Benjamin lo que falta**

En su cuenta de produccion no se toca nada sin su OK y **sin copia de la BD
antes** (es su contabilidad). Si quiere, se hace con el delante.
