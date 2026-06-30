# Plan — Troceado de la UI (bloque UIR)

> **Decidido Benjamin 2026-06-30.** Objetivo: desmontar el God Object
> `BenjagestUiApplication.java` (~44.125 líneas, ~145 métodos de pantalla) en
> clases de pantalla independientes, replicando el patrón **ya probado** en el
> proyecto (`screens/AccountingScreen.java`, `screens/ClientFinancialsScreen.java`).
> No es un rediseño: es mover código a su sitio sin cambiar comportamiento.

## Por qué
- El backend está bien modularizado (35 paquetes de dominio). La UI no: una sola
  clase concentra casi toda la interfaz.
- El 18% del archivo (~8.200 líneas) es solo i18n; 24 tipos van anidados; las
  pantallas leen campos privados directamente y se llaman entre sí (`showX()→showY()`).

## Contrato de extracción (el patrón que ya funciona)
Cada pantalla = una clase en `ui/screens/`:
- Constructor recibe `AppContext` (+ su `ApiClient` concreto).
- Expone `public Node buildView()` (o `build*Tab()`).
- El `showX()` del monolito queda como **wrapper fino** que instancia la clase y
  la monta con `setCenterAnimated(...)`. El dispatcher `showModule` no se toca.
- Los campos cacheados de esa pantalla (`TableView`, `ComboBox`, `Runnable refresh`)
  **se mudan dentro** de la clase nueva.

## Fases y slices

### 🟢 FASE 1 — Andamiaje (movimientos puros, sin cambiar comportamiento)
- **UIR-1** — Extraer i18n a `I18n` (`t(Language, key)`); el monolito conserva
  `private String t(String key) { return I18n.t(language, key); }`. Mueve `t` +
  los ~28 métodos `tXxxEn/Es`. Recorta ~8.200 líneas. Riesgo bajo (solo depende de
  `language`). **Preservar la fragmentación** de métodos (límite 64KB de bytecode).
- **UIR-2** — Sacar los 24 records/enums/interfaces anidados a ficheros propios
  (`model/`, `support/nav/`). Incluye `Language`, `AppMode`, `ModuleLink`, los
  `*Bundle`, `*Row`, interfaces funcionales. Hojas sin dependencias.
- **UIR-3** — Helpers **stateless** compartidos en `support/`: `Icons` (icon),
  `Formatters` (money/displayValue + DISPLAY_DATE/CURRENCY_FORMAT), `Dialogs`
  (error/info/toast). El monolito conserva métodos delegados (call-sites intactos).
  > **Decisión 2026-06-30:** se DESCARTA el `AppContext` god-object. El patrón ya
  > usado en el proyecto (`AccountingScreen(apiClient, this::t)`) inyecta dependencias
  > **concretas** por pantalla — es mejor diseño que un contexto-dios. Las pantallas
  > extraídas reciben su(s) ApiClient(s) + la función `t`, e **importan** los helpers
  > stateless directamente. Lo stateful que faltaba (navegación) se resuelve en UIR-4
  > (Router); el async (`start(Task)`) cada pantalla lo gestiona como ya hace
  > `AccountingScreen`.

### 🟡 FASE 2 — Router (cortar llamadas cruzadas)
- **UIR-4** — Interfaz `Router` (`navigateTo(module)`, `setCenter(node)`) que las
  pantallas extraídas reciben (junto a su ApiClient + `t`) para navegar en vez de
  `this.showY()`. El monolito implementa el `Router` durante la transición.

### 🔴 FASE 3 — Pantalla por pantalla (orden de menor a mayor acoplamiento)
- **UIR-5** — Login / Registro / Onboarding (M)
- **UIR-6** — RETA / DEHú (M)
- **UIR-7** — Portal empleado (MEMP) (S)
- **UIR-8** — Sugerencias / Perfil / Bloqueo / Equipo (M)
- **UIR-9** — Fiscal (Modelos AEAT 303/130/347/390/190) (L)
- **UIR-10** — Calendario (S)
- **UIR-11** — Facturación / Compras / Ventas / VeriFactu (XL)
- **UIR-12** — Configuración / Settings / Certificados / Credenciales (L)
- **UIR-13** — Asesoría / Clientes gestionados / Consolidación / TPB (XL)
- **UIR-14** — Trabajos / Calendario laboral / Centros / Tablas año-dependientes (L)
- **UIR-15** — Laboral / Nómina (NOM) (XXL) — **el último**, cuando el patrón ya esté rodado

## Reglas de seguridad (no negociables)
1. **Un dominio por commit** (revertible).
2. **Cero cambios de comportamiento**: mover, no reescribir. No tocar CSS ni claves
   i18n (reglas duras `CLAUDE.md` §4).
3. Tras cada slice: `mvn compile -q` + **arrancar y abrir la pantalla afectada**
   (no hay tests automáticos → verificación manual es la red).
4. **Aditivo**: `showX()` se queda como wrapper; navegación y dispatcher intactos.
5. Push a `feat/Benjamin` + merge `--no-ff` a `develop` al cerrar cada slice.

## Alcance
Bloque largo (~15 slices, varias sesiones). Cada slice deja el proyecto compilando
y funcionando, así que se puede parar en cualquier punto. La Fase 1 sola ya ordena
mucho. **La subida de versión (`UpdateService.APP_VERSION` + release) se hace una sola
vez, cuando TODO el bloque (hasta UIR-15) esté terminado**, para que llegue como una
única actualización vía auto-update. Hasta entonces, cada slice solo va a `develop`.

## Estado
- [x] UIR-1 (i18n→I18n, −8.684 líneas) · [x] UIR-2 (tipos transversales; bundles diferidos a Fase 3)
  · [x] UIR-3 (helpers stateless Icons/Formatters/Dialogs; AppContext god-object descartado)
  · [x] UIR-4 (Router: navigateTo/setCenter/runTask) · [ ] UIR-5 … UIR-15
