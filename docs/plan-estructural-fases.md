# Plan estructural — slice a slice, archivo por archivo

> Compañero de docs/plan-legal-2035.md. Creado 2026-07-10. Prefijos de
> slice al estilo del proyecto. Cada slice: commit propio + i18n ES/EN +
> auto-refresh + VERIFICAR EN EJECUCIÓN antes de cerrar.

## FASE 1 — 2026 (consolidación)

**F1-303UI — casillas "otros tipos" en el editor del 303** ✅ HECHO 2026-07-10
- `ui/screens/TaxScreen.java`: pintar `base_otros_tipos`/`cuota_otros_tipos`
  (el backend ya las manda desde AeatExtraModelsService). i18n aeat303.*
- Cerrado: fila nueva en devengado (suma a casilla 27), prefill con Recalcular,
  se persiste en el JSON. Verificado: compila + curl real preview 303 T1/T2
  (claves presentes) + arranque UI dev sin errores. Visual queda para la
  próxima release por lotes (flujo de Benjamin: prueba sobre la instalada).

**F1-NOMTEST — tests fiscales de nómina** ✅ HECHO 2026-07-10 (fixture real pendiente)
- `backend-java/.../labor/PayslipService` → extraer cálculo puro
  `computePayslip(...)` (patrón compute130) + test con una nómina real de
  Benjamin como fixture.
- Cerrado: compute() = orquestador (carga BD) + computePayslip(EngineInputs)
  PURO. GOLDEN SNAPSHOT: 5 escenarios capturados del motor en ejecución
  ANTES del refactor (contrato 21k, 2 pagas, grupo 5, tipos 2026) y fijados
  en PayslipComputeTest — el motor extraído los reproduce al céntimo,
  incluido el solve-target. Suite completa verde (107 tests).
- ⚠️ Pendiente Benjamin: (1) aportar una nómina REAL de software oficial
  como fixture (como MOD-130-FIX); (2) al actualizar la release, smoke de
  Personal → Nóminas → Vista previa (el curl autenticado del endpoint
  refactorizado no fue posible en sesión autónoma).

**F1-SSTOPES — topes de cotización por grupo** ✅ HECHO 2026-07-10
- `PayslipService`: integrar tabla `ss_group_bases` (ya existe, V123) en el
  clamp de bases. Test con grupo 8 (base diaria) y grupo 1.
- Cerrado: el clamp por grupo YA existía (resolveGroupCaps, 2026-06-16); lo
  que faltaba era el "paso 4" — grupos DIARIOS 8-11 mensualizados ×30 (el
  mínimo 47,48/día → 1.424,40/mes antes no se aplicaba, devolvía 0). Lógica
  extraída a `groupCapsMonthly` PURA (patrón compute130) + 5 tests con las
  cifras oficiales 2026 (V122). Suite backend completa verde.

**F1-WINSVC — backend como servicio de Windows** ✅ HECHO 2026-07-12 (v0.1.35)
- Servicio con **winsw** (WinSW.NET4) en `packaging/service/`; registro con
  `install-service.ps1` (auto-eleva UAC + abre firewall 8080). Auto-arranque.
- Requisito nuevo: los datos dejan el perfil del usuario y viven en
  `%ProgramData%\BENJAGEST` (`BenjagestHome`, machine-wide) porque el servicio
  corre como LocalSystem. Migración de la BD de Benjamin hecha a mano una vez.
- **Smoke real (verificado): reiniciar Windows → backend RUNNING solo, health
  200, lee la BD en %ProgramData%, sin lanzar nada a mano.**

**F1-INSTVAR — instalador Asesoría/Puesto** ✅ HECHO 2026-07-12 (v0.1.35)
- `build-msi.ps1 -Variant advisory|puesto`: dos MSI. **advisory** = servidor
  completo (UI+backend+BD+servicio). **puesto** = 2º PC de escritorio LAN,
  solo UI apuntando al servidor por `BENJAGEST_API_BASE_URL` (`set-server.ps1`).
  `UpdateService.APP_VERSION` compartido.
