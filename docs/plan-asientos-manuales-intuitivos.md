# Plan — Asiento manual más intuitivo (pedido Benjamin 2026-06-19)

> Filosofía (Benjamin): **el usuario manda**. Todo son ayudas/sugerencias; el
> asesor sigue pudiendo **añadir/quitar línea** y teclear lo que quiera. Editor
> afectado: `AccountingScreen.buildEntryDialog` (tabla editable `EditableLine`
> con celda de cuenta `AccountComboCell` + descripción/debe/haber).

## ME-1 — Tab recorre la fila (no baja de columna)
**Bug:** al elegir cuenta y pulsar Tab, el foco baja a la celda *Cuenta* de la
línea siguiente, en vez de avanzar **cuenta → descripción → debe → haber** y, al
final de la fila, saltar a *Cuenta* de la línea siguiente.
**Causa probable:** `AccountComboCell` es un `ComboBox` (GRAPHIC_ONLY) que consume
el Tab; la traversal por defecto de `TableView` no avanza por columnas.
**Enfoque:** interceptar TAB en las celdas (al menos en la de cuenta tras
`persistCurrentValue`) y llamar `table.edit(row, siguienteColumna)` en orden;
al pasar de *haber*, `table.edit(row+1, cuenta)` (creando línea si es la última).
**Requiere prueba en vivo** (el comportamiento de foco en JavaFX hay que verlo).

## ME-2 — Facturas pendientes del tercero al elegir su cuenta
Al seleccionar en *Cuenta* un código de **cliente (430xxxx)** o **proveedor
(400xxxx)**, mostrar debajo del editor (panel info) las **facturas impagadas**
de ese tercero (nº, fecha, total, pendiente), para cobro/pago manual.
**Backend:** endpoint `GET /api/accounting/third-party/{code}/open-invoices`
(o por NIF del tercero): resuelve la cuenta → tercero (customer/supplier por
`tercero_account` / NIF) → facturas con `payment_status IN (PENDING,PARTIAL)`
(ventas) o vencimientos pendientes (compras, vía `invoice_due_dates`).
**UI:** al `persist` de una cuenta 430/400 en `AccountComboCell`, cargar async y
pintar una tabla pequeña read-only bajo el asiento. Solo informativo (de momento;
fase 2: doble-click rellena debe/haber con el pendiente).

## ME-3 — Sugerir la cuenta de contrapartida (IVA)
Al teclear/elegir una cuenta de **ingreso 7xx** → proponer línea **477** (IVA
repercutido). De **gasto 6xx** → proponer **472** (IVA soportado). Ejemplo
Benjamin: línea1 cliente (430), línea2 700 → aparece línea3 con 477.
**Enfoque (gentle, no intrusivo):** al `persist` de una cuenta 6xx/7xx, si no
existe ya una línea con su IVA (472/477), **rellenar la primera línea en blanco**
debajo con ese código (sin importe — lo pone el usuario), o añadir una nueva.
Nunca tocar importes ni forzar. Configurable/silenciable si molesta.
**Mapa inicial:** `7→477`, `6→472`. (Ampliable: retenciones 4751 para 64x, etc.)
**Requiere prueba en vivo** para validar que el automatismo no estorba.

## Orden sugerido
1. **ME-1 (Tab)** — es un bug y el más usado (alto valor por uso).
2. **ME-3 (sugerencia IVA)** — UI pura, alto "intuitivo", bajo riesgo (línea
   editable/quitable).
3. **ME-2 (facturas pendientes)** — el más potente; necesita endpoint + UI.

**Hacerlo con el backend levantado** para iterar el tacto del Tab y la sugerencia.
