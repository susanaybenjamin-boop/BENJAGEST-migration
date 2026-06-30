# Plan — Asiento manual más intuitivo (pedido Benjamin 2026-06-19)

> ## ✅ IMPLEMENTADO (verificado en código 2026-06-30)
> ME-1/2/3 cerrados: traversal de TAB por columnas en `AccountingScreen` (`table.edit`
> por celda) + `accounting/ManualEntryAssistController.java` con `/assist/open-invoices`
> y `/assist/suggest` (`ManualEntryAssistService`). Plan cerrado; se conserva como
> diseño de referencia.

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

## ME-3 — Sugerir la(s) cuenta(s) que faltan (contexto + histórico) — NO solo IVA
**Aclaración Benjamin:** "intuitiva = tener TODAS las posibilidades como opción".
No es solo el IVA. Hay que deducir la(s) cuenta(s) probable(s) que faltan a partir
de **(a) lo que ya hay en el asiento** y **(b) el histórico de ese tercero**
(qué cuentas suele llevar ese cliente/proveedor), y **ofrecerlas como opciones**
para que el usuario elija. **El usuario manda**: son sugerencias, no autocompletado
forzado; añadir/quitar línea sigue igual.

**Ejemplos:**
- Línea1 cliente (430), línea2 IVA (477) → sugerir el grupo de ingreso que ese
  cliente suele usar (p.ej. **700** ventas) según su histórico.
- Gasto: línea1 proveedor (400), línea2 IVA soportado (472) → sugerir el **6xx**
  habitual de ese proveedor.
- Reglas de partida doble como respaldo cuando no hay histórico: 7xx→477, 6xx→472,
  430↔(700+477), 400↔(6xx+472), etc.

**Backend:** endpoint que, dadas las cuentas ya presentes en el asiento (+ tercero
si lo hay), devuelve cuentas **sugeridas ordenadas por probabilidad** combinando:
1. **Histórico**: en asientos POSTED previos que incluyan la cuenta del tercero,
   qué otras cuentas co-ocurren y con qué frecuencia (consulta a `journal_entry_lines`).
2. **Reglas aprendidas**: reutilizar `AccountingLearningService` (ya clasifica
   cuentas por NIF/keyword).
3. **Reglas de partida doble** (respaldo, sin histórico).

**UI:** al confirmar una cuenta, mostrar las sugerencias como **opciones elegibles**
(p.ej. una fila de "sugerencias" con chips/botones, o priorizadas arriba del
desplegable de la siguiente celda). Un clic rellena la cuenta de la línea; el
importe lo pone el usuario. Silenciable.
**Requiere diseño de la presentación + prueba en vivo** (que ayude sin estorbar).

## Orden sugerido
1. **ME-1 (Tab)** — es un bug y el más usado (alto valor por uso, contenido).
2. **ME-2 (facturas pendientes del tercero)** — endpoint + panel info. **Comparte
   con ME-3 la consulta al histórico/tercero**, conviene hacerlos seguidos.
3. **ME-3 (sugerencia contexto+histórico)** — el más rico: backend de sugerencias
   (histórico de co-ocurrencia + `AccountingLearningService` + reglas) + UI de
   opciones elegibles. Diseñar la presentación.

**Hacerlo con el backend levantado** para iterar el tacto del Tab y de las
sugerencias (que ayuden sin estorbar).