- OJO nomenclatura: "puesto" = 2º escritorio de la asesoría; el EMPLEADO FINAL
  (fichar/nóminas) es la **PWA móvil** del bloque MEMP, sin instalador.
- Pendiente: probar la conexión del MSI puesto en un 2º PC real (aceptado red
  estándar como suficiente: binding 0.0.0.0 + regla de firewall).

**F1-N43 — conciliación bancaria Norma 43** ✅ HECHO 2026-07-10
- ~~Nuevo N43ImportService~~ — el plan estaba DESACTUALIZADO: REC-BANCARIA ya
  había construido BankImportService (N43+CSV), BankMovementService (matching
  factura por importe+fecha+tercero), BankReconciliationService y la pestaña
  Bancos (ClientFinancialsScreen.buildBanksTab, accesible desde la ficha del
  cliente Y desde "Mi gestión"). Endpoint POST /api/accounting/bank-imports.
- Lo que SÍ faltaba (hecho hoy): el parser N43 tenía 3 BUGS contra la norma
  AEB nunca detectados (0 tests, 0 usos): (1) importe cortado a 12 dígitos →
  ÷100 y sin céntimos; (2) registro 23 perdía el 1er carácter del concepto;
  (3) la ref externa mezclaba restos del importe con el nº de documento.
  Corregidos + parser estático testeable + VALIDACIÓN de cuadre contra el
  registro 33 (fichero que no cuadra → 400) + descripción de respaldo desde
  la referencia 2. BankImportParserTest: 6 tests con fichero sintético fiel
  a la norma. Suite 113 verde. Pendiente Benjamin: probar con un N43 real
  de su banco cuando tenga uno.

**F1-BANCO — import de extracto Excel (BBVA)** ✅ HECHO 2026-07-10 noche
- BBVA no ofrece N43 → formato XLSX en BankImportService: `XlsxLite` (lector
  .xlsx mínimo con zip+DOM del JDK, sin Apache POI), cabecera localizada por
  NOMBRE de columna (vale para otros bancos), dedup por saldo posterior
  (permite 2 pagos idénticos el mismo día), NIF con puntos normalizado.
  UI: formato XLSX + Base64 en el diálogo de la pestaña Bancos. Verificado
  contra el export REAL de Benjamin: 178 movimientos, 100 % con fecha y
  saldo. 4 tests. El parser N43 queda contrastado con el importador OCA
  (posiciones confirmadas + BOM/CtrlZ/registro 88 con tolerancia ±1).
- ⚠️ **Matiz (2026-07-12)**: aquella verificación fue del PARSER (aislado), NO
  del import end-to-end. Al probarlo Benjamin de verdad saltaron 2 bugs (ver
  F1-BANCO-CUENTA/REVIEW/AUTO abajo): la restricción `ck_bib_format` no admitía
  XLSX y `ignore()` usaba `||`. Lección: parser verde ≠ camino real verde.

**F1-BANCO-CUENTA — alta de cuenta bancaria (UI)** ✅ HECHO 2026-07-11 (v0.1.33)
- Había endpoint POST /accounting/bank-accounts pero NINGUNA UI para crear la
  cuenta → el import quedaba bloqueado en "Crea primero una cuenta bancaria".
- Botón "Nueva cuenta" en la pestaña Bancos (alias + IBAN + banco; el 572 lo
  resuelve el backend con la 572 genérica si no se enlaza una). **Confirmado en
  producción por Benjamin.**

**F1-BANCO-REVIEW — conciliación por REVISIÓN (no auto-posteo)** ✅ HECHO 2026-07-11 (v0.1.33)
- La importación deja de auto-postear a ciegas. GET /bank-movements/
  reconcile-review: por cada movimiento pendiente → factura candidata + ESTADO
  (sin cobrar/pagar · ya cobrada/pagada · borrador · sin candidata) + PAGOS
  EXISTENTES (unifica vencimientos saldados + cobros de venta + banco ya
  conciliado). UI = TreeTableView tras importar: checkbox (automarcado solo los
  pendientes) y los YA cobrados/pagados desmarcados con su **pago existente
  colgando debajo** (BENJAGEST no es multiventana). POST /bank-movements/
  reconcile concilia solo los marcados, cada linkToInvoice su transacción
  (éxito parcial). Cierra 2 agujeros del auto-match viejo: ventas roto
  (total_amount/customer_legal_name inexistentes) + compras podía duplicar.
