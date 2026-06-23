# Bloque REFLEJO — Factura de la asesoría ⇒ gasto del cliente

> Diseño acordado a partir de la sesión 2026-06-23. Estado: **propuesto,
> pendiente de decisiones de Benjamin antes de codificar.**

## 1. Objetivo

Cuando la asesoría (empresa `INTERNAL`) **emite una factura a un cliente
suyo**, ese mismo documento debe aparecer **automáticamente en los libros
del cliente como factura recibida (gasto)** con su asiento contable. Y
cuando se cobra, reflejar también el **pago** en los asientos del cliente.

Es el mismo documento fiscal visto por las dos caras:
- Asesoría → factura **emitida** (ingreso). Ya existe.
- Cliente → factura **recibida** (gasto). **Esto es lo nuevo.**

## 2. Modelo de datos (verificado en BD viva, 2026-06-23)

- `companies.company_type` ∈ { `INTERNAL` (la asesoría), `CLIENT`
  (cliente-tenant con login), `MANAGED_CLIENT` (ficha gestionada, sin
  login) }. Los clientes cuelgan de la asesoría por `parent_company_id`.
- **Enlace customer ⇒ empresa-cliente = por NIF** (`customers.tax_identifier`
  = `companies.tax_identifier`). No hay columna directa; el NIF es la clave.
