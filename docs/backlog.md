# Backlog operativo BENJAGEST

> **Última actualización:** 2026-06-14 (NOM validado contra AEAT + **bloque CICLO-VIDA completo y probado** + sesión autónoma: análisis de **preparación para LOCAL/on-premise** y **Verifactu en local** con dos agentes Explore → guía `docs/despliegue-local.md`, scripts de arranque y bloque **DEPLOY-LOCAL** en este backlog).
>
> **Forma de trabajo (junio 2026):** Benjamin lidera y decide. Pablo solo entra de uvas a peras desde 05-30. Todo el trabajo va por `feat/Benjamin` → prueba local → commit → merge `--no-ff` a `develop`. Cada item cerrado lleva commit hash + fecha. **Regla 10.bis de CLAUDE.md aplica siempre: verificar código antes de tocar.**
>
> **Fuentes complementarias:** [`gap-analysis-contendo.md`](gap-analysis-contendo.md), [`gap-analysis-config-ui.md`](gap-analysis-config-ui.md), [`migration-roadmap.md`](migration-roadmap.md), [`vf-chain-fix.md`](vf-chain-fix.md), [`agents-debug-pattern.md`](agents-debug-pattern.md).

---

## 2026-06-14 — DEPLOY-LOCAL: ¿está listo para funcionar en local? ✅🖥️

> Pregunta de Benjamin (se fue a trabajar): *"este programa va a trabajar en
> local, ¿estamos preparándolo para eso? ¿Verifactu está preparado?"*.
> Investigado con **dos agentes Explore en paralelo** (preparación local +
> Verifactu). Veredicto y entregables abajo.

**Veredicto: SÍ, el código está local-ready (≈85-90%).** No hay acoplamiento a
la nube (ni S3, ni OAuth Google, ni subdominios SaaS). UI→backend configurable
por `BENJAGEST_API_BASE_URL`; BD y puerto por env vars; ficheros en filesystem
local configurable (`benjagest.invoices.storage-root`,
`benjagest.imported-pdfs.root`); multitenant por `company_id` encaja en una
asesoría local con N clientes. La UI es **JavaFX de escritorio (HttpClient)** →
**no hay problema de CORS** al apuntar a otra IP de la LAN. Servicios externos
(AEAT, BOE, email, geocoding) son **opcionales y degradan bien** sin internet.

**Verifactu en local:** funciona **100% offline en modalidad NO_VERIFACTU**
(huella SHA-256 encadenada + firma local + QR + eventos SIF, todo en el
servidor). La modalidad **VERI*FACTU** (envío a la AEAT) está implementada pero
**NO probada contra la AEAT**: requiere certificado FNMT registrado + ajustar el
XML al XSD oficial + firma XAdES-EPES (`AeatVerifactuClient` lo dice). El
scheduler de envío con reintentos ya existe. Para una asesoría on-premise, lo
correcto hoy es **NO_VERIFACTU**; el salto a VERI*FACTU es mejora futura con una
salida puntual a internet.

**Hecho en esta sesión (aditivo, sin tocar auth/seeds/AEAT):**
- ✅ `docs/despliegue-local.md` — guía oficina (1 servidor + N puestos por LAN).
- ✅ `start-local-server.ps1` (servidor: Docker + espera BD + backend) y
  `start-ui.ps1 -ServerIp <IP>` (puesto: fija API base + lanza UI).

### 🎯 DEPLOY-PKG — Instalable Windows (decisiones Benjamin 2026-06-15)

> **NO construir todavía.** Se empaqueta **al terminar la app**. Anotado aquí para
> no perder las decisiones de producto.

**Visión de producto:**
- El programa tendrá **dos versiones**: **Asesoría** y **Empleado**.
- **Modelo por defecto = "todo es un puesto"**: en una sola máquina se instala
  **UI + backend + MariaDB embebida**, autocontenido. Un **empresario con un solo
  PC no necesita un segundo ordenador** ni configurar nada de red.
- **Asesoría con varios empleados (caso opcional/avanzado):** el PC del **OWNER
  hace de servidor** (tiene la BD + backend) y los **empleados son puestos** que
  apuntan a su IP por la LAN. Reusa el modo LAN ya documentado en
  `docs/despliegue-local.md`. Pero **no es obligatorio**: si la asesoría es de una
  persona, también funciona como puesto único.
- Implicación técnica clave: la app debe poder arrancar en **modo embebido**
  (todo local en una máquina) como caso primario; el modo cliente→servidor LAN es
  configuración (apuntar `BENJAGEST_API_BASE_URL` a otra IP), ya soportado.

**Decisiones cerradas:**
- ✅ **MariaDB embebida/portable** dentro del instalador (sin Docker). Se empaqueta
  al final.
- ✅ Runtime de **Java incluido** vía `jpackage`/`jlink` (verificado: ambas
  herramientas están en el JDK Temurin 21 de la máquina; la UI ya es modular con
  `module-info.java`).

**Pendientes DEPLOY-PKG (al terminar la app, no arquitectura):**
- ⬜ **jpackage** del puesto (UI) + backend embebido + MariaDB portable → un
  instalable que arranque todo automático en una máquina ("todo es un puesto").
- ⬜ Backend (+ MariaDB) como **servicio de Windows** con auto-arranque (necesario
  sobre todo en el rol servidor/OWNER; en puesto único puede arrancar al abrir la app).
- ⬜ Instalar **WiX Toolset v3** en la máquina de build para generar `.msi`/`.exe`
  nativo (servicio + accesos directos + desinstalador). Sin WiX solo "app-image".
- ⬜ Variante de instalador/branding por **versión (Asesoría / Empleado)**.
- ⬜ Revisar/abrir puerto 8080 en firewall solo en el rol servidor (LAN).

**Pendiente Verifactu real (independiente del empaquetado):**
- ⬜ **VF-SIGN-XADES** (cuando se quiera VERI*FACTU real): cert FNMT + XSD AEAT
  oficial + XAdES-EPES + parseo de respuesta AEAT. Probar contra preproducción.

---

## 2026-06-14 — PROPUESTA: Ciclo de vida laboral y societario (CICLO-VIDA) ⚖️💰

> Identificado por Benjamin: hoy solo gestionamos **altas** (empleados,
> contratos, RETA autónomos, empresa). Falta toda la **salida/cese**, que
> conlleva nóminas, pagos, indemnizaciones y documentos legales. Propuesta
> de bloque a abordar tras validar el IRPF. Orden por dependencia/valor.

> **Avance 2026-06-14 (todo en develop):** el bloque CV se ha rediseñado como
> flujo AUTOMÁTICO (decisión Benjamin): la acción "Despedir / finiquito" sobre
> el empleado extrae todo solo (no se teclea cese/días). Cerrados:
> CV-VAC (registro de vacaciones, V114), CV-1 (finiquito SETTLEMENT),
> CV-2 (indemnización por tipo), CV-ORQ (orquestador baja/despido,
> TerminationService), CV-3 (jubilación = tipo RETIREMENT), CV-DOC (carta de
> despido + certificado de empresa, TerminationDocsService) y PAY-RECURRENT
> ("Generar mes": nómina mensual de todos los activos de una vez, idempotente).
> **Bloque CV probado por Benjamin (va todo bien).** Ajustes tras pruebas:
> antigüedad en años/meses/días; **fecha de antigüedad reconocida** en el
> contrato (V115) para indemnización con contratos sucesivos; tipo de trabajo
> localizado (combo Jornada completa/parcial); al despedir, el empleado pasa a
> baja si no le queda contrato activo; pool **Hikari** resiliente a conexiones
> muertas (suspensión del equipo / reinicio de MariaDB).
> Pendientes (futuro): CV-4 baja RED real, CV-5 excedencias, CV-6/7 autónomos
> (cese de actividad / jubilación RETA), CV-8 cese de empresa.

