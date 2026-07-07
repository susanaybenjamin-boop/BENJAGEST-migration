# Compras y gastos — marco legal y decisiones de diseño

> Última revisión: 2026-07-07 (añadido: gastos/recibos sin factura — recibo de
> autónomo / cuota RETA, bloque GAS). Si la normativa AEAT cambia, actualizar
> aquí antes de modificar el módulo.

## Marco normativo

### Conservación obligatoria

- **[Real Decreto 1619/2012, de 30 de noviembre](https://www.boe.es/buscar/act.php?id=BOE-A-2012-14696)** —
  Reglamento por el que se regulan las obligaciones de facturación.
  - Art. 19: el destinatario de la factura debe **conservar la factura
    recibida** durante el plazo de prescripción tributaria.
  - Plazo general: **4 años**.
  - Plazo ampliado: **10 años** para inmovilizado material/intangible
    y operaciones con plazo de devolución de IVA superior.
- **[Real Decreto 1065/2007, de 27 de julio](https://www.boe.es/buscar/act.php?id=BOE-A-2007-15984)** —
  Reglamento General de Gestión e Inspección Tributaria, art. 29.2.f.
- **[Orden HAC/612/2017, de 27 de junio](https://www.boe.es/buscar/act.php?id=BOE-A-2017-7610)** —
  Estructura del libro registro de facturas recibidas (libro IVA
  soportado). Campos obligatorios: NIF del proveedor, número de
  factura, fecha de expedición, fecha de recepción, base imponible,
  tipo y cuota de IVA, deducible/no deducible.

### Inalterabilidad — **NO aplica** a facturas recibidas

- **[Ley 11/2021](https://www.boe.es/buscar/act.php?id=BOE-A-2021-11473)** antifraude
  y **[RD 1007/2023](https://www.boe.es/buscar/act.php?id=BOE-A-2023-24840)** SIF/VeriFactu
  obligan a inalterabilidad de **facturas EMITIDAS** (cadena hash,
  firma electrónica, envío AEAT). **No aplican a facturas recibidas**.
- En consecuencia, BENJAGEST NO genera hash encadenado ni firma
  electrónica para los gastos. Sí audita la creación y eliminación
  en `audit_events` para trazabilidad interna.

## Práctica en programas de gestión españoles

Comparativa de cómo gestionan A3 Gestión, Sage 50/200, Contasol y
FacturaPlus la "eliminación" de un gasto:

| Sistema   | Antes de cerrar período  | Tras cerrar período / presentar 303 |
| --------- | ------------------------ | ----------------------------------- |
| A3        | Eliminar libre           | Bloquea. Exige rectificativa.       |
| Sage 50   | Eliminar libre           | Bloquea. Asiento contrario.         |
| Contasol  | Eliminar libre           | Solo "marcado de baja" + nueva.     |
| FacturaPlus | Eliminar libre         | Bloquea. Rectificativa.             |

Conclusión: **eliminar es la operación natural** cuando el período no
está cerrado. La "anulación" con flag VOID es propia de las facturas
emitidas (porque ahí sí hay obligación de inalterabilidad VeriFactu),
no de las recibidas.

## Decisiones de diseño BENJAGEST

### Hoy (2026-06-05)

- **Acción**: `DELETE /api/purchases/invoices/{id}` hace **borrado
  físico** de la fila + revierte el asiento contable si lo había.
- **Auditoría**: se registra `PURCHASE_INVOICE_DELETED` con id,
  proveedor, total e importe IVA en `audit_events` antes de borrar.
  Si la Inspección de Trabajo o la AEAT requiriera la traza, la
  tenemos en el log aunque la fila no exista.
- **Re-subir** el mismo PDF tras eliminar funciona: el dedup por
  SHA-256 se rompe al borrar la fila, así que el nuevo POST genera
  una factura limpia.
- **UI**: el módulo en sidebar se llama "Compras y Gastos" /
  "Purchases & Expenses" (no solo "Compras") para alinear con la
  nomenclatura habitual de A3/Sage. El botón se llama "Eliminar"
  (no "Anular") con confirmación reforzada.

### Gastos y recibos SIN factura — recibo de autónomo / cuota RETA (bloque GAS, 2026-07-07)

Hay gastos deducibles que **no llevan factura** (basta el extracto/recibo): el más frecuente es
la **cuota de autónomo (RETA)** que la Tesorería General de la Seguridad Social (NIF
`Q2827003A`) domicilia cada mes. Fiscalmente:
- **Deducible al 100 %** en IRPF, apartado "gastos fiscalmente deducibles" del **modelo 130**,
  **por devengo** (la cuota del trimestre, no necesariamente la pagada).
- **No necesita factura**: basta el extracto bancario / recibo.
- (Fuentes: AEAT manual IRPF — gastos del titular; Infoautónomos; Holded.)

**Modelo en BENJAGEST:** se registra como **GASTO** (`purchase_invoices`) **sin IVA**, eligiendo
la cuenta de gasto (típicamente **642** "Seguridad Social a cargo de la empresa"), con proveedor
TGSS. **Dos asientos**, como CONTENDO:
1. **Devengo** (al crear el gasto): Debe `642` / Haber `400x` (proveedor TGSS).
2. **Pago** (botón "Registrar pago"): Debe `400x` / Haber `572` (banco).

Diseño (ver sesión 2026-07-07 en `backlog.md`):
- Alta manual → asiento **validado directo** (POSTED); el flujo automático (PDF/cascada) va a
  "Por validar". Al validar ahí un asiento `PURCHASE_INVOICE` se **sincroniza** el estado del
  gasto (no quedan descuadrados: asiento POSTED ↔ gasto POSTED).
- **Sin IVA** ⇒ no se genera línea `472`.
- **Recurrente**: casilla "Repetir cada mes" (o "Hacer recurrente" con selector de cuenta) →
  genera el gasto en Compras y Gastos cada mes con la cuenta fija; el pago se registra manual.
- Un gasto que viva solo como **asiento** (no como fila de Compras) **suma igual** en los KPIs
  de gastos y en los modelos (130/303/P&G): estos leen de la **contabilidad** (`SUM` del debe de
  las cuentas 6xx en asientos POSTED, `SalesAndExpensesKpiService`), no de la lista de Compras.

### Pendiente — cuando se cierre el slice de cierre fiscal

- Bloquear `DELETE` si la factura cae en un **fiscal_year LOCKED/
  CLOSED** o si su período se ha presentado en un modelo 303/347.
- Ofrecer entonces un flujo de **rectificativa**: nueva fila de
  gasto con signo negativo (estilo asiento contrario) vinculada a la
  original por `rectified_invoice_id`. Mismo enfoque que ya tenemos
  para `sales_invoices` con `rectifying_invoice_id`.
- Posible flag `is_rectifying BOOLEAN` y FK opcional
  `rectified_purchase_invoice_id`.

## Plazos prácticos

- Una empresa que pretenda solicitar devolución de IVA tarde
  (régimen general): conservar 4 años desde fin del plazo de
  presentación del último modelo afectado.
- Si la factura corresponde a inmovilizado deducible (regularizado
  en 5 o 10 años según mueble/inmueble): conservar hasta el final
  del último ejercicio de regularización + 4 años de prescripción.
- En la práctica BENJAGEST: **no purgar automáticamente** ningún
  registro. Si en el futuro se quiere eliminar histórico antiguo,
  exigir confirmación manual del operador con visibilidad clara del
  plazo legal.