- Bug cazado por smoke: candidata de compras usaba `purchase_invoices.
  payment_status` (columna inexistente en V39/V40) → 500. Corregido.

**F1-BANCO-AUTO — un solo import, autodetectado + Conciliar/Ignorar** ✅ HECHO 2026-07-12 (v0.1.34)
- Petición de Benjamin (sin extensiones visibles no sabe el tipo): se quita el
  selector de formato. UN SOLO "Importar extracto" que manda el fichero en
  Base64 y el backend AUTODETECTA por contenido (xlsx = firma ZIP 'PK'; texto =
  N43 si abre con registro '11' de 80+ chars, si no CSV). El batch guarda el
  formato DETECTADO.
- **V176**: `ck_bib_format` no permitía 'XLSX' → import de Excel daba 500. Fix.
- Errores del backend legibles en la UI (no JSON/SQL crudo; 5xx → texto genérico).
- Bloque bancario completo: botón "Conciliar" (reabre la revisión cuando
  quieras) + botón "Ignorar movimiento" (comisiones sin factura). Bug cazado:
  `ignore()` usaba `||` (OR en MariaDB) → 500; fix `CONCAT`.
- Smoke end-to-end verde (BD embebida + import real XLSX y CSV + Ignorar +
  reconcile-review). **BLOQUE BANCARIO CERRADO.**

**FAC-CLIVAL — no emitir factura a cliente incompleto** ✅ HECHO 2026-07-12 (v0.1.36, `343c0940`)
- Bug real (Benjamin): se podía emitir factura ordinaria a un cliente sin
  NIF/dirección sin aviso; el PDF salía con campos en blanco. Ni UI ni backend
  ni VeriFactu validaban al destinatario.
- `InvoiceEditorScreen.persistDraft`: factura NO simplificada + falta NIF, nombre,
  dirección, población, provincia o CP → aviso "Faltan datos del cliente" + botón
  "Completar datos" (modal de cliente reutilizado vía nuevo `Host.openCustomerEditor`)
  → al guardar recarga, re-selecciona y REINTENTA la emisión. Bloquea hasta
  completar (art. 6 RD 1619/2012). i18n ES+EN. **Pendiente smoke visual de Benjamin.**

**F1-XDIARIO — export xDiario/SUENLACE** (ya especificado en backlog líneas
  1731-1733): `accounting/export/XDiarioExportService` + combo en informes.

**F1-GOOGLE — verificación + Gmail API** (tareas de Benjamin en consola
  Google; sin código salvo probar). Requiere web pública (fase D-WEB).

## FASE 2 — VF3-FINAL antes de 1-ene-2027

**VF3-CERT — certificado y entorno**
- Benjamin: FNMT de representante + alta SIF en sede AEAT (pruebas).
- `backend/.../billing/verifactu/VerifactuConfig*`: campos keystore path/pin
  por empresa (cifrados con FieldCipher, patrón RGPD).

**VF3-XSD (=VF3-XML) — XML oficial** ✅ HECHO 2026-07-12 (`5c7cc332`)
- `AeatRegistroAltaXmlBuilder` (clase PURA, patrón `VerifactuHashService`):
  construye el `<RegistroAlta>` según `SuministroInformacion.xsd` (orden del
  XSD: IDVersion, IDFactura, TipoFactura, DescripcionOperacion, Destinatarios,
  Desglose por tipo de IVA, CuotaTotal/ImporteTotal, Encadenamiento, Sistema-
  Informatico, FechaHoraHusoGenRegistro, TipoHuella=01, Huella). 4 tests golden.