**Empleados (laboral):**
- ✅ ⚖️ **CV-1 Finiquito / liquidación** *(2026-06-14)* — al
  terminar cualquier contrato (baja voluntaria, fin de contrato, despido,
  jubilación): salario de los días trabajados + vacaciones no disfrutadas +
  prorrata de pagas extras devengadas no cobradas + pluses pendientes. Genera
  **recibo de finiquito** (PDF). Reusa el motor de nómina (tipo `SETTLEMENT`).
  Cotiza/tributa por conceptos; la **indemnización va aparte** (campo propio,
  exenta de IRPF hasta el límite legal; el detalle de tipos de despido es CV-2).
- ✅ ⚖️ **CV-2 Despido + indemnización** *(2026-06-14, motor)* — improcedente
  33 d/año (tope 24 mens; tramo 45 d hasta 2012-02-12, tope 42 mens), objetivo
  20 d/año (tope 12 mens), disciplinario 0, fin temporal 12 d/año. Salario
  diario=anual/365, antigüedad por fechas. Exenta IRPF hasta 180.000 €.
  (La **carta de despido** es CV-DOC, pendiente.)
- ✅ ⚖️ **CV-DOC Documentos de baja** *(2026-06-14)* — carta de despido (por
  tipo) + certificado de empresa. Se descargan tras la baja (TerminationDocsService).
- ✅ ⚙️ **PAY-RECURRENT** *(2026-06-14)* — "Generar mes": nómina mensual de todos
  los empleados activos de una vez, salta las ya hechas. Recurrente como ventas/gastos.
- ✅ ⚖️ **CV-3 Jubilación del empleado** *(2026-06-14)* — tipo RETIREMENT del
  orquestador: finiquito sin indemnización + cierre de contrato. (Baja RED +
  certificado de empresa: CV-DOC / CV-4.)
- ⬜ ⚖️ **CV-4 Baja en Sistema RED / certificado de empresa** — al cesar
  cualquier empleado: comunicación de baja (RED) + certificado de empresa
  (datos de cotización para la prestación). Hoy solo está el alta (contrat@).
- ⬜ **CV-5 Excedencias / suspensiones / reducción de jornada** — afectan
  cotización y nómina (suspensión sin sueldo, reducción por guarda legal…).

**Autónomos (RETA):**
- ⬜ ⚖️ **CV-6 Cese de actividad autónomo** — baja en RETA + AEAT (036/037) +
  prestación por cese de actividad ("paro del autónomo"). Liquidación de cuotas.
- ⬜ ⚖️ **CV-7 Jubilación del autónomo** — baja en RETA, compatibilidad
  jubilación activa, cálculo de la base reguladora.

**Empresa (societario):**
- ⬜ ⚖️ **CV-8 Cese / disolución de empresa** — baja de todos los empleados +
  finiquitos, despido colectivo (ERE) si aplica, baja de la empresa en SS y
  AEAT, liquidación. Implica N finiquitos + documentación.

**Transversal:** todos estos generan **documentos** (finiquito, carta despido,
certificado empresa) + **cálculos** (indemnización exenta/sujeta) + **pagos** +
**comunicaciones** (RED/AEAT). Decisiones pendientes de Benjamin: alcance de
cada uno (¿solo cálculo+PDF, o también el envío telemático real?).

---

## 2026-06-13 tarde — Bloque NOM-FLUJO (nómina profesional) ⚖️💰

Sobre el bloque NOM, construido el flujo completo estilo A3/Nomio:

- ✅ **NOM-6 Coste empresa/empleado** — pestaña Labor "Coste empresa".
- ✅ **PDF recibo modelo oficial** (Orden ESS/2098/2014, estilo Nomio): cabecera
  rejilla + tabla conceptos CLAVE/DEVENGOS/DEDUCCIONES + bases cotización
  (remuneración+prorrata) + 2 tablas aportación trabajador|empresa + firmas.
- ✅ **Fix base cotización = anual/12 SIEMPRE** (incluye prorrata, art.147 LGSS).
  Casilla prorrateo invertida corregida; default 14 pagas (art.31 ET).
- ✅ **DEV-DESGLOSE** (V107): `contract_salary_items` (salario base + complementos
  libres con cotiza/tributa) + `payslip_lines`. Editor de complementos en el
  editor de contrato. Nómina con una línea de devengo por concepto.
- ✅ **Complementos por nómina** (dietas/km/asistencia) en el diálogo de calcular.
- ✅ **PARAM-YEAR** (V108): `ss_contribution_rates` global por año (seed 2026).
  El cálculo lee los tipos de la tabla (no a fuego). Pestaña "Tipos cotización".
- ✅ **PREVIEW**: `compute()` puro + `preview()` + endpoint `/preview`. Botón
  Previsualizar + resumen en vivo + botón **Validar** en el diálogo.
- ✅ **OBJETIVO**: `solveTarget()` + endpoint `/solve-target`. "Llegar a objetivo"
  (bruto=resta / neto=modelo lineal con %IRPF contrato) → propone Mejora voluntaria.
- ✅ **REPLICAR**: botón "Lote a objetivo" → genera nóminas a un sueldo objetivo
  para varios empleados (mismo bruto=mismo plus; mismo neto=plus distinto por
  situación familiar).

**Pendiente futuro:** complementos en el asistente de alta.
(✅ topes cotización TGSS — V109; ✅ pagas extra EXTRA_* sin cotización propia;
✅ inverso NET por bisección con tipo IRPF real — 2026-06-14.)

**Pendiente NOM (refinamiento complementos, 2026-06-14):**
- ⬜ ❓ **Reparto del objetivo entre varios complementos con min/max** — hoy
  "Proponer plus" añade/actualiza UNA "Mejora voluntaria" (idempotente). Benjamin
  quiere poder repartir el objetivo entre varios complementos, cada uno con un
  mínimo/máximo, de forma idempotente. **Decisión pendiente**: definir el modelo
  (¿qué complementos son "ajustables", su orden y topes?). No es estándar A3
  (A3 usa un único concepto "a cuenta convenio"/mejora para cuadrar).
- ⬜ **IRPF: regularización intra-anual** (recalcular al cambiar datos a mitad de
  año) y **límite art. 85.3 afinado** por meses restantes.
- ⬜ **Reducciones algoritmo no modeladas**: pensionista (600), desempleado que
  acepta puesto (1.200), anualidades por alimentos (+1.980 en cuota2). Hoy no se
  modela situación pensionista/desempleado del perceptor.
- ⬜ **Aviso BOE de cambios de parámetros** — afinar BOE-RSS para alertar cuando
  se publique la norma de retenciones/cotización del nuevo año (recordatorio de
  actualizar las tablas por año).

---

## 2026-06-14 — PROPUESTA: Análisis financiero del cliente (FIN-ANALYSIS) 📊💰

> Idea de Benjamin: que la asesoría pueda sacar un **análisis financiero
> instantáneo** de cualquier cliente, con KPIs, y proponer cómo mejorar el
> beneficio. **Pendiente** (no arrancado). Exploración hecha 2026-06-14.

**Base que YA existe (reutilizar, NO duplicar):**
- `SalesAndExpensesKpiService` — P&L por empresa desde el diario: ventas (7xx
  haber), gastos (6xx debe), IVA repercutido (477) / soportado (472), **modelo
  303 estimado**, asientos DRAFT. Por rango de fechas, <200 ms.
- `AdvisoryDashboardService` — nivel cartera (cross-cliente): facturado,
  pendiente de cobro, vencidas, obligaciones, workflow.