- Tanto `CLIENT` como `MANAGED_CLIENT` son `companies` con su propio
  `company_id`, **plan contable completo** (600/472/400/**623**) y
  **ejercicio fiscal 2026 OPEN**. → Se les puede asentar gasto y pago.
- Los `customers` que NO casan con ninguna `company` (clientes-de-un-cliente)
  **no reflejan en ningún sitio** (no son clientes de la asesoría).

## 3. Alcance y dirección (IMPORTANTE — límite legal)

El reflejo es **general para toda la cartera**, no solo asesoría→cliente
(decisión Benjamin 2026-06-23, "ampliarlo al máximo"):

- Para **cualquier factura emitida** dentro de la cartera de la asesoría
  (la emite la asesoría, o un cliente con su propia facturación / por
  tercero), si el **customer casa por NIF con otra `company` de la cartera**
  ⇒ reflejar en esa company como **gasto recibido + asiento, EN BORRADOR
  (por validar)**.
  - El que **emite** ya recibe su asiento de **venta** al validar (existe).
  - El que **recibe** obtiene el **gasto + asiento por validar**; el asesor,
    al entrar en su gestión, **solo valida** — sin PDF importado ni asiento
    manual.
  - El **proveedor (cuenta 400)** del gasto = la empresa **emisora**
    (asesoría o cliente A), casada por su NIF.
- **SÍ:** cobro de la factura ⇒ reflejar **pago + asiento de pago** en el
  cliente que recibe (también por validar).
- **NO (muro legal):** crear de la nada una **factura emitida** para quien
  NO la emitió. Eso exige numeración de serie + cadena SIF/VeriFactu + un
  acuerdo de **facturación por tercero (TPB)** firmado. Reflejar el gasto de
  una factura que YA existe es legal; *fabricar* la emitida del otro lado, no.

### 3.bis. Estado "por validar" y avisos (decisión Benjamin)

El gasto y su asiento **se generan automáticamente pero nacen en DRAFT /
auto_proposed=TRUE** (por validar), no posteados. El asesor los valida con
el flujo de validación en lote que ya existe (`PurchaseInvoiceService.validateBatch`
+ validación de asientos). Avisos vía `PendingTasksService` (ya cubre los dos
ámbitos, empresario `forCurrent()` y asesoría `forPortfolio()`):
- `DRAFT_JOURNAL` (ya existe) capta los **asientos por validar** automáticamente.
- **Nuevo bucket `DRAFT_PURCHASES`** = facturas **recibidas** por validar.

El gasto reflejado es una fila **normal de `purchase_invoices`** (la MISMA tabla
que usa la importación por PDF) → aparece en el módulo de Gastos del cliente
**igual que si la hubiera importado por PDF**, por validar. Solo cambia **quién
valida**:
- **Con vinculación** (CLIENT, con login): le entra en SUS Gastos; lo valida el
  **empresario** él mismo (o el asesor), con su aviso "facturas por validar".
- **Sin vinculación** (MANAGED): lo valida el **asesor** al entrar en su gestión.

Las facturas **recibidas NO entran en VeriFactu/SIF** del cliente (solo las
emitidas se encadenan). El reflejo respeta esto: crea gasto + asiento, nunca
toca la cadena SIF del cliente. (Coherente con `docs/legal-compras-gastos.md`.)

## 4. Arquitectura técnica

### 4.1. El problema del cross-tenant

`PurchaseInvoiceService.save()` y `PurchaseJournalEntryService.createForPurchase()`
están **atados a `tenantContext.getCurrentCompanyId()`** (el de la asesoría) y
usan helpers tenant-scoped (learning/tercero/classifier). No sirven tal cual
para escribir en los libros del cliente.

### 4.2. Decisión: servicio dedicado con `companyId` explícito

Nuevo `CrossInvoiceReflectionService` que **NO depende de `TenantContext`** y
recibe el `targetCompanyId` (el del cliente). Hace sus propios INSERT scoped
al cliente (patrón ya usado en el fix de `work_logs`), replicando la lógica
simple del asiento de compra (80%) pero con **cuenta de gasto fija 623**
(servicios profesionales — es una factura de asesoría, gasto conocido):

```
Debe  623x (Servicios profesionales)   = base
Debe  472x (IVA soportado)             = cuota IVA
        Haber 400x (Proveedor = asesoría)  = total
```

- Cuentas resueltas por prefijo dentro del `company_id` del cliente.
- Ejercicio fiscal OPEN del cliente para la fecha de la factura.
- El proveedor (400) = la asesoría (NIF de la empresa `INTERNAL`); se
  auto-crea sub-cuenta de tercero si no existe.

**No se toca** `TenantContext`, ni `AuthService`, ni los servicios de compra
existentes (CLAUDE.md §11.2). Coste: ~40 líneas duplicadas de asiento, a
cambio de aislamiento total y cero regresión en el flujo de compras normal.

### 4.3. Idempotencia y trazabilidad

Nuevas columnas en `purchase_invoices` (migración aditiva V141):
- `source_sales_invoice_id CHAR(36) NULL` — la factura emitida origen.
- `source_company_id CHAR(36) NULL` — la asesoría que la emitió.
- `UNIQUE(company_id, source_sales_invoice_id)` — evita duplicar el reflejo.

Si el cliente ya registró la factura a mano, el asesor verá el enlace y
podrá fusionar/descartar (no se crea doble gasto silencioso).

## 5. Slices propuestos (bloque REFLEJO)

- **REFLEJO-1 — Esquema.** V141: en `purchase_invoices`,
  `source_sales_invoice_id`, `source_company_id`, `UNIQUE(company_id,
  source_sales_invoice_id)`. Aditivo.
- **REFLEJO-2 — Reflejo del gasto (por validar).** `CrossInvoiceReflectionService`
  (sin `TenantContext`, recibe `targetCompanyId`); hook en
  `SalesInvoiceService.validate()`: si el customer de la factura casa por NIF
  con una `company` de la cartera → crear en esa company el gasto recibido
  **status DRAFT** + asiento **DRAFT/auto_proposed** 623/472/400, proveedor =
  empresa emisora. Idempotente por `source_sales_invoice_id`. Best-effort (si
  faltara cuenta/ejercicio, gasto DRAFT sin asiento).
- **REFLEJO-3 — Cascada anulación/rectificación.** Si la emitida se anula
  (VOID) o rectifica → revertir el gasto + asiento reflejados (scoped al
  cliente). Si ya estaban validados en el cliente, generar contrasiento en
  vez de borrar (respetar Diario validado).
- **REFLEJO-4 — Reflejo del pago.** Al marcar la emitida **cobrada** → reflejar
  en el cliente el pago del gasto: asiento `400 proveedor (Debe) / 572 banco ·
  570 caja (Haber)` **por validar** + registro de pago en el gasto reflejado.
  Idempotente vs. pago que el cliente ya hubiera registrado.
- **REFLEJO-5 — Avisos.** Bucket nuevo `DRAFT_PURCHASES` en `PendingTasksService`
  (facturas recibidas por validar); el de asientos (`DRAFT_JOURNAL`) ya capta
  los reflejados. i18n ES+EN del bucket + destino de navegación. Verificar que
  el centro de avisos se refresca solo (RefreshBus) al reflejar.
- **REFLEJO-6 — Visibilidad UI + interruptor.** En la emitida: "Reflejada como
  gasto en {cliente}". En el gasto: "Origen: factura de {emisor}". Ajuste para
  activar/desactivar el reflejo automático (por asesoría y/o por cliente).
  i18n ES+EN.

## 6. Decisiones (cerradas con Benjamin 2026-06-23)

1. **Cuenta de gasto:** 623 (servicios profesionales), editable. ✅
2. **Estado:** gasto + asiento **automáticos pero POR VALIDAR**; el asesor
   solo valida. Avisos en los dos modos. ✅
3. **Pago:** se refleja **al marcar la factura cobrada** (cubre cobro en mano
   sin movimiento bancario). ✅
4. **Alcance:** reflejo del **gasto para cualquier par de la cartera**
   (emisor→receptor); fabricar una emitida del otro lado queda fuera (muro
   TPB/SIF). ✅
