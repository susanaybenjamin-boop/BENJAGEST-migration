# Backlog operativo BENJAGEST

> **Última actualización:** 2026-07-09 (**MOD-130-FIX** — **bug fiscal real**: el modelo 130
> omitía el **5% de gastos de difícil justificación** (art. 30 Reglamento IRPF, tope 2.000€),
> cobrando de más a TODOS los autónomos. Detectado comparando con DOS declaraciones REALES
> de Benjamin (2026, 1T 827,04 y 2T 522,84); antes daba 870,57 / ~910. Arreglado en backend
> (`compute130` puro y testeable) y en la UI (display + guardado). **9 tests fiscales** (5
> del 130 con las cifras reales + 4 de la aritmética del IVA 303/390). Verificado en vivo:
> el endpoint aplica el 5%. Model130View enriquecido (rendimiento neto, cuota, 5%). Ver
> sesión 2026-07-09 debajo. **NO liberado aún** (queda en develop; próximo release lo
> incluye). Histórico previo:
> **Última actualización:** 2026-07-08 noche (**RELEASE v0.1.11** — empaqueta los cuatro
> bloques legales de la auditoría en un `.msi`: **DR** (declaración responsable) + **LOCK**
> (bloqueo tras cierre fiscal) + **RECT** (rectificativas R1-R5, parcial, simplificada F2) +
> **RGPD** (cifrado datos sensibles, clave por instalación, control de acceso, auditoría de
> lecturas). MSI 302 MB, publicado en GitHub Releases como `latest` (draft:false,
> prerelease:false); tag apunta a develop `fc43c77`. Auto-update lo detecta.
> **Pendiente Benjamin:** actualizar la instalada (botón Buscar actualizaciones) y probar
> los 4 bloques. Histórico previo:
> **Última actualización:** 2026-07-08 tarde (**bloque RGPD** — **datos sensibles**, Fase B
> del plan de la auditoría: **clave maestra por instalación** (fichero protegido en
> %ProgramData%\BENJAGEST\secret con ACL; la de desarrollo queda solo como fallback de
> descifrado → lo cifrado antes sigue legible, verificado con el certificado FNMT real);
> **cifrado de columna** de datos de salud (notas de bajas médicas, motivo de ausencias) e
> **IBAN** de empleados y clientes (prefijo ENC:, rotación perezosa, sin migración big-bang;
> V169 ensancha las columnas); **control de acceso** (el rol EMPLOYEE ya NO puede listar
> nóminas ni bajas de terceros por los endpoints admin → 403; el empleado ve lo suyo por la
> PWA); **auditoría de lecturas** (SENSITIVE_DATA_READ). NO se cifran importes de nómina
> (se suman en SQL); se protegen con acceso+auditoría. Ver sesión 2026-07-08 tarde debajo.
> Histórico previo:
> **Última actualización:** 2026-07-08 (**bloque RECT** — **rectificativas R1-R5 + factura
> simplificada F2**, paso 3 del plan de la auditoría. Anular pide **causa legal** con
> selector (R1-R4, R5 automática en simplificadas); nuevo botón **Rectificar** (parcial:
> borrador con líneas en negativo → editor → validar, la original NO se anula); **factura
> simplificada** sin cliente con tope 400 € (art. 4.1 RD 1619/2012); el **TipoFactura de la
> huella VeriFactu** deja de ser F1 fijo (F1/F2/R1-R5, guardado al emitir en
> `verifactu_registry.invoice_type_code`, NULL histórico = F1 → cadenas antiguas intactas,
> verificado en vivo). V168 + serie SIMP + CHECKs de la auditoría. **Bonus**: fix de bug
> latente — empresas post-V16 (¡incluida la de Benjamin!) no tenían serie RECT y anular
> fallaba con 428; ahora las series reservadas PROF/RECT/SIMP se crean al vuelo.
> Ver sesión 2026-07-08 debajo. Histórico previo:
> **Última actualización:** 2026-07-07 noche (**bloque LOCK** — **cierre fiscal blindado**:
> guards `FiscalYearGuardService` en los 8 caminos de mutación que faltaban (crear/editar/
> validar factura de venta, registrar gasto, borrar asientos de ventas/compras/nómina/
> importados, regenerar asiento) + **3 fixes del cierre**: `reopen()` ahora sí devuelve
> `fiscal_years` a OPEN (antes reabrías y todo seguía bloqueado), cerrar = OWNER/ADMIN/
> ACCOUNTANT y reabrir = **solo OWNER** (antes cualquier EMPLOYEE), y auditoría
> FISCAL_YEAR_CLOSED/REOPENED con motivo. Verificado en vivo con ejercicio 2025 CLOSED
> temporal: 409 en todo lo fechado 2025, 201 con fecha de hoy, 403 reopen de empleado.
> Ver sesión 2026-07-07 noche debajo. Histórico previo:
> **Última actualización:** 2026-07-07 tarde (**bloque DR** — **Declaración responsable del
> fabricante** del SIF, RD 1007/2023 + Orden HAC/1177/2024 art. 15: datos **reales** del
> productor (Benjamín Recio López, NIF 74668351R — decisión de Benjamin en sesión), **versión
> dinámica** (la UI pasa `UpdateService.APP_VERSION`, se acabó el "0.1.0-SNAPSHOT" congelado),
> endpoints nuevos `/text` y `/pdf`, y **visible en Configuración → Acerca de** con el texto
> completo + botón "Descargar PDF" (sin exigir el módulo billing — la Orden pide que conste
> "de modo visible en el propio sistema en cada versión"). El diálogo previo de Facturación →
> Configuración ahora también muestra la versión real. **4 tests unitarios** fijan el
> contenido legal obligatorio (NIF, versión, citas normativas, compromiso). Verificado en
> vivo contra backend arrancado (texto + PDF 200 OK). Ver sesión 2026-07-07 tarde debajo.
> Histórico previo:
> **Última actualización:** 2026-07-07 (**bloque GAS** —Compras y Gastos: alta de
> gastos/recibos **SIN factura** (recibo de autónomo / cuota RETA) **eligiendo cuenta** (642),
> con o sin IVA; **registrar pago** (2º asiento, como CONTENDO); los recibos manuales entran
> **validados directos**; **sincronización asiento→gasto** al validar en "Por validar"
> (arregla un **descuadre latente**: asiento POSTED / gasto DRAFT); **recurrentes con cuenta
> fija** que aparecen en Compras y Gastos (casilla "Repetir cada mes" + selector de cuenta en
> "Hacer recurrente"). V166/V167. **Release v0.1.9** (+ **GAS-10** fix: el concepto del gasto
> recurrente expande comodines `{MES}`/`{YYYY}` → **v0.1.10**). Ver sesión 2026-07-07 debajo.
> **Pendiente Benjamin:** actualizar la instalada (botón Actualizar) y meter los 6 recibos RETA
> reales. Histórico previo:
> **Última actualización:** 2026-06-26/27 (**bloque GOOGLE-UNIFICADO** —login con Google +
> envío de correo por Gmail (OAuth) + **Google Calendar ↔ Agenda bidireccional**; modelo
> **híbrido** con credenciales **centrales** de la cuenta `benjagest2026` en
> `google-secrets.yml` (gitignored) + override per-instalación; tab **Integraciones fusionado
> en Correo**—; **bloque MIG** —migración: importar la última factura emitida, **OCR**
> autorellena, **troceador** del número (etiquetar Serie/Año/Número → plantilla
> `{CODE}-{YYYY}-{0000}`), crear la serie si no existe, guardar PDF de prueba + **declaración
> firmada**; V151—; y una **tanda de fixes** —salvapantallas a pantalla completa (logo grande +
> animación suave), **guard central anti doble-ejecución** en `start()`, **hora UTC→local** en
> tablas, **Lock wait timeout de auditoría** (escribir el evento *after-commit*), **purga de
> ficheros huérfanos** en Backups + `deleteCompanyFiles`—. Ver **🎯 RUTA DE CIERRE** y la sesión
> 2026-06-26/27 justo debajo. **Pendiente Benjamin (una vez):** verificar el proyecto Google
> central (quita el aviso "app no verificada"; necesita nombre de producto + dominio + web).
> Histórico previo:
> **Última actualización:** 2026-06-25/26 (**tres bloques "haz los tres"**:
> **CONTRATO-MODALIDADES** —desempleo según código SEPE del contrato (V143/V144,
> no-code); **formativos cuota fija CM-6** (V145); **intercambio contable**
> —round-trip import del JSON canónico—; **CICLO LABORAL completo CL-1→CL-4**
> —suspensión/excedencia + guarda en nómina (V146), atrasos retroactivos (15%
> IRPF tramos anteriores), cese de empresa en lote, y su **UI** (botones en
> Empleados: Suspensión, Atrasos, Cese de empresa con doble confirmación)—.
> **Aparcado**: xDiario/SUENLACE (sin fichero real). **Con Benjamin**: CL-5
> (recibo/asiento/L13 de atrasos). Ver sesiones 2026-06-25 debajo.
> Histórico previo:
> **Última actualización:** 2026-06-24 (**bloque REFLEJO COMPLETO 1-6** —reflejo
> bidireccional factura↔gasto + pago↔cobro, cascada de reversión, avisos por validar,
> interruptor on/off—; **mejoras de avisos** —banner "por validar" en la home (dos modos),
> el empresario no ve "asientos por validar", cartera **desglosada por cliente** con "Abrir
> cliente"—; y una **tanda de fixes de contraste** (texto blanco ilegible) que afectaban a
> cabeceras de tabla de contabilidad, banner AEAT, y **valores de KPI + datos del cliente**
> del resumen de actividad. Ver sesión 2026-06-24 debajo.
> Histórico previo:
> **Última actualización:** 2026-06-23 (módulo **Trabajos** cerrado: TRB-4 tarifas por
> cliente, trabajos del titular, formulario grande, importar trabajos desde Nueva factura,
> fixes `work_date`/botón Facturar/**3 bugs al facturar**; **ocultar borradores cancelados**;
> decisión legal **no "cobrar sin facturar"**; y **diseño + arranque del bloque REFLEJO**
> (factura emitida ⇒ gasto+asiento por validar en el cliente). Ver sesión 2026-06-23 debajo).
> Histórico previo:
> **Última actualización:** 2026-06-22 (sesión autónoma — Benjamin trabajando.
> Cerrado y mergeado a develop: **reparto guiado de pago parcial** en "Registrar pago
> de varias facturas" (pregunta qué factura completar) · **export PDF de Mayor + Sumas
> y Saldos** (cierra el bloque de informes contables en PDF) · **VIG-3 guard
> server-side** (no mover fecha inicio/antigüedad con nóminas) · **JOR-4 Planificado vs
> Real** (descriptivo + descuento de festivos) · **limpieza técnica** (gitignore del
> artefacto shade + borrado de código muerto AEAT filings). Ver sesión 2026-06-22
> justo debajo. Lo que queda necesita a Benjamin: motor de cálculo de nómina/contratos
> (§11.2), ficheros de referencia (FORMATS-EXCHANGE/AEAT 180), infra (push PWA) o
> decisiones de producto (partes de día, fichajes sospechosos)).
> Histórico previo:
> **Última actualización:** 2026-06-21 (GESTOR-NAVEGADOR en ventana aparte + login por
> certificado Fase 2 (importa el .p12 del cliente al almacén de Windows) + rediseño del
> módulo **Comunicación** + fixes cert .NET / 403 SSE / LOPD en logs; todo mergeado a
> develop. Revisión y reconciliación COMPLETA del backlog contra el código — ver
> "ESTADO Y PENDIENTE VERIFICADO" justo debajo).
> Histórico previo:
> **Última actualización:** 2026-06-20 (Portal del empleado MEMP-2..5 COMPLETO +
> firma de nóminas + notificaciones en tiempo real (SSE); ver sesión 2026-06-20).
> Histórico previo:
> **Última actualización:** 2026-06-16 (cerrada la **COLA AUTÓNOMA** salvo lo no
> [ver bloque de estado verificado justo debajo, 2026-06-17]
> prioritario; **topes de cotización SS por grupo** cableados 1+2+3 con cifras
> oficiales 2026; **auditoría completa del ciclo de vida del empleado** con 4
> agentes — contabilidad confirmada correcta, bugs triados; **investigación legal
> del ascenso** → bloque **CONTRATO-VIGENCIAS** decidido. Lo pendiente del bloque
> nómina queda como **PRIORIDAD 1** abajo).
>
> **Forma de trabajo (junio 2026):** Benjamin lidera y decide. Pablo solo entra de uvas a peras desde 05-30. Todo el trabajo va por `feat/Benjamin` → prueba local → commit → merge `--no-ff` a `develop`. Cada item cerrado lleva commit hash + fecha. **Regla 10.bis de CLAUDE.md aplica siempre: verificar código antes de tocar.**
>
> **Fuentes complementarias:** [`gap-analysis-contendo.md`](gap-analysis-contendo.md), [`gap-analysis-config-ui.md`](gap-analysis-config-ui.md), [`migration-roadmap.md`](migration-roadmap.md), [`vf-chain-fix.md`](vf-chain-fix.md), [`agents-debug-pattern.md`](agents-debug-pattern.md).

---

## 🎯 RUTA DE CIERRE — qué queda para cerrar el producto (2026-06-27)

> Esquema de lo que falta para dejar BENJAGEST listo para usar/vender. El detalle por ítem está
> en las secciones 🔴/🟠/🟡/🟢 del final. Orden = lo que bloquea producción primero. Las Fases
> 1-2 dependen de **material/decisiones externas** (FNMT, alta AEAT, dominio, credenciales
> reales); las Fases 3-5 se pueden cerrar con el código actual.

### 🟢 ESTADO REAL AL CERRAR LA SESIÓN (2026-06-29 noche)

Tras la tanda larga del 29-jun, lo que **antes estaba pendiente y AHORA está HECHO**:

- ✅ **CONSOL (consolidación contable intragrupo, 1-4)** — grupos de empresas + balance agregado +
  eliminaciones intragrupo (430/400 recíprocos) + informe PDF. Útil en modo asesoría y empresario.
- ✅ **OFFLINE (fichaje sin red, OFF-1..4)** — sync idempotente (`client_uuid`, sello offline) en
  **kiosco** y **PWA del empleado**; origen visible en Auditoría. *(OFF-3b Service Worker app-shell:
  **HECHO en la PWA del empleado** — `EmployeeAppService` sirve `/sw.js`, cache `benjagest-empleado-v5`;
  corrección 2026-06-30. Solo queda como refinamiento en la SPA del kiosco, que usa cola en localStorage.)*
- ✅ **AUTO-UPDATE** — `UpdateService` consulta GitHub Releases (repo público), descarga e instala
  el `.msi` encima (major-upgrade). **Probado en real 0.1.0→0.1.1→0.1.2.** Botón en Config + chequeo al arrancar.
- ✅ **Instalable .msi** autocontenido (UI+backend+MariaDB embebida+OCR) con **icono propio redondo**.
- ✅ **Fichajes sospechosos (FICHA-REVIEW)** — Plan-vs-Real marca incidencias + "Dar por bueno"/"Quitar revisado".
- ✅ **Calendario laboral** subido a 1ª de "Tiempo" (visible) + **mejoras de agenda** (flechas ◀▶/Hoy, nombres de evento en celda, máscara de fecha en el import).
- ✅ **tipo_iva por cliente** aplicado en Nueva factura · **"Clientes"** y **"Grupos"** en el sidebar.

**⏳ LO QUE QUEDA DE VERDAD** (Benjamin: "túnel + VeriFactu y poco más" → casi exacto):
1. **VeriFactu real ante la AEAT** (Fase 1, ⏸️ aparcada esperando a Pablo) — el grande legal: certificado FNMT + alta SIF + XAdES estricto.
2. **Cloudflare Tunnel** del portal del empleado (Fase 5) — para fichar desde fuera de la oficina; Benjamin crea la cuenta/túnel.
3. **Verificación del proyecto Google** (Fase 1) — quita el aviso "app no verificada"; necesita dominio + web (privacidad/términos).
4. **Conectores reales** (Fase 2): DEHú real + SS RED/SILTRA real — envío con certificado; necesitan credenciales reales.
5. **Modelos AEAT 100/180/200/411** (Fase 1) — investigación legal + mapeo de casillas.
6. **Afinado menor** (con Benjamin, no bloqueante): CL-5 atrasos (recibo/asiento/L13), versionar complementos salariales en vigencias, JOR-4 refinos, CONSOL-3b (P&G intragrupo), CONSOL-5 (NOFCAC minoritarios). *(El asiento de pagas extra `EXTRA_*` y el modelo de VIGENCIAS/ascenso YA están hechos — ver corrección 2026-06-30 en Fase 3.)*

> En una frase: **el producto está funcionalmente completo y desplegable/auto-actualizable**. Lo que
> queda es **legal (VeriFactu, modelos AEAT, Google) + operativo externo (túnel, conectores con
> credenciales reales) + afinado**. Nada de eso bloquea usarlo en NO_VERIFACTU on-premise hoy.

### ✅ RECONCILIACIÓN VERIFICADA EN CÓDIGO (2026-06-29) — revisión que pidió Benjamin

Repaso punto por punto de lo que Benjamin reportó con el check en blanco. Estado **comprobado leyendo el código**, no de memoria:

- **Calendario laboral (importar PDF) — ✅ HECHO y disponible para el empresario.** `buildWorkCalendarTab()` tiene el botón **"Importar PDF"** (`workcal.btn.import_pdf`, primario) → `HolidayPdfExtractor` + `POST /api/labor/work-calendars/{id}/holidays/replace`. Backend `WorkCalendarController` con `@RequiresModule("labor")` + OWNER/ADMIN. **El problema era de NAVEGACIÓN, no de función**: está enterrado en **Personal → categoría "Tiempo" → sub-pestaña "Calendario laboral"** (5ª). *Mejora propuesta: subirlo de nivel o un acceso directo.*
- **Nómina incidencias (horas extra, ausencias, etc.) — ✅ BASE HECHA** (INC-1..4, 2026-06-22): `labor/incidencias/NominaIncidenciaService` + `V136__nomina_incidencias.sql`; afectan el cálculo de nómina (`PayslipService`, `OvertimeRatesService`). **Corrección 2026-06-30:** el asiento de **pagas extra `EXTRA_*` YA está hecho** (`createExtraProvision/Accrual/Payment`, cableados); este punto ya no es pendiente.
- **Bajas (IT) / vacaciones — ✅ en la UI** (`buildMedicalLeavesTab`, `buildVacationsTab`, `buildLeaveRequestsTab` en Personal → Ausencias). Backend IT cerrado 2026-06-09.
- **Fichajes sospechosos — ✅ HECHO DESPUÉS (FICHA-REVIEW, ver ESTADO arriba).** Auditoría + el
  informe Plan-vs-Real marca incidencias y ya tiene **"Dar por bueno"/"Quitar revisado"**.
- **Widgets personalizables — ❌ DESCARTADO (Benjamin 2026-06-29: "no lo vamos a hacer").** La **base** ya existía (DASH-CUSTOM: mostrar/ocultar secciones del inicio). **No se ampliará** (nada de reordenar). Se quita de pendientes. *(El código base se queda; es inocuo. Si quieres quitarlo del todo, dilo.)*
- **Consolidación intragrupo (empresas asociadas) — ✅ HECHO DESPUÉS (bloque CONSOL 1-4, ver ESTADO arriba).**
- **Sincronización offline de fichajes — ✅ HECHO DESPUÉS (bloque OFFLINE, ver ESTADO arriba).** El de
  **VeriFactu** offline NO (va con la Fase 1 aparcada). *(Recordatorio: PDF offline de TPB sigue BLOQUEADO — solo PIN_SESSION.)*

> **Resumen:** de lo que reportaste, **calendario laboral, incidencias base y bajas/vacaciones YA están** (el calendario solo estaba escondido). **Fichajes sospechosos** está a medias (auditoría sí, "validar" no). **Widgets** se descarta. **Consolidación intragrupo** y **offline** siguen pendientes de verdad.

### Fase 1 — Legal/fiscal para PRODUCIR (bloqueante antes de vender) ⚖️  ⏸️ APARCADA
> **APARCADA 2026-06-27** hasta que Benjamin hable con **Pablo** y le dé un repaso al proyecto.
- **VeriFactu real con AEAT**: XAdES-EPES estricto (`VF-SIGN-XADES-AEAT`) + parseo real de la
  respuesta SOAP (`VF3-SOAP`). Requiere **certificado FNMT real + alta SIF en sede AEAT**.
- **Obligaciones de fabricante VeriFactu**: registro como SIF + declaración responsable + página
  pública de cumplimiento (RD 1007/2023).
- **Verificación del proyecto Google** (PARTE B): quita el aviso "app no verificada" en
  login/Calendar. Requiere **nombre definitivo del producto + dominio + web** (privacidad +
  términos) → tarea de Benjamin; Claude redacta los textos legales.
- **Modelos AEAT 100/180/200/411**: investigación legal + patrones de casillas + mapeo.

### Fase 2 — Conectores externos reales 🔌
- **DEHú real**: job de descarga SOAP/REST con certificado.
- **SS RED / SILTRA real**: envío real (AFI/CRA/DELT@/CRETA) — las credenciales ya se guardan.

### Fase 3 — Afinado laboral/nómina ⚖️💰
> **CORRECCIÓN 2026-06-30 (reconciliación con código, 4 agentes):** dos ítems que
> figuraban aquí como pendientes **ya estaban implementados**. Se marcan ✅ abajo.
- ✅ **Incidencias de nómina** (horas extra, complementos variables) — **BASE HECHA** (INC-1..4,
  2026-06-22; `NominaIncidenciaService` + V136). Cableada al motor (`PayslipService` aplica
  COMPLEMENT/OVERTIME/ABSENCE/DEDUCTION).
- ✅ **Pagas extra con asiento (EXTRA_*)** — **HECHO** (verificado 2026-06-30):
  `PayslipJournalEntryService.createExtraProvision/createExtraAccrual/createExtraPayment`
  invocados desde `PayslipService.calculate()` y `markPaid()`, con flag de empresa
  `provision_extra_pay` (V126). *(Matiz cierto: las extra no cotizan aparte; su SS va
  prorrateada mes a mes. Eso es correcto, no un pendiente.)*
- ✅ **CONTRATO-VIGENCIAS / ascenso (novación)** — **HECHO** (verificado 2026-06-30):
  `V125__contract_vigencias.sql` + `EmploymentContractService.promote()` + endpoint
  `POST /api/labor/contracts/{id}/promote` + `resolveActiveContract` (deriva salario/grupo/
  categoría/IRPF por vigencia vigente a la fecha, sin tocar antigüedad) + UI "Ascender".
  **Queda solo** versionar `contract_salary_items` (complementos) — refinamiento menor.
- **Revisión de contratos + flujo de alta del empleado** — repaso pendiente con Benjamin
  (el modelo de vigencias ya está; falta versionar complementos salariales y revisar el alta).
- **CL-5** (recibo/asiento/L13 de atrasos) — con Benjamin. *(Hoy solo existe el `preview`
  de `BackPayService`; faltan recibo, asiento, liquidación complementaria L13 y atrasos sobre extras.)*
- **JOR-4**: excepciones por fecha + comparación planificado-vs-real (refinamiento). *(El plan-vs-real
  ya está + FICHA-REVIEW "dar por bueno"; queda solo el afinado de excepciones por fecha.)*
- ✅ **Sincronización offline de fichajes** (kioscos sin red) — **HECHO 2026-06-29** (bloque OFFLINE,
  kiosco + PWA).

### Fase 4 — Cierres menores y pulido 🧹  (revisión 2026-06-27)
- ✅ **REFLEJO banner "reflejada en {cliente}"** — **YA estaba hecho** (verificado 2026-06-27):
  columna `colReflejo` en el listado de facturación (`billingInvoicesTab`) con carga async de
  `/billing/invoices/reflections` → muestra "↪ {cliente}". El backlog estaba desactualizado.
- ✅ **Doble reflejo de cobro** — **idempotencia confirmada**: `reflectPayment` (paso 2) ignora
  si ya existe asiento `REFLECTED_PAYMENT` con el mismo `source_id`. El doble del log antiguo era
  un cobro registrado dos veces en pruebas (dos eventos = dos reflejos, correcto), no un fallo.
- ✅ **MIG-3** — cerrado 2026-06-27: botón "Ver migraciones guardadas" en Config → Facturación
  → diálogo con la tabla de baselines (serie/número/fecha/cliente/total/registrada) + "Ver PDF
  de prueba" (endpoint `/migration-baseline/{id}/evidence` + visor interno). *(Match de cliente
  contra la cartera = mejora futura menor.)*
- ✅ **OCR Tesseract** — cerrado 2026-06-27 (Benjamin OK al binario): tess4j + fallback en
  `PdfTextExtractor` (renderiza páginas + Tesseract spa+eng) cuando el PDF no trae texto.
  Degrada con gracia. **DESPLIEGUE**: el instalador debe incluir el binario de Tesseract +
  tessdata (spa/eng) y apuntar `TESSDATA_PREFIX`.
- ✅ **CENTROS-MAP** — cerrado por decisión 2026-06-27: el **geocode "Buscar coordenadas"** es el
  cierre. El **mapa visual descartado** (Benjamin: no meter WebKit/javafx-web en el instalador).
- ✅ **Régimen especial IVA (base)** — cerrado 2026-06-27: V153 `companies.vat_regime` +
  `prorrata_percent`; bloque "Régimen de IVA" (General/Prorrata/Criterio de caja) en Config →
  Facturación. *(Efecto fino en el cálculo = afinar con caso real.)*
- ✅ **Workflow trabajos** — ya hecho (DRAFT→APPROVED→BILLED + work_log→línea de factura).
- ✅ **Workflow SUBMITTED** (TRB-SUBMIT) — cerrado 2026-06-27: estado SUBMITTED
  (DRAFT→SUBMITTED→APPROVED→BILLED). **Portal empleado**: tile "Mis partes" + pantalla para
  anotar/listar/enviar/borrar (PWA, `/api/work-logs/mine`). **Admin** (Trabajos): filtro "Enviado"
  + botón Aprobar arreglado (DRAFT|SUBMITTED→APPROVED). Ciclo completo: empleado envía → admin
  aprueba → facturable.
- ✅ **Dashboard widgets personalizables (base)** — cerrado 2026-06-27 (DASH-CUSTOM): botón
  "Personalizar" en el inicio → mostrar/ocultar Indicadores/Accesos rápidos/Actividad/Panorama.
  Preferencia local por usuario. ❌ **NO se amplía** (Benjamin 2026-06-29: "no lo vamos a hacer").
  Lo de "reordenar" queda DESCARTADO, no pendiente.

> **Resumen Fase 4 (CERRADA AL 100% — 2026-06-27):** ✅ REFLEJO, MIG-3, Workflow trabajos +
> **SUBMITTED** (portal empleado), **OCR Tesseract**, **Régimen IVA base**, **Dashboard
> personalizable**; **CENTROS-MAP** cerrado por decisión (solo geocode). **Fase 4 completa.**
> *(Histórico: hasta esta sesión el SUBMITTED se consideraba pendiente porque requería un slice
> del portal del empleado; se construyó hoy.)*

### Fase 5 — Empaquetado y despliegue 📦
- ✅ **DEPLOY-PKG**: instalable Windows autocontenido (UI + backend + MariaDB embebida + OCR Tesseract)
  con icono propio. **HECHO y verificado 2026-06-29** (`.msi` por jpackage, arranca/reabre solo).
  - ⚠️ **PENDIENTE empaquetado (anotado 2026-06-30): incluir `gestor-navegador.jar` en el instalable.**
    La UI lanza el gestor-navegador como **proceso aparte** desde
    `gestor-navegador/target/gestor-navegador.jar` (fat-jar shade, `mvn -pl gestor-navegador package`).
    Hoy `build-msi.ps1`/`build-app-image.ps1` **NO lo copian** → en una instalación el botón "Gestor
    navegador" muestra "no está compilado". **Antes de la próxima subida de versión**: empaquetar ese
    jar dentro del `.msi` y que `gestorNavegadorJar()` (UI) lo localice también en la ruta instalada
    (hoy busca rutas relativas al repo + `-Dgestor.navegador.jar`). Bloquea que el gestor funcione en
    la app instalada, no en desarrollo.
- ✅ **AUTO-UPDATE**: comprobación + actualización in-app vía GitHub Releases. **HECHO y probado en
  real 2026-06-29** (0.1.0→0.1.1→0.1.2). `gh release create` + bump `UpdateService.APP_VERSION`.
- **Cloudflare Tunnel** para el portal del empleado (acceso externo). ← pendiente. **Estado y plan
  (verificado en código 2026-06-29):**
  - **Benjamin ya registró la cuenta `benjagest2026` en cloudflare.com** (2026-06-29). Falta montar el túnel.
  - ⚠️ **NO confundir con el cliente OAuth de Google** (eso es login/Calendar, `google-secrets.yml`,
    Fase 1). El túnel **no usa cliente OAuth**: usa un `cloudflared` + token de túnel en el PC servidor.
    Lo único "OAuth-like" es `cloudflared tunnel login` (autoriza la cuenta, baja un cert al servidor).
  - ✅ **BENJAGEST ya está preparado**: propiedad `benjagest.public-base-url` (Spring `@Value`) que usan
    `EmployeeAppService` (QR/enlaces del portal del empleado) y `TpbMagicLinkService`. El código ya
    contempla el buffering SSE de Cloudflare (fallback por sondeo en EmployeeAppService). → Cuando el
    túnel esté en marcha, solo hay que poner `benjagest.public-base-url=https://<hostname>`.
  - **Pasos:** (1) instalar `cloudflared` en el servidor + `cloudflared tunnel login`; (2) crear túnel
    + rutar un **hostname** → `http://localhost:8080`; (3) correr `cloudflared` como servicio Windows;
    (4) fijar `benjagest.public-base-url`.
  - **DECISIÓN PENDIENTE — dominio**: para una **URL fija** del empleado hace falta un **dominio** en
    Cloudflare (el `quick tunnel` da URL `*.trycloudflare.com` EFÍMERA, cambia cada arranque → no sirve).
    **Ese mismo dominio sirve para la verificación de Google (Fase 1)** → un dominio cierra los dos.
  - **Pendiente de Claude cuando Benjamin diga**: doc con comandos exactos + script de `cloudflared`
    como servicio + (opcional) campo en Configuración para la URL pública sin tocar ficheros.
- Rellenar credenciales Google centrales tras la verificación (Fase 1).

- 🔒 **LICENCIA / ANTI-COPIA (LIC-1..N) — PENDIENTE, sin código aún (decidido 2026-06-30).**
  **Problema verificado en código (2026-06-30):** hoy NO existe ningún control de licencia
  ni de instalación única. Cada instalación lleva su **propia MariaDB embebida** y arranca
  mirando solo `hasAccounts` ([AuthController.java:86] `/auth/bootstrap-status`); no hay
  servidor central que sepa que un usuario ya tiene instalación → **se puede instalar en
  N ordenadores** sin límite. Además el **tipo de cuenta (ADVISORY/BUSINESS) lo elige libre
  el usuario** en el combo de registro (`BenjagestUiApplication.showRegister`, ~línea 610) y
  el backend se fía sin validar (`RegisterService.createAccount`: `boolean advisory =
  "ADVISORY".equalsIgnoreCase(...)`) → **un empresario puede instalarse en modo asesoría**.
  Lo único restringido hoy es el **multi-puesto** (máx. 5 dispositivos, solo OWNER de
  asesoría — `DeviceTokenService.MAX_ACTIVE_DEVICES_PER_COMPANY=5`), pero es un límite
  *dentro de una instalación*, NO una licencia.

  **Modelo decidido (Benjamin 2026-06-30) — preparar estructura para DOS piezas que conviven:**
  1. **Fichero de licencia firmado, local y oculto.** Al activar, el programa guarda un
     **fichero de licencia firmado** en una **carpeta segura y oculta de Windows** (p. ej.
     `%ProgramData%\BENJAGEST\license\` o `%LOCALAPPDATA%` con atributo oculto/ACL
     restringida). El fichero lleva: **tipo** (ASESORÍA/EMPRESA), **nº de puestos/sublicencias**,
     **NIF/titular**, **fecha de emisión/caducidad** y va **firmado** (clave privada nuestra;
     el programa valida con la pública embebida → no se puede falsificar ni editar a mano).
  2. **Código ligado a la cuenta de acceso, leído en CADA arranque.** El fichero contiene un
     **código ligado a la cuenta del usuario** (no solo a la máquina). En cada arranque el
     programa **lee y valida** ese código contra la cuenta con la que se entra. Así
     **no se puede usar la instalación en otro ordenador a no ser que se pague** (el código
     de otro PC no casa con la cuenta / la licencia es de un puesto).
  3. **Estructura para servidor central (futuro).** Dejar el enganche para que, cuando haya
     conexión, un **servidor de licencias** pueda emitir/renovar/revocar y rellenar el fichero
     firmado. Mientras no exista el servidor, la activación es por fichero (offline-friendly);
     el servidor es la evolución natural para emitir y revocar de verdad.

  **Reglas de producto que esto debe imponer (hoy NO se imponen):**
  - **Una licencia por cuenta**; instalar en otro PC exige nueva licencia (pago).
  - **Solo ASESORÍA puede tener sublicencias de empleados/puestos**; EMPRESA no crea sublicencias.
  - **El modo ASESORÍA debe quedar bloqueado** tras una licencia de tipo ASESORÍA (no elegible
    libre en el combo de registro).

  **Pendiente Claude cuando Benjamin lo arranque:** diseñar el bloque LIC (formato del fichero
  firmado + algoritmo de firma/validación, ubicación+ocultación de la carpeta, lectura en
  arranque ligada a la cuenta, gating del combo ADVISORY, y el contrato del futuro servidor
  central). **Verificar antes de tocar auth/registro** (zona caliente, CLAUDE.md §11.2).

- ✅ **UIR — Troceado de la UI (refactor estructural) — CERRADO 2026-07-01.**
  > **Release `v0.1.3` publicado** (https://github.com/susanaybenjamin-boop/BENJAGEST-migration/releases/tag/v0.1.3)
  > con el `.msi` autocontenido (jpackage+WiX, backend+UI+MariaDB embebida+gestor-navegador+Tesseract).
  > `UpdateService.APP_VERSION` 0.1.2→0.1.3 (commit `123814b`); las instalaciones existentes lo reciben
  > vía auto-update. Antes del bump se corrió una **auditoría estática pre-release** (3 agentes en
  > paralelo: UI↔backend, backend↔BD/Flyway, integridad estructural del troceado) pedida por Benjamin
  > para no subir versión con nada roto — resultado: 0 llamadas UI→backend rotas en los 15 `*ApiClient.java`,
  > 0 mismatches columna/tabla en 161 migraciones Flyway, Host interfaces y wrappers del troceado intactos.
  > Único hallazgo real (deuda previa, no introducida hoy): 3 strings de login sin `t()` + una clave i18n
  > `settings.email.prompt.host` faltante — ambos corregidos (commit `8c3eb80`).
  >
  > **v0.1.4 (2026-07-01, mismo día) — icono nuevo + limpieza de 2 módulos fantasma.** Icono de la app
  > sustituido: emblema oscuro/bronce → diseño plano en la paleta real de BENJAGEST (azul `#2357f6` →
  > turquesa `#0aa6a6`, blanco, dorado `#f8d348`), gráfico de barras + flecha con volumen, más grande y
  > legible a 16-32px. Generador en `packaging/GenerateAppIcon.java`. **Módulo Sugerencias eliminado**
  > (per-tenant, sin consumidor real, campo "respuesta" nunca escribible). **Módulo Informes (`reports`)
  > eliminado** — nunca fue un módulo de informes: era el hueco genérico de `WorkspaceRepository` sin
  > pantalla propia, mostrando una tabla `notifications` de avisos manuales sin ninguna vía de escritura
  > real fuera de sí mismo (etiqueta interna `module.unit.alerts`). Los informes contables reales (Balance,
  > PyG, Libro Mayor, Sumas y Saldos) siguen intactos en Contabilidad; no dependían de esto. Tabla
  > `notifications` NO se borró (la sigue leyendo el dashboard de inicio, aunque quedará vacía al no existir
  > ya ninguna vía de escritura). V163 desactiva/borra `reports` de `module_catalog`/`company_modules`.
  > Commit `a92d9c3`, release [v0.1.4](https://github.com/susanaybenjamin-boop/BENJAGEST-migration/releases/tag/v0.1.4).
  > **HECHO y en develop:** NOM-1..11 (bloque Nómina entero → screens), **SM-PKG** (gestor-navegador dentro del
  > `.msi` + `locateGestorJar` ruta instalada), **SM-1** `CalendarScreen`, **SM-2a** `SettingsScreen` núcleo,
  > **SM-3** `ProfileScreen`, **SM-2b 7/7 COMPLETO** (owners/credenciales/auditoría/backup/BOE/mi-TPB/mi-asesoría),
  > **CommScreen** (módulo Comunicación completo). 2 bugs cerrados: resaltado sidebar (work-logs/pending-tasks no
  > fijaban `select(key)`) + título Calendario i18n (`data.title()`→`moduleTitle(data.module())` vía Host).
  > Monolito ~15.170 líneas.
  >
  > **1) COMUNICACIÓN → `CommScreen` + SM-2b 7/7 (Mi Asesoría) — ✅ CERRADO 2026-07-01.** Verificado en código
  >   (§10.bis) que `settingsMyAdvisoryTab` NO llamaba a `buildCommMessagesPane`/`buildCommDocumentsPane` — solo
  >   compartía rango de líneas en el God Object (los sub-tabs Mensajes/Documentos se habían movido al módulo
  >   Comunicación en 2026-06-11, comentario explícito en el propio código). `CommScreen` nuevo con
  >   showCommModule + CommRecipient + humanizeDocStatus + humanSize + buildCommMessagesPane/buildCommDocumentsPane
  >   (públicos). Shell wrapper `showCommModule()`→`commScreen().showCommModule()` (commit `b4fd3ca`).
  >   `settingsMyAdvisoryTab` movido a `SettingsScreen` (llamada directa desde `settingsView`); Host puentea
  >   `pollPendingInvitations()`/`refreshActiveModulesAndRender()`; se quitó `myAdvisoryTab()` del Host
  >   (commit `bc38bd1`). Compila, mergeado a `develop`.
  >
  > **2) SM-4 `AuthScreen` — ✅ CERRADO 2026-07-01, probado por Benjamin (PIN, email, Google, registro,
  >   verificación email, olvidar-equipo — todo OK).** Movido verbatim: showInitialScreen/showLogin/
  >   showEmailLogin/showRegister/showPairingScreen/showPinKeypad/showEmailVerification + handlers
  >   (login/doRegister/startGoogleLogin/startGoogleRegister/confirmForgetDevice) + helpers (field/
  >   pinKey/defaultDeviceName) a `AuthScreen.java`. **Corrección sobre el plan original** (verificado
  >   en código, sin otro caller): `passwordWithToggle` y `blankAny` se movieron TAMBIÉN a AuthScreen
  >   (no se quedaron puenteados en shell). `bringToFront` sí se quedó en el shell (lo usa también
  >   Configuración) y llega por `Host`. `AppBrand`/`createLogoMark()` pasaron a `public` para que
  >   AuthScreen pinte el logo. `handleLoginSuccess` sigue en el shell, por callback. NO se tocó
  >   `AuthService`/JWT/`AuthSession`. Commit `cca7a62`, merge `0ecbeec` a `develop`.
  >
  > **3) BUMP ÚNICO + RELEASE (al final, con Benjamin).** `UpdateService.APP_VERSION` 0.1.2→0.1.3 +
  >   `build-msi.ps1 -Version 0.1.3` + `gh release create v0.1.3` con el `.msi`. El gestor ya va dentro
  >   (SM-PKG). **Antes de compilar el `.msi`: auditoría estática backend-ui-BD** (pidió Benjamin
  >   2026-07-01) — verificar que no quede nada roto tras el troceado UIR completo antes de subir versión.
  >
  > **Patrón SM-2b probado (para el 7/7 y para Comm):** localizar rango del cluster (grep firmas) → PowerShell splice
  >   moviendo del shell a la Screen, **saltando utils compartidos que ya están en ScreenBase** (shortIso/shortId/
  >   blankToNullOrSelf/stripDiacritics/humanizeCalendarEventType) → hacer `public` la tab, quitar su método del Host,
  >   `settingsView` llamada directa, quitar impl del accessor → compilar e iterar imports/helpers (copiar los puros:
  >   textInput/settingsSection/passwordWithToggle/formGrid/addFormRow/tabLayout/humanSize/humanizeCode; Host-bridge
  >   los que tocan estado del shell). Reglas duras: fichero es **CRLF** (respetarlo al reescribir con PowerShell);
  >   el sandbox de PowerShell **bloquea literales con `//` o doble-asterisco inline** → esos textos van a un FICHERO
  >   y PowerShell lo lee, nunca en el comando. El bump NO se hace hasta el final (una sola actualización).
  > **BLOQUE ASESORÍA CERRADO 2026-06-30**: AS-1 `ClientCustomersScreen`, AS-2 `ClientConfigScreen`,
  > AS-3 `ClientSummaryScreen`, AS-4 `ClientSalesArchivedScreen`, AS-5 `ClientTpbAgreementScreen`,
  > AS-6 `ClientBillingScreen`. **AS-7 DESCARTADO** (decisión Benjamin): `buildClientDetailView` es el
  > orquestador (~15 tabs + TPB dinámico), se queda en el shell como `showBilling` — extraerlo sería un
  > Host de ~25 métodos de puro reenvío. Todo compila, en `develop`; versión SIN subir (UIR sube una vez
  > al terminar todo). **Bugs cerrados** (post-pruebas): Tipo↔Forma jurídica, shifts (módulo a operativa),
  > NIF en Mi gestión, TPB sin parpadeo, email visible, y **seguridad: vínculo por NIF no email** (V160).
  > Email resuelto vía Gmail (hotmail SMTP muerto por Microsoft). FAC-1..4 cerrado.
  > **FAC-4b HECHO (2026-06-30):** invoices tab → `BillingInvoicesScreen` con Host (clase anónima,
  > sin tocar visibilidad); −678 líneas; commit `2f7ce2b`, merged a `develop`. Wrapper
  > `billingInvoicesTab(list)` conservado (1 call site). `validateInvoiceFromList` NO se movió (compartido
  > con la ficha de cliente). Auto-refresh vía TOPIC_SALES intacto. Compila.
  > **FAC-4a HECHO (2026-06-30):** config tab → `BillingConfigScreen` con Host
  > (`refreshBillingConfig`/`showMigrationBaselines`/`showManufacturerDeclaration`); −1.060 líneas del
  > monolito; commit `ac24450`, merged a `develop`. Wrapper `billingConfigTab(...)` conservado (2 call
  > sites intactos). Compila; versión SIN subir (UIR sube una sola vez al final).
  > **Estado al pausar 2026-06-30 (sesión maratón):** monolito 30.447 líneas. HECHO y en `develop`:
  > FAC-2 (editor→`InvoiceEditorScreen`), FAC-1 (`BillingDialogsScreen`), FAC-3 (resuelto por FAC-2),
  > FAC-4 parcial (`VatRatesScreen` + `SifAuditScreen`). Además, fuera del troceado: V157 (fix validar
  > VeriFactu), bloque AGR (gate de acuerdo de facturación por tercero, backend+UI), desglose de IVA por
  > tipo en asientos + 303/390 desde la contabilidad (V158/V159), prefill 303/130, fix texto vencimiento
  > PDF, dedup declaraciones AEAT. Todo compila; nada a medias; versión SIN subir.

  Desmontar el God Object `BenjagestUiApplication.java` (~44.125 líneas, ~145 métodos de
  pantalla) en clases de pantalla independientes (`ui/screens/`), replicando el patrón ya
  probado (`AccountingScreen`, `ClientFinancialsScreen`). **Plan completo en**
  [`plan-ui-refactor.md`](plan-ui-refactor.md). Movimientos puros, sin cambiar comportamiento
  (no tocar CSS ni claves i18n). Un dominio por commit; compilar + arrancar + merge develop
  por slice. **La versión se sube una sola vez al terminar UIR-15** (actualización de seguridad
  única vía auto-update), no por slice.
  - **FASE 1 (andamiaje):** `[x]` UIR-1 i18n→`I18n` (HECHO 2026-06-30: −8.684 líneas del monolito;
    `i18n/I18n.java` + `model/Language.java`; 8.445=8.445 `case`, sin pérdida) · `[x]` UIR-2 tipos
    **transversales** (HECHO 2026-06-30: `model/AppMode`, `model/ModuleLink`, `model/PaletteAction`,
    `support/ThrowingRunnable|ConsolAction|WorkLogAction`; los *bundles* por pantalla —`BillingBundle`,
    `LaborBundle`, `TaxBundle`, `*Row`, `SalaryComplementsEditor`…— se difieren a Fase 3 para viajar
    con su pantalla) · `[x]` UIR-3 helpers **stateless** (HECHO 2026-06-30: `support/Icons`,
    `support/Formatters`, `support/Dialogs`; monolito delega; −63 líneas netas. **AppContext god-object
    DESCARTADO**: se mantiene el patrón de inyectar dependencias concretas por pantalla, mejor diseño).
  - **FASE 2:** `[ ]` UIR-4 Router (cortar llamadas `showX()→showY()`).
  - **FASE 3 (pantalla por pantalla, menor→mayor acoplamiento):** `[ ]` UIR-5 Login/Registro ·
    `[ ]` UIR-6 RETA/DEHú · `[ ]` UIR-7 Portal empleado · `[ ]` UIR-8 Sugerencias/Perfil/Equipo ·
    `[ ]` UIR-9 Fiscal · `[ ]` UIR-10 Calendario · `[ ]` UIR-11 Facturación/Compras/VeriFactu ·
    `[ ]` UIR-12 Configuración/Certificados · `[ ]` UIR-13 Asesoría/Consolidación/TPB ·
    `[ ]` UIR-14 Trabajos/Calendario laboral/Tablas año · `[ ]` UIR-15 Laboral/Nómina (el último).

---

## 📅 SESIÓN 2026-07-09 — MOD-130-FIX (bug del 5% en el modelo 130) + tests fiscales

> **Origen:** Benjamin aportó DOS declaraciones REALES suyas (modelo 130, 2026, 1T y 2T,
> estimación directa simplificada) como fixture. Al comparar con el cálculo de BENJAGEST
> salió un bug legal de bulto.

- `[x]` **El bug** — `AeatExtraModelsService.generate130` hacía `pago = (ingresos-gastos) ×
  20%`, saltándose el **5% de gastos de difícil justificación** (art. 30 Reglamento IRPF,
  tope 2.000€/año). Cobraba de más a todos los autónomos: en el 1T de Benjamin, 870,57 en
  vez de 827,04.
- `[x]` **Fix backend** — `compute130` extraído como método PURO (rendimiento previo → 5%
  con tope → rendimiento neto → cuota 20% → pago, 0 si negativo). Reproduce EXACTO las dos
  declaraciones (827,04 / 522,84). Constantes legales nombradas. `Model130View` enriquecido
  con gastosDificilJustificacion, rendimientoNeto y cuota.
- `[x]` **Fix UI** — el editor 130 recalculaba en 2 sitios (display + guardar) con la misma
  fórmula incompleta; nuevo `computeModel130` espejo del backend. El display muestra ahora
  rendimiento neto + cuota + pago.
- `[x]` **Tests** — 5 del 130 (2 con las cifras reales de Benjamin + tope 2.000 + trimestre
  negativo + retenciones) + 4 de la aritmética del IVA (deriveRepercutido / computeResultadoIva
  extraídos como puros). El 303 NO tiene fixture casilla-a-casilla: las capturas de Benjamin
  solo mostraban el resultado final (713,14 / 1.325,80) sin las bases.
- **Verificado en vivo:** el endpoint `/aeat/extras/130/2026/1/preview` aplica el 5% y
  devuelve rendimiento neto, cuota y pago.
- **Nota:** el 5% es de estimación directa SIMPLIFICADA (la de la mayoría de autónomos y la
  de Benjamin). La estimación directa NORMAL no lo lleva — si algún cliente estuviera en
  normal, sería un refinamiento futuro (hoy se asume simplificada).

---

## 📅 SESIÓN 2026-07-08 (tarde) — bloque RGPD (datos sensibles)

> **Origen:** Fase B del plan de la auditoría (el último rojo legal grande que es puro
> código). Un agente inventarió qué datos personales estaban en claro y cuáles NO se pueden
> cifrar por participar en agregaciones SQL.

- `[x]` **RGPD-1 infra cripto** — `MasterKeyResolver` (clave por instalación en
  %ProgramData%\BENJAGEST\secret\master.key + ACL icacls), `FallbackStringEncryptor`
  (rotación: cifra con la nueva, descifra con nueva→legacy), `FieldCipher` (cifrado de
  campo con prefijo ENC:, legacy en claro passthrough → sin migración big-bang). 4 tests.
- `[x]` **RGPD-2 cifrado columna** — `medical_leaves.notes` (salud, art. 9),
  `employee_leave_requests.reason`, `employees.iban`, `customers.iban`. V169 ensancha
  (iban VARCHAR(255), reason TEXT) porque el cifrado no cabía en VARCHAR(34). `leave_type`,
  fechas e importes quedan en claro (los usa nómina/calendario/SQL).
- `[x]` **RGPD-3 acceso + auditoría** — rol de clase de PayslipController y MedicalLeave
  Controller: EMPLOYEE fuera (veía nóminas/bajas de todos). El empleado ve lo suyo por la
  PWA. `SENSITIVE_DATA_READ` en list+PDF de nóminas y list+detalle de bajas.
- **Verificación en vivo** (datos de prueba borrados; BD real intacta): clave generada +
  ACL correcto · certificado FNMT real legible tras rotación · IBAN de empleado y nota
  clínica quedan ENC:… en BD y la API los descifra · round-trip OK tras V169 · EMPLOYEE →
  403 en payslips y medical-leaves · SENSITIVE_DATA_READ registrado.
- **Notas/pendientes RGPD (no bloqueantes, para cuando toque):**
  1. `issuers.iban`, `bank_accounts.iban` y `companies.iban` (cuentas PROPIAS de la
     empresa, no de terceros) siguen en claro — menor prioridad; el foco fue dato de
     tercero. `employee_leave_attachments.content` (partes médicos, LONGBLOB) tampoco se
     cifró aún.
  2. Importes de nómina cifrados = imposible sin refactor (SUM en cierre/reportes). Si se
     quisiera, tabla de agregados precalculados. Hoy: acceso+auditoría, suficiente.
  3. Falta lo NO-código de RGPD (Fase B del plan): política de privacidad, registro de
     actividades de tratamiento, procedimiento de derechos. Es papel, no toca a Claude.

---

## 📅 SESIÓN 2026-07-08 — bloque RECT (rectificativas R1-R5 + simplificada F2)

> **Origen:** paso 3 del plan de la auditoría. Decisiones de Benjamin: alcance completo
> (R1-R5 **y** F2 de una), y la anulación **pregunta siempre** la causa con selector.

- `[x]` **RECT-1 schema** — V168: `rectification_code`/`rectification_scope` en
  sales_invoices; `customer_id` NULL-able (F2); CHECKs (RECTIFYING⇒original,
  no-SIMPLIFIED⇒cliente); `invoice_type_code` en verifactu_registry; CHECK de series
  ampliado + semilla SIMP. **Gotcha**: la semilla chocó con `ck_invoice_series_kind` (V2)
  en el primer arranque; DDL MariaDB no transaccional → revertir parcial a mano y re-run.
- `[x]` **RECT-1b series al vuelo** — bug latente: empresas creadas tras V16 sin
  PROF/RECT (la de Benjamin misma: solo tenía FRA+TPB) → anular daba 428. Fallback
  `ensureReservedSeries` en `findActiveByKind` (patrón ensureTpbSeries).
- `[x]` **RECT-2 backend** — void con causa (R4 default, R5 auto en simplificadas);
  `rectifyPartial` + POST /rectify (borrador PARTIAL, líneas en negativo, original
  intacta, se admiten varias parciales); validate exige causa en RECTIFYING y aplica tope
  400 € en SIMPLIFIED; cascada VOIDED solo si scope≠PARTIAL (null=histórico=anulación).
- `[x]` **RECT-3 huella** — TipoFactura real (F1/F2/R1-R5) en el canonical; se persiste
  al emitir y la verificación usa el GUARDADO (patrón TPB-4). 3 tests de compatibilidad
  (tipo null == hash histórico byte a byte). `/verifactu-registry/verify` OK en vivo
  sobre la cadena real antes y después.
- `[x]` **RECT-4 UI** — diálogo de causa en Anular; botón Rectificar → editor; tipo
  simplificada en editor (cliente opcional) y filtro; i18n ES+EN completo.
- **Verificación en vivo** (borradores de prueba borrados; 0 documentos reales emitidos):
  simplificada 500 € → 400 · NORMAL sin cliente → 400 · simplificada 121 € sin cliente →
  201 · rectify sobre factura real → borrador R1/PARTIAL vinculado, original VALIDATED ·
  void de DRAFT → 409 · cadena VeriFactu íntegra tras todo.
- **Pendiente/nota:** el asiento de una rectificativa parcial validada sale por
  `createForSales` con importes en negativo (igual que la anulación) — revisar junto a la
  duda contable del bloque LOCK (nota 1 de la sesión 2026-07-07 noche) cuando Benjamin
  quiera sentarse con un caso real. El supuesto F2 de 3.000 € (art. 4.2) queda fuera
  adrede, con mensaje que lo explica.

---

## 📅 SESIÓN 2026-07-07 (noche) — bloque LOCK (bloqueo tras cierre fiscal)

> **Origen:** paso 2 del plan de la auditoría integral (tras el bloque DR). Un agente
> inventarió TODOS los caminos de mutación contable: el guard existía y estaba conectado
> en asientos manuales, pagar/validar/eliminar gastos, banco, inmovilizado y préstamos —
> pero ventas entera, crear gastos y los 4 caminos de borrado de asientos estaban sin
> proteger, y el cierre tenía 3 fallos propios.

- `[x]` **LOCK-1 ventas** — guard en `createDraft`/`updateDraft` (fallo temprano) y
  `validateInternal` (número fiscal + hash + asiento no nacen en año cerrado);
  `reverseForSales` comprueba cada asiento por `entry_date` antes de borrar (todo-o-nada);
  `regenerateForSales` bloqueado. Anulación de factura de año cerrado: el asiento del año
  cerrado SE CONSERVA (el guard 409 se absorbe a propósito) y la rectificativa con fecha
  de hoy contrarresta en el ejercicio corriente — el único camino legal.
- `[x]` **LOCK-2 compras/nómina/importados** — `save` de gasto con fecha en año cerrado →
  409; `reverseForPurchase` comprueba devengo + pagos; reversión de nómina con asiento
  POSTED en año cerrado → 409 (ni borrar ni contraasiento imposible de validar);
  `deleteImportedByIds` (único borrado sin check) ahora comprueba.
- `[x]` **LOCK-3 cierre robusto** — `reopen()` sincroniza `fiscal_years`→OPEN (bug: antes
  reabrías y todo seguía 409); roles por método (cerrar OWNER/ADMIN/ACCOUNTANT, reabrir
  SOLO OWNER — antes cualquier EMPLOYEE); auditoría `FISCAL_YEAR_CLOSED`/`REOPENED` con
  motivo.
- **Verificación en vivo** (ejercicio 2025 CLOSED temporal insertado y retirado; BD real
  intacta): venta 2025 → 409 · gasto 2025 → 409 · asiento manual 2025 → 409 (regresión
  guard previo) · reopen EMPLOYEE → 403 · reopen OWNER → 200 + fiscal_years OPEN + evento
  auditado · borrador fecha hoy → 201 y borrado limpio.
- **Notas para Benjamin (decisiones/pendientes):**
  1. ⚠️ **Duda contable en anulación (año ABIERTO)**: `voidValidated` BORRA el asiento de
     la original Y la rectificativa genera su asiento negativo → el Diario queda en -X en
     vez de 0. ¿Es lo querido (¿CONTENDO hacía esto?) o debería conservarse el asiento
     original + negativo (neto 0)? Revisar juntos con un caso real antes de tocar.
  2. Estado `LOCKED` (bloqueo provisional del asesor tras presentar el 303): el guard lo
     soporta, pero **no hay UI/endpoint que lo ponga**. Slice futuro pequeño.
  3. Test preexistente en rojo (sin relación con LOCK, verificado con stash):
     `InvoiceFieldsExtractorTotalsTest.losLlanos` — el OCR extrae `GR/10606` y el test
     espera `263274`. Arreglar parser o fixture.

---

## 📅 SESIÓN 2026-07-07 (tarde) — bloque DR (Declaración responsable del fabricante)

> **Origen:** auditoría integral del proyecto (4 agentes: backend / legal / UI+BD / docs)
> con veredicto y plan por fases para que BENJAGEST sea vendible como competidor legal de
> Sage/A3. Primer paso elegido: la declaración responsable, porque es obligación del
> **fabricante** ya vigente (multas de hasta 150.000 €/producto/ejercicio por software no
> conforme) y no depende de trámites externos. Contexto normativo verificado: plazos
> Verifactu prorrogados a 2027 (sociedades 1-ene, autónomos 1-jul), pero las obligaciones
> del fabricante aplican ya. Benjamin aclaró su rol: es albañil, no asesor — el módulo de
> presentación de modelos/colaborador social queda para cuando haya una asesoría cliente.

- `[x]` **DR-1 backend** — `ManufacturerDeclaration.current(version)`: datos reales del
  productor (decisión Benjamin: nombre + NIF en claro), versión dinámica pasada por la UI,
  texto de compromiso preciso (cumplimiento en modalidad NO VERI*FACTU; envío VERI*FACTU
  declarado como hoja de ruta). Nuevo `ManufacturerDeclarationPdfService` (texto plano y
  PDF salen del MISMO texto — no pueden divergir). Controller sin `@RequiresModule`
  (visible aunque billing esté desactivado) y con EMPLOYEE en roles; endpoints `/text`
  (pantalla) y `/pdf` (descarga con filename versionado).
- `[x]` **DR-1 tests** — `ManufacturerDeclarationPdfServiceTest` (4 tests): versión
  dinámica, fallback sin versión, contenido legal mínimo (NIF, producto, versión, citas
  RD/Orden, compromiso, fecha/lugar) y PDF real (`%PDF-`). Primer test de la capa legal.
- `[x]` **DR-2 UI** — Configuración → **Acerca de**: sección "Declaración responsable del
  fabricante" con el texto completo (TextArea solo-lectura, carga async) + botón
  "Descargar PDF" (visor interno vía nuevo puente `Host.showInternalPdfViewer`). i18n
  ES+EN completo (5 claves `settings.about.dr.*`). El diálogo existente de Facturación →
  Configuración ahora pasa `APP_VERSION` (antes mostraba el 0.1.0-SNAPSHOT congelado).
- `[x]` **DR-3 docs** — `docs/declaracion-responsable.md` (base legal, dónde vive en
  código, qué actualizar en cada release) + este backlog.
- **Verificación:** `mvn compile` OK · tests OK · backend arrancado y endpoints probados
  en vivo con JWT (texto completo correcto, PDF 2.150 bytes `%PDF-1.5`) · backend apagado.
- **Pendiente (siguiente sesión sugerida):** bloqueo de asientos/gastos tras cierre
  fiscal (paso 2 del plan de la auditoría — convierte otro rojo legal en verde sin
  dependencias externas).

---

## 📅 SESIÓN 2026-07-07 — bloque GAS (Compras y Gastos: recibo de autónomo / gastos manuales + recurrentes)

> **Origen (duda de Benjamin):** el titular RETA dado de alta en la asesoría no aparecía en
> modo empresario. **Diagnóstico (sin bug):** una sola BD y una sola empresa (Benjamín,
> `AUTONOMO`, `company_id` único); RETA es solo-asesoría por decisión previa (26-jun);
> "Titulares y administradores" (`company_owners`) ≠ perfil RETA (`reta_profiles`). De ahí
> salió el bloque de gastos/recibos, para que el empresario vea el RETA entre sus gastos.

**Bloque GAS (9 slices, en `develop`, release v0.1.9):** alta de gastos/recibos **SIN factura**
(p.ej. recibo de autónomo / cuota RETA) en Compras y Gastos, eligiendo cuenta, con pago manual
y opción recurrente.
- **GAS-1** (V166): `purchase_invoices.expense_account_code`. Si el gasto trae cuenta fija
  (642), el asiento la usa exacta y **salta la cascada**; sin IVA **no** crea línea 472.
- **GAS-2** (V167): `paid`/`paid_date`/`payment_account_code` + `createPaymentForPurchase`
  (asiento de pago Debe 400 proveedor / Haber 572 banco) + `POST /purchases/invoices/{id}/pay`.
- **GAS-3/4/5** (UI, `BenjagestUiApplication` + `PurchaseInvoiceApiClient`): botones **Nuevo
  gasto/recibo** (elige cuenta 6xx, con/sin IVA), **Recibo de autónomo** (642 + TGSS
  prefijados) y **Registrar pago** (fecha + banco 572).
- **GAS-6**: los gastos/recibos **manuales entran VALIDADOS directos** (POSTED con número);
  el flujo automático (PDF/cascada) sigue en "Por validar". Fix i18n `accounting.source_type.
  PURCHASE_PAYMENT` (salía la clave cruda en el Diario). `buildConcept` sin "Fra. -" en recibos
  sin nº de factura.
- **GAS-7**: **sincronización asiento→gasto** — al validar en "Por validar" un asiento
  `PURCHASE_INVOICE`, `ManualJournalEntryService.post` marca también el gasto POSTED (arregla
  **descuadre latente**). "Validado directo" pasa a depender de flag explícito
  `postJournalDirectly` (solo alta manual), no de tener cuenta fija. El recurrente PURCHASE
  **ya no anula** su asiento → aparece en "Por validar" con la cuenta fija.
- **GAS-8**: casilla **"Repetir cada mes"** en el diálogo de recibo → crea plantilla recurrente
  PURCHASE (payload con `expenseAccountCode`); genera un **GASTO** en Compras y Gastos cada mes.
- **GAS-9**: selector **"Cuenta gasto"** en el editor de **"Hacer recurrente"** (`showRecurringEditor`);
  antes no tenía → el recurrente salía sin cuenta (`expense_account_code=null` → cascada).

**Decisiones cerradas con Benjamin:**
- El recibo de autónomo se hace como **GASTO** (dos asientos: devengo 642→proveedor TGSS, pago
  proveedor→572), no como asiento suelto, para que el empresario lo vea en Compras y Gastos.
- Recurrente **sigue en borrador** (revisar el importe, que en RETA cambia con la base).
- **KPIs de gastos y modelos** (130/303/P&G) salen de la **contabilidad** (`SUM(debit)` de 6xx
  en POSTED, ver `SalesAndExpensesKpiService`), **no** de la lista de Compras → el recurrente
  cuenta aunque no sea fila en Compras. Confirmado con búsqueda: cuota RETA = gasto deducible
  100% (modelo 130, **por devengo**, sin factura; proveedor TGSS).
- El editor de asiento recurrente (`JOURNAL_ENTRY`) NO se usa para RETA: genera asiento suelto
  que no sale en Compras (esa lista lee `purchase_invoices`, no el Diario).

**Release:** bump `APP_VERSION` 0.1.8→0.1.9, merge `--no-ff` a develop (`d652f02`), `.msi`
(301 MB) y **`v0.1.9`** publicada como *latest* (auto-update). Push a develop funcionó directo
esta vez (histórico decía que lo bloqueaba el clasificador — parece intermitente).
- **GAS-10** (fix, tras probar): `runPurchase` expandía comodines en `invoiceNumber` pero **no
  en el concepto** → el gasto recurrente salía con `{MES} {YYYY}` literal. Ahora el concepto
  pasa por `expandPlaceholders`. Bump 0.1.9→**0.1.10**, merge a develop (`c62275d`), **`v0.1.10`**
  publicada como *latest*.

**Pendiente Benjamin:** actualizar la instalada (botón Actualizar) y meter los **6 recibos RETA
reales** (ene–abr 299,57; may–jun 328,11). **CONTENDO:** exportar/backup y cancelar
Supabase+Render (~60 €/mes) — decidido retirarlo; su sistema oficial es Excel + PDFs en iCloud.

**Ruido inofensivo (no tocado, decidido dejarlo):** `RealtimeService.heartbeat` loguea
`IOException: broken pipe` cuando un cliente SSE se desconecta — cosmético, solo al probar.

---

## 📅 SESIÓN 2026-07-04/05 — IMP-H (importación histórica CONTENDO)

> **Problema de Benjamin:** dado de alta como asesoría, importó el diario CSV de CONTENDO
> desde la gestión del cliente; decía "válido" pero no listaba nada. **Causa doble
> confirmada:** (1) el importador CSV esperaba cabeceras en inglés y `parseAmount` rompía
> los decimales con punto (`12.55`→`1255`), así que cada asiento iba al error_log; (2) los
> asientos se creaban DRAFT y el Diario filtra POSTED por defecto.

**Solución (bloque IMP-H, 5 slices, todos en `develop`):** importador dedicado "CONTENDO –
Diario histórico" que reconstruye asientos (POSTED), facturas de venta/gasto reales,
terceros (sin NIF) y cobros/pagos, con auto-refresco.
- **IMP-H1**: V164 (customers NIF opcional + unique por empresa), V165 (invoice_type
  HISTORICAL + formato CSV_CONTENDO + content_sha256). Blindaje solo-lectura de facturas
  HISTORICAL (no void, no PDF, no email).
- **IMP-H2**: `ContendoCsvParser` (clasifica venta/rectificativa/cobro/gasto/pago/otro) +
  `ManualJournalEntryService.createImportedPosted`. Test verde.
- **IMP-H3**: `ContendoImportService` pasada 1 (cuentas/terceros/asientos/facturas) +
  endpoint. **Línea roja:** nunca pasa por `SalesInvoiceService.validate()` ni toca
  VeriFactu/SIF/series/PDF.
- **IMP-H4**: pasada 2 (cobros→ventas, pagos→gastos vía `invoice_due_dates` PAID,
  rectificativa anula original).
- **IMP-H5**: UI (formato + diálogo resumen + RefreshBus + filtro Origen + i18n).

**Decisiones cerradas con Benjamin:** gastos sin IVA (SS/autónomo) → solo asiento, no
factura; rectificativa 0006R → original 0006 queda ANULADA + enlazada.

**Pendiente Benjamin (verificar):** reimportar su fichero real con el nuevo formato
(los intentos previos no guardaron nada → sin duplicados). Comprobar: 91 asientos POSTED,
8 facturas venta (1 rectificativa), cobros/gastos, y que `verifactu_registry`/`sif_events`
NO cambian.

**Deferidos (follow-up, no bloquean):**
- Deshacer/borrar un lote de importación (borrado físico con año OPEN).
- **Bug latente hallado de paso:** `ExternalImportService.importCsvParties`/`importJsonParties`
  INSERTan en columnas inexistentes (`customers(nif,email,...)` / `suppliers(nif,name,...)`)
  → todo import CSV/JSON de clientes/proveedores falla hoy en silencio. Arreglar aparte.
- UI para enriquecer NIF de terceros importados; cobros parciales múltiples; pagos a
  proveedor sin factura (TGSS 4000003) quedan como aviso (source_id colgante, cosmético).
- Variantes A3/SAGE del mismo pipeline; integrar con `invoice_migration_baseline` (V151).

---

## 📅 SESIÓN 2026-06-29 — Visibilidad calendario + FICHA-REVIEW + plan CONSOL/OFFLINE

> Tras la reconciliación del backlog, Benjamin eligió atacar 3 frentes. Decisiones:
> consolidación **contable real** (NOFCAC), grupo **definido por el usuario**, offline en
> **kiosco + PWA del empleado**.

- ✅ **Calendario laboral 1ª en "Tiempo"** (visibilidad; estaba enterrado).
- ✅ **FICHA-REVIEW** (fichajes sospechosos): V154 `time_clock_day_reviews`; Plan-vs-Real marca
  incidencias (⚠) + "Dar por bueno"/"Quitar revisado" (no toca fichajes, RD 8/2019). Verificado.

### 🧩 BLOQUE CONSOL — Consolidación contable intragrupo (real, NOFCAC). Multi-slice.
- ✅ **CONSOL-1** *(2026-06-29)* — **Grupo empresarial**: V155 `company_groups` +
  `company_group_members` (dueño = activeCompanyId). `CompanyGroupService` + UI (entrada "Grupos"
  en el sidebar para OWNER/ADMIN: crear/eliminar grupo, añadir/quitar empresas). Verificado.
- ✅ **CONSOL-2** *(2026-06-29)* — **Agregación**: `ConsolidationReportService.aggregatedTrialBalance`
  suma el balance de comprobación (saldos por cuenta) de las empresas del grupo a una fecha +
  resultado agregado. Endpoint `/trial-balance`. UI "Balance agregado".
- ✅ **CONSOL-3** *(2026-06-29)* — **Eliminaciones intragrupo**: subcuentas 430/400 cuyo
  `tercero_nif` es otra empresa del grupo → excluidas del consolidado y listadas como
  eliminaciones (con cuadre clientes≈proveedores). Endpoint `/consolidated`. UI "Consolidado".
  *Pendiente fino CONSOL-3b: eliminación de P&G intragrupo (ventas 70 vs compras 60, requiere
  trazar facturas REFLEJO).*
- ✅ **CONSOL-4** *(2026-06-29)* — **Informe PDF** del consolidado (`ConsolidationPdfService`,
  endpoint `/consolidated.pdf`, visor interno). *Ajustes manuales de consolidación = futuro.*
- **CONSOL-5** — Refinos NOFCAC (participaciones, socios externos/minoritarios) — con Benjamin.

> **CONSOL contable cerrado (1-4) el 2026-06-29.** Falta solo el refino P&G (3b) y NOFCAC (5).

### 📴 BLOQUE OFFLINE-FICHAJE — Fichaje sin red (kiosco + PWA). ✅ CERRADO 2026-06-29.
> Hecho SIN tocar `punch()` (núcleo legal intacto): camino aparte `syncPunch` que preserva el
> sello horario del momento offline + `client_uuid` para dedup. No había cadena hash por evento.
- ✅ **OFF-1** — `POST /…/fichaje/sync` idempotente: V156 `client_uuid` + índice único;
  `TimeClockService.syncPunch` (sello offline, dedup, no bloquea geo) + `insertEventWithUuid`/
  `existsClientUuid`. Verificado.
- ✅ **OFF-2** — **Kiosco offline** (SPA del kiosco): cola en localStorage + sync al arrancar y al
  volver la red; endpoint `/api/kiosk/fichaje/sync` (actor explícito, sin JWT).
- ✅ **OFF-3** — **PWA empleado offline**: `doPunch` encola si no hay red (client_uuid + eventTime),
  `flushQueue` sube el lote a `/empleado/fichaje/sync` al reconectar; indicador "N pendientes".
  *(Cola con localStorage; Service Worker para cachear el app-shell = refinamiento OFF-3b futuro.)*
- ✅ **OFF-4** — Orden y auditoría: el `event_time` real se preserva (el orden sale correcto en
  jornada/auditoría); origen `OFFLINE`/`KIOSK_OFFLINE` visible en la Auditoría (i18n ES+EN). Los
  conflictos (OUT sin IN) los marca la incidencia de auditoría + FICHA-REVIEW ("dar por bueno").

---

## 📅 SESIÓN 2026-06-26/27 — GOOGLE-UNIFICADO + MIG (migración facturación) + tanda de fixes

> Continuación tras el bloque REGISTRO. Todo compila (backend+ui) y mergeado a `develop`.

**Bloque GOOGLE-UNIFICADO** (modelo híbrido, decisión Benjamin 2026-06-26):
- ✅ **Login + envío de correo (Gmail) por OAuth** + **Google Calendar ↔ Agenda bidireccional**
  (`GoogleCalendarService.sync`: push de los eventos de la agenda → Google y pull → agenda;
  auto-refresh de la agenda al sincronizar).
- ✅ **Credenciales CENTRALES** (cuenta `benjagest2026`, cliente OAuth de escritorio) en
  `google-secrets.yml` **gitignored** (cargado por `spring.config.import`), con override
  per-instalación en Integraciones. `config()` devuelve enabled con las centrales → cero config.
- ✅ **Integraciones fusionado en el tab Correo**: el panel Google aparece al elegir proveedor
  Google; la config "mi propio proyecto Google" queda plegada como avanzado.
- ✅ Fix **HTTP 400** de Calendar (timeMin sin segundos → `Instant` UTC con segundos).
- ⏳ **PARTE B (Benjamin):** verificar el proyecto Google para quitar el aviso "app no verificada".

**Bloque MIG — migración de facturación (importar la última factura emitida):**
- ✅ **MIG-1** (V151 `invoice_migration_baseline`) — guarda la última factura como
  REFERENCIA+PRUEBA (no se contabiliza, no genera cadena SIF) + declaración firmada; fija
  `next_number` de la serie. `MigrationBaselineService` + controller + `setNextNumber`.
- ✅ **MIG-2** UI: importar PDF → **OCR** (reusa `PdfTextExtractor`+`InvoiceFieldsExtractor`)
  autorellena serie/número/fecha/cliente/total; serie **editable** (se crea si no existe).
- ✅ **MIG-2c troceador**: el usuario etiqueta cada parte del número (Serie/Año/Número/Fijo) →
  plantilla `{CODE}-{YYYY}-{0000}` → las próximas facturas salen idénticas (FRA-2026-0008). +
  campo Año confirmable (`current_year`).
- ⏳ **MIG-3** (opcional): listar baselines + visor PDF + match de cliente.

**Tanda de fixes:**
- ✅ **Salvapantallas** a pantalla completa real: logo grande (≈50% alto) + animación suave
  (EASE_BOTH + deriva), sin saltitos; cálculo del área de la pantalla del usuario antes del fondo.
- ✅ **Guard central anti doble-ejecución** en `start(task,name)` — ignora la 2ª tarea con la
  misma clave en vuelo (el doble clic en Guardar). Cubre ~348 botones de una vez.
- ✅ **Lock wait timeout de auditoría** (causa raíz real, no el doble clic): `audit_events` tiene
  FK a `companies`; el INSERT (REQUIRES_NEW, transacción aparte) pedía lock compartido sobre la
  fila que el `UPDATE companies` del guardado tenía en exclusiva → se esperaban 50 s. Fix:
  escribir el evento **after-commit** (`TransactionSynchronizationManager`) → sin contención y el
  evento se graba SIEMPRE (antes se perdía → hueco en la cadena).
- ✅ **Hora UTC→local** en tablas (`shortIso` convierte instantes con zona Z/offset) — los
  backups salían 2 h por debajo.
- ✅ **Purga de ficheros huérfanos** en Backups + `BackupService.deleteCompanyFiles` (al purgar
  empresas de prueba quedaban sus PDFs en disco → aparecían en los backups).
- ✅ Fix **V151 errno 150** (collation FK): las tablas referenciadas usan `utf8mb4_unicode_ci`,
  pero el default de MariaDB 11.4 es `utf8mb4_uca1400_ai_ci` → fijado `COLLATE` en la tabla.

---

## 📅 SESIÓN 2026-06-25 (tarde, autónoma) — "haz los tres" bloques

> Benjamin: "haz los tres" (intercambio contable + formativos + ciclo laboral).
> Todo compila y mergeado a `develop`, slice a slice. Criterio: construir lo que
> la ley dicta y es seguro; estructura + guardas + marcar lo que necesita su
> validación (§11.2). NO se inventan números ni formatos de fabricante.

**1. Intercambio contable** *(merge import JSON)* ✅
- **Import del JSON canónico BENJAGEST** — cierra el round-trip (el export ya lo
  producía, el import estaba sin implementar). Backup/restore + migrar entre
  instalaciones. Reutiliza `createDraft` (mismas validaciones). Sin adivinar nada.
- **xDiario (A3) / SUENLACE (ContaPlus)**: ⏸️ **APARCADOS hasta el final**
  (Benjamin 2026-06-25: no tiene los ficheros ni quien se los dé). Son
  posicionales de fabricante; sin muestra real las posiciones se adivinan y el
  fichero falla en silencio al importar. Se retoman si aparece un fichero real.

**2. Formativos — cuota fija (CM-6)** *(merge CM-6)* ✅
- Formación en alternancia (421/521) cotiza por **cuota fija** (no porcentajes).
  V145 no-code, seed 2026 (trab. 36,01 + empresa 161,23 = 197,24 €/mes, Orden
  PJC/297/2026). Branch en `PayslipService` solo para esos contratos; fallo
  ruidoso si el año no está configurado. Neto exacto. **Marcado para validar**:
  proración mes parcial, desglose por concepto TC/RED, UI para editar la cuota.

**3. Ciclo laboral (CL-1/2/3 + UI CL-4 completo)** ✅ **`docs/design-ciclo-laboral.md`**
- **CL-1 suspensión/excedencia** (art. 45 ET) *(merge CL-1)*: V146
  `contract_suspensions` + GUARDA en la nómina (no genera recibo a quien está en
  excedencia sin sueldo) + CRUD API. Default-seguro.
- **CL-2 atrasos retroactivos** *(merge CL-2)*: `BackPayService.preview` calcula
  los atrasos desde las vigencias por LEY — 15% IRPF en tramos de ejercicios
  anteriores, tipo del contrato en el año en curso, SS sobre la diferencia. Solo
  cálculo (sin efectos). Falta con Benjamin: recibo+asiento+L13, atrasos de extras.
- **CL-3 cese de empresa** *(merge CL-3)*: `TerminationService.{preview,execute}
  CompanyClosure` — extinción colectiva en lote (20 días/año, reutiliza el cese
  individual validado). Preview sin efectos + ejecución all-or-nothing.
- **CL-4 UI** *(merge CL-4)* ✅: botones en la barra de Empleados — Suspensión
  (listar/registrar/cerrar/borrar), Atrasos (cálculo + desglose), Cese de empresa
  (preview + **doble confirmación**, acción irreversible). i18n ES+EN.
- **CL-5** (con Benjamin): generar RECIBO de atrasos + asiento + liquidación L13;
  proración de meses parciales; efecto fino en antigüedad del finiquito; atrasos
  sobre pagas extra.

---

## 📅 SESIÓN 2026-06-25 (autónoma, Benjamin fuera) — finiquito + reflejo + nómina + revisión

> Benjamin se fue dejando items + "revisa el código con tu equipo" + registro, y luego
> (con PDFs reales de nómina/finiquito de CONTENDO) autorizó: montar el recibo de finiquito,
> cablear el banner de reflejo, y validar/arreglar el cálculo si la diferencia es clara.
> Todo compila y mergeado a `develop`.

**Bloque NÓMINA / FINIQUITO (con datos reales):**
- ✅ **Recibo / carta de finiquito** *(c1f061b)* — faltaba el documento legal (existían carta de
  despido + certificado + el cálculo del finiquito). Nuevo `TerminationDocsService.settlementReceipt`
  + endpoint `/labor/terminations/docs/settlement-receipt` + botón en el diálogo de baja. Formato
  fiel a CONTENDO. Robusto post-baja (carga el finiquito guardado; recalcula solo pre-baja). NO
  toca el motor de cálculo.
- ✅ **Validación del cálculo vs PDF real** (solo lectura): tipos trabajador **CC+MEI 4,85%**
  (4,70+0,15) ✓, **FP 0,10%** ✓ — CORRECTOS (tabla no-code `ss_contribution_rates` 2026).
- ✅ **Desempleo por tipo de contrato — RESUELTO** *(bloque CONTRATO-MODALIDADES, 9080f00)*.
  El desempleo no es igual: indefinido 1,55/5,50 (7,05%) vs temporal 1,60/6,70 (8,30%), Orden
  PJC/297/2026. **Sin duplicar**: el catálogo legal ya existía (`sepe_contract_types`, V74, todos
  los códigos SEPE) → se **extiende** con `unemployment_scheme` (V144), y el par temporal por año
  va en `ss_contribution_rates` (V143, no-code). La nómina deriva el esquema del `sepe_contract_code`
  del contrato (`ContractCatalogService.isTemporalUnemployment`), default DEFENSIVO indefinido.
  **MATIZ legal respetado**: sustitución/interinidad (411/511), formativos (421/521) y prácticas
  (401/501) cotizan desempleo al esquema INDEFINIDO aunque sean temporales → por eso se marca
  código a código, no por familia. Solo TEMPORAL: producción (300/410/510/420), inserción
  (405/505), Fondos Europeos (406/506).

**Otros items:**
- ✅ **Indicador "reflejada como gasto en {cliente}"** *(25d10e7)* — columna en el listado de
  Facturación (no hay vista de detalle) + endpoint `/api/billing/invoices/reflections`.

**Pendiente / decisiones de Benjamin:**
- **Su trabajador de construcción**: hoy su contrato tiene `sepe_contract_code=100` (Indefinido
  ordinario) → cotiza desempleo INDEFINIDO (1,55/5,50). Él lo describió como "hasta terminación de
  obra". Post-Reforma 2022 el "obra o servicio" está derogado: en construcción lo correcto es
  **indefinido adscrito a obra** (fijo de obra = INDEFINIDO, 1,55/5,50) — coincide con el código
  actual. Si la gestoría lo registró como temporal de verdad, basta cambiar el código SEPE en el
  wizard (200 fijo-discontinuo o 410 producción). **Pregunta**: ¿qué código SEPE es el real?
- (Opcional) CM-5: pantalla en config asesoría para ver/editar `unemployment_scheme` por código.
- Registro: las 5 decisiones de `docs/design-registro-alta.md` (se construye con él, auth).
- Double-pay / tolerancia 0,01 en pagos (toca dinero, con él).

---

## 📅 SESIÓN 2026-06-25 (autónoma, Benjamin fuera) — items aditivos + revisión de código

> Benjamin se fue dejando 4 items + "revisa el código completo con tu equipo" +
> "mira el registro". Trabajo aditivo y seguro; lo de auth/dinero/seeds NO se tocó
> (§11.2). Todo compila y mergeado a `develop`.

**Cerrado:**
- ✅ **Doble reflejo de cobro — NO era bug** (investigado en BD): F-2026-0104 se cobró
  por DOS vías (multi-allocation `4e95f13d` + vencimiento `c9361536`), cada una reflejó
  una vez (correcto). La idempotencia de REFLEJO está bien. *(Gap pre-existente, NO de
  REFLEJO: se puede pagar la misma factura por dos mecanismos → doble pago. Anotado; no
  se toca el flujo de pagos en autónomo.)*
- ✅ **Contador de gastos sin nómina** *(dd54c04)* — el KPI "GASTOS · N facturas" ya no
  cuenta los asientos de nómina (640/642 son 6xx); el TOTAL sí los incluye (gasto real).
- ✅ **Pestaña Trabajos en la ficha** *(a12f6af)* — Mi gestión y clientes con módulo
  `shifts`; opera sobre la empresa activa. Reusa `buildWorkLogsModule()`.
- ✅ **Registro/alta — DISEÑO escrito** *(712f065)* — `docs/design-registro-alta.md`. NO
  existe auto-registro hoy; el esquema (`user_accounts`+`company_memberships`) ya lo
  soporta sin migración. 5 decisiones abiertas. **Se construye con Benjamin** (auth).

**Revisión de código (2 agentes Explore en paralelo, backend+frontend) — TRIADA:**
- Los agentes **sobre-reportaron** (lo habitual). Verificado uno a uno:
  - Frontend **#1 "500+ claves ES huérfanas" = FALSO** (muestreé 4, todas tienen gemela
    ES; el agente miró el método equivocado — `tEs()` está aparte). El español NO está roto.
  - Backend **#4 (MultiAlloc no valida estado)** y **#8 (method null)** = falsos.
  - Backend **#2 (`work_logs.billed_invoice_line_id` no se rellena)** = no funcional: el
    filtro lleva `AND status <> 'BILLED'`, que ya excluye los facturados.
- **Reales pero menores / a decidir (NO tocados):**
  - Backend: comparación con tolerancia 0.01 en `PaymentScheduleService` (PAID vs break de
    conciliación) — debatible, toca dinero → con Benjamin.
  - Frontend: ~4 literales en español hardcodeados (login subtitle/Google, placeholder de
    líneas, "X cambios sin guardar") — i18n pendiente; los de **login no se tocan** (§11.2).

**Pendiente documentado (no hecho, por riesgo/scope):**
- ⏳ **Banner "reflejada en {cliente}"** en la factura emitida. Bloqueo: NO hay vista de
  detalle de factura (se ve como PDF), así que iría como **indicador en el listado de
  facturación** + dato de reflejo por factura (endpoint `purchase_invoices WHERE
  source_sales_invoice_id=?` → company_name). Cirugía moderada en el listado; no se hizo
  en autónomo sobre la base del cambio CSS global aún sin probar. Listo para cablear.

---

## 🔗 BLOQUE REFLEJO — factura emitida ⇒ gasto+asiento por validar en el cliente (decidido 2026-06-23)

> Diseño completo en [`design-reflejo-factura-cliente.md`](design-reflejo-factura-cliente.md).
> Cuando una factura **emitida** en la cartera tiene como cliente a otra empresa de la
> cartera (match por NIF), se refleja en sus libros como **factura recibida (gasto) +
> asiento, por validar** (misma tabla `purchase_invoices` que el import por PDF). El
> asesor/empresario **solo valida**. Decisiones cerradas: cuenta **623**, estado **por
> validar**, **pago al marcar cobrada**, alcance **gasto para cualquier par de la cartera**
> (fabricar la emitida del otro lado queda fuera, muro TPB/SIF).

- ✅ **REFLEJO-1 — Esquema** (V141): `purchase_invoices.source_sales_invoice_id` +
  `source_company_id` + UNIQUE idempotente. Aplicada.
- ✅ **REFLEJO-2 — Reflejo del gasto** *(c43b8cb)* — `CrossInvoiceReflectionService` + hook
  en `SalesInvoiceService.validate()` (best-effort, nunca rompe validación/SIF). Crea gasto
  DRAFT + asiento 623/472/(4751)/400 DRAFT. **Verificado en vivo**: F-2026-0104 reflejada en
  Marcos Construcciones, asiento cuadrado (2420=2420).
- ✅ **REFLEJO-3 — Cascada de reversión** *(776a88d)* — anular la emitida revierte el gasto
  reflejado (borra DRAFT / contrasiento si POSTED); deshacer un cobro/pago (`unpay`) revierte
  el pago/cobro reflejado. Helper `revertEntryBySource`.
- ✅ **REFLEJO-4 — Reflejo del pago/cobro (bidireccional)** *(fbf80d6 + b04f585)* — al cobrar
  la emitida → pago `400/572·570` por validar en el cliente (4a); al pagar el gasto reflejado
  → cobro `572·570/430` por validar en el emisor + factura marcada cobrada (4b). Enganchado en
  los 3 cobros (multi-allocation, vencimiento, conciliación), best-effort, idempotente.
- ✅ **REFLEJO-5 — Avisos** *(776a88d)* — bucket `DRAFT_PURCHASES` (facturas recibidas por
  validar) en los dos modos + i18n + navegación. El de asientos ya lo captaba `DRAFT_JOURNAL`.
- ✅ **REFLEJO-6 — Interruptor on/off** *(d11e401)* — flag `companies.reflejo_auto_enabled`
  (V142, default sí) con checkbox por empresa en su configuración; el servicio respeta el flag
  de la empresa que recibe. Visibilidad vía concepto "Fra. X - emisor" + tipos "Pago/Cobro
  reflejado" + avisos por validar.

> **Bloque REFLEJO COMPLETO (1-6).** Aparte (mismo día): **fix ruido SSE** *(a34df54)* — el
> heartbeat cierra el emisor caído en vez de soltarlo; Tomcat deja de loguear "broken pipe".

---

## 📅 SESIÓN 2026-06-24 — REFLEJO completo + avisos por cliente + tanda de fixes de contraste

> Continuación de la noche del 23. Se cerró el **bloque REFLEJO (1-6)** entero y verificado en
> vivo, se mejoraron los **avisos** (home + por cliente + regla empresario/gestoría) y se cazó
> una **familia de bugs de contraste** (texto blanco ilegible del tema sobre fondos claros).
> Todo compila (backend+ui, exit 0) y mergeado a `develop`.

**Bloque REFLEJO — cerrado y verificado (ver bloque dedicado arriba):**
- ✅ REFLEJO-2 gasto reflejado *(c43b8cb)* — **verificado en vivo** (Marcos F-2026-0104, asiento
  cuadrado 2420=2420).
- ✅ REFLEJO-4 pago/cobro **bidireccional** *(fbf80d6 + b04f585)* — asesoría cobra→pago en cliente
  (4a) y cliente paga→cobro en emisor (4b), verificado en log.
- ✅ REFLEJO-3 cascada de reversión *(776a88d)* · REFLEJO-5 aviso `DRAFT_PURCHASES` *(776a88d)* ·
  REFLEJO-6 interruptor `reflejo_auto_enabled` V142 *(d11e401)*.

**Avisos — mejoras:**
- ✅ **Banner "por validar" en la home** *(dafd3bf)* — asientos+gastos+facturas por validar, en
  empresario y asesoría, con botón Revisar → vista Avisos.
- ✅ **Regla empresario/gestoría** *(87eba95)* — el empresario NO lleva contabilidad → no ve
  "asientos por validar" (solo gastos y facturas); la gestoría lo ve todo. Bucket nuevo
  `DRAFT_SALES` (facturas emitidas por validar).
- ✅ **Cartera desglosada por cliente** *(b322329)* — la vista Avisos de la asesoría ya dice de
  QUÉ cliente es cada aviso, con botón **"Abrir cliente"** que entra directo (antes había que ir
  cliente por cliente). Endpoint `/pending-tasks/portfolio-detailed`.

**Fixes de contraste (texto blanco ilegible del tema sobre fondo claro):**
- ✅ **Cabeceras de tabla en Contabilidad** *(d908bd4)* — las tablas de `AccountingScreen` no
  llevaban `.data-table`; marcado el TabPane raíz → todas las cabeceras legibles.
- ✅ **Banner AEAT** *(856c0fb)* — texto blanco del titular + **no se quitaba al marcar
  presentado** (genérico clonado a SUBMITTED pero el genérico seguía PENDING; arreglado con
  `NOT EXISTS`).
- ✅ **Valores de KPI + datos del cliente** del resumen de actividad *(956b669)* — el backend
  servía los datos bien (verificado con log temporal); `kpiCardValue` y los labels de "Datos del
  cliente" no fijaban color → invisibles. Color explícito añadido.

**Pendientes detectados:**
- ✅ **Descuadre gastos en KPI — NO era bug** (investigado): el trimestre llega a 30 jun y el
  "año hasta hoy" a 24 jun; la diferencia es exactamente la **nómina de junio devengada a 30/06**
  (640/642, ~9.741) que cae en el hueco 25-30 jun. Ambos números correctos para su rango. *(Nota
  menor: el contador "N facturas" del gasto cuenta también los asientos de nómina; afinar si
  molesta.)*
- ✅ **Enlace desde el aviso de cartera** *(0590cdf)* — "Abrir cliente" ya **salta a la pestaña**
  que resuelve el aviso (asientos→Contabilidad/Por validar, gastos→Compras y Gastos,
  facturas→Facturación, etc.) en vez de abrir el Resumen. (Falta solo resaltar la fila exacta —
  mejora futura.)
- ⏳ **Verificar doble reflejo de cobro**: el log mostró F-2026-0104 reflejada como pago 2 veces
  (¿se registró el cobro 2 veces, o falla la idempotencia de `reflectPayment`?).
- ⏳ REFLEJO menores: banner de visibilidad "reflejada en {cliente}" en la propia factura;
  dirección TPB inversa (fuera de alcance por ley).

---

## 📅 SESIÓN 2026-06-23 — Módulo Trabajos (cierre + fixes) + facturación + diseño bloque REFLEJO

> Día largo: por la mañana se cerró el **módulo Trabajos** completo (TRB-1..4 + varios
> fixes), por la tarde fixes de facturación, una decisión legal de producto, y el
> **diseño + arranque del bloque REFLEJO** (factura emitida ⇒ gasto del cliente).
> Todo compila (backend+ui, exit 0) y está mergeado a `develop`.

**Módulo Trabajos — cierre y arreglos (mañana):**
- ✅ **TRB-4 tarifas por cliente** con autorrelleno *(d1d9fad)* — V140 `customer_work_rates`,
  precios por unidad (hora/día/mes) por cliente, con defaults generales.
- ✅ **Trabajos del TITULAR** *(9c4f856)* — un autónomo sin empleados puede crear sus
  propios trabajos (V139 `work_logs.employee_id` NULLABLE).
- ✅ **Formulario más grande + descripción multilínea** *(213f9bb)* como en Nueva factura.
- ✅ **Importar trabajos pendientes desde Nueva factura** *(ba7fd03)* — al elegir un cliente
  con trabajos sin facturar, se ofrecen para meter como líneas (estilo CONTENDO).
- ✅ **Fix `work_date` legacy** *(500c36d)* — no se podía crear un trabajo (columna legacy
  NOT NULL); se puebla `work_date = log_date` al guardar.
- ✅ **Fix botón Facturar** *(5b02364)* — no se activaba porque el parser JSON devuelve `""`
  (no null) para campos vacíos; cambiado a `isBlank()`.

**Facturación / Trabajos — fixes de tarde:**
- ✅ **3 bugs al facturar un trabajo (40h×25€)** *(f56664a)*:
  - (A) la línea salía **1 × 1000€** en vez de **40 × 25€** → `billSelected` ahora preserva
    cantidad × precio.
  - (B) **diálogo de éxito en blanco** (carrera de JavaFX al cerrar el modal) → aviso con
    `Platform.runLater`.
  - (C) **borrar el borrador no revertía el trabajo** (FK `work_logs.invoice_id`; el borrado
    es soft-cancel) → `SalesInvoiceService.deleteDraft` revierte los trabajos enlazados a
    APROBADO.
- ✅ **Reparación puntual en BD** del trabajo huérfano ("COLOCACIÓN DE LADRILLOS") que quedó
  FACTURADO apuntando a un borrador cancelado *antes* del fix C → revertido a APROBADO,
  facturable de nuevo con su valoración 40×25.
- ✅ **Ocultar borradores cancelados del listado** *(cf1e1bc)* — el soft-cancel de un borrador
  lo dejaba visible como CANCELLED; `findAll` ahora excluye `status=CANCELLED AND
  invoice_number IS NULL` (las validadas anuladas van a VOIDED, no se afectan).

**Decisión de producto (legal):**
- ❌ **"Cobrar un trabajo sin facturar"** — DESCARTADO. Contradice el principio "no migrar lo
  ilegal; crear por ley" y el diseño SIF/VeriFactu; los competidores (A3/Sage/Holded) atan el
  cobro a la factura. Se usa el tipo **"cobro al contado"** sobre la factura para el cobro en
  mano. No se añade nada.

**Bloque REFLEJO (diseño + arranque) — ver bloque dedicado arriba:**
- ✅ **REFLEJO-1** *(a5b16c3)* — esquema V141 + diseño completo + decisiones cerradas.
- ⏳ REFLEJO-2..6 pendientes.

---

## 📅 SESIÓN 2026-06-22 — Autónoma (Benjamin trabajando). Cobro parcial + informes PDF + limpieza

> Benjamin se fue a trabajar y dejó: "completa todo lo que no necesites que yo esté
> para responder y deja lo poquito que queda". Cerrados los ítems aditivos/decididos
> sin bloqueos; lo legal-sensible (toca el motor de cálculo / necesita validar con
> caso real) y lo que necesita decisión suya / infra se deja MARCADO abajo.

**Cerrado y mergeado a develop hoy (compila backend+ui, exit 0):**
- ✅ **Cobro parcial guiado (multi-allocation)** *(53dec1b → merge)* — partió de un
  reporte de Benjamin: "tengo 2 facturas que suman 4537,50 y una transferencia de
  4000, no me deja pagar salvo importe exacto". Decisión suya: **"preguntar cuál
  completar"**. El diálogo "Registrar pago de varias facturas" ya no exige cuadre
  exacto: si el importe no cubre el pendiente de todas las marcadas, pregunta **qué
  factura completar** (se paga entera), reparte el resto de más antigua a más nueva y
  lo que sobra queda PENDIENTE (PARTIAL). Si cubre el pendiente exacto, paga todas sin
  preguntar. Columna "Repartir" pasa a SOLO LECTURA (la calcula el diálogo). Backend
  `MultiAllocationPaymentService` ya soportaba el parcial → PARTIAL; no se tocó.
  *(Antes, el "solo aparece una factura" era porque hay que seleccionar el cliente en
  el combo — el diálogo agrupa por cliente; no era bug.)* **Mejora posterior (feedback
  Benjamin):** selección MÚLTIPLE de facturas a completar (casillas + control de que la
  suma marcada no supere el pago), para poder pagar enteras varias de una vez.
- ✅ **Fix layout listados en pantalla pequeña (portátil)** *(→ merge)* — feedback Benjamin:
  en Facturación el listado quedaba en una franja de ~1/3 con doble scroll. `tabLayout`
  metía la tabla en un ScrollPane y la TableView mantenía su alto preferido (~400px).
  Nuevo `tabLayoutFill` (cuerpo directo al centro → la tabla llena el alto y scrollea por
  dentro). Enrutados Facturación>Facturas, Config>Propietarios, Config>Certificados.
  **PENDIENTE: prueba visual en el portátil + barrido de otras pantallas si reaparece**
  (SIF y Credenciales se dejaron con scroll por cuerpo mixto).
- ~~**BUG DOBLE PANTALLA**~~ ✅ **CERRADO 2026-06-22** (confirmado Benjamin): (1) ventana
  acotada a la pantalla VISIBLE al abrir + centrada, **sin** listener dinámico (peleaba con
  mover/maximizar); (2) barras de acción y filtros que **ENVUELVEN** (FlowPane `actionFlow` +
  `filterGroup`); (3) botones de acción compactados (CSS acotado a `.settings-actions .button`)
  y **cabecera de módulo** compactada; (4) en "Mi gestión" sin **doble hero card**
  (`billingView(bundle, showHeader=false)` embebido); (5) **scroll de página en la ficha de
  asesoría** (`buildClientDetailView` en ScrollPane) = el "doble scroll" para llegar al listado
  en portátil. *Pendiente menor (si molesta): en pantalla grande la tabla queda a ~400px con
  hueco debajo en la ficha de asesoría; se afinaría con fitToHeight + minHeight de tablas.
  Extender el "sin doble hero card embebido" a otros módulos de Mi gestión si hace falta.*
- ✅ **Export PDF Mayor + Sumas y Saldos** *(→ merge)* — cierra "Export PDF de informes
  contables" (Balance + PyG ya estaban). `AccountingReportsPdfService.ledgerPdf` (saldo
  apertura + movimientos + saldo final) y `trialBalancePdf` (con fila de totales),
  endpoints `/ledger/{id}/export.pdf` y `/balance/export.pdf`, botón "Exportar PDF" en
  ambas pestañas (reusa `savePdf`).
- ✅ **VIG-3 guard server-side** *(→ merge)* — `update()` rechaza con 409 cambiar
  `start_date`/`seniority_date` de un contrato CON nóminas (la UI ya bloqueaba los
  campos; esto cierra la defensa por API). **Cierra el pendiente menor de VIG-3.**
- ✅ **Limpieza técnica** *(→ merge)* — `.gitignore` del `dependency-reduced-pom.xml`
  (artefacto del shade) + `git rm --cached`; borrado código muerto
  `buildClientTaxFilingsTab`/`loadClientFilings` (sin callers; verificado).
- ✅ **JOR-4 Planificado vs Real** *(2 commits → merge)* — sección nueva en Laboral >
  Jornadas: por empleado-día cruza planificado (bloques WORK del horario asignado,
  JOR-2) vs real fichado (JOR-1) con diferencia coloreada. **Descuenta festivos/cierres**
  del calendario laboral activo (cuentan 0 planificado; un fichaje en festivo sale como
  diferencia). Backend `PlanVsRealService` + `GET /api/labor/plan-vs-real` (reusa
  servicios tenant-scoped, no toca el motor). Descriptivo a propósito (sin tolerancias).

**⬜ Dejado MARCADO para cuando esté Benjamin (necesita su validación/decisión/infra):**
- **Nómina — incidencias** (horas extra, complementos variables por periodo, pagas
  extra cotizadas con asiento): toca el **motor de cálculo** → legal-sensible, validar
  con caso real (§11.2: no tocar cálculo sin él).
- ~~**Bloque D restante**: VIG-4 atrasos · CV-5 excedencias/suspensiones · CV-8 cese
  empresa~~ ✅ **HECHO 2026-06-25 (CL-1→CL-4)**. Falta **CL-5** (con Benjamin):
  recibo/asiento/L13 de atrasos; proración de meses parciales.
- **FORMATS-EXCHANGE** (xDiario + SUENLACE): ⏸️ **APARCADO** — Benjamin no tiene
  fichero real ni quien se lo dé. El round-trip JSON ya está. Se retoma si aparece.
- **FJ-5b** incidencia schedule-aware · **JOR-4** plan-vs-real · **Partes de día**:
  features grandes / legal-sensibles.
- **Push instantáneo PWA**: necesita túnel con nombre o WebSocket (cuenta Cloudflare).
- **AEAT 180** (arrendamientos) editor: opcional, necesita modelo real para el mapeo.

---

## 🧭 ESTADO Y PENDIENTE VERIFICADO (2026-06-21)

> Snapshot reconciliado con el código a día de hoy. Es la foto rápida de "qué queda";
> el detalle vive en los cubos de prioridad del final (ALTA/MEDIA/BAJA) y en cada sesión.
> Verificado: MEMP-2..5 ✅, JORNADAS ✅, topes de cotización SS ✅ (V109/V121),
> Comunicación ✅, gestor-navegador + cert Fase 2 ✅.

**🔴 Bloqueado por certificado FNMT real / alta SIF en sede AEAT (no se puede cerrar sin eso):**
- VeriFactu estricto: XAdES-EPES sobre XML canónico AEAT + parseo SOAP real + alta como SIF
  en sede + obligaciones de fabricante. *(Los editables 347/390/190 ya están.)*
- Conectores reales **DEHú / SS RED / SILTRA** (envío real con certificado).
- Modelos **AEAT 100 / 180 / 200 / 411**.
- 🔭 **E-FACTURA-ESTRUCTURADA (prep 2027-2030)** — soporte de factura electrónica
  estructurada **Facturae / EN 16931** + red **Peppol**, además del PDF. Cubre las tres
  normas que convergen: VeriFactu obligatorio (ene 2027 empresas / jul 2027 autónomos),
  factura electrónica B2B de la Ley Crea y Crece (oct 2027 grandes / oct 2028 resto) y
  ViDA UE (1 jul 2030, intracomunitario + reporte casi en tiempo real). Prioridad ligada
  al certificado FNMT (va de la mano del VeriFactu real). NO urge hoy; es la jugada para
  no llegar tarde. *(Investigado con fuentes 2026-06-22; el "VeriFactu mandará la factura
  en 2030" del vídeo es impreciso: VERI\*FACTU ya envía el registro hoy; lo nuevo es la
  factura estructurada + reporte casi en tiempo real.)*

**🟠 Funcional atacable (sin bloqueos):**
- ~~Export PDF Mayor + Sumas y Saldos~~ ✅ **HECHO 2026-06-22** (cierra informes PDF).
- **FORMATS-EXCHANGE**: ✅ **round-trip JSON canónico HECHO 2026-06-25** (import del
  JSON que produce el export). ⏸️ **xDiario/SUENLACE APARCADOS** (sin fichero real).
- ~~**Nómina — incidencias** (INC-1..4)~~ ✅ **HECHO 2026-06-22**: tabla `nomina_incidencias`
  + gestión en el diálogo Calcular nómina; **horas extra con cotización adicional legal**
  (V137 no-code, 14%/28,30%, no en base CC); **complementos variables**; **ausencias no
  retribuidas** (descuentan devengo/base/IRPF); **pagas extra con asiento** (devengo 640→465
  sin provisión). MARCADO para validar con caso real: proración del mínimo de base por mes
  parcial · importe de ausencia por días×salario diario · auto-detección de ausencias desde
  bajas/permisos (no hecha, opcional).
- **CONTRATO-VIGENCIAS (bloque D)**: ~~VIG-3 (guard `hasPayslips`)~~ ✅ **2026-06-22**;
  ~~VIG-4 atrasos~~ ✅ **CL-2 2026-06-25** (cálculo); ~~CV-5 excedencias/suspensiones~~
  ✅ **CL-1 2026-06-25**; ~~CV-8 cese empresa~~ ✅ **CL-3 2026-06-25**. **Falta CL-5**
  (con Benjamin): recibo/asiento/L13 de atrasos; proración meses parciales.
- **FJ-5b** incidencia "schedule-aware" (legal-sensible → validar con caso real).
- ~~**JOR-4** comparación planificado-vs-real~~ ✅ **HECHO 2026-06-22** (descriptivo + descuento
  de festivos/cierres). Queda el sub-ítem **excepciones de calendario por fecha** (AJUSTE de
  horas, calendarios por centro de trabajo).
- ~~**Módulo TRABAJOS** (partes/work_logs facturables)~~ ✅ **HECHO 2026-06-23** (TRB-1..4 + fixes):
  entrada propia en el sidebar; alta/edición con valoración por unidad
  (horas/días/meses × precio) o **precio cerrado** (como CONTENDO); estados
  DRAFT→APPROVED→BILLED; bandeja "pendientes de facturar"; **facturar trabajos del mismo
  cliente → factura borrador** (elegir 1 línea por trabajo o agrupar en una con concepto
  editable + suma). V138 + `WorkLogService.billSelected` (createDraft, IVA 21% editable).
  **TRB-4 tarifas por cliente** con autorrelleno (V140 `customer_work_rates`). **Trabajos del
  TITULAR** (autónomo sin empleados). **Formulario grande + descripción multilínea** como en
  factura. **Importar trabajos pendientes desde Nueva factura** (estilo CONTENDO). Fixes:
  V139 `work_date` legacy (no se podía crear), botón Facturar (`isBlank` vs null), y los **3
  bugs al facturar** (cantidad 40×25 no 1×1000, diálogo en blanco, revertir trabajo al borrar
  borrador) — ver sesión 2026-06-23 abajo.
  *Pendiente menor: pestaña Trabajos en la ficha de "Mi gestión" (hoy la entrada del sidebar
  opera sobre la empresa propia); registro desde la PWA del empleado; "rentabilidad real"
  (margen) si se quiere — el plan-vs-real ya está en JOR-4.* **fichajes sospechosos** sigue
  pendiente (aparte).
- **Push instantáneo PWA** (túnel con nombre o WebSocket; el quick-tunnel bufferiza SSE).

**🟡 Media / decisión:**
- Régimen especial IVA / prorrata / criterio caja (UI). · OCR PDFs escaneados (decidir
  binario Tesseract). · CENTROS-MAP (mapa Leaflet; el geocode ya está). · Dashboard widgets
  personalizables. · Consolidación intragrupo. · EQUIPO S2 (permisos finos).

**🟢 Baja:** Alertas de seguridad · Email personal OAuth · Google Calendar bidireccional.

**🧹 Limpieza técnica menor:** ~~`dependency-reduced-pom.xml` trackeado · código muerto
`buildClientTaxFilingsTab`/`loadClientFilings`~~ ✅ **HECHO 2026-06-22**.

---

## 📅 SESIÓN 2026-06-21 — GESTOR-NAVEGADOR (ventana aparte) + login por certificado (Fase 2)

> Benjamin: arrancar el GESTOR-NAVEGADOR (navegador embebido a AEAT/DEHú/SS RED/SILTRA) y
> luego el **login por certificado electrónico**. Camino: spike → ventana aparte por cliente
> → intento de embeber de verdad → pared técnica → revert → certificado.

**Cerrado en `feat/Benjamin` (compila backend+ui):**
- ✅ **GESTOR-NAVEGADOR** — módulo nuevo `gestor-navegador` (NO modular a propósito: los jars
  de JCEF tienen nombres ilegales para JPMS). Chromium real vía `jcefmaven 127.3.1`, FlatLaf
  para estética BENJAGEST (azul de acento, pestañas subrayadas). Ventana aparte por cliente
  (proceso `java -jar`), pestañas AEAT/DEHú/SS RED. Fixes: barra con BorderLayout (botones no
  se ocultaban al redimensionar), botón X de cierre, `cache_path` propio (singleton).
  **PROBADO OK** por Benjamin (entró en AEAT/DEHú con certificado del almacén de Windows).
- 🧱 **PARED TÉCNICA — embeber dentro de BENJAGEST es inviable con JCEF.** Se intentó
  des-modularizar `ui` (E1) para meter el navegador en una pestaña JavaFX. Dos paredes:
  (1) airspace JavaFX/AWT pesado; (2) **`CefBrowser` (jcef 127) NO expone
  `sendMouseEvent/sendKeyEvent/wasResized`** → un OSR a JavaFX sería **solo-lectura** (sin
  clics ni teclado). Decisión Benjamin: **revertir E1, quedarnos con ventana aparte** *(9c518fe)*.
- ✅ **LOGIN POR CERTIFICADO — Fase 2** *(commits backend + ui en `feat/Benjamin`)*.
  · Hallazgo: **JCEF 127 NO expone `onSelectClientCertificate`** → NO se puede auto-seleccionar
    el cert por código. Lo único viable: dejar el cert del cliente en el **almacén de Windows**
    para que el diálogo nativo de Chromium lo ofrezca.
  · Diseño (clave privada NO viaja por HTTP): al abrir el gestor de un cliente, el **backend**
    (mismo usuario on-premise) descifra el `.p12` del cliente activo (X-Company-Id) y lo
    **importa a `Cert:\CurrentUser\My`** vía PowerShell (contraseña por variable de entorno,
    `.pfx` temporal borrado tras importar). Registra el uso en `certificate_usage_log` (LOPD).
    Al cerrar el gestor, **quita la huella** del almacén (no deja la clave del cliente residente).
  · `WindowsCertStoreService` + `BrowserCertSessionService` + `POST /api/certificates/browser/{open,close}`;
    UI: `openBrowserCertSession`/`closeBrowserCertSession` cableados en `launchGestorNavegador`.

**✅ PROBADO OK por Benjamin (2026-06-21):** "el certificado va perfecto". Mergeado a
develop *(merge 7c461fb)* junto con la cola post-pruebas pendiente. Fallback: si el import
falla, el navegador abre igual (almacén del sistema).
- ✅ **Fix enlace SS → Import@ss** *(abf82d9)* — la pestaña de Seguridad Social apuntaba al
  portal público general; cambiada a `portal.seg-social.gob.es` (Import@ss).

**✅ HECHO — DISEÑO módulo Comunicación asesoría↔cliente** (el "no olvidar" de antes):
aire moderno en JavaFX vía CSS/app.css (no FlatLaf). Burbujas tipo chat (`.comm-bubble`
in/out con remitente Asesoría/Empresa + hora), lienzo tarjeta, composer inferior, pestañas
`settings-tabs` limpias (fuera la barra negra y los iconos rotos), cabecera con icono y sin
títulos duplicados *(1e0909a, 9757765)*.
- ✅ **Fix import .NET puro** *(95669e4)* — `Import-PfxCertificate`/`ConvertTo-SecureString`
  fallaban por autoload de módulos; ahora `X509Store`/`X509Certificate2`. PROBADO OK.
- ✅ **Fix 403 SSE** *(cc1a0b3)* — `JwtAuthenticationFilter.shouldNotFilterAsyncDispatch()=false`
  (re-autentica en el dispatch async del stream). Quita el `AccessDenied` repetido del log.
- ✅ **LOPD log** *(3c9d13e)* — el log de la sesión de navegador ya no escribe alias
  (nombre+NIF) ni huella; el audit vive en `certificate_usage_log`.
- ✅ Todo mergeado a develop *(merge ae32b1e)*.

---

## 📅 SESIÓN 2026-06-20 — FICHAJE-JORNADA + Portal empleado (MEMP-3/4/5) + firma nóminas + tiempo real

> Benjamin: terminar el portal del empleado (FICHAJE-JORNADA → MEMP-3 → MEMP-4 → MEMP-5),
> luego firma de nóminas y notificaciones en tiempo real. **PROBADO EN VIVO OK** (escritorio
> directo + PWA por túnel Cloudflare). Todo en `feat/Benjamin` (pusheada). **Merges a develop
> pendientes** (cierre de bloque, ver abajo).

**Cerrado y PROBADO hoy en `feat/Benjamin` (compila backend+ui):**
- ✅ **FJ-1/FJ-2** *(e61f7b4)* — `ScheduleFichajeService.suggestNextPunch` (plantilla vigente
  + bloques → transiciones IN/BREAK_START/BREAK_END/OUT → siguiente con ventana ±15 min) +
  `GET /api/empleado/fichaje/sugerencia`.
- ✅ **FJ-3/FJ-4** *(c14663b)* — resaltado del botón que toca en escritorio + PWA.
- ✅ **FJ disable-others** *(64678d6)* — en ventana, solo el botón sugerido activo; los demás
  desactivados (estilo CONTENDO) + enlace "Fichar otra cosa" (RD 8/2019: registrar tiempo real).
- ✅ **FJ-5a** *(6267860)* — "Corregir…" accionable en Auditoría de fichajes (TIME_ADJUST/
  TYPE_CHANGE/VOID → `POST /api/timeclock/correction`).
- ✅ **MEMP-3** *(5b90f64)* — "Mi jornada" en la PWA: horario (JOR-2) + jornada real (JOR-1) +
  festivo + qué toca (FJ). `GET /api/empleado/jornada`.
- ✅ **MEMP-4** *(17c5f01)* — solicitudes vacaciones/bajas desde el móvil + aprobación. V134
  `employee_leave_requests` + `employee_leave_attachments` (BLOB). Tipos VACATION/SICK_LEAVE/
  PAID_LEAVE/OTHER (baja exige adjunto). PWA pedir/listar/cancelar; escritorio sub-tab
  "Solicitudes" (aprobar/rechazar + adjunto). Aprobar VACATION espeja a `employee_vacations`.
- ✅ **MEMP-5** *(240e4ad)* — nóminas en la PWA: recibir/descargar PDF/confirmar recibí.
  `EmployeePayslipController /api/empleado/nominas`. **→ Portal MEMP-2..5 COMPLETO.**
- ✅ **MEMP-5b SIGN** *(84516f1)* — firma electrónica simple del recibí (estilo Sesame, sin
  certificado). V135 evidencias (`acknowledged_ip/device/method/code`). Step-up de PIN
  (`passwordEncoder.matches` vs `employees.pin_hash`); el PDF incluye bloque "RECIBÍ — Firma
  electrónica" (firmante/NIF/fecha/dispositivo/IP/código) que el jefe descarga. PWA: modal
  que pide PIN para firmar.
- ✅ **NOTIF-RT** *(c203582, f983830)* — notificaciones en tiempo real (SSE):
  · Backend `RealtimeService` (conexiones por empresa/empleado, publishToCompany/Employee,
    heartbeat, publicación AFTER-COMMIT) + `RealtimeController GET /api/realtime/stream`
    (token por query SOLO en /api/realtime/ para EventSource).
  · Eventos emitidos: fichaje, solicitud creada/resuelta, nómina entregada/firmada.
  · Escritorio: `RealtimeClient` (SSE por localhost) → `RefreshBus` (TIMECLOCK/AUDIT/
    LEAVE_REQUESTS/PAYSLIPS) + campana. Pantallas suscritas: fichajes, auditoría, Solicitudes,
    **Nóminas** *(3babd8f, fix: faltaba la suscripción)*.
  · PWA: `EventSource` + **fallback de sondeo 18s** *(b5b6977)* porque el quick-tunnel de
    Cloudflare **bufferiza el SSE** (diagnosticado: 0 bytes por túnel vs OK en localhost).
  · AVISOS: bucket `LEAVE_REQUESTS` en PendingTasksService + i18n + navegación → resuelve
    "¿dónde recibe el empresario las solicitudes?".
- ✅ **Fixes:** SSE anti-buffering (cabeceras no-transform + padding) *(b8201d1)*; modal de
  firma PWA salía siempre por `display:flex` inline venciendo a `.hidden` *(c2e5043)*.

**Cerrado DESPUÉS de las pruebas (mismo día, ya en `feat/Benjamin`):**
- ✅ **Merge `--no-ff` a develop** del bloque inicial *(merge 496c6f3)*.
- ✅ **Pollers 5s → push** *(9fb47fb)* — quita el parpadeo: `AdvisoryInvitationService` emite
  eventos SSE (invitation/clients) en create/accept/reject/revoke/unlink; el escritorio
  refresca por push y ya NO crea Timeline de 5s (solo carga inicial).
- ✅ **AEAT-ED-1/2/3** — editores específicos y editables 347 *(59a728e)*, 390 y 190
  *(2cba953)*, fieles a CONTENDO, sustituyen el editor JSON genérico. Recalcular desde
  facturas + guardado. (Split de `t()` → `tEs()` por límite 64KB de bytecode.)
- ✅ **Fix push escritorio al firmar nómina** *(3babd8f)* — faltaba suscribir Nóminas a TOPIC_PAYSLIPS.

**Pendiente (siguiente sesión / por decidir):**
- ⬜ **DISEÑO módulo Comunicación asesoría↔clientes** (pedido Benjamin 2026-06-21): darle el
  mismo aire moderno que el Gestor-Navegador (azul de acento, look limpio). OJO: ese módulo
  es JavaFX → NO es FlatLaf (eso es Swing, solo del navegador); es pulir su CSS/layout
  (tabs Mensajes/Documentos, ~`tMessages`/`tDocs`) con la paleta de app.css. NO OLVIDAR.
- ⬜ **FJ-5b** — incidencia "schedule-aware" (esperado vs fichado por día cerrado). Flag
  **legal-sensible** → validar con caso real.
- ⬜ **Push instantáneo en PWA** — el quick-tunnel bufferiza SSE; aparcado (Benjamin no tiene
  cuenta Cloudflare). Para instantáneo en móvil: **túnel con nombre** o **WebSocket**.
  Mientras: fallback de sondeo 18s en la PWA.
- ⬜ **Merge a develop** del trabajo post-pruebas (de-flicker + AEAT editores) cuando se valide.
- Mejoras menores: email al jefe con el PDF al firmar; encadenar sugerencias FJ; calendario
  semanal en "Mi jornada"; AEAT 180 (arrendamientos) editor si se quiere.

---

## 📅 SESIÓN 2026-06-19 — Autónoma (Benjamin fuera hasta 19:00). Bloques A + B (FIN)

> Benjamin dejó cola decidida: cerrar A, B, C, D + GESTOR-NAVEGADOR (E y F a otra
> sesión). Decisiones: bloque D = construir cálculo pero MARCAR para validar juntos;
> FORMATS-EXCHANGE = por especificación; profundidad (100% cerrado) > amplitud.
> Pruebas de flujo a las 19:00 juntos.

**Cerrado y mergeado a develop hoy (compila limpio; backend arranca limpio en puerto
aparte — context Spring + Flyway OK):**
- ✅ **ACC-TEMPLATES UI** *(edded5c → merge ff3c4fc)* — cierra el bloque Contabilidad.
  Nueva pestaña "Plantillas" en `AccountingScreen`: tabla + filtro archivadas; editor
  (cabecera + tabla editable de líneas FIXED/VARIABLE/FORMULA con pista de cuadre y
  validación); aplicar plantilla (fecha + concepto + contabilizar-ya + un campo por
  variable → genera asiento DRAFT/POSTED y refresca el Diario). `AccountingApiClient`
  list/create/update/archive/apply + `AccountingModels.EntryTemplate(+Line)`. i18n ES+EN.
- ✅ **FIN-1 cuadro de mando** *(3002195 → merge 8794dd3)* — `ClientFinancialsService`
  (tenant) reusa `SalesAndExpensesKpiService` + coste personal (64x) + ratios (margen %,
  gasto/ingreso %, personal/ingreso %) + tesorería de COBROS (sales_invoices) + aviso
  de drafts. Endpoint `/api/accounting/financials`. Pestaña "Cuadro de mando" con
  tarjetas KPI + rango de fechas.
- ✅ **FIN-2 evolución mensual** *(956691d → merge ec39dea)* — `monthlySeries(year)` (12
  meses, una query agrupada). Endpoint `/financials/monthly`. Tabla "Evolución mensual"
  bajo las tarjetas.
- ✅ **FIN-3 proyección de cierre + IS** *(7b3c7d3 → merge 88ef72f)* — `projectYearEnd`
  (extrapola YTD a 12 meses + IS 25%, orientativo, NO declaración). Endpoint
  `/financials/projection`. Sección "Proyección de cierre" con 4 tarjetas.
- ✅ **FIN-4 recomendaciones** *(fdb3013 → merge 1af1d7d)* — sección "Recomendaciones"
  con reglas sobre las cifras (vencidas, pérdida, coste personal >40%, margen ajustado,
  IVA a pagar/compensar, drafts). Calculadas en UI para pasar por i18n ES+EN.
- ✅ **FIN-5 informe PDF** *(0aedd89 → merge df560bb)* — `FinancialDashboardPdfService`
  (OpenPDF): resumen + proyección + recomendaciones + evolución mensual. Endpoint
  `/financials/export.pdf` + botón "Exportar PDF".
  **→ Bloque FIN-ANALYSIS (FIN-1..5) COMPLETO.**
- ✅ **REPORTS-PDF (Balance + PyG)** *(0d2e7ef → merge 4582d0d)* — `AccountingReportsPdfService`
  (OpenPDF): PDF del Balance de Situación y de PyG. Endpoints `/reports/{balance-sheet,
  profit-and-loss}/export.pdf` + botón "Exportar PDF" en ambas pestañas + helper `savePdf`.
  Cierra parcialmente "Export PDF de informes": **pendiente Mayor + Sumas y Saldos**.
- ✅ **ACC-TEMPLATES fix UX** *(c012964 → merge fb99233)* — feedback Benjamin: diálogo
  dimensionado (setPrefSize + resizable + CONSTRAINED_RESIZE columnas que no se cortan),
  **enums traducidos** (D/H = Debe/Haber, Tipo = Fijo/Variable/Fórmula vía codeLabelConverter),
  **cuenta = selector del PGC** (TplAccountCell, autocompletar + alta de tercero 4000/4300).
- ✅ **FIN-1b pendiente de pago a proveedores** *(83821d2 → merge ee7e66d)* — saldo acreedor
  400/410 del diario (medida robusta tras la reestructuración V45). Tarjeta + línea PDF.
- ✅ **FIN fix "por validar"** *(ade0d83 → merge bd2c386)* — el contador del cuadro de mando
  contaba TODOS los DRAFT en vez de solo los auto-propuestos (pestaña "Por validar"). Ahora
  coinciden. + atajo "Ir a Por validar" *(e2d6a1e)*.
- ✅ **PAGO-PROVEEDOR — VENCIMIENTOS (PV-1..4, núcleo funcional)** — plan en
  [`plan-pago-proveedor-vencimientos.md`](plan-pago-proveedor-vencimientos.md):
  · **PV-1** V133 `invoice_due_dates` (compras+ventas) · **PV-2** `PaymentScheduleService`
  (vencimientos + pagar contra tesorería 572/570 → asiento 400→572/570, unpay, replace)
  · **PV-3** `DueDateController` /api/due-dates · **PV-4** UI en Compras (botón
  "Vencimientos / Pago": tabla + Pagar banco/caja + **Pagar al contado** (ticket) +
  Deshacer + Editar cuadro). *(622492d, 1fc5192 → merge bbfced7, 11a51d8)*.
  ✅ **PV-5** *(3badeb4 → merge 58da3fd)* — la **conciliación bancaria marca el vencimiento
  como PAGADO** (settleByBankMovement, sin asiento nuevo). Unifica las dos vías de pago.
  ❌ **PV-6 DESCARTADO** (innecesario): el saldo acreedor 400/410 que ya usa FIN-1b **YA
  refleja los pagos** (pagar un vencimiento/conciliar reduce el saldo 400). Leer de los
  vencimientos infracontaría las compras sin vencimiento creado aún. Se queda el 400/410.
  ✅ **PV-7 COBRO POR PLAZOS** *(0c50de3 → merge 49d0110)* (decidido Benjamin: sí, cobrar
  a plazos). Hecho **con unificación** (no sistema paralelo): `syncSalesPaymentStatus`
  proyecta los vencimientos PAGADOS de venta al `payment_status`+`paid_amount` de la factura
  (los vencimientos son la fuente). UI: botón **"Vencimientos / Cobro"** en Ventas (VALIDATED)
  que reutiliza el diálogo de vencimientos con kind=SALES. *Mejora menor: labels del diálogo
  por kind ("Cobrar" vs "Pagar"). Edge case: multi-allocation no pasa por vencimientos.*
  **→ Bloque PAGO/COBRO POR VENCIMIENTOS (PV-1..7) COMPLETO** (PV-6 descartado a propósito).
- ✅ **MEMP-2 fichar desde el móvil** *(420d31b → merge 41f352b)* — `EmployeeFichajeController`
  /api/empleado/fichaje (rol EMPLOYEE, reusa TimeClockService) + pantalla de fichaje en la PWA
  (Entrada/Salida/Pausa/Vuelta + geo + estado + últimos). **Probado en vivo OK** por Benjamin
  (fichaje móvil de Marcos visible en Auditoría). Caso "empresa de servicios" cubierto.
- ✅ **Auditoría fichajes — fixes UX** *(tras feedback Benjamin)* — combo empleado con **"Todos"**
  visible en el desplegable (cellFactory/buttonCell) y seleccionado al abrir; tooltip en el
  resumen explicando que el click de fila filtra el detalle (vía de revisión de la incidencia).
- ✅ **Fix i18n source_type DUE_DATE_PAYMENT** + **CLAUDE.md §4/§10 regla dura de i18n**
  (valores de enum/estado/source_type que el backend genera necesitan clave ES+EN).
- ✅ **Fix filtro Origen (Diario)** *(b1f116e)* — listaba 13 de 19 source_types; faltaban
  nóminas/recurrente/DUE_DATE_PAYMENT/venta-PDF. Ahora completo (verificado contra BD).
- ✅ **Auto-refresh cuadro de mando** *(390fca0)* + **CLAUDE.md §4/§10 regla dura de auto-refresh**
  (toda acción → `RefreshBus.emit`; toda vista/aviso → `subscribe`; el usuario nunca refresca).
- ✅ **ASIENTO MANUAL INTUITIVO (ME-1/2/3)** *(f78bd81, e9da2fc → merge 124d4d0, 886cae2)* —
  plan en [`plan-asientos-manuales-intuitivos.md`](plan-asientos-manuales-intuitivos.md).
  **ME-1** Tab recorre la fila (cuenta→desc→debe→haber→sig. línea). **ME-2** al elegir cuenta
  de tercero (43x/40x) muestra sus facturas pendientes debajo. **ME-3** sugiere cuentas
  (histórico de co-ocurrencia + regla IVA) como botones; clic rellena línea. Backend
  `ManualEntryAssistService` + `/api/accounting/assist/*`. **Probado por Benjamin: mejor que
  CONTENDO.** ✅ **ME-2 fase 2** *(5fc376c)*: facturas pendientes CLICABLES → rellenan la línea
  del tercero + contrapartida tesorería (572) con el importe en el debe/haber correcto.
  Pendiente menor: encadenar sugerencias ME-3; clic en factura podría dejar elegir banco/caja.

> **Validación:** todo compila (backend+ui), el backend ARRANCA limpio (V133 migra OK),
> rutas nuevas 403 (mapeadas/protegidas), la PWA sirve el HTML nuevo. **MEMP-2 probado en vivo.**

**Pendiente del plan (orden sugerido):**
- ⭐ **PRÓXIMA SESIÓN (decidido Benjamin 2026-06-19): terminar el PORTAL DEL EMPLEADO (MEMP)** —
  **MEMP-3** calendario / jornada / plan del día (que el empleado vea SU horario JOR-2 + su
  jornada real JOR-1 + festivos) · **MEMP-4** vacaciones y bajas (pedir desde el móvil, con
  adjuntos) · **MEMP-5** nóminas (recibir/confirmar/firmar/descargar; falta backend de
  entrega/firma). MEMP-2 (fichar) ya está. *Sinergia: MEMP-3 comparte con FICHAJE-JORNADA la
  resolución del horario del empleado; conviene hacer FICHAJE-JORNADA antes o a la vez.*
- ⬜ **FICHAJE-JORNADA** *(pedido Benjamin 2026-06-19)* — botones de fichaje según el horario
  asignado (estilo CONTENDO: ±15 min, "solo el botón que toca"). Plan slice a slice (FJ-1..5,
  incluye la incidencia schedule-aware + acción de revisar/corregir = punto 2) en
  [`plan-fichaje-por-jornada.md`](plan-fichaje-por-jornada.md). **Feature grande → contexto fresco.**
- ⬜ **A restante**: Export PDF de **Mayor + Sumas y Saldos** (Balance+PyG ya hechos) ·
  **FORMATS-EXCHANGE** (xDiario + SUENLACE export/import, por spec, marcar para validar).
- ⬜ **PV-5/6/7** (enhancements de pago proveedor, ver arriba).
- ⬜ **C resto**: MEMP-3 (calendario/jornada) · MEMP-4 (vacaciones/bajas) · MEMP-5 (nóminas) ·
  JOR-4 · partes de día · fichajes sospechosos.
- ⬜ **D entero** (decisión Benjamin: construir + MARCAR para validar): VIG-3 menor
  (guard `hasPayslips`) · VIG-4 atrasos · CV-5 excedencias/suspensiones · CV-8 cese empresa.
- ⬜ **GESTOR-NAVEGADOR (JCEF)** Fase 1 — integración pesada (binarios nativos Chromium);
  pendiente entera. Aviso: posible muro de entorno (descarga libs nativas).
- **Nota puertos:** dejé 8080 y 8090 libres tras validar; al probar a las 19:00 se
  arranca backend fresco con el código nuevo. MariaDB 3307 intacta.

---

## 📅 SESIÓN 2026-06-18 — Jornadas + Portal empleado (PWA) + UX. Estado y pendientes

**Cerrado y mergeado a develop hoy:**
- **PORT-2 / JORNADAS completo** (JOR-1 jornada real desde fichajes + JOR-2/3
  planificación de plantillas). Ver bloque "PORT-2 JORNADAS" en Decisiones.
- **MEMP-1** (portal del empleado, PWA): invitación + activación + login PIN +
  cascarón PWA instalable (iOS arreglado: storage separado → reutilizable +
  "Copiar código" en navegador). Conectividad = **Cloudflare Tunnel** (decidido).
  Ver bloque MEMP en "Decisiones bloqueantes".
- **Máscaras de entrada** (UX global): horas `HH:mm` y fechas `dd-MM-yyyy` con los
  separadores automáticos (`EditableCells.installTimeMask/installDateMask/
  enableDateMaskOnFocus`); conversor de fecha unificado a `dd-MM-yyyy`.
- **Fix `@PathVariable` sin nombre** en WorkScheduleService (rompía asignar/bloques).
- **Editor de bloques rediseñado** tipo CONTENDO (día + copiar a días).

**PENDIENTE — UX-DIMENSIONES (barrido de campos/etiquetas truncadas):**
Diagnóstico con 2 agentes Explore (convergieron). Causa raíz: `Label` en `HBox`
sin `setMinWidth(Region.USE_PREF_SIZE)` + combos/pickers sin `setMaxWidth(MAX)` y
`GridPane` sin `ColumnConstraints` Hgrow. Helper `formLabel(...)` ya creado y
aplicado a los diálogos de horarios (Asignar + bloques). **Falta aplicar el mismo
patrón en** (file:line de BenjagestUiApplication.java, aprox.):
  - Editor de empleado: combos sexo/estado civil/régimen SS (~22383).
  - Editor/wizard de contrato: convenio/categoría/grupo SS/estado (~23867, ~23002, ~23133).
  - Suspender/finiquitar empleado: combos tipo/devengo (~20370).
  - Editor RETA tramos (~36240), calcular nómina objetivo BRUTO/NETO (~21621).
  - Long tail: algún DatePicker dentro de `Dialog<>` que no pase por el helper
    puede necesitar máscara (revisar si aparece sin separadores al usar).

**PENDIENTE — MEMP-2…5** (funciones reales de la PWA del empleado): fichar,
mi jornada/calendario/plan, vacaciones/bajas, nóminas. **Siguiente: MEMP-2 (fichar)**.
Para probar en producción: arrancar backend + `cloudflared tunnel --url
http://localhost:8080` + `BENJAGEST_PUBLIC_BASE_URL`=URL del túnel.

**PENDIENTE — JORNADAS menor:** excepciones por fecha; comparación plan-vs-real (JOR-4).

---

## ✅ ESTADO VERIFICADO — auditoría 4 agentes + verificación manual (2026-06-17)

> Barrido del código (backend + UI) para reconciliar el backlog con la realidad.
> Veredicto: el backlog estaba ~85-90% fiel. Confirmado que la app es muy completa
> en lo on-premise. Trampa recurrente detectada: **endpoint backend ≠ UI**.

**Correcciones aplicadas (estaban marcadas como hechas pero NO lo estaban del todo):**
- ✅ **BANK-IMPORT** (Norma 43 / CSV): **UI hecha 2026-06-17** (sesión autónoma,
  botón "Importar extracto" en pestaña Bancos).
- ✅ **EXPORT-CONTABLE + EXT-IMPORT** (CSV/Contasol/JSON): **UI hecha 2026-06-17**
  (pestaña "Exportar/Importar" en Contabilidad). A3/Sage siguen pendientes en backend.
- ✅ **ACC-TEMPLATES**: **UI de gestión (CRUD) hecha 2026-06-19** (edded5c) — pestaña
  "Plantillas" con editor de líneas FIXED/VARIABLE/FORMULA + diálogo de aplicar con
  variables. Cierra el bloque Contabilidad.
- ✅ **ECPN** (cambios patrimonio neto): **UI hecha 2026-06-17** (pestaña ECPN).
- Matiz **Modelos AEAT 347/390/190**: backend OK pero **editor UI genérico** (JSON);
  solo 130/303 tienen editor específico. **Benjamin pidió editores específicos
  (greenlight)** — pendiente; requiere mapear campos exactos por modelo.
- Matiz **VeriFactu**: NO_VERIFACTU (offline) ✅ completo; envío AEAT (VERI*FACTU) +
  XAdES-EPES estricto 🔵 implementado pero **NO probado contra AEAT** (bloqueado FNMT).

**Confirmado ✅ COMPLETO (backend + UI), antes con dudas:**
- **REPORTS-UI** (Mayor, Sumas y Saldos, Balance de Situación, PyG) — hecho 2026-06-17.
- **REC-BANCARIA** (conciliación asistida) — tiene diálogo UI (verificado).
- Bloques Nómina/NOM, Contratos/CTR, RETA-0..4, VIG-0..3, CV-1..3, TPB, Comunicación,
  Equipo S1, AVISOS-1, Auth/JWT/PIN, Cierre de ejercicio, Calendario fiscal,
  Modelos 130/303, fichaje RD 8/2019 de escritorio + GEO.

**Sesión autónoma 2026-06-17 (Benjamin fuera hasta 15:00) — CERRADO:**
1. BANK-IMPORT UI (`1378858`) · 2. EXPORT-CONTABLE+EXT-IMPORT UI (`0df00e3`) ·
3. ECPN tab (`277cfa7`) · 4. VIG-3 menor / bloqueo fechas (`d6006c0`).
Greenlit por Benjamin pero NO empezados (parado en punto limpio, §11.3, para no
dejar UI compleja sin probar): **ACC-TEMPLATES** (CRUD con editor de líneas),
**editores AEAT específicos 347/390/190**, **FIN-1** (cuadro de mando), **export
PDF de informes**. Plan de cada uno en su sección. Todo compila y mergeado a develop.

**Gaps reales pendientes (no empezados o parciales), por prioridad:**
- 🔴 **N2** clamp BCCC/BCCP + tiempo parcial (ignora `weekly_hours`) + grupos 8-11 (legal; validar caso real).
- 🟠 **N5** incidencias de nómina · **PORT-2 jornadas** (skeleton, falta modelo plantilla) · **ACC-TEMPLATES UI** · **editores AEAT 347/390/190** (greenlit) · **FIN-1** (greenlit).
- 🟡 **FIN-ANALYSIS** FIN-2..5 · export PDF de informes contables · VIG-4 atrasos · régimen especial IVA · OCR · AVISOS-2 cross-cartera (verificar).
- 🔵 **Decisiones/planes sin código:** MOBILE-EMPLEADO (stack) · FICHAJE-MÓVIL/KIOSCO (FM-1..5) · GESTOR-NAVEGADOR (JCEF) · DEPLOY-PKG · CV-4..8 · EQUIPO S2.
- 🔒 **Bloqueado por certificado FNMT real:** VeriFactu estricto/envío AEAT, Modelos 100/180/200/411, conectores DEHú/SS RED/SILTRA.

---

## 🔴 PRIORIDAD 1 — CERRAR EL BLOQUE NÓMINA (2026-06-16)

> Benjamin: cerrar el bloque de nóminas con lo que quede pendiente, antes de
> seguir con el resto de la cola. Orden sugerido:

- **N1 · CONTRATO-VIGENCIAS** (ascenso + derivar grupo) → bloque detallado abajo.
  ✅ **VIG-0/1/2** hechos. ✅ **VIG-3 (UI ascenso)** *(2026-06-17, 4a6d226)*:
  diálogo "Ascender / cambiar condiciones" (fecha de efecto + motivo → /promote,
  nueva vigencia, antigüedad intacta). Sigue **VIG-4** (atrasos). *Pendiente menor
  VIG-3: bloquear edición destructiva de start_date/antigüedad en contratos CON
  nóminas (requiere check backend hasPayslips).*
- **N2 · NOM paso 4 (refinamientos del clamp por grupo)** *(de item #3)* — ⬜
  **PENDIENTE, a validar con caso real (legal-sensible, toca importes):**
  desglose **BCCC/BCCP** (mín del grupo solo en contingencias comunes; mín común
  1.424,40 en AT/EP, desempleo, FOGASA, FP); **tiempo parcial** (base por horas /
  base mínima horaria, leer `weekly_hours` — hoy se ignora, sobrecotiza parciales);
  **grupos 8-11 base diaria** (base diaria × días). Validar con caso real.
- **N3 · NO-CODE de nómina** *(principio Benjamin: nada legal hardcodeado)*:
  ✅ **N3(a)** *(2026-06-17, fdc4df8)*: quitados los fallbacks 2026 a fuego de
  `SsContributionRatesService` e `IrpfRetentionService` → lanzan 422 si la tabla
  está vacía (el fallback al último año ≤ pedido se mantiene). ✅ **N3(b)**
  *(2026-06-17, a2dc7a6)*: topes de **indemnización** (33/720/45/1260/20/360 días,
  exención 180.000€) a tabla no-code `severance_params` (V127, seed 2012 = valores
  actuales, behavior-preserving) + `SeveranceParamsService` + `TerminationService`
  la lee por el año del cese + pestaña "Indemnización" en Laboral. `REFORM_2012`
  (hito legal fijo) se queda en código.
- **N4 · Bugs menores del ciclo de vida** *(auditoría 4 agentes 2026-06-16)* —
  ✅ **CERRADO** *(2026-06-17, 0cdf0e4)*: validación **NIF** (formato laxo como
  CONTENDO + único por empresa) + vacaciones del finiquito a **/365** (criterio
  legal/estándar, barrido A3Nom/INEAF; coherente con la indemnización). El
  `professional_category` ya lo guardaba el wizard (verificado); `markPaid`
  re-pago ya estaba cerrado (0e6c566).
- **N5 · Incidencias de nómina** *(item #4 de la cola)* — portar de CONTENDO:
  horas extra, ausencias/bajas, complementos variables por periodo, que alimentan
  el cálculo. (Toca el cálculo → mismo cuidado legal.)

> Hecho ya del bloque nómina: tabla de bases por grupo (V121) + cifras oficiales
> 2026 (V122) + grupo en contrato (V123) + clamp por grupo + provisión/pago de
> pagas extra + fix IRPF (SS anual acotada). Ver item #3 abajo.

---

## 🔁 FORMATS-EXCHANGE — Export/Import contable por formato estándar (decidido Benjamin 2026-06-17)

> Benjamin: no poner nombres de programas competidores en el combo, y JSON no se
> conoce. Investigación (web): no hay un estándar único, pero **xDiario** (Sage 50/
> ContaPlus/ContaSol/Aplifisa), **SUENLACE** (A3 Wolters Kluwer), **Conta3** (Cegid)
> y **CSV/Excel** (universal) son los formatos de intercambio reales. Dato: A3 carga
> el saldo de apertura EN la cuenta, no como asiento.

**Decisión Benjamin: etiquetar por FORMATO estándar (no por programa) y soportar
CSV + xDiario + SUENLACE.**
- ✅ Interino 2026-06-17: combos traducidos ("CSV / Excel (universal)", "Contasol",
  "Copia BENJAGEST (interna)") + el combo "Datos" traducido.
- ⬜ **xDiario** export+import (backend) — cubre Sage/ContaPlus/ContaSol/Aplifisa.
- ⬜ **SUENLACE** export+import (backend) — A3. Ojo apertura→saldos de cuenta.
- ⬜ Reetiquetar el combo a "xDiario (Sage/ContaPlus/ContaSol)" y "SUENLACE (A3)"
  al tenerlos. El "Contasol" actual probablemente ya sea xDiario-compatible (verificar
  AccountingExportService al implementar). A3/SAGE/XML_ESPI hoy lanzan "no implementado".
- Fuentes: ayudacontasol.sdelsol.com (C662), es-kb.sage.com (Enlace A3), criterium.es.

## 🧾 AEAT-EDITORS — Editores específicos 347/390/190 — ✅ HECHO 2026-06-20

> ✅ **HECHO 2026-06-20** (commits 59a728e + 2cba953): editores específicos y editables
> 347 (tabla terceros), 390 (casillas IVA con cuotas auto) y 190 (tabla perceptores),
> fieles a CONTENDO, con "Recalcular desde facturas" y guardado. Sustituyen el editor
> genérico JSON. (Antes: 130/303 ya tenían editor; el resto JSON.)
> Pendiente opcional: editor del 180 (arrendamientos) si se quiere; layout aún más
> fiel a las casillas oficiales si Benjamin pasa un modelo real.

---

## 📊 REPORTS-UI — Pantallas de informes contables — ✅ HECHO 2026-06-17

> Benjamin: en CONTENDO sí estaban y le gustaban; aquí faltaba la UI (el backend
> ya estaba). Construidas 4 pestañas nuevas en `AccountingScreen`.

- ✅ **Libro Mayor** — pestaña: combo de cuenta + rango → movimientos con saldo
  corriente + saldo apertura/final. `AccountingApiClient.ledger`.
- ✅ **Balance de Sumas y Saldos** — rango + filtro por grupo → debe/haber/saldo
  deudor/acreedor por cuenta + totales. `AccountingApiClient.trialBalance`.
- ✅ **Balance de Situación** — a fecha → Activo vs Patrimonio Neto y Pasivo por
  masas. `AccountingApiClient.balanceSheet`.
- ✅ **Pérdidas y Ganancias (PyG)** — rango → Ingresos / Gastos por masas +
  resultado. `AccountingApiClient.profitAndLoss`.
- ⬜ **ECPN** (`/reports/equity-changes`) — backend listo, UI no añadida (opcional).
- ⬜ **Export PDF** de estos informes — pendiente (mejora).
- Parseo JSON anidado (Balance/PyG) con `extractArrayField` + `splitJsonArray`
  (sin Jackson en UI). NOTA: ACC-BOOKS / REPORTS-CONTABLES estaban marcados ✅
  pero era solo backend; ahora la UI también está. **Pendiente: prueba visual de
  Benjamin.**

---

## 🐞 BUGS UX/NAV GLOBALES (reportados Benjamin 2026-06-16) — ✅ CERRADOS 2026-06-17

> Dos bugs globales de la capa de UI/navegación. Causa ya diagnosticada; fix en
> sesión enfocada (tocan muchos sitios → riesgo de regresión, §11.2). Hacerlos bien.
>
> **CERRADOS 2026-06-17** (feat/Benjamin): BUG-UX-2 en `7cc10fa`, BUG-NAV-1 en
> `6131984`. Pendiente: validación visual de Benjamin (toast nuevo + recarga en
> sitio) antes de mergear a develop. Hallazgo NAV-1: solo Labor (14 sitios) y
> Facturación (2 sitios, CRUD de series) tenían el bug. **Compras** ya refrescaba
> en sitio (`reloadPurchaseInvoices`) y **Contabilidad** usa instancias propias de
> `AccountingScreen`/`ClientFinancialsScreen` sin acceso al centro del padre → no
> tenían el bug. Fix con indirección `laborRefresh`/`billingRefresh` (patrón
> `reloadRetaProfiles`). Helpers nuevos reusables: `toast()`, `highlightMissing()`,
> `clearMissingOnChange()` + clases CSS `.toast`/`.field-error`.

- **BUG-NAV-1 · La acción de un sub-tab pierde los tabs generales.** En "Mi gestión"
  (y la ficha de cliente), que se montan con `buildClientDetailView` (vista con
  pestañas; Laboral = `buildClientLaborTab()`), al **validar una nómina** (y en
  general cualquier acción de los sub-tabs de Laboral) el handler llama a
  `showLaborModule()` → `setCenterAnimated(laborView standalone)`, que **reemplaza
  toda la vista con pestañas** y deja solo los tabs de personal; hay que volver a
  pulsar "Mi gestión". **Causa:** ~15 llamadas a `showLaborModule()` en los handlers
  de acción (grep `showLaborModule()`), pensadas para el módulo standalone del
  sidebar, NO para la vista embebida en ficha. **Fix:** refresco contextual — un
  `Runnable` de recarga que, embebido, refresca el holder del tab en su sitio (como
  ya se hizo con `reloadRetaProfiles` para RETA), y solo standalone use
  showLaborModule. Revisar también Facturación/Compras/Contabilidad por el mismo
  patrón (probablemente igual). Afecta a TODOS los sub-tabs según Benjamin.
- **BUG-UX-2 · Validar sin empleado cierra el diálogo y saca ventana de error.** Al
  calcular nómina sin empleado y pulsar Validar: sale un `Alert` de error Y se cierra
  el diálogo de calcular. **Correcto (Benjamin):** NO cerrar el diálogo, NO sacar
  ventana de error (no es un error, es un campo que falta); mostrar **globo de
  notificación (toast) no modal + sombrear el campo** que falta. **Fix:** en el
  diálogo de calcular nómina, `addEventFilter(ACTION)` en el botón Validar que
  `consume()` el evento si falta el empleado (evita el cierre) + helper `toast()`
  reusable + resaltar el campo. Es un patrón global (vale para todos los diálogos);
  empezar por el de calcular nómina y dejar el helper para reusar.

---

## 2026-06-16 — BLOQUE FICHAJE-MÓVIL/KIOSCO (pedido Benjamin) 📱 — ✅ MÓDULO CERRADO 2026-06-17

> **➡️ PLAN/ESTADO: [`plan-fichaje-movil-kiosco.md`](plan-fichaje-movil-kiosco.md)**.
> ✅ **Backend + frontend del fichaje kiosco/móvil COMPLETO** (2026-06-17):
> FM-1 V129 (tablas) · FM-2 KioskService+interceptor+API · FM-3/4 V130 + página web
> `/api/public/kiosk/app` (activar→PIN→fichar+foto+geo) · FM-admin pestaña "Kioscos"
> en Laboral (alta+código activación+empleados). Decisiones: PIN+QR, foto opcional
> no-facial (AEPD), geo, sin OTP. Verificado: compila, arranca, V129/V130 aplican,
> /app sirve la página, smoke OK.
> **Pendiente FUERA de este módulo:** FM-5 (fichaje→jornadas) = parte de PORT-2
> (jornadas, decisión de diseño pendiente); cola offline = fase 2; deshacer-60s =
> vía correcciones (mejora). Probar en vivo con una tablet/móvil en la LAN.
>
> Benjamin: falta el **fichaje MÓVIL y KIOSCO (tablet)** con **invitación**, igual
> que en CONTENDO. Por ley (RD-Ley 8/2019). **Bloque grande → contexto fresco.**
>
> ✅ **FM-0 explorado** (agente, 2026-06-16). Modelo CONTENDO + qué hay en BENJAGEST:
> - **Reusar (ya existe):** `time_clock_events` (V2, = `fichajes_180`) con geo
>   (GEO-FICHAR) + cadena hash + correcciones/verificaciones (V21, RD 8/2019);
>   `work_centers` con lat/lng + radio + `geo_policy` none/info/soft/strict (V89,
>   = `centros_trabajo_180` + `geoValidator.js`); `daily_work_reports` (V2, =
>   `jornadas_180`); `device_tokens` + `employees.pin_hash` (V70).
> - **Falta (crear):** tablas de KIOSCO + OTP + cola offline. CONTENDO:
>   `kiosk_devices_180` (device_token secreto + offline_pin), `kiosk_activation_tokens_180`
>   (token QR 30 min), OTP por email/SMS, offline sync.
> - **Fichaje** CONTENDO: `POST /api/fichaje` (tipo entrada|salida|descanso_inicio|
>   descanso_fin; subtipo pausa_corta|comida|trayecto; lat/lng/accuracy). Kiosco:
>   /activate, /config, /identify, /estado, /fichaje, /otp/request, /void (60s).
> - **Invitación (matiz):** en CONTENDO el kiosco se EMPAREJA con QR (token 30 min),
>   no hay invitación por email de empleados. Para BENJAGEST local: el OWNER habilita
>   al empleado (PIN, ya existe) + empareja la tablet con QR. ⚠️ Confirmar con Benjamin
>   si "invitación" = habilitar empleado + QR de tablet, o algo más (p.ej. enlace al móvil).
>
> Plan de implementación (fresco, slice a slice, compilar+verificar):
> - **FM-1**: migración kiosco (`kiosk_devices`, `kiosk_activation_tokens`,
>   `kiosk_employee_assignments`) + `otp_codes`. Additive, NO tocar AuthService core.
> - **FM-2**: `KioskController` (Java) + `KioskTokenInterceptor` (header `KioskToken`),
>   reusando `TimeClockService`/`time_clock_events` para crear el fichaje. OTP por email
>   (SES ya existe). Geo validada con `work_centers.geo_policy` (reusar).
> - **FM-3 (móvil web)**: página de fichaje servida por el backend, accesible desde el
>   móvil en la LAN (entrada/salida/pausas + geo). Responsive.
> - **FM-4 (kiosco)**: pantalla completa (idle→identificar→confirmar→OTP/PIN→éxito,
>   ventana de deshacer 60s); la misma web en modo kiosco o vista JavaFX. + cola offline.
> - **FM-5**: que lo fichado alimente jornadas/partes (PORT-2) y el calendario.
> Coherente con despliegue local ("todo es un puesto").

## 2026-06-16 — BLOQUE CONTRATO-VIGENCIAS (decidido por Benjamin) 🔵

> Decisión Benjamin tras barrido legal + competencia (A3Nom/Nóminasol/Factorial):
> el ascenso/cambio de condiciones se modela con **VIGENCIAS con fecha de efecto**
> sobre el MISMO contrato (no contrato nuevo; antigüedad intacta; variación SS no
> SEPE). Detalle en memoria `project_benjagest_ascenso_vigencias.md`. Bloque grande
> que toca el motor de nóminas → hacer con contexto fresco, slice a slice, validar.

- ✅ **VIG-0 (derivar grupo)** *(2026-06-16, commit 06c99d7)*: V124
  `professional_categories.ss_contribution_group` (1-11) + seed por categoría
  (~80 filas, defaults editables). `ContractCatalogService` expone el campo; el
  **asistente** de contrato deriva el grupo de cotización de la categoría elegida.
  *Pendiente menor: editor de catálogo de categorías en la UI para ajustar el
  grupo por categoría (hoy se ajusta por-contrato en el editor plano); revisar los
  defaults del seed por convenio.*
- ✅ **VIG-1 (tabla)** *(2026-06-16, ba3c754)*: `contract_vigencias` append-only +
  backfill (una vigencia inicial por contrato, effective_from = start_date).
- ✅ **VIG-2 (resolución en motor)** *(2026-06-16, 071639f)*:
  `PayslipService.resolveActiveContract` lee la vigencia vigente a la fecha del
  periodo (COALESCE con fallback al contrato). Behavior-preserving con 1 vigencia;
  query verificada contra BD. **Validar con caso real al ascender.**
- ✅ **VIG-3** *(backend a8bd3ab 2026-06-16; UI 4a6d226 2026-06-17)*: create()/update()
  sincronizan la vigencia (alta=crea inicial; editar=actualiza la última);
  `promote()` + endpoint `POST /contracts/{id}/promote` = ascenso con fecha de
  efecto (nueva vigencia, antigüedad intacta). **UI hecha**: botón "Ascender /
  cambiar condiciones" en el diálogo de contratos del empleado → editor en modo
  ascenso (fecha de efecto + motivo, bloquea tipo/SEPE/fechas/antigüedad/estado,
  valida la fecha con toast). `LaborApiClient.promoteContract`.
  ⬜ **Pendiente menor**: bloquear edición destructiva de start_date/antigüedad en
  contratos CON nóminas en el editor normal (requiere check backend hasPayslips).
  Distinguir cambio de categoría (variación SS) vs cambio de tipo de contrato
  (novación SEPE 100/200/300). + e2e real al ascender.
- **VIG-4 (atrasos de convenio)**: cálculo de atrasos comparando vigencias en el
  periodo afectado (caso de uso que justifica el histórico). Para más adelante.

## 2026-06-15 — PROPUESTA: GESTOR-NAVEGADOR (navegador embebido a AEAT/DEHÚ/SS RED/SILTRA) 🌐

> Idea de Benjamin: un tab por cliente (y para la propia asesoría) con un
> **navegador embebido con pestañas** a DEHÚ, AEAT, SS RED y SILTRA, logueado con
> el **certificado** ya importado del cliente, persistente hasta cerrar el
> programa. (CONTENDO lo intentó vía API/conexión directa y fue inviable.)

**Opinión crítica (Claude):** alto valor y diferencial, PERO el login con
certificado es el punto crítico:
- **JavaFX WebView NO sirve** (WebKit antiguo, sin TLS de cliente ni Autofirma →
  renderiza pero falla el login). Hay que embeber **Chromium real**: **JCEF**
  (gratis, integración pesada) o **JxBrowser** (de pago, soporta client-certs).
  Ambos suman ~150 MB al instalable.
- "Auto-login sin prompt inyectando el .p12" es lo más caro y sensible (cert en
  memoria, aislado por cliente). Sesiones AEAT/SS caducan en su servidor igual.
- **Fases:** Fase 1 = pestañas embebidas persistentes + el usuario elige el
  certificado una vez por sesión (ya enorme). Fase 2 = inyección automática del
  certificado. 
- **Cuándo:** tras cerrar la cola actual y tener el instalable (afecta peso/
  empaquetado). Fase 1 primero.
- ✅ **DECIDIDO (Benjamin 2026-06-16): usar JCEF** (gratis, siempre sin coste; NO
  JxBrowser de pago). Crear el tab en modo asesoría **por cliente** con navegador
  embebido con pestañas. **Tarea de última prioridad**: solo si se termina TODO el
  resto del backlog. Fase 1 (pestañas persistentes + el usuario elige certificado
  una vez por sesión) primero.

---

## 2026-06-15 — 🚀 COLA AUTÓNOMA (decisiones cerradas por Benjamin)

> Benjamin se va a trabajar y deja esta cola decidida para trabajo autónomo
> (CLAUDE.md §11: commit por slice + merge develop + compilar antes de commitear;
> reportar a la vuelta). Orden de ejecución y decisiones:

1. ✅ **CLIENT-CONFIG + fix no-vinculados** *(2026-06-15)* — #1 `ensure-operativa`
   (auto-activa módulos al entrar al cliente) + #2 tab "Configuración" (V119:
   cifras manuales anual/trimestral + datos de gestión: periodicidad/régimen/
   contacto/notas). Pendiente menor: toggles de módulos manuales en el tab (hoy
   auto-activados). [Spec original abajo.]
   **CLIENT-CONFIG + fix no-vinculados** — tab "Configuración" (2º lugar) en la
   ficha. **Decisión:** **auto-activar los módulos operativos** del cliente al
   gestionarlo desde la asesoría (no más error "módulo no activo") **+ toggles**
   de módulos por cliente en el tab Config. Secciones del tab: (a) datos
   fiscales/identidad (reusar `companies`/`customers` + ACT-CATALOG ya hecho);
   (b) cotización RETA manual (acceso directo a perfil); (c) **cifras manuales
   sin contabilidad: ANUAL obligatorio + desglose TRIMESTRAL opcional** (tabla
   nueva, alimenta RETA/KPIs/avisos); (d) preferencias (módulos activos, contacto,
   notas internas). Que cargar un no-vinculado NO dé error.
2. ✅ **AVISOS** *(2026-06-15)* — `PendingTasksService` (8 buckets) per-empresa +
   cartera; entrada "Tareas pendientes" en sidebar + panel con toggle Esta
   empresa/Cartera + tarjetas por severidad + "Abrir". Vale para empresario.
   Pendiente menor: badge total en la campana; añadir RETA/contratos como buckets.
   [Spec abajo.] **AVISOS** — per-empresa + cartera + empresario.
3. ✅ **Topes cotización TGSS + asiento pagas extra** *(2026-06-16)*. Pasos 1+2+3
   cableados (decisión Benjamin: hacerlo según la ley):
   - Tabla no-code de bases por GRUPO (V121) + cifras OFICIALES 2026 (V122, Orden
     PJC/297/2026) + pestaña "Bases por grupo" en Laboral.
   - **Paso 1**: V123 `employment_contracts.ss_contribution_group` (1-11, default
     7) + desplegable en el editor de contrato.
   - **Paso 2**: `PayslipService` acota la base al [mín del grupo, máx común]
     leído de la tabla por año (no-code); fallback al tope global si no hay grupo.
   - **Paso 3**: provisión MENSUAL de pagas extra no prorrateadas (640→465) +
     asiento de pago de la paga extra (465→4751/572, sin SS). Asientos DRAFT,
     try/catch, aditivos (las pagas extra no generaban asiento antes).
   - ⬜ Refinamientos PENDIENTES (paso 4, en memoria): desglose BCCC/BCCP (mín
     común para AT/EP en grupos 1-3 bajo mínimo), tiempo parcial (base horaria),
     grupos 8-11 base diaria. **A VALIDAR por Benjamin contra un caso real** los
     asientos de pagas extra antes de confiar.
4. **Incidencias de nómina** — **igual que CONTENDO** (localizar su modelo en
   `C:\Proyectos\CONTENDO GESTIONES` y portarlo): horas extra, ausencias/bajas,
   complementos variables por periodo, que alimentan el cálculo de la nómina.
5. **FIN-ANALYSIS completo** — FIN-1 (cuadro de mando: ingresos/gastos/margen/
   beneficio/coste personal %/tesorería/ratios) + FIN-2 (evolución mensual e
   interanual) + FIN-3 (proyección cierre + IS) + FIN-4 (recomendaciones para
   mejorar beneficio) + FIN-5 (informe PDF). Reusar `SalesAndExpensesKpiService`,
   `AdvisoryDashboardService`, year-close.
6. **JORNADAS UI (PORT-2)** — modelo **CONTENDO**: 1 plantilla = N bloques
   horarios, adjudicable a M empleados; partes reportados en solo-lectura hasta
   la app móvil. Backend skeleton ya en V86/V88.
7. **Asistente de ALTA de empleado completo** — wizard: datos → contrato
   (SEPE/convenio) → acceso app/PIN → perfil RETA si procede, con validaciones.
8. **Partes de día (work_logs) lado asesoría** — workflow DRAFT→APROBADO→
   FACTURADO + convertir parte aprobado en línea de `sales_invoice` al cobrar.
9. **OCR (Tess4J + Tesseract)** — integrar OCR para PDFs escaneados (importación
   facturas/calendario) + **anotar en DEPLOY-PKG** que el instalable Windows debe
   empaquetar el binario Tesseract.
10. **CENTROS-MAP** — ❌ NO por ahora (Benjamin: nos quedamos con el geocoder por
    texto).
11. ✅ **RETA-4** *(2026-06-15)* — V120 `companies.legal_form` + combo en tab
    Configuración; AUTONOMO → auto-perfil RETA de la empresa; ensure al abrir
    Perfiles. *Pendiente menor: combo también en Configuración→Empresa del
    empresario.* [Spec original:]
    **RETA-4 — forma jurídica + perfil RETA garantizado** *(decisión Benjamin
    2026-06-15)*. Añadir **forma jurídica** a la empresa (combo: AUTONOMO, S.L.,
    S.A., S.L.U., S.C., C.B., COOPERATIVA, OTRO) editable en el perfil de la
    empresa (empresario y asesoría, vinculado y no). Regla: si **AUTONOMO** → el
    propio cliente es el autónomo → auto-crear su perfil RETA con nombre/NIF de la
    empresa. Si es **sociedad** → exigir los datos del **titular OWNER** que
    cotiza RETA (company_owner ss_regime=RETA) → perfil desde el titular. Así
    SIEMPRE hay perfil RETA. (Extiende RETA-2.) Migración nueva (companies.legal_form).
12. 🔵 **FICHA-TABS — agrupar pestañas de la ficha** *(parcial 2026-06-15)*.
    ✅ **Contabilidad** agrupada en sub-tabs {Diario/Validar, Bancos, Préstamos,
    Inmovilizado}. ⬜ **Facturación** {Ventas, Clientes, Config, TPB} PENDIENTE:
    el TPB se añade/quita dinámicamente a la barra principal (onTpbActivated/
    onTpbRevoked insertan por índice y quitan por etiqueta); agruparla exige
    reescribir esa lógica para apuntar al sub-TabPane. Hacerlo con cuidado.

**AL TERMINAR LA COLA (pedido Benjamin 2026-06-15, con agentes/equipo):**
- **SEC-AUDIT** — barrido de seguridad del proyecto completo (inyección SQL,
  fuga multi-tenant, authz/`@RequiresRole`/`@RequiresModule`, secretos, cifrado
  Jasypt, validación de entrada, path traversal en ficheros, etc.) y **corregir**
  lo encontrado. Usar agentes Explore en paralelo (CLAUDE.md §2).
- **I18N-AUDIT** — verificar que TODO pasa por `t(key)` con par ES+EN y que no
  queda nada hardcodeado, **incluidos los listados/combos/enums**. *Matiz: el
  catálogo CNAE/IAE son términos legales oficiales en español (no se traducen);
  el resto de la UI sí.* Hacerlo en el mismo barrido de agentes que SEC-AUDIT.

**Bloqueado (no tocar hasta tener certificado FNMT real):** VeriFactu estricto
(XAdES/SOAP), obligaciones fabricante SIF, Modelos AEAT 100/180/200/411,
conectores DEHú y SS RED/SILTRA reales. **Para el final:** DEPLOY-PKG, CV-4..8.

---

## 2026-06-15 — CLIENT-CONFIG: tab "Configuración" en la ficha del cliente (plan)

> Decisión Benjamin: cada cliente de la asesoría tendrá un tab **"Configuración"
> en 2º lugar** (tras Resumen). Sirve para clientes **sin vínculo** (sin
> contabilidad en BENJAGEST de la que extraer datos) y para que no falle nada al
> cargar. **"Mi gestión" = solo la gestión de la propia asesoría**; lo
> cross-cartera va a notificaciones/banners (ver AVISOS).

**Contenido (las 4 secciones elegidas):**
- ⬜ **Datos fiscales/identidad**: NIF, régimen fiscal, epígrafe IAE/CNAE,
  dirección, periodicidad de modelos (mensual/trimestral). Parte ya existe en
  `companies`/`customers`; consolidar aquí.
  - ✅ **ACT-CATALOG** *(2026-06-15)* — catálogo OFICIAL CNAE-2009 (INE, 1010) +
    IAE (AEAT, 908) en `activity_catalog` (V118), descargado de las fuentes
    oficiales. Endpoint `/api/reta/activity-catalog?type=`. Editor RETA: combos
    CNAE/IAE **filtrables al teclear** (código+descripción) + custom; al elegir
    CNAE autocompleta la descripción. Reutilizable para el resto de la ficha.
- ⬜ **Cotización RETA del titular (manual)**: rendimiento neto previsto + base +
  cuota → alimenta la Revisión RETA en no vinculados (ya soportado vía
  `reta_profiles.expected_net_income`; aquí un acceso directo).
- ⬜ **Datos para extraer/estimar sin contabilidad**: cifras manuales de
  ventas/gastos/resultado por periodo para clientes que no llevan contabilidad
  aquí → alimentan avisos y KPIs. **Requiere tabla nueva** (p.ej.
  `client_manual_financials`).
- ⬜ **Preferencias de gestión**: módulos/avisos activos por cliente, vía de
  contacto, notas internas de la asesoría. **Requiere tabla/campos nuevos**.

**Notas de implementación:**
- El tab va en `buildClientDetailView`, posición 2 (tras Resumen), para TODOS los
  clientes (vinculados y no). Pensado para que cargar un no vinculado no dé error.
- Reusar lo que ya existe (NIF/IAE en companies/customers; RETA en
  reta_profiles) y añadir solo lo nuevo (financials manuales, prefs, notas).
- ⚠️ Benjamin reporta posible "error al cargar" clientes no vinculados — verificar
  el caso real (no encontrado aún en código; puede haberse resuelto con la
  Revisión RETA por-cliente).

---

## 2026-06-15 — AVISOS: centro de "Tareas pendientes" (plan, auditado por agente)

> Origen: Benjamin vio 2 asientos por validar y no se enteró ("si no entro no me
> entero"). Quiere un centro de avisos que mantenga informada a la asesoría (y
> al empresario) de TODO lo pendiente. Agente Explore auditó 35 estados
> accionables; Claude supervisó/curó el set v1.

**Arquitectura:** `PendingTasksService` agregador EN VIVO (estado actual, no
eventos) → buckets `{tipo, etiqueta, count, severidad, destino}`. Panel "Tareas
pendientes" + contador en la campana existente (`AdvisoryNotificationService` +
`buildAdvisoryNotificationsBell`). Dos ámbitos: **por empresa** (empresario / Mi
gestión / dentro de un cliente) y **cross-cliente** (asesoría sobre cartera,
reusar patrón `AdvisoryDashboardService`). Todas las tablas ya tienen índice
(company_id, status) → rápido.

**v1 (curado):**
- 🔴 Asientos DRAFT por validar · Facturas vencidas sin cobrar · Declaraciones
  fiscales que vencen sin presentar · Asiento de cierre fiscal pendiente.
- 🟠 Nóminas del mes sin generar/pagar/entregar · Facturas PENDING_CLIENT_APPROVAL
  (TPB) · **RETA fuera de tramo (RETA-3)** · DEHú pendientes · Contratos por
  vencer (ContractAlertService) · VeriFactu en ERROR.
- 🟡 Movimientos bancarios sin conciliar · Docs/mensajes cliente sin leer ·
  Notificaciones URGENT · Certificados por caducar.
- Descartado v1 (ruido/ya cubierto): clientes sin email, importaciones históricas,
  asignaciones sin módulos, colaboraciones, candidatos recurrentes (ya tiene
  banner), BOE (ya tiene pantalla).

**Plan de construcción (incremental, por riesgo):**
- ⬜ **AVISOS-1** — `PendingTasksService` **por empresa** (tenant actual) con las
  queries v1 + panel "Tareas pendientes" + badge. Resuelve la pain directamente
  (en Mi gestión / empresario / dentro de un cliente). Bajo riesgo (sin cross-tenant).
- ⬜ **AVISOS-2** — roll-up **cross-cliente** para la asesoría (recorre cartera).
  Reusar el patrón de aislamiento de `AdvisoryDashboardService` (¡cuidado
  multi-tenant!). Incluye RETA-3 con P&L real por cliente.
- ⬜ **AVISOS-3** — replicar en modo empresario (su propia empresa) — en parte
  sale gratis de AVISOS-1 si se hace agnóstico del modo.
- Inventario completo (35 fuentes) documentado para ampliar después.

---

## 2026-06-15 — RETA: split operativa + alerta de regularización (plan)

> Decisión Benjamin: RETA tiene dos naturalezas → **operativa** del autónomo
> (en la ficha) y **vigilancia** cross-cliente (admin). Casi todos los clientes
> de la asesoría son autónomos (detrás de cada empresa hay un autónomo).

- ✅ **RETA-0** *(2026-06-15)* — tramos de cotización por **año en BD** (V117
  `reta_tramos` + seed 2026) en vez de hardcodeados en Java; `suggestTramo` los
  lee de BD; **editor no-code** en Laboral → "Tramos autónomo" (clonar año +
  editar). 2027 sin tocar código. *Ojo: el seed son valores 2025 placeholder;
  revisar/ajustar al publicarse el PGE 2026.*
- ✅ **RETA-1** *(2026-06-15)* — operativa RETA movida a la ficha (pestaña
  "Autónomos (RETA)" en Mi gestión + cada cliente, reutiliza `retaView`);
  quitada del sidebar del cockpit propio (filtro `activeModules`).
- ✅ **RETA-2** *(2026-06-15)* — `ensureOwnerProfiles(companyId)` crea perfiles
  RETA para titulares con `company_owners.ss_regime IN (RETA, AUTONOMO_SOCIETARIO)`
  que no lo tengan. Idempotente, sin falsos positivos en sociedades. El scan de
  RETA-3 lo ejecuta en toda la cartera (cobertura automática). Endpoint
  POST `/api/reta/ensure-profiles`.
- ✅ **RETA-3 (alerta de regularización, cross-cliente)** *(2026-06-15)* — regla
  Benjamin = **rendimiento REAL** (P&L). `scanRegularization(year)` recorre
  empresa propia + cartera (`parent_company_id`); por empresa calcula rendimiento
  neto real (7xx haber − 6xx debe, POSTED, por company_id) → tramo del año
  (`reta_tramos`) → compara base cotizada con [base mín, máx] → marca
  UNDER/OVER/NO_BASE. UI: pestaña "Revisión RETA" en Laboral (desde Mi gestión
  cubre la cartera). Endpoint POST `/api/reta/regularization/scan`.
  **Legal-sensible: validar la regla con Benjamin** (RD-Ley 13/2022; la TGSS
  regulariza al año siguiente). Solo aplica a clientes con contabilidad en BENJAGEST.
  *Pendiente futuro: integrarla como una fuente del centro AVISOS (badge en campana).*

---

## 2026-06-15 — Sesión con Benjamin: cierre, nómina, sidebar ✅

- ✅ **CONS-CIERRE** — pantalla de cierre de ejercicio cableada en Contabilidad
  (precalcular + preview regularización + cerrar con aplicación + reabrir).
- ✅ **fix cierre** — `sumIncome` usaba `sales_invoices.total_amount` (no existe);
  corregido a `total`. El "Precalcular" ya no da *bad SQL grammar*.
- ✅ **PAY-DELIVERY** — entrega de nómina con vía + acuse de recibo (V116).
- ✅ **VG-FULL-SCAN restante** — 7 comparadores de ordenación.
- ✅ **SIDEBAR-ADMIN** *(decisión Benjamin)* — el sidebar de la asesoría queda
  como **administración** (Clientes, Equipo, Informes, Agenda, Configuración,
  Asesoría) y la **operativa del propio negocio** (Fiscal/Laboral/Facturación/
  Compras/Contabilidad) se accede entrando en **"Mi gestión"**, que ahora muestra
  las **pantallas completas** (no versiones reducidas) para la empresa propia.
- ✅ **DEPLOY-PKG** anotado — instalable Windows autocontenido (MariaDB embebida,
  "todo es un puesto", dos versiones Asesoría/Empleado); se empaqueta al terminar.
- 🔵 **Pendiente nómina**: incidencias por periodo (horas extra/bajas/variables)
  — necesita decisión de modelo (se solapa con complementos por nómina).
- 🔵 **Pendiente legal-sensible**: topes de cotización TGSS + pagas extra
  cotizadas — construir y validar con Benjamin como el IRPF.

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

- **Cerrado a fecha de hoy**: VeriFactu / Facturación + Contabilidad PGC PYMES completo +
  RD 8/2019 fichaje legal + **FICHAJE-JORNADA** + Asesoría↔cliente (sidebar dual + módulo
  **Comunicación** rediseñado) + **EQUIPO S1** + exports verificables (audit + SIF + fichajes)
  + bloque **CTR** (CTR-1..7) + **PORT-1..5 / JORNADAS** + bloque **TPB** (+ Magic Link/revocación)
  + UIs autónomas (PANORAMA, BOE, Backup, Multi-allocation, Rec. bancaria, Cal. fiscal) +
  **bloque NOM** (ciclo nómina + asientos + topes cotización) + **FIN-ANALYSIS** (cuadro de
  mando) + **PAGO/COBRO por vencimientos (PV-1..7)** + **AEAT editores 347/390/190** +
  **Portal del empleado (MEMP-1..5b: fichar/jornada/vacaciones-bajas/nóminas + firma)** +
  **notificaciones en tiempo real (SSE)** + **GESTOR-NAVEGADOR + login por certificado**.
- **🔴 Crítico abierto**: Modelos AEAT 100/180/200/411; VeriFactu estricto (XAdES + SOAP +
  alta SIF en sede AEAT); conectores DEHú/SS RED/SILTRA reales — todo **bloqueado por
  certificado FNMT real / alta SIF**.
- **🟠 Alta abierta**: Export PDF Mayor + Sumas y Saldos, FORMATS-EXCHANGE (xDiario/SUENLACE),
  incidencias de nómina (+ pagas extra cotizadas), CONTRATO-VIGENCIAS (bloque D), partes de día.
- **🟡 Media abierta**: régimen especial IVA/prorrata/criterio caja, dashboard widgets,
  CENTROS-MAP interactivo, OCR PDFs escaneados, push instantáneo PWA, EQUIPO S2.
- **🟢 Baja abierta**: Alertas de seguridad, Email personal OAuth, Google Calendar bidireccional.

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
- ✅ Libro Diario + Mayor + Sumas y Saldos (ACC-BOOKS) — **UI COMPLETA 2026-06-17**
  (Diario ya la tenía; Mayor/Sumas y Saldos añadidos en REPORTS-UI).
- Cuentas bancarias + movimientos + cobros/pagos (BANK-ACCOUNTS).
- 🔶 Importación Norma 43 + CSV bancario (BANK-IMPORT) — **solo BACKEND**
  (`BankImportService` + endpoint); **falta UI** para elegir y subir el fichero
  (verificado 2026-06-17: sin botón ni método en AccountingApiClient). La
  auto-conciliación (REC-BANCARIA) sí tiene UI.
- Préstamos + cuadro amortización (LOANS).
- Inmovilizado + amortización (ASSETS-ENTRIES).
- 🔶 **Plantillas asiento manual (ACC-TEMPLATES)** — backend con endpoints, pero
  **falta UI de gestión (CRUD)** de plantillas (verificado 2026-06-17).
- ✅ Balance situación + PyG (REPORTS-CONTABLES) — **UI COMPLETA 2026-06-17**
  (REPORTS-UI: pestañas Balance de Situación y PyG en AccountingScreen). ECPN
  (`/reports/equity-changes`) sigue 🔶 solo-backend (opcional).
- Aprendizaje contable UI (ACC-LEARN-UI).
- 🔶 Exportación contable Contasol/A3/Sage (EXPORT-CONTABLE) + EXT-IMPORT inversa —
  **solo BACKEND** (`AccountingExportService`); **falta UI** (selector de formato +
  descarga). Verificado 2026-06-17.
- 💰 Motor recurrentes (cron) con 7 kinds.
- RefreshBus publish/subscribe central.
- V59 relax UK tax_identifier para shadow companies + start-management.
- V60 `journal_entries.source_pdf_path` + visor PDF reutilizable PDFBox.
- ⚖️ Modelos AEAT **347 + 390 + 190** (AEAT-EXTRAS) — backend completo; UI con
  **editor genérico (JSON)** salvo 130/303 que tienen editor específico (matiz
  verificado 2026-06-17). Editores específicos 347/390/190 = mejora pendiente.
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

- ✅ **MEMP — Portal del empleado (móvil) — COMPLETO 2026-06-20** (MEMP-1..5b probado en
  vivo). Decisiones Benjamin 2026-06-18 que se cumplieron:
  - **Tecnología = PWA servida por Spring** (HTML/JS + manifest + service worker,
    mismo patrón que la página del kiosko). Igual que CONTENDO (Next.js + manifest.ts
    + /activar + activate-install). NO nativa, NO Next.js.
  - **Alcance = completo**: fichar, vacaciones/bajas (con adjuntos), nóminas
    (recibir/confirmar/firmar/descargar), calendario/jornada/plan del día.
  - **Caso de uso clave (Benjamin 2026-06-18)**: EMPRESA DE SERVICIOS cuyos empleados
    fichan en VARIOS clientes y lugares distintos. Aquí el kiosko NO sirve (no hay
    centro fijo) → fichaje por PWA en el móvil con GEO obligatorio. El modelo YA lo
    soporta: `time_clock_events.customer_id` + `latitude/longitude` y el `punch(...)`
    los aceptan; la geo se captura como EVIDENCIA (geo_policy `info`), no contra radio
    fijo (`strict` es solo para centros físicos/kiosko).
  - **Conectividad = CLOUDFLARE TUNNEL** (decidido Benjamin 2026-06-18). Acceso
    externo obligatorio (el empleado ficha fuera del WiFi de la oficina); túnel
    saliente del equipo on-premise → URL HTTPS sin abrir puertos del router. Gratis.
    Solo-LAN queda para clientes con un único centro físico (kiosko). **NO bloquea
    construir**: la PWA es el mismo código; el túnel se configura al empaquetar.
    Se construye LAN-first para desarrollo.
  - **Plan slices**:
    - ✅ **MEMP-1 HECHO** (2026-06-18) — invitación + activación + login + cascarón PWA.
      MEMP-1a: V132 `employee_app_invitations` + `EmployeeAppService` (admin invita /
      público activa) + `DeviceTokenService.pairEmployeeDevice` (additive, reusa modelo
      PIN). MEMP-1b: PWA servida por Spring (`/api/public/empleado/app` + manifest + sw
      + icon): activar→PIN→home con stubs. MEMP-1c: botón "Invitar al móvil" en el
      editor de empleado (enlace + código copiables). El empleado entra por
      `/api/auth/pin-login` (JWT EMPLOYEE). Verificado: V132 aplica, smoke OK, PWA sirve.
    - ✅ **MEMP-2** fichar desde la PWA *(420d31b → merge 41f352b)* — probado en vivo.
    - ✅ **MEMP-3** calendario/jornada/plan *(5b90f64)* — horario JOR-2 + real JOR-1 + festivo.
    - ✅ **MEMP-4** vacaciones/bajas con adjuntos *(17c5f01, V134)* — pedir/listar/cancelar + aprobar.
    - ✅ **MEMP-5** nóminas *(240e4ad)* + **MEMP-5b** firma del recibí estilo Sesame *(84516f1, V135)*.
    - Fuente CONTENDO: empleado*Controller.js, nominaEntregasController.js,
      app180-frontend/app/empleado + /activar.
  - **→ PORTAL DEL EMPLEADO (MEMP-1..5b) COMPLETO** (2026-06-20, probado en vivo).
- ✅ **PORT-2 JORNADAS — CERRADO 2026-06-18** (decisión Benjamin: real + planificación,
  modelo 1 plantilla = N bloques → M empleados, CONTENDO). Entregado:
  - **JOR-1** jornada REAL desde fichajes: `WorkdayService` calcula horas
    trabajadas/pausas por empleado-día agregando `time_clock_events` (flag
    `is_work_time`), `GET /api/labor/workdays`. UI: sección "Jornadas fichadas"
    en la pestaña Jornadas. **Esto cierra FM-5 (fichaje→jornada).**
  - **JOR-2** planificación: V131 `work_schedule_templates`+`work_schedule_blocks`
    +`work_schedule_assignments`; `WorkScheduleService` (CRUD plantillas, reemplazo
    de bloques con validación fin>inicio y sin solapes, asignaciones con vigencia);
    `/api/labor/schedule-templates`.
  - **JOR-3** UI: pestaña "Planificación" (Tiempo y jornada) con CRUD plantillas +
    editor de bloques por día + asignación a empleados.
  - **Pendiente menor (no bloqueante):** excepciones por fecha (CONTENDO
    `plantilla_excepciones_180`); comparación planificado-vs-real (JOR-4).

---

# 🟠 PENDIENTE — ALTA PRIORIDAD

## 💰 UI asesoría↔cliente

- ✅ 💰 **Mensajes / Documentos / Notificaciones** — cerrado en módulo **Comunicación** (COMM-MOD/COMM-LINK, V77/V78). Timeline + upload multipart + badge no leídos. Solo visible si hay vínculo asesoría↔empresario.
- ✅ 💰 **Vista panorámica asesoría** — cerrado (PANORAMA-ASESORIA, `546792c`): 5 KPIs cruzados de cartera.

## Empleados / Nóminas

- ✅ ⚖️ **Payrolls — ciclo mensual** — cerrado en bloque NOM (calcular/pagar/PDF/email + asientos devengo/pago + SS empresa vía cuotas TC).
- ✅ 💰 **Reporte coste empresa por empleado** — cerrado (NOM-6, `aa61627`): pestaña "Coste empresa" en Labor con bruto anual + SS empresa + coste total por empleado y totales al pie.
- ✅ **Entrega de nóminas con firma trabajador** *(PAY-DELIVERY, 2026-06-15)* — V116 (delivered_at, delivery_method, acknowledged_at). Pestaña Nóminas: columna "Entrega" (Pendiente/Entregada/Firmada) + botón "Entrega / acuse" (fecha + vía HAND/EMAIL/PORTAL/POSTAL + acuse del trabajador). ET art. 29.
- ✅ **Incidencias de nómina** — horas extra, complementos, ausencias, deducciones por periodo
  (INC-1..4, `NominaIncidenciaService` + V136, cableadas a `PayslipService`). *(Corrección 2026-06-30.)*
- ✅ **Topes de cotización TGSS por grupo** — cerrado (V109 `ss_contribution_base_caps` +
  V121 `ss_contribution_group_bases`, aplicados en `PayslipService`, cifras 2026).
- ✅ **Pagas extra con asiento (EXTRA_*)** — HECHO (`createExtraProvision/Accrual/Payment` +
  flag `provision_extra_pay` V126). *(Corrección 2026-06-30; las extra cotizan prorrateadas, sin cuota propia.)*
- 🟡 Revisión completa contratos + flujo alta del empleado — el modelo de **VIGENCIAS/ascenso ya está**
  (V125 + `promote()`); queda versionar complementos salariales y revisar el alta del empleado.

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
- ✅ ❓ **Integración Google Calendar bidireccional** — cerrado 2026-06-27 (GOOGLE-UNIFICADO):
  `GoogleCalendarService.sync` push agenda→Google + pull Google→agenda, a demanda, con
  credenciales centrales (`benjagest2026`) o per-instalación. *(Quitar el aviso "app no
  verificada" = PARTE B, Fase 1 de la ruta de cierre.)*
- ⬜ Importación masiva calendarios.

## Asesoría / multi-cliente

- ⬜ Permisos finos por sub-recurso (ej. `configuracion:write` sobre cliente concreto).
- ⬜ **EQUIPO S2 — permisos finos por (empleado, cliente, módulo)** — si la necesidad real aparece.

---

# 🟢 PENDIENTE — BAJA PRIORIDAD

- ⬜ Alertas de seguridad (`security_alerts_180`) — intentos login, accesos sospechosos.
- ✅ Análisis / Alertas BOE — cerrado (BOE-RSS diario + pantalla dedicada con apertura de PDF oficial).
- ⬜ Acceso PWA / móvil *(posiblemente cubierto por MOBILE-EMPLEADO)*.
- ✅ **Envío de correo por Gmail (OAuth2)** — cerrado 2026-06-27 (GOOGLE-UNIFICADO): la empresa
  conecta su Gmail y BENJAGEST envía facturas/nóminas/enlaces por ahí (sin contraseña de
  aplicación). Per-empresa, no a nivel usuario. El correo normal sigue por SMTP.

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
| 🟡 OCR Tesseract | ⬜ OCR PDFs escaneados | Sí, instalar binario nativo. Hoy PDFs imagen rechazan con 422. |
| 🟡 CENTROS-MAP | ⬜ Mapa lat/lng | WebView + Leaflet + Nominatim (offline-friendly) |
| 🟡 Régimen especial IVA / prorrata / criterio caja | UI fiscal afinado | Modelar tras un caso real de cliente que lo necesite |
| ✅ Hechas | — | — |
| App móvil empleado: stack técnico | **PWA servida por Spring** (no Capacitor/Next); MEMP-1..5b completo | |
| JORNADAS: modelo plantilla-bloques-asignación | 1 plantilla = N bloques → M empleados (CONTENDO); PORT-2 cerrado | |
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