- Labor `EmployerCostRow` (coste empresa/empleado por año); `fiscal`/`tax`
  (modelos + `tax_filings`); `accounting` cierre de ejercicio (precalcula
  resultado); `purchases` + banco (compras, conciliación).

**Plan por slices (orden por valor/dependencia):**
- ⬜ **FIN-1 Cuadro de mando del cliente (KPIs nivel 1)** — servicio
  `ClientFinancialsService(companyId, periodo)` que reúne: ingresos, gastos,
  **margen y beneficio**; coste de personal y su % sobre ingresos; carga fiscal
  (303 estimado ya está + IRPF/retenciones); tesorería (cobros/pagos
  pendientes desde `sales_invoices`/`purchases`); ratios (margen %, gasto/ingreso,
  ticket medio, DSO morosidad). UI: pantalla por cliente con tarjetas KPI.
- ⬜ **FIN-2 Evolución y comparativa** — serie mensual ingresos/gastos/beneficio
  del año + comparativa interanual (gráfica).
- ⬜ **FIN-3 Proyección de cierre** — extrapola tendencia + estima beneficio e
  IS de fin de año (reusa `year-close precalculate`). Aviso de tesorería futura.
- ⬜ **FIN-4 Recomendaciones (prescriptivo)** — reglas: IVA soportado sin
  deducir, gastos atípicos/recurrentes altos, amortizaciones pendientes, tipo
  IRPF del autónomo / pagos fraccionados, morosos a reclamar. Presentadas como
  "sugerencias a revisar por el asesor". Opcional: narrativa con IA (el cálculo
  va sobre datos reales, no inventado).
- ⬜ **FIN-5 Informe PDF** del cuadro de mando + recomendaciones.

**Límites honestos:** la proyección es tan buena como el histórico; sin
benchmarks de sector no se puede comparar "con empresas similares"; las
recomendaciones fiscales se marcan como sugerencias (las decide el asesor).
Empezar por **FIN-1 + FIN-2** (sólido y rápido); FIN-3/4/5 incrementales.

---

## 2026-06-14 tarde — Cierre y validación del bloque NOM ✅⚖️

- ✅ **Complementos del contrato MENSUALES** (€/mes, se guardan ×12). Base /N pagas,
  complementos /12; en pagas extra solo el salario base.
- ✅ **Objetivo → mejora al contrato (recurrente)**: "Proponer complemento" guarda
  la mejora como complemento mensual del contrato (anualiza en SS e IRPF).
  `solveTarget` reescrito por **bisección** (NETO no lineal). `recurringConcepts`
  reemplazan al concepto del contrato del mismo nombre (sin doble conteo). Lote idem.
  Endpoint POST `/api/labor/contracts/recurring-complement`.
- ✅ **Prorrateo por contrato** (V112): `extras_prorated`; casilla en el editor de
  contrato; en calcular nómina la casilla se ajusta al contrato del empleado.
- ✅ **Recibo de paga extra**: sin cuotas SS ni prorrata (solo MONTHLY genera TC);
  devengo "Paga extra de verano/Navidad"; período lo indica; nombre del PDF por
  tipo (`nomina-extra-verano-…`). FECHA ALTA cae a inicio del contrato si falta
  hire_date. Filas de relleno del recibo sin borde; zona conceptos mín. 14 filas.
- ✅ **Persistencia de sub-tab Labor**: tras cualquier acción se vuelve a la pestaña
  activa (no salta a Empleados).
- ✅ **IRPF VALIDADO contra calculadora AEAT 2026** (algoritmo oficial 26-12-2025).
  Caso Marcos (sit.3, 3 hijos, hipoteca) clava 8,62 %. Correcciones (V113):
  reducción **+2 descendientes 600 €** (el desfase), RNT art.20 = retrib − cotiz.,
  3er tramo art.20, truncado del tipo y de la minoración (como AEAT).
- ✅ **Editor UI de mínimos/reducciones IRPF por año** (pestaña Parámetros IRPF →
  "Mínimos y reducciones"). Con clonar año + editar escala, el sistema queda
  **100 % no-code para 2027**.

---

## 2026-06-13 — Bloque NOM (ciclo mensual de nómina) ⚖️

Retomadas las decisiones aparcadas de la tarea #43 (Payrolls UI). Análisis
conjunto Benjamin + Claude barriendo internet (Orden PJC/297/2026, ejemplos
de asiento de Sage/Cegid/Wolters Kluwer/Billin).

**Decisiones cerradas (2026-06-13):**
1. **SS a cargo de la empresa** → se lee enlazando la tabla de cuotas TC
   (`social_security_contributions`); la nómina la alimenta (es su propósito
   documentado).
2. **Asiento contable** → dos asientos: devengo (al calcular) + pago (al
   marcar pagada). Estructura PGC 640/642 → 476/4751/465 y 465 → 572.
3. **AT/EP (accidentes de trabajo)** → tipo **por contrato** (varía por CNAE).
   Columna `at_ep_percent` en `employment_contracts`, default 1,50 %.

**Implementado:**
- ✅ **NOM-1** — `PayslipService.calculate()` calcula el desglose SS 2026 y
  hace upsert de las filas TC (EMPLOYEE_* / EMPLOYER_*) del periodo. Solo
  toca filas DRAFT (respeta FILED/PAID). Solo nóminas MONTHLY.
- ✅ **NOM-2** — fix SS trabajador **6,35 % → 6,50 %** (faltaba el MEI 2026).
- ✅ **NOM-3** — `PayslipJournalEntryService` (clon del de ventas): asiento de
  devengo, leyendo la SS empresa (642) y el acreedor TGSS (476) de las cuotas
  TC. Idempotente ante recálculos (borra el DRAFT previo).
- ✅ **NOM-4** — asiento de pago (465 → 572) al marcar pagada; reversión de
  ambos asientos al borrar la nómina.
- ✅ **NOM-5** — `at_ep_percent` cableado de punta a punta (migración V106 +
  EmploymentContractService + ContractEntry/LaborApiClient + ambos editores
  de contrato en la UI, con i18n ES/EN).

**Limitaciones honestas (documentadas en el javadoc):**
- Base de cotización = bruto (sin topes mín/máx por categoría TGSS).
- Desempleo/AT a tipos de indefinido; las pagas extra (EXTRA_*) no generan
  asiento todavía (cotizan prorrateadas — slice futuro).
- "Otras deducciones" (embargos/anticipos) quedan fuera del asiento MVP.

**Pendiente / próximo:** mostrar SS empresa + enlace al asiento en la pestaña
Nóminas; afinar topes de cotización; pagas extra.

---

## 🗺️ Leyenda visual

| Marcador | Significado |
|---|---|
| ✅ | Hecho — cerrado, commiteado, mergeado |
| 🔵 | Parcial / skeleton — funciona pero falta UX o features |
| ⬜ | Pendiente — atacable |
| ⏸ | Aplazado conscientemente |
| ❓ | Decisión de Benjamin pendiente |
| ❌ | Descartado por Benjamin |
| 🔴 | Crítico (legalidad, seguridad, bloqueo) |
| 🟠 | Alta prioridad |
| 🟡 | Media prioridad |
| 🟢 | Baja prioridad |
| ⚖️ | Obligación legal |
| 💰 | Diferencial de valor vs competencia |
| ⭐ | Próximo en pelear |

---

## 📊 Resumen ejecutivo