- **Hallazgo**: la HUELLA de `VerifactuHashService` YA es el canónico oficial
  (portado de CONTENDO validado contra AEAT) → no se rehace.
- Pendiente dentro de VF3: VF3-SIF (identidad del SIF, decide Benjamin al darse
  de alta como productor) · VF3-CHAIN (persistir nº+fecha del registro anterior).

**VF3-XADES — NO aplica a VeriFactu** ~~firma estricta~~
- **Corregido 2026-07-12**: en el XSD `ds:Signature` es 0..1; el modo VeriFactu
  (con remisión) NO exige firma XAdES (la cadena de huella + el cert del TLS
  bastan). El XAdES solo lo exige el modo NO-VeriFactu (offline). Queda para el
  día que se implemente NO-VeriFactu offline; VF3-FINAL no lo necesita.

**VF3-SOAP (=VF3-SEND) — envío real**
- `AeatVerifactuClient`: embeber el RegistroAlta de `AeatRegistroAltaXmlBuilder`
  en el SOAP; quitar TrustManager permisivo (CAs del sistema); parseo
  Aceptado/AceptadoConErrores/Rechazado; cola de reintento + estados en la UI.

**VF3-SWITCH — cambio de modalidad**
- `VerifactuConfig` + UI Configuración→Facturación: selector NO VERIFACTU ↔
  VERIFACTU por empresa con asistente (cierra cadena local, primer envío).
  `InvoiceQrService.complianceLabel` ya es correcto (QR-LEY).

**VF3-E2E — validación en pruebas AEAT** con facturas reales + simulacro
  de requerimiento (export registros+eventos, ya existe el registro SIF).

## FASE 3 — jul-2027 (sin código nuevo)
- ONB-1: checklist por cliente de la cartera (modalidad, cadena verificada,
  declaración responsable visible). Guía en docs/.

## FASE 4 — e-factura B2B (esperar la ORDEN; diseño preliminar)
- B2B-1 `billing/einvoice/Facturae4Writer` + `UblWriter` (EN 16931) desde
  `SalesInvoice` — y `EInvoiceReader` para GASTOS (sustituye OCR cuando el
  proveedor mande e-factura).
- B2B-2 conexión solución pública AEAT (API por definir en la orden).
- B2B-3 estados: tabla `invoice_status_events` + comunicar pago ≤4 días
  (enganchar a PV-4/vencimientos: al registrar pago → estado automático).
- B2B-4 buzón de recepción + bandeja "Facturas recibidas electrónicas".

## FASE 5 — ViDA/DRR 2030 (diseño cuando haya norma técnica)
- DRR-1 `aeat/drr/` módulo de reporte intracomunitario en tiempo casi real;
  el 349 pasa a derivarse de él. Reusar outbox/reintentos de VF3-SOAP.

## FASE 6 — 2031-2035
- CONV-1 adaptación al estándar UE (transposición española pendiente).
- AUD-2034 auditoría externa completa.

## Orden de ataque (actualizado 2026-07-10 noche)
HECHOS: F1-303UI · F1-SSTOPES · F1-NOMTEST (+ fixture con 2 nóminas
REALES) · F1-N43 (parser corregido + contrastado con OCA) · F1-BANCO
(import XLSX BBVA, verificado con extracto real). Releases v0.1.30/31/32.

Quedan, en orden:
1. **Cuadre 130 2T con la asesoría** (operativa, no código): BENJAGEST
   693,36 con la nómina de Rubén dentro; a la asesoría le sobran +580 de
   ingresos y ~1.477 de gastos por justificar.
2. **F1-WINSVC + F1-INSTVAR** — CON Benjamin (reinicio + instalación).
3. **VF3 completo** — DESBLOQUEADO: el certificado FNMT de Benjamin ya
   está localizado (ver memoria/backlog). Cuando él quiera.
4. F1-XDIARIO — aparcado sin fichero de referencia real.
5. F1-GOOGLE — necesita dominio/web (fase D).
