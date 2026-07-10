# Plan BENJAGEST → 1 de enero de 2035

> Creado 2026-07-10 con Benjamin, tras verificar contra el BOE las fechas
> vigentes (RDL 15/2025, RD 238/2026, Directiva UE 2025/516 "ViDA").
> Regla: cada fase se cierra con VERIFICAR EN EJECUCIÓN + tests fiscales
> con declaraciones/documentos reales. Vigilancia normativa continua:
> revisar BOE/sede AEAT al inicio de cada fase (las fechas se han
> prorrogado ya DOS veces y la orden de la solución pública B2B está
> pendiente — ninguna fecha de este plan es inmutable).

## FASE 1 — 2026 (ahora → dic-2026): consolidar el producto en NO VERI*FACTU
**Ya conforme hoy** (SIF con huella encadenada, eventos, QR en ambas
modalidades, leyenda solo en VERIFACTU [QR-LEY], declaración responsable,
bloqueo de cierre, rectificativas R1-R5, RGPD técnico).
- [ ] Operativa Benjamin: cuadre 130/303 1T-2T con la asesoría (duplicado
      Los Llanos 263274, gasoil 6281→628, IVA gasto viejo Loren) + decisión
      vehículo 50% → regla DEDUC.
- [ ] Tests fiscales de nómina + fixture 303 casilla a casilla.
- [x] UI editor 303: pintar base/cuota_otros_tipos (F1-303UI, 2026-07-10).
- [ ] Producto (fase C auditoría): backend como servicio de Windows,
      instalador Asesoría/Empleado, verificación Google + Gmail API,
      topes SS en nómina, conciliación N43, export xDiario/SUENLACE.
- [ ] RGPD papel: política de privacidad, RAT (además desbloquea Google).

## FASE 2 — ene→dic 2026/27 (duro: antes del 1-ene-2027): VERI*FACTU real (VF3-FINAL)
Las SOCIEDADES de la cartera quedan obligadas el 1-ene-2027.
- [ ] Certificado FNMT + alta como SIF en sede AEAT (entorno de pruebas).
- [ ] XML según XSD oficial AEAT + firma XAdES-EPES estricta
      (SignaturePolicyIdentifier, SigningCertificate).
- [ ] Cliente SOAP real: envío, parseo Aceptado/AceptadoConErrores/
      Rechazado, reintentos, flujo de subsanación.
- [ ] Selector de modalidad por empresa + migración asistida
      NO VERIFACTU → VERIFACTU (cadenas intactas).
- [ ] Validación en el entorno de pruebas AEAT con facturas reales.

## FASE 3 — 1-jul-2027: autónomos obligados (Benjamin incluido)
- [ ] Onboarding de la cartera: activar modalidad elegida por cliente,
      verificación de cadenas, declaración responsable visible.
- [ ] Simulacro de requerimiento AEAT (export de registros + eventos).

## FASE 4 — 2027-2028: factura electrónica B2B (RD 238/2026, Crea y Crece)
Trigger real: la ORDEN de la solución pública (vigilar BOE). +12 meses
obligatoria >8M€; +24 meses el resto (≈2028, incluye autónomos).
- [ ] Motor EN 16931: emitir y LEER Facturae 4.x y UBL (la importación de
      gastos ganará precisión total frente al OCR).
- [ ] Interconexión con la solución pública AEAT (UBL de referencia,
      copia fiel Facturae) + buzón de recepción.
- [ ] Estados de factura: aceptación/rechazo comercial y comunicación de
      PAGO EFECTIVO en ≤4 días naturales (encaja con nuestro módulo de
      vencimientos/pagos — automatizar el estado al registrar el pago).
- [ ] Convivencia VeriFactu (integridad del registro) + B2B (formato e
      intercambio): una emisión alimenta ambos.

## FASE 5 — 2029-2030: ViDA / DRR intracomunitario (1-jul-2030)
- [ ] E-factura EN 16931 obligatoria en B2B intracomunitario, emisión ≤10
      días desde el devengo.
- [ ] Módulo DRR: reporte digital transaccional casi en tiempo real de
      operaciones intracomunitarias (sustituye al modelo 349 — nuestro 349
      pasa a generarse desde el DRR).

## FASE 6 — 2031-2035: convergencia (1-ene-2035)
- [ ] Adaptar VeriFactu al estándar armonizado UE cuando España publique
      la transposición (los sistemas nacionales deben converger).
- [ ] Revisión completa de cumplimiento + auditoría externa antes de 2035.

## Transversales (todas las fases)
- Vigilancia BOE/AEAT al abrir cada fase (afinar BOE-RSS del backlog).
- Tests fiscales con documentos reales como fixtures (patrón MOD-130-FIX).
- Backups verificados incluyendo ~/.benjagest/.dbkey.
- Comercial (fase D): marca, web pública (privacidad — desbloquea Google
  y la declaración responsable pública), soporte — cuando Benjamin decida.