- **Cerrado a fecha de hoy**: bloque VeriFactu / Facturación + Contabilidad PGC PYMES completo + RD 8/2019 fichaje legal + Asesoría↔cliente con sidebar dual y módulo Comunicación + **EQUIPO S1** + exports verificables (audit + SIF + fichajes) + bloque CTR contratos completo (CTR-1..7) + PORT-1..5 + bloque TPB (facturación por tercero + Magic Link/revocación) + UIs autónomas (PANORAMA, BOE, Backup, Multi-allocation, Rec. bancaria, Cal. fiscal) + **bloque NOM (ciclo mensual de nómina con asientos)**.
- **🔴 Crítico abierto**: Modelos AEAT 100/180/200/411; VeriFactu estricto (XAdES + SOAP + alta SIF en sede AEAT) — todo **bloqueado por certificado FNMT real**; decisiones estructurales MOBILE-EMPLEADO + JORNADAS UI.
- **🟠 Alta abierta**: Reporte coste empresa/empleado, entrega nóminas con firma + incidencias, conectores DEHú/SS RED reales (necesitan certificado).
- **🟡 Media abierta**: Reconciliación ML "casi-iguales", régimen especial IVA/prorrata/criterio caja, dashboard widgets, CENTROS-MAP interactivo, OCR PDFs escaneados, VG-FULL-SCAN restante, workflow partes de día (ligado a app móvil).
- **🟢 Baja abierta**: Alertas de seguridad, Email personal OAuth, PWA (cubierto por MOBILE-EMPLEADO).

---

# ✅ HECHO — orden cronológico inverso (más reciente arriba)

## 📅 2026-06-13 — TPB-CLIENT-SETUP + navegación + i18n + pulido (sesión autónoma)

> Todo en `develop`, compila limpio. Pendiente prueba de Benjamin.

| Slice | Commits | Qué hace |
|---|---|---|
| ✅ **I18N-ENUMS** | `07e501b` | ~20 valores enum en bruto (COMPANY, RETA, MONTHLY, BANK_TRANSFER…) traducidos. Helpers `localizedEnum` + `localizeEnumCombo` + ~70 keys ES/EN. Auditado con 2 agentes Explore. |
| ✅ **TPB-CLIENT-SETUP F1** | `ddc515d` | El editor de factura del cliente sin receptores ofrece "Crear cliente" (alta receptor bajo la shadow company). Backend POST /api/customers-extended. |
| ✅ **TPB-CLIENT-SETUP F2** | `97e4347` | Sub-pestaña "Clientes" en la ficha del titular: crear/editar/listar su cartera de receptores. |
| ✅ **TPB-CLIENT-SETUP F3** | `5432f1a` | Sub-pestaña "Config. facturación" del titular: VERIFACTU/NO_VERIFACTU + series + textos + certificado bajo su tenant. |
| ✅ **NAV-CLIENT-BACK** | `3c886bd` | En modo cliente, "Nueva factura" ya no deja atrapado: "Volver"/"Cancelar"/tras emitir reconstruyen la pantalla del cliente con sus tabs. Auditado: era el único editor que reemplazaba el centro desde modo cliente. |
| ✅ **VG-FULL-SCAN-2** | `9b0b0a1` | Comparadores de ordenación añadidos en columnas numéricas/fecha restantes (contabilidad, facturación cliente, empleados, AEAT, portal nóminas, contratos, partes, calendario fiscal). Helper `addColSorted`. |

### 🔎 Hallazgos de la sesión — resueltos en NOM (2026-06-13)

