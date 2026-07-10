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

**F1-WINSVC — backend como servicio de Windows**
- `build-msi.ps1` + WiX: instalar servicio (winsw o jpackage service) con
  auto-arranque; `EmbeddedMariaDbConfig` ya tolera reinicios. Smoke:
  reiniciar Windows → app conecta sin lanzar backend a mano.

**F1-INSTVAR — instalador Asesoría/Empleado**
- `build-msi.ps1 -Variant advisory|employee`: dos MSI (el de empleado solo
  UI apuntando a servidor LAN); `UpdateService.APP_VERSION` compartido.

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

**F1-XDIARIO — export xDiario/SUENLACE** (ya especificado en backlog líneas
  1731-1733): `accounting/export/XDiarioExportService` + combo en informes.

**F1-GOOGLE — verificación + Gmail API** (tareas de Benjamin en consola
  Google; sin código salvo probar). Requiere web pública (fase D-WEB).

## FASE 2 — VF3-FINAL antes de 1-ene-2027

**VF3-CERT — certificado y entorno**
- Benjamin: FNMT de representante + alta SIF en sede AEAT (pruebas).
- `backend/.../billing/verifactu/VerifactuConfig*`: campos keystore path/pin
  por empresa (cifrados con FieldCipher, patrón RGPD).

**VF3-XSD — XML oficial**
- `AeatVerifactuClient.java` (TODOs líneas ~134/172/206): sustituir el XML
  artesanal por JAXB generado desde los XSD oficiales AEAT (SuministroLR).
  Test: validar contra XSD en build.

**VF3-XADES — firma estricta**
- `XmlSignerService`: XAdES-EPES con SignaturePolicyIdentifier +
  SigningCertificate (librería xades4j). Test de estructura de firma.

**VF3-SOAP — envío real**
- `AeatVerifactuClient`: quitar TrustManager permisivo; SOAP real con
  parseo Aceptado/AceptadoConErrores/Rechazado; cola de reintento
  (tabla `verifactu_outbox` V17x) + reenvío programado; estados visibles
  en la UI de facturación (badge por factura).

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

## Orden de ataque propuesto (próximas sesiones)
1. F1-303UI (corto) → 2. F1-NOMTEST + F1-SSTOPES → 3. F1-WINSVC +
   F1-INSTVAR → 4. F1-N43 → 5. F1-XDIARIO → 6. VF3 completo (cuando
   Benjamin tenga el certificado FNMT — pedirlo YA, tarda).
