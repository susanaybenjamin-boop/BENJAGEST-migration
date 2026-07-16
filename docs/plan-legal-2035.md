# Plan BENJAGEST → 1 de enero de 2035

> Creado 2026-07-10 con Benjamin, tras verificar contra el BOE las fechas
> vigentes (RDL 15/2025, RD 238/2026, Directiva UE 2025/516 "ViDA").
> Regla: cada fase se cierra con VERIFICAR EN EJECUCIÓN + tests fiscales
> con declaraciones/documentos reales. Vigilancia normativa continua:
> revisar BOE/sede AEAT al inicio de cada fase (las fechas se han
> prorrogado ya DOS veces y la orden de la solución pública B2B está
> pendiente — ninguna fecha de este plan es inmutable).

> ## ⚠️ RE-ENCUADRE 2026-07-16 — BENJAGEST de USO PROPIO (posible)
>
> Benjamin está pensando en **dejar BENJAGEST solo para él y NO sacarla a
> la venta**. Y ha confirmado su perfil: **factura a particulares Y a
> empresas/autónomos; NO hace operaciones intracomunitarias.** Esto cambia
> el alcance legal de raíz — de "fabricante de software comercial" a
> "autónomo con su propia herramienta de uso propio":
>
> - **FASE 5 (ViDA/DRR intracomunitario, 2030): NO LE APLICA.** Sin
>   operaciones intracomunitarias, no hay DRR ni e-factura intracomunitaria.
>   El hito 2030 se cae para Benjamin.
> - **FASE 4 (e-factura B2B, 2028): aplica SOLO a la parte a empresas/
>   autónomos** (no a particulares). Depende de la orden de la solución
>   pública, aún sin publicar → no se construye hasta que salga.
> - **FASE 2/3 (VERI*FACTU): sigue aplicando**, como AUTÓNOMO → **1-jul-2027**
>   (no 1-ene-2027; no tiene clientes sociedad). Software de uso propio: el
>   propio Benjamin es productor+usuario y suscribe la declaración
>   responsable para su sistema (ya está en el programa). *Ojo: los detalles
>   finos del régimen "uso propio" del RD 1007/2023 NO están verificados —
>   confirmar en fuente antes de dar pasos.*
>
> **Toda la carga regulatoria futura vive en UN sitio: la emisión de
> facturas** (VERI*FACTU + e-factura B2B). El resto de BENJAGEST
> (contabilidad, 303/130, nóminas, conciliación, gestión) NO tiene estas
> obligaciones. La emisión es a la vez lo más arriesgado de construir bien
> (ver auditoría 2026-07-16: el envío a AEAT está a medias, piezas
> desconectadas, nunca probado contra AEAT) y lo de menor valor diferencial.
>
> **DECISIÓN PENDIENTE (no urge — tomarla a principios de 2027):**
> - **(A) DELEGAR la emisión** en un programa ya certificado (los hay
>   gratuitos) e importar a BENJAGEST para la contabilidad (ya sabe importar
>   PDF). Elimina de golpe el único bloque arriesgado (VERI*FACTU real) y el
>   B2B de 2028. **Recomendada** salvo que el objetivo sea aprender.
> - **(B) CONSTRUIR VERI*FACTU real** en BENJAGEST. Legítima solo si el
>   objetivo es aprendizaje/autonomía total. Trabajo grande + riesgo de no
>   pasar la validación AEAT, para emitir facturas de un solo autónomo.
>
> Hoy BENJAGEST es LEGAL en NO_VERIFACTU. Lo sensato: no construir nada de
> esto todavía; decidir A/B a principios de 2027 con la norma más asentada.
>
> ### Auditoría de estado del código VERI*FACTU (2026-07-16)
> Verificado leyendo el código: **HECHO** = huella canónica
> (`VerifactuHashService`, heredada de CONTENDO, NO reverificada contra caso
> oficial AEAT en este repo), cadena encadenada por huella (detección, NO
> prevención — sin triggers BD), XML `RegistroAlta` al XSD
> (`AeatRegistroAltaXmlBuilder` + 4 golden tests) **pero HUÉRFANO** (no
> cableado al envío), QR + leyenda condicional (bien, verificado BOE),
> modalidad VERIFACTU/NO_VERIFACTU (hoy NO_VERIFACTU). **A MEDIAS / NO
> probado**: cliente SOAP `AeatVerifactuClient` (envía el XML INTERNO, no el
> XSD → AEAT lo rechazaría; TrustManager permisivo inseguro; respuesta no
> parseada; NUNCA probado contra AEAT). **NO EXISTE**: `RegistroAnulacion`
> XSD, XAdES (solo XML-DSig; opcional para VERIFACTU), e-factura B2B
> (Facturae/EN16931/UBL), DRR. **Bloqueante externo real**: alta/identidad
> del SIF + validación en PREPRODUCCIÓN AEAT — sin eso, ningún código lo
> desbloquea.

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

**Hallazgos 2026-07-12 (contra el XSD oficial SuministroInformacion.xsd):**
la HUELLA ya es correcta (`VerifactuHashService` = canónico oficial, validado
contra AEAT vía CONTENDO); y el **XAdES NO hace falta para VeriFactu** — en el
XSD `ds:Signature` es opcional (0..1), solo el modo NO-VeriFactu (offline) exige
firma. Esto reduce el trabajo real. Slices:
- [x] **VF3-XML** — `AeatRegistroAltaXmlBuilder` (RegistroAlta al XSD oficial) +
      4 tests golden. HECHO (`5c7cc332`).
- [ ] **VF3-SIF** — identidad del SIF (NombreSistemaInformatico, IdSistemaInformatico
      [2 chars], Versión, NºInstalación, NombreRazon/NIF del PRODUCTOR = Benjamin).
      **Requiere que Benjamin decida darse de alta como productor de software SIF
      en sede AEAT.**
- [ ] **VF3-CHAIN** — persistir nº+fecha del registro anterior (hoy solo la huella)
      para el bloque `RegistroAnterior` del Encadenamiento.
- [ ] **VF3-SEND** — embeber el RegistroAlta real en el SOAP de `AeatVerifactuClient`,
      TrustManager real (CAs del sistema, no permisivo), parseo
      Aceptado/AceptadoConErrores/Rechazado + reintentos/subsanación.
- [ ] **VF3-CERT** — cargar el .p12 FNMT (sesión conjunta, contraseña de Benjamin)
      + alta como SIF → validar en PREPRODUCCIÓN AEAT (mode=TEST, `prewww1.aeat.es`).
- [ ] Selector de modalidad por empresa + migración asistida
      NO VERIFACTU → VERIFACTU (cadenas intactas).

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
> **⚠️ NO APLICA A BENJAMIN** (uso propio, sin operaciones intracomunitarias
> — re-encuadre 2026-07-16). Se conserva por si cambia la actividad.
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