- ✅ **Payrolls UI / asiento de nómina (#43)**: CERRADO en bloque NOM (ver arriba). Decisiones tomadas con Benjamin: SS empresa vía cuotas TC, 2 asientos (devengo+pago), AT/EP por contrato.
- 🟠 **Reporte coste empresa/empleado**: AHORA FACTIBLE — el bloque NOM ya escribe la SS empresa en `social_security_contributions` por empleado/periodo. Falta solo construir el informe (coste = bruto + Σ EMPLOYER_* cuotas TC, agrupado por empleado). Pendiente en Alta prioridad.
- ✅ **Backlog desactualizado**: pasada de marcado ✅ hecha en esta misma sesión (06-13). Mensajes/Documentos/Notificaciones, PANORAMA, CTR-3/5/6/7, Backup, Multi-allocation, Rec. bancaria, Cal. fiscal, BOE, GEO-FICHAR, REC-IGNORE → todos cerrados.

---

## 📅 2026-06-11 / 06-12 — Bloque TPB completo + UIs autónomas + Magic Link + i18n enums

> Sesión larga de 2 días. Migraciones nuevas: V96–V105. Todo en `develop`.

### Bloque TPB (facturación por tercero, RD 1619/2012 art. 5)

| Slice | Commits | Qué hace |
|---|---|---|
| ✅ **TPB-1** Acuerdo previo | `V96` | Tabla `third_party_billing_agreements`. Propuesta + estados PROPOSED/ACTIVE/REVOKED. Scope ventas/compras/modelos. PDF del acuerdo. |
| ✅ **TPB-2** Serie por tercero | `V97` | `invoice_series.expedited_by_company_id`. Serie TPB separada (art. 6.1.b). Auto-reparación en `findCurrent`. Endpoint `preview-next` para que el editor muestre la serie correcta en el banner. |
| ✅ **TPB-3** Aceptación factura-a-factura | `V98` | Estado `PENDING_CLIENT_APPROVAL`. El cliente vinculado aprueba/rechaza. Doble clic abre el editor para revisión/corrección. |
| ✅ **TPB-4** Marca AEAT Verifactu | `V99` | Campos `issued_by_third_party` en verifactu_registry. |
| ✅ **TPB firma con PIN** | varios | Modal "Define tu PIN" si el empresario no lo tiene antes de firmar. |
| ✅ **TPB offline-PDF BLOQUEADO** | `332bd69` | Flujo de subir PDF sin verificar → HTTP 410. Se prestaba a fraude (asesoría activaba sin firma real del cliente). Memoria guardada. |
| ✅ **TPB Magic Link + OTP** | `V104`, `9c2abb4` | Cliente sin cuenta firma desde el navegador: enlace por email + OTP de 6 dígitos. Página HTML pública servida por Spring. Evidencia legal (IP/UA/hora). eIDAS art. 25. |
| ✅ **TPB revocación cliente** | `V105`, `0f83a06` | Cliente sin cuenta revoca igual que firmó: email con enlace permanente + OTP al entrar. Protección de evidencia (no revoca si hay facturas sin PDF guardado). |
| ✅ **TPB live polling** | `bb81539` | Tab acuerdo se auto-actualiza cada 5s. Al firmar aparece el tab Facturación + KPIs en caliente; al revocar desaparece. |

### UIs autónomas sobre backend ya existente

| Slice | Commit | Qué hace |
|---|---|---|
| ✅ **PANORAMA-ASESORIA** | `546792c` | Dashboard asesoría: 5 KPIs cruzados de cartera. |
| ✅ **UI-BOE-ALERTS** | `bf2d644` | Pestaña Configuración → Alertas BOE con barrido + abrir PDF oficial. |
| ✅ **UI-BACKUP-LOCAL** | `44d1be3` | Pestaña Copias de seguridad: tabla + hacer ahora + abrir carpeta. |
| ✅ **UI-MULTI-ALLOCATION** | `6f57cdb` | Modal "Cobrar varias": un pago reparte entre N facturas. |
| ✅ **UI-REC-BANCARIA** | `a7cb076` | Diálogo conciliación bancaria asistida (Levenshtein). |

### Fixes en vivo

| Fix | Commit | Qué hace |
|---|---|---|
| ✅ Iconos tabs Comunicación invisibles | — | Color inline #1e293b (CSS .font-icon los pisaba). |
| ✅ Banner TPB con nombre asesoría | `037137f` | Antes decía "Tu asesoría", ahora el nombre real. |
| ✅ Tab "Mi acuerdo facturación" empresario | `037137f` | Configuración del cliente para gestionar su TPB. |
| ✅ Botones barra facturación truncados | `3bc16aa` | Textos acortados + minWidth para que no se corten con "...". |
| ✅ Estado PENDING_CLIENT_APPROVAL traducido | `3bc16aa` | Faltaba en localizedInvoiceStatus. |
| ✅ Email cliente desde `customers.email` | `4200245` | El portfolio leía solo customer_contacts legacy → magic link iba al email viejo. |
| ✅ Magic link SMTP de la asesoría | `d7f8f81` | Buscaba SMTP del cliente (tenant header) en vez del de la asesoría. |
| ✅ Página magic link 500 + IP LAN | `b28a24f`, `6b592b0` | CSS con `%` rompía String.format; enlace usaba localhost (no accesible desde móvil) → IP de red local. |
| ✅ **I18N-ENUMS** | `07e501b` | Barrido con 2 agentes Explore: ~20 valores enum mostrados en bruto (COMPANY, RETA, MONTHLY, BANK_TRANSFER...) ahora traducidos. Helpers `localizedEnum` + `localizeEnumCombo` + ~70 keys ES/EN. |

---

## 📅 2026-06-10 noche — Sprint A+B SIF + fixes editor factura

| 🔴 Slice | Commit | Qué hace |
|---|---|---|
| **Editor factura — dirección cliente debajo del combo** | `a168c32` | El editor mostraba solo NIF/email/teléfono. Ampliado `CustomerResponse` y `CustomerSummary` con address/city/province/postalCode/country. SELECTs con COALESCE y email/phone con doble fallback a `customer_contacts` legacy. `refreshClientDetail` pinta dirección + país. |
| 🔴 **Sprint A — Reset cadena SIF legacy** | `1a63d3c` | Endpoint `DELETE /api/billing/sif-events/legacy-chain` bloqueado para empresas en VERIFACTU (Orden HAC/1177/2024). UI bloque en Configuración → Auditoría. |
| 🔴 **Sprint B — SIF-SCHEDULER-LOCKS** | `1a63d3c` | `SifEventService.record/recordForCompany` ahora con `@Transactional(REQUIRES_NEW)`. Reduce contención con `AuditChainService` FOR UPDATE. |

## 📅 2026-06-10 tarde — feedback en vivo + refinamientos (10 slices)

| Slice | Commit | Qué hace |
|---|---|---|
| Combo Política i18n + CAL-A v2 keyword matching | `8d98643` | StringConverter en geo_policy + matching tolerante por keyword en event_type Agenda. |
| Importar PDFs solo asesoría-actuando-por-cliente | `8d98643` | `importSalesPdfsBtn` oculto si appMode != ADVISORY o !actingForClient. |
| Revisar candidatos recurrentes con Button visible | `8d98643` | Hyperlink → Button con icono `fas-search` + `button-primary`. Banner se oculta si no hay candidatos. |
| Sub-tab renombrada "Jornadas" | `8d98643` | Quitado "Nuevo parte" (vendrá de app móvil). Sección "Plantillas — próximamente" + "Partes reportados" solo lectura. |
| **Fix V90 work_logs ALTER aditivo** | `eae26d1` | V2 ya tenía `work_logs` con esquema viejo. V86 CREATE IF NOT EXISTS se la saltó. V90 ADD COLUMN log_date/minutes_worked/is_billable/billable_amount/status + sincronización registros viejos. |
| **Fix UserSettingsService columnas dropeadas** | `eae26d1` | V87 dropeó language/ai_enabled/avatar_path/workday_template pero service seguía haciendo SELECT. Simplificado a 2 campos (pin_timeout_min + screensaver_style). |
| **Fix editor cliente: combo Tipo i18n + dirección postal + labels sin truncar** | `eae26d1` | StringConverter con `t("cli.type.*")`. ColumnConstraints minWidth=140 + Hgrow.ALWAYS. Sección "Dirección postal" añadida. |
| **Fix PDF factura — bloque cliente con dirección** | `eae26d1` | `InvoicePdfGenerator` inyecta `JdbcTemplate` + `loadCustomerView(customerId)`. Pinta NIF + dirección + email/teléfono. |
| Fix V87 — quitar AFTER pin_hash + IF NOT EXISTS | `f993397`/`fecc723` | `user_accounts` no tenía `pin_hash`. ALTER idempotente. |
| 🔴 **CLAUDE.md sección 10.bis — NO ASUMIR** | `6358b1d` | Regla persistente tras 4 fallos por asumir sin verificar. |

## 📅 2026-06-10 mañana — autonomía total (18 slices)

| Slice | Commit | Qué hace |
|---|---|---|
| ⚖️ **TC-CAL — warning amarillo fichaje en festivo** | `89ea42c` | `findTodaysHolidayForEmployee` JOIN employees↔holidays. `PunchResult` con `HolidayWarning(name, type, scope)`. UI tarjeta amarilla. |
| 🔵 **PORT-2 skeleton work_logs embebido** | `c5bbbcb` | V86 (corregida por V90) 4 tablas + `WorkLogService` básico. |
| **PORT-4 CLI — rediseño editor cliente con TabPane** | `d5cfd05` | V85 ADD COLUMN address/city/province/postal_code/country/internal_code/default_mode/phone/email/website. `CustomerExtendedController` `/api/customers-extended`. |
| **PORT-3 PERFIL+LOCK — preferencias usuario + bloqueo PIN** | `0139e0a` | V84 `user_settings`. Lock screen con Timeline + Stage UNDECORATED. |
| **PORT-3 SUG — Módulo Sugerencias** | `74f42ae` | V83 tabla `suggestions` + módulo `suggestions` catalog. CRUD + modal alta. |
| **PORT-1 EMP-1..4 — Portal del empleado 4 tabs** | `9602e22` | V82 módulo `employee-portal`. *(Más tarde retirado del sidebar — futura app móvil.)* |
| **PORT-5 CAL-D — CAL-IMPORT-MODAL** | preexistente | Modal side-by-side editable con `HolidayPdfExtractor`. |
| **PORT-5 CAL-C — Cargar festivos nacionales** | `ebc8fa1` | Botón con Easter Meeus/Jones/Butcher. 10 festivos fijos. |
| **PORT-5 CAL-B — Quitar de Agenda** | `9da186d` | DELETE inverso a dump-to-agenda. Idempotente. |
| **PORT-5 CAL-A — Badge color event types** | `66ff009` | Variantes CSS `calendar-event-card-type--{holiday,work-adjustment,work-closure,general}`. |
| **FIX-T-LIMIT — extraer keys calendar.* a helper** | `66ff009` | `t()` excedía 64KB. |
| **V88 — reactivar módulo `shifts` para sub-tab Labor** | tarde | Reinsert + activación condicionada a labor activo. |
| **V89 — work_centers (port CONTENDO `centros_trabajo_180`)** | tarde | Tabla con lat/lng/radio_m + geo_policy CHECK + `employees.work_center_id`. Sub-tab "Centros". |
| **V87 — DROP columnas obsoletas + módulos + ADD logo_path + session_pin_hash** | `1493ed3` | Cleanup tras prueba en vivo. |
| **PORT-4 LOGO — logo empresa upload + storage + preview** | `aaa7e5a` | `CompanyLogoService` con auto-resize 400px + auto-compress <2MB. Guardado en `{root}/{companyId}/_brand/`. |
| **PORT-4 SESSION — pestaña Sesión + PIN + salvapantallas** | `6cc8ace` | `SessionPinService` BCrypt sobre `user_accounts.session_pin_hash`. Lock screen verifica con `/api/settings/session/pin/verify`. 4 salvapantallas (clock/logo/dark/carousel). |
| **VG-FULL-SCAN parcial — comparators** | `85c3388` | RetaBaseChangeEntry + ContractTemplate.salary. 25+ omisiones restantes documentadas. |

## 📅 2026-06-09 noche — cierre día (Benjamin presente)

| Slice | Commit | Qué hace |
|---|---|---|
| fix(workcal) — revert UI+backend a 71e0697 | `49e030d`/`410ccc3` | Revert quirúrgico por archivos tras bug que dejaba al empresario sin ver datos. |
| 🔴 **fix(config) — MariaDB 3307 (no 3306 de Pablo)** | `e98fbb1` | application.yml apuntaba a 3306 por defecto (BD de Pablo casi vacía). |
| ⚖️ **CAL-FIX 1-4 — Calendario laboral** | `064b6df` | DatePicker → TextField flexible CAL-IMPORT. Botones top bar. "Volcar a Agenda" + endpoint. V81 `employees.work_calendar_id`. |
| Humanizar event_type en Agenda | `8077226` | `humanizeCalendarEventType()` + back-compat. |

## 📅 2026-06-09 tarde — autonomía Benjamin fuera

| Slice | Commit | Qué hace |
|---|---|---|
| DatePicker parsea varios formatos | `1a43661` | `parseFlexibleDate` ISO + dd/MM/yyyy + dd/MM/yy + dd-MM-yyyy + d.M.yyyy. |
| ⚖️ **UI Bajas IT (MedicalLeave)** | `8fdcff1` | Pestaña Labor con tabla + diálogo. 38 keys i18n. |
| ⚖️ **UI Cotizaciones SS** | `50dee71` | Pestaña solo lectura con filtros. 32 keys i18n. |
| footer Cotizaciones SS con totales | `9aa61fb` | Mejora aditiva. |
| installFlexibleConverter para DatePickers sueltos | `4729eca` | Helper aplicado a 4 DatePickers de alto uso. |

## 📅 2026-06-09 mañana — autonomía total (17 commits)

- ✅ Bloque L3 work calendars cerrado: L3-2 service + controller, L3-4 UI tab "Calendario laboral".
- ✅ HolidaySeed2026 retirado, sustituido por flujo import PDF (BOJA/BOPV/DOGC).
- ✅ Modal CAL-IMPORT-MODAL con `HolidayPdfExtractor` (port `calendarioParser.v3.js` CONTENDO).

## 📅 2026-06-08 — Bloque CTR (Contratos) primeros 2 slices

| Slice | Qué hace |
|---|---|
| ⚖️ **CTR-1 — V74 catálogos SEPE + 25 convenios + tablas salariales** | WebSearch legal previo. 28 SEPE codes + 25 convenios PYMEs + 12 anexos built-in. |
| ⚖️ **CTR-2 — backend ContractCatalog + UI wizard 4 pasos** | Wizard SEPE/Convenio/Datos/Resumen + combo modelo PDF + 12 cláusulas. 45+ keys i18n. |
| **L4-4 Alta empleado con Acceso a la app + PIN** | `provisionAppAccess` / `revokeAppAccess`. PIN bcrypt. |
| **L4-5 Refactor Equipo → app_access=TRUE** | Filtro correcto sin OWNER huérfano. |
| **L4-6 V71 advisory_collaborations** | Asesoría↔asesoría con invite/accept/reject/revoke. |
| **L4-7 Tab Colaboradores en Equipo** | 4ª tab del módulo Equipo. |
| fix Flyway outOfOrder + UX errores humanos | `outOfOrderCustomizer` + `humanizeBackendError()`. |

## 📅 2026-06-07 — EQUIPO S1 + decisiones arquitectura

- ✅ 💰 **EQUIPO S1 — Reparto de clientes** *(antes marcado ⬜, confirmado HECHO en V66 + `ClientAssignment{Service,Controller,Repository}` + `showTeamModule` 4 tabs)*. Decisión: empleado → cartera de clientes (1:N). Email "Te han asignado al cliente X". **Diferencial real vs Holded/Quipu/A3/Sage**. S2 con permisos finos por módulo queda como deuda futura si se necesita.

## 📅 2026-06-06 — Bloque Contabilidad completo (35+ slices)

✅ **TODO cerrado en una sesión maratón + post-mortem 06-07**:

- ⚖️ V46 catálogo PGC PYMES (RD 1515/2007) sembrado por empresa.
- Asiento automático al validar (compras + ventas).
- `TerceroAccountResolverService` (port CONTENDO) BY_INDEX/BY_NIF.
- `ExpenseAccountClassifierService` + `IncomeAccountClassifierService`.
- `AccountingLearningService` + endpoint `/reclassify`.
- V56 `companies.tercero_account_length/mode` + UI.
- V57 `sales_invoices.concept/purchase_invoices.concept`.
- Asientos manuales con bloqueo periodo (ACC-MANUAL).
- ⚖️ Libro Diario + Mayor + Sumas y Saldos (ACC-BOOKS).
- Cuentas bancarias + movimientos + cobros/pagos (BANK-ACCOUNTS).
- Importación Norma 43 + CSV bancario + auto-conciliación (BANK-IMPORT).
- Préstamos + cuadro amortización (LOANS).
- Inmovilizado + amortización (ASSETS-ENTRIES).
- ✅ **Plantillas asiento manual (ACC-TEMPLATES)**.
- ⚖️ Balance situación + PyG (REPORTS-CONTABLES).
- Aprendizaje contable UI (ACC-LEARN-UI).
- Exportación contable Contasol/A3/Sage (EXPORT-CONTABLE) + EXT-IMPORT inversa.
- 💰 Motor recurrentes (cron) con 7 kinds.
- RefreshBus publish/subscribe central.
- V59 relax UK tax_identifier para shadow companies + start-management.
- V60 `journal_entries.source_pdf_path` + visor PDF reutilizable PDFBox.
- ⚖️ Modelos AEAT **347 + 390 + 190** (AEAT-EXTRAS).
- ✅ ⚖️ **YEAR-CLOSE — Cierre ejercicio con aplicación resultado**.

## 📅 2026-06-05 — Asesoría↔cliente + exports legales (15 slices)

- ✅ V41+V42 advisory_invitations con token base62 32 chars + estados.
- ✅ UNLINK-SYNC + REINVITE + POLLING-FIX + INSTANT-REFRESH + DEHU-POLLING.
- ✅ 💰 DUAL-SIDEBAR + V43 — "Mi empresa" vs "Mis clientes" en ADVISORY.
- ✅ EMP-USER-MAP — TimeClockService.resolveCurrentEmployee.
- ✅ ⚖️ TC-EXPORT — PDF/CSV verificable fichajes RD 8/2019.
- ✅ ⚖️ AUDIT-EXPORT — PDF/CSV verificable audit_events.
- ✅ ⚖️ AUDIT-CHAIN — V44 hash encadenado audit_events + collation fix MariaDB 11.4.

## 📅 2026-06-04 — PDF-EXTRACT v2 + RETA + ALTA

- ✅ PDF-EXTRACT v2 layout X/Y por span. Port `calendarioParser.v3.js`.
- ✅ PDF-TEMPLATES aprendizaje por NIF (V37 `supplier_extraction_templates`).
- ✅ PDF-AMAZON específico + PDF-MULTI multi-factura.
- ✅ TC-CFG tipos evento configurables + TC-AUDIT sub-tab + EMP-GEO opcional.
- ✅ Bloque L1 Employees + Contracts backend + UI.
- ✅ Bloque L2 RETA backend + UI completo.
- ✅ ⚖️ Modelo 130 IRPF + Modelo 303 IVA UI.
- ✅ ⚖️ Bloque C4 RD 8/2019: V21 + TimeClockService.punch + CSV publico + UI.
- ✅ ALTA-5 credenciales externas cifradas Jasypt (DEHú, SS RED, SILTRA).
- ✅ CERT-IMPORT certificado .p12 (V19) + UI.

## 📅 2026-06-03 — VeriFactu completo (cuerpo legal)

✅ **Bloque entero cerrado en una sesión** — cumple Orden HAC/1177/2024:

- ⚖️ V14 verifactu_registry + VerifactuHashService + hook en `validate` + `/verify`.
- ⚖️ V17 modality separada de mode (TEST/PROD).
- ⚖️ V18 `sif_event_registry` + 13 tipos evento + hooks SYSTEM_START/STOP/INVOICE_VALIDATED/VOIDED/SUMMARY_6H/ANOMALY_DETECTION.
- V19 `companies.invoice_storage_root` + InvoiceStorageService.
- ⚖️ VF3-QR oficial AEAT (zxing) + endpoints AEAT TEST y PROD.
- F-EMAIL (factura por email) + EmailSenderService.
- ⚖️ VF-SIGN MVP firma XML-DSig (Apache Santuario + BouncyCastle).
- ⚖️ VF-ANOMALY job 12h.
- VF4 reintento firma 10min batch=100.
- PROFORMA-FLOW (PDF sin QR ni huella).
- VF3-SOAP cliente AEAT (no probado contra AEAT real).
- ⚖️ VF-EVENTS-EXPORT (PDF/CSV verificable).

## 📅 2026-06-02 — F4 editor + F4b PDF + i18n

- ✅ F4 Editor de factura estilo CONTENDO.
- ✅ F4b PDF multipágina con OpenPDF.
- ✅ F5+ Configuración facturación.
- ✅ Command Palette Ctrl+K + atajos + navegación mouse BACK/FORWARD.
- ✅ ⚖️ Anulación con vínculo (`voidValidated` atómico).
- ✅ i18n pasada con lupa.

## 📅 2026-06-01 — F1 dominio facturas + F2/F3/F5 + seguridad

- ✅ F1 paquete dedicado `billing/invoices/`.
- ✅ V13 + verifactu/ shell.
- ✅ V15 + 6 textos legales + invoice_show_iban + migration_acknowledged_at.
- ✅ D1 V10/V11 unificación issuers→companies + customer_billing_profiles→customers.
- ✅ 🔴 Refactor WorkspaceRepository → `tenantContext.getCurrentCompanyId()`.
- ✅ 🔴 @RequiresRole + RoleInterceptor.
- ✅ ⚖️ Audit log activo.
- ✅ 🔴 Cifrado columnas sensibles con Jasypt.
- ✅ 🔴 Refresh token revocation V12.

## 📅 Sesiones C1-C4 (mayo–junio 06-01)

- ✅ C1 login real email/password con JWT + AuthSession + selector empresa.
- ✅ C3 Configuración MVP — V9 + Jasypt + 3 controllers `/api/settings/*` + UI TabPane.
- ✅ Issuer módulo (deprecado por D1).
- ✅ Infra modular: `module_catalog`, `company_modules`, `TenantContext`, `@RequiresModule`, interceptor 403.
- ✅ 🔴 Fix seguridad `CustomerRepository` filtra por `company_id`.

---

# 🔴 PENDIENTE — CRÍTICO

## ⚖️ Legal obligatorio

- ✅ ⚖️ **CTR-4 — PDF SEPE oficial firmable** *(cerrado — `ContractPdfGenerator` con modelo UNIFIED_2022 / BY_CODE)*.
- ⬜ ⚖️ **VF-SIGN-XADES-AEAT estricto** — ampliar `XmlSignerService` para producir XAdES-EPES estricto sobre XML canónico AEAT (XSD oficial). Incluye `SignaturePolicyIdentifier` + `SignedSignatureProperties` + `SigningCertificate`. Requiere FNMT real.
- ⬜ ⚖️ **VF3-SOAP afinado** — parseo real respuesta AEAT (Aceptado / AceptadoConErrores / Rechazado). Requiere FNMT real + alta SIF en sede AEAT.
- ⬜ ⚖️ **Obligaciones fabricante VeriFactu** — registro como SIF en sede AEAT + documento declaraciones responsables + página pública de cumplimiento. Atacar antes de despliegue comercial.
- ⬜ ⚖️ **Modelos AEAT 100 / 180 / 200 / 411** — WebSearch legal extenso + patrones casillas regex (`fiscal_casilla_patterns_180`, 69 patrones) + mapeo (`aeat_campo_mapeo_180`, 32 mapeos).

## 🔴 Decisiones bloqueantes

- ❓ **MOBILE-EMPLEADO — App móvil/tablet del empleado**. Los partes de día + Portal del empleado vivirán en una app que el empresario instala en el dispositivo del empleado. **Sin diseño técnico aún**: ¿Capacitor/Tauri? ¿React-native? ¿PWA? ¿Compartirá BD vía API REST?
- ❓ 💰 **PORT-2 JORNADAS — UI completa** *(decisión arquitectura ya tomada: embebido CONTENDO)*. Backend skeleton ya en V86+V88. **Necesito tu diseño UX**: ¿TabPane interno o sub-pestañas? ¿1 plantilla = N bloques + adjudicada a M empleados como CONTENDO?

---

# 🟠 PENDIENTE — ALTA PRIORIDAD

## 💰 UI asesoría↔cliente

- ✅ 💰 **Mensajes / Documentos / Notificaciones** — cerrado en módulo **Comunicación** (COMM-MOD/COMM-LINK, V77/V78). Timeline + upload multipart + badge no leídos. Solo visible si hay vínculo asesoría↔empresario.
- ✅ 💰 **Vista panorámica asesoría** — cerrado (PANORAMA-ASESORIA, `546792c`): 5 KPIs cruzados de cartera.

## Empleados / Nóminas

- ✅ ⚖️ **Payrolls — ciclo mensual** — cerrado en bloque NOM (calcular/pagar/PDF/email + asientos devengo/pago + SS empresa vía cuotas TC).
- ✅ 💰 **Reporte coste empresa por empleado** — cerrado (NOM-6, `aa61627`): pestaña "Coste empresa" en Labor con bruto anual + SS empresa + coste total por empleado y totales al pie.
- ⬜ **Entrega de nóminas con firma trabajador** — fecha + vía (acuse de recibo).
- ⬜ **Incidencias de nómina** — horas extra, bajas, complementos variables por periodo.
- ⬜ **Topes de cotización TGSS + pagas extra cotizadas** — afinar el cálculo NOM (hoy base = bruto sin topes; EXTRA_* sin asiento).
- ⬜ Revisión completa contratos + flujo alta del empleado.

## ⚖️ RD 8/2019 fichajes extensión

- ✅ **Geolocalización al fichar** — cerrado (GEO-FICHAR): verificación en `TimeClockService.punch` contra `work_centers` (lat/lng/radio_m + geo_policy).
- ⬜ **Sincronización offline batches** (kioskos sin red) — para cuando exista app móvil.

## ⚖️ Conectores externos reales

- ⬜ **Conector DEHú real** — falta job que descarga del servicio AEAT vía SOAP/REST con certificado.
- ⬜ **Conector SS RED / SILTRA real** — credenciales guardadas, falta envío real (AFI/CRA/DELT@/CRETA).

## CTR bloque restante

- ✅ **CTR-3 — Plantillas reutilizables** (`contract_templates`) — cerrado.
- ✅ **CTR-6 — Alertas vencimientos** — cerrado (cron + plazos prueba/temporal/anuales).
- ✅ **CTR-7 — Anexos** — cerrado (confidencialidad/no competencia/exclusividad).
- ✅ ⚖️ **CTR-5 — XML contrat@ SEPE oficial** — cerrado (`ContractXmlGenerator`).

---

# 🟡 PENDIENTE — MEDIA PRIORIDAD

## Compras / pagos / banco

- ✅ **Reconciliación bancaria asistida** — cerrado (REC-BANCARIA, Levenshtein "casi-iguales"). *Refinamiento ML adicional queda como mejora futura.*
- ✅ **Gastos recurrentes silenciados** — cerrado (REC-IGNORE, V91).
- ✅ **Multi-allocation pagos** — cerrado (un pago reparte entre N facturas).

## Fiscal afinado

- ✅ ⚖️ **Calendario fiscal con vencimientos** — cerrado (CAL-FISCAL, seed 303/130/111/190/347/390/200 + tabla próximos vencimientos).
- ⬜ ⚖️ **Régimen especial IVA, prorrata, criterio caja** — catálogo cuentas lo soporta pero no hay UI.
- ✅ **CONS-CIERRE** *(2026-06-15)* — nueva pestaña **"Cierre de ejercicio"** en el módulo Contabilidad (`AccountingScreen`): precalcular (ingresos/gastos/resultado + IS 25%), **previsualizar el asiento de regularización** (6x/7x→129) sin crear asiento, **cerrar** con aplicación del resultado (reservas/dividendos/pérdidas, cuadre en vivo + confirmación) y **reabrir**. Resuelve el hallazgo previo: el backend del cierre existía pero la UI no lo invocaba (los métodos year-close estaban muertos en `LaborApiClient`; movidos a `AccountingApiClient`). *Nota: "previsualizar regularización" requiere fila en `fiscal_years` (si falta, 404 manejado); precalcular/cerrar no.* Compila limpio.
- ⬜ **Consolidación empresas asociadas** — eliminación operaciones intragrupo. No urgente.

## UI/UX

- ⬜ **Dashboard widgets personalizables** — por usuario, activar/desactivar/reordenar.
- ✅ **Backup local automático** — cerrado (BACKUP-LOCAL semanal lunes 03:00 + panel Configuración).
- ⬜ **CENTROS-MAP** — mapa interactivo Leaflet+Nominatim en WebView para seleccionar lat/lng. *(El botón "Buscar coordenadas" con Nominatim ya está hecho — CENTROS-GEOCODE; falta solo el mapa visual.)*
- ✅ **REC-IGNORE** — cerrado (botón "Ignorar candidato recurrente", V91).
- ✅ Editor calendario event card "Editar"/"Eliminar" *(ya implementado — verificado 2026-06-15: `dayEventCard` tiene botones Editar (`showFormDialog("calendar", …)`) y Eliminar (`deleteCalendarEvent` → DELETE `/calendar/{id}`); backlog estaba desactualizado).
- ⬜ Auditar otros módulos viejos (customers detail, dashboard CRUDs).
- ✅ **VG-FULL-SCAN restante** *(2026-06-15)* — auditado con agente Explore (294 columnas). Añadido comparador a las 7 columnas numéricas/fecha que faltaban (TPB total, validez cert., multi-asignación fecha+importe, recurrentes importe+fecha). Las de tamaño de archivo (humanSize, unidades mezcladas KB/MB) se excluyen a propósito.
- ⬜ ❓ **OCR para PDFs escaneados** (Tess4J + Tesseract) — necesito decisión: instalar binario nativo.

## Workflow trabajos / Derivados PORT-2

- ⬜ **Partes de día con validación admin** — DRAFT → SUBMITTED → APPROVED → BILLED.
- ⬜ 💰 **Conversión work_log → línea sales_invoice** automática al cobrar. Setar `billed_invoice_line_id`.
- ⬜ **Fichajes sospechosos** — detección patrones anómalos.

## Calendario

- ⬜ Calendario laboral por empresa completo.
- ⬜ ❓ Integración Google Calendar bidireccional — necesito credenciales OAuth Google Cloud Console.
- ⬜ Importación masiva calendarios.

## Asesoría / multi-cliente

- ⬜ Permisos finos por sub-recurso (ej. `configuracion:write` sobre cliente concreto).
- ⬜ **EQUIPO S2 — permisos finos por (empleado, cliente, módulo)** — si la necesidad real aparece.

---

# 🟢 PENDIENTE — BAJA PRIORIDAD

- ⬜ Alertas de seguridad (`security_alerts_180`) — intentos login, accesos sospechosos.
- ✅ Análisis / Alertas BOE — cerrado (BOE-RSS diario + pantalla dedicada con apertura de PDF oficial).
- ⬜ Acceso PWA / móvil *(posiblemente cubierto por MOBILE-EMPLEADO)*.
- ⬜ Email personal via Google OAuth2 — a nivel usuario.

---

# ❌ DESCARTADOS POR BENJAMIN

- ❌ **AI Copilot** — descartado 06-10 tarde. NO se hará en BENJAGEST.
- ❌ **Mi perfil** (módulo) — eliminado V87. Consolidado en Configuración → Sesión.
- ❌ **Portal del empleado desktop** — eliminado V87. Migra a app móvil futura (MOBILE-EMPLEADO).
- ❌ **L3-3 seed festivos autonómicos hardcoded** — retirado 2026-06-09. Sustituido por flujo PDF (parser CONTENDO).
- ❌ **Sugerencias_180** — ya cerrado como módulo SUG (06-10 mañana, `74f42ae`).

---

# ❓ DECISIONES TUYAS PENDIENTES

| Decisión | Bloquea | Mi recomendación si decido yo |
|---|---|---|
| 🔴 App móvil empleado: stack técnico | MOBILE-EMPLEADO + PORT-2 partes reales | Capacitor (compartir Java backend vía REST) |
| 🔴 JORNADAS: modelo plantilla-bloques-asignación | PORT-2 UI completa | 1 plantilla = N bloques + adjudicada a M empleados (CONTENDO) |
| 🟡 OCR Tesseract | ⬜ OCR PDFs escaneados | Sí, instalar binario nativo. Hoy PDFs imagen rechazan con 422. |
| 🟡 CENTROS-MAP | ⬜ Mapa lat/lng | WebView + Leaflet + Nominatim (offline-friendly) |
| 🟡 Régimen especial IVA / prorrata / criterio caja | UI fiscal afinado | Modelar tras un caso real de cliente que lo necesite |
| ✅ Hechas | — | — |
| Nómina: SS empresa + asiento (NOM) | SS vía cuotas TC, 2 asientos, AT/EP por contrato | |
| Backup local: ruta + cron | semanal lunes 03:00 | |
| TC-CAL fichaje en festivo | warning amarillo, no bloqueo | |
| work_logs embebido vs separado | embebido CONTENDO | |
| Avatar usuario | = logo empresa | |
| AI Copilot | descartado | |
| Idioma | botón ES/EN en header | |

---

# 📚 Reglas de manejo del backlog

1. **Trabajo siempre desde `feat/Benjamin`**. Prueba local antes de commitear.
2. **Un commit cierra como mucho un item** (si cierra varios relacionados, listar todos en el mensaje).
3. **Marcar `✅` con hash + fecha** al cerrar. Nunca borrar lo cerrado.
4. **Tras commit + merge `--no-ff` a `develop`**: push a ambas ramas.
5. **Si aparece algo nuevo durante el trabajo**: añadirlo aquí en su cubo de prioridad.
6. **Antes de empezar sesión**: leer este fichero. Antes de cerrar sesión: actualizar.
7. **Regla 10.bis (CLAUDE.md)**: verificar código antes de tocar. No asumir.
8. **Tabla ✅ HECHO** se reordena por fecha (más reciente arriba). Tabla pendiente por prioridad.
