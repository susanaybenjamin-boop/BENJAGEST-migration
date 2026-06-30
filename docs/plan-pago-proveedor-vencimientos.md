# Plan — PAGO-PROVEEDOR con modelo de VENCIMIENTOS (decidido Benjamin 2026-06-19)

> ## ✅ IMPLEMENTADO (verificado en código 2026-06-30)
> PV-1..6 cerrados: migración `V133__invoice_due_dates.sql` +
> `accounting/PaymentScheduleService.java` + `DueDateController.java` (vencimientos por
> factura, saldo contra tesorería 400→572/570). Plan cerrado; se conserva como diseño
> de referencia.

> **Decisión Benjamin:** registrar el pago de compras (y simétricamente el cobro de
> ventas) con un **modelo de vencimientos completo** estilo A3/Sage: cada factura puede
> tener N vencimientos (fechas), y cada uno se **salda** contra una cuenta de
> **tesorería** (Banco 572 / Caja 570), generando el asiento `400→572/570`. Cubre los 3
> casos que pidió: **extracto bancario, ticket y caja**.

## Contexto (investigado 2026-06-19)
- **CONTENDO**: compra pagada cuando tiene `fecha_pago` (NULL = no pagada) + `metodo_pago`
  (transferencia/efectivo/tarjeta/bizum/otro). Importa extracto bancario; criterio de caja
  usa la fecha de pago.
- **BENJAGEST hoy**: `purchase_invoices` se reestructuró en V45 (sin `payment_status`). El
  pago se rastrea SOLO por **conciliación bancaria** (`bank_movements.linked_invoice_kind=
  'PURCHASE'`, contrapartida 400). No hay "marcar pagada por caja/ticket".
- **Competidores**: A3/Sage/Contasol = vencimientos saldados contra tesorería + extracto
  Norma 43 auto-concilia. Holded/Quipu/Declarando = "marcar pagado" eligiendo banco/caja +
  fecha + método; tickets/caja se registran ya pagados.
- **FIN-1b ya hecho** (83821d2): el cuadro de mando ya muestra "pendiente de pago" como
  saldo acreedor 400/410. Cuando exista el modelo de vencimientos, FIN puede leer el
  pendiente de los vencimientos PENDING (más preciso) — ver PV-6.

## Modelo de datos
Tabla nueva (additive) `invoice_due_dates` — **sirve para compras Y ventas** (simetría):
- `id` (uuid), `company_id`, `invoice_id`, `invoice_kind` ('PURCHASE' | 'SALES')
- `seq` (1,2,3…), `due_date` DATE, `amount` DECIMAL(14,2)
- `status` ('PENDING' | 'PAID'), `paid_date` DATE NULL
- `payment_method` VARCHAR ('TRANSFER'|'CASH'|'CARD'|'BIZUM'|'OTHER') NULL
- `treasury_account_code` VARCHAR NULL (572 banco / 570 caja / …)
- `journal_entry_id` VARCHAR NULL (asiento de pago generado), `bank_movement_id` NULL
- `created_at`. Índice `(company_id, invoice_kind, status, due_date)`.
- Invariante: Σ amount de los vencimientos de una factura = total de la factura.

## Slices (orden, cada uno additive + compila + commit)
- **PV-1 — Migración**: `V{N}__invoice_due_dates.sql` (tabla + índice). Backfill opcional:
  por cada factura sin vencimientos, crear 1 vencimiento = total a fecha de factura
  (PENDING) para no romper el listado. Additive, no toca seeds.
- **PV-2 — Backend servicio**: `PaymentScheduleService`:
  - `listByInvoice(kind, invoiceId)`, `replaceSchedule(kind, invoiceId, vencimientos[])`
    (valida Σ = total), `ensureDefault(kind, invoiceId)` (1 vencimiento = total).
  - `pay(dueDateId, treasuryAccountCode, paidDate, method)` → genera el asiento de pago
    (compra: `400→572/570`; venta: `572/570→430`) reutilizando el patrón de
    `PurchaseJournalEntryService` / `ManualJournalEntryService`; marca PAID + guarda
    `journal_entry_id`. Idempotente (no re-pagar). `unpay(dueDateId)` revierte.
  - Tenant-scoped; `@Transactional`.
- **PV-3 — Endpoints**: bajo `/api/purchases/{id}/due-dates` y `/api/billing/.../due-dates`
  (o un controller común `/api/due-dates`). GET/PUT schedule, POST `{id}/pay`, POST `{id}/unpay`.
  `@RequiresRole` operacional, `@RequiresModule` purchases/billing.
- **PV-4 — UI Compras**: sección **"Vencimientos"** en el detalle/editor de compra:
  tabla (fecha, importe, estado) + añadir/editar/quitar + botón **"Pagar"** (diálogo:
  cuenta de tesorería Banco 572 / Caja 570, fecha, método) → genera el asiento y refresca.
  Para **ticket/caja**: atajo "Pagar al contado" que crea 1 vencimiento ya pagado por caja.
  Diálogos **dimensionados + i18n ES/EN** (lección 2026-06-19).
- **PV-5 — Conciliación bancaria ↔ vencimientos**: al conciliar un `bank_movement` con una
  compra, marcar el/los vencimiento(s) correspondiente(s) como PAID (enlazar
  `bank_movement_id`). Reusar `BankMovementService` (no duplicar el asiento: la
  conciliación ya genera 400→572).
- **PV-6 — Integración FIN**: `pendingPayments` (y `pendingCollections`) leen de
  `invoice_due_dates` PENDING (más preciso que el saldo 400/410). Mantener fallback al
  saldo si una factura no tiene vencimientos.
- **PV-7 — Simetría Ventas (opcional)**: mismos vencimientos para cobros; sustituye el
  cálculo actual de `pendingCollections` por ventas.

## Notas
- **Legal/criterio de caja IVA**: la fecha de pago del vencimiento alimenta el criterio de
  caja (Art. 163 LIVA) — relevante si el cliente está en ese régimen.
- **Cuentas de tesorería**: ofrecer las cuentas 57x activas del PGC del cliente (banco/caja),
  reutilizando el selector de cuentas (`AccountComboCell`).
- **Riesgo**: toca el motor de asientos → validar los asientos generados con Benjamin antes
  de confiar (como el resto de lo contable legal-sensible).
