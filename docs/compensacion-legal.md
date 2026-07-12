# Compensación de facturas (netting) — marco legal y contable

> Investigación previa al bloque **COMP-** (Tema 1 de v0.1.36). Barrido de
> internet pedido por Benjamin (2026-07-12): *"lo vamos a hacer como se debe
> hacer por ley, no el camino fácil… puede haber retenciones y otras cuentas
> en juego"*. Fuentes citadas al final. Este documento fija QUÉ dice la ley y
> QUÉ se hace en contabilidad; el modelo acordado para BENJAGEST va en la
> sección final.

## 1. Qué es la compensación

Extinción de obligaciones que ocurre cuando **dos personas son recíprocamente
acreedora y deudora la una de la otra** (mismo tercero es a la vez mi CLIENTE
—me debe una venta X— y mi PROVEEDOR —yo le debo una compra Y—). También se
llama *netting*. Las deudas se extinguen **hasta la cantidad concurrente**
(la menor); si son distintas, es **compensación parcial** y subsiste la
diferencia.

## 2. Marco legal — Código Civil, arts. 1195–1202

- **Art. 1195**: hay compensación cuando dos personas, por derecho propio, son
  recíprocamente acreedoras y deudoras la una de la otra.
- **Art. 1196 — requisitos** (los cinco):
  1. Cada obligado lo esté **principalmente** y sea a la vez **acreedor
     principal** del otro (reciprocidad directa, mismo tercero).
  2. Ambas deudas consistan en **dinero** (o cosas fungibles de la misma
     especie y calidad).
  3. Ambas deudas estén **vencidas**.
  4. Sean **líquidas y exigibles**.
  5. Que sobre ninguna haya **retención o contienda de terceros** notificada
     al deudor.
- **Efecto (art. 1202)**: extingue ambas deudas en la cantidad concurrente,
  aunque no tengan conocimiento los acreedores/deudores.
- **Clases**: **legal** (se dan los requisitos del 1196), **voluntaria /
  convencional** (las partes lo pactan aunque falte algún requisito, p.ej.
  deudas aún no vencidas) y **judicial**.
- **Cómo opera en la práctica**: aunque el CC dice que opera *ipso iure*, en la
  práctica **hay que alegarla/pactarla** — un tercero no la aplica solo, y en
  juicio debe alegarse (LEC art. 408). Por eso entre empresas se formaliza con
  un **acuerdo/contrato de compensación** firmado por ambas partes, que detalla
  las deudas compensadas y el saldo resultante. **Ese documento es la prueba.**

### ⚠️ Matiz 1 — la "retención" del art. 1196.5 NO es el IRPF

El requisito 5 habla de **embargo o retención judicial/administrativa de un
tercero** (p.ej. la AEAT embarga el crédito del proveedor). Si un crédito está
embargado, **no se puede compensar**. No tiene nada que ver con la retención de
IRPF de una factura de profesional. (Caso borde; no MVP, pero conviene saberlo.)

## 3. Tratamiento contable

### ⚠️ Matiz 2 — el IRPF NO cambia el asiento de compensación

- En la **compra Y con retención IRPF**: la cuenta **400 (proveedor)** ya está
  **neta de retención**. El IRPF retenido va a **4751 (HP acreedora por
  retenciones)** — es deuda con **Hacienda**, no con el proveedor.
- En la **venta X con retención** (si me retienen a mí): la **430 (cliente)** ya
  está **neta**. La retención practicada por el cliente va a **473 (HP
  retenciones y pagos a cuenta)** — crédito contra Hacienda.
- **Conclusión**: la compensación opera sobre los **saldos netos de 400 y 430**
  (justo el "pendiente" que ya trackean `sales_invoices` / `purchase_invoices`).
  Las retenciones se liquidan **aparte con Hacienda** (modelos 111/190 y el IRPF
  propio) y **no son compensables** entre las dos partes privadas.

### El asiento

Dos enfoques admitidos:

- **(A) Directo** — `Debe 400 (proveedor) / Haber 430 (cliente)` por el importe
  **concurrente** (el menor). Estándar, limpio y auditable. La factura mayor
  queda pendiente por la diferencia.
- **(B) Cuenta puente** — grupo **555 (partidas pendientes de aplicación)** o
  **551 (cuentas corrientes con socios/terceros)**: se cargan/abonan las
  facturas contra la puente y se salda el neto con tesorería. Útil para
  **netting periódico/acumulado**; más complejo.

### Principio de no compensación (PGC) — no lo prohíbe

El principio de no compensación del PGC prohíbe **compensar partidas de activo y
pasivo en la PRESENTACIÓN de las cuentas anuales**, no saldar deudas recíprocas
reales. El netting es **extinción real de obligaciones**, no una presentación
artificial → **es admisible**. El asiento se hace cuando se materializa la
compensación (se dan de baja los saldos deudor y acreedor).

## 4. Tratamiento fiscal

- **IVA / 303**: la compensación es un **MEDIO DE PAGO**, no un hecho imponible.
  El IVA ya se devengó al **emitir** las facturas. La compensación **NO toca el
  303**. (Excepción: régimen especial de **criterio de caja (RECC)**, donde el
  devengo es al cobro/pago — ahí la compensación cuenta como cobro/pago a esos
  efectos. Verificar si Benjamin está en RECC; por defecto NO.)
- **Modelo 347**: se declara por el **importe de las operaciones**, con
  independencia del medio de pago. La compensación **no cambia el 347**. (Ojo: el
  347 desglosa aparte los importes **en metálico**; la compensación NO es
  metálico.)
- **Retenciones (111/190)**: se declaran por las facturas, no por cómo se
  paguen. Independiente de la compensación.
- **Límite de 1.000 € de pago en efectivo**: **no aplica** — la compensación no
  es un pago en efectivo.
- **SIF / VeriFactu**: la compensación **NO crea factura nueva** → **NO entra en
  la cadena SIF** (`sif_event_registry`, `verifactu_registry`). Es un evento de
  cobro/pago/liquidación. Se queda **fuera de la línea roja**, igual que los
  asientos del bloque REFLEJO.

## 5. Modelo acordado para BENJAGEST (bloque COMP-)

Decisiones de Benjamin (2026-07-12):
- **Asiento**: hacerlo bien por ley (investigado arriba).
- **Disparo**: **propuesta automática** (estilo REFLEJO / sugerencias bancarias);
  el asesor confirma, nunca automático a ciegas.
- **Banco**: soportar **los dos escenarios** (ver abajo).
- **Alcance**: **mismo NIF**, **varias** facturas de cada lado.

Diseño propuesto:

1. **Detección automática** por NIF: terceros que a la vez tienen ventas
   VALIDATED pendientes/parciales (430) y compras pendientes/parciales (400).
   Comprobar requisitos del 1196: líquidas (facturas validadas ✓) y
   **vencidas/exigibles** (revisar `due_date`). Proponer, el asesor confirma.
2. **Selección**: mismo NIF, N ventas + M compras. Importe compensable =
   `min(Σ ventas pendientes, Σ compras pendientes)`. Regla de asignación
   (qué factura absorbe la compensación): **FIFO por fecha** (las más antiguas
   primero). *(Detalle de implementación, revisable.)*
3. **Asiento** `Debe 400 / Haber 430` por el importe concurrente. Sube
   `paid_amount` de cada factura compensada hasta agotar el importe; el resto
   queda pendiente. **Idempotente** por `source_type='COMPENSATION'` + `source_id`
   (patrón REFLEJO). Sin tocar el SIF.
4. **Escenario bancario — POR LA DIFERENCIA** (confirmado Benjamin 2026-07-12):
   el caso real es **un solo movimiento bancario por la diferencia X−Y**, no un
   movimiento por cada factura. El modelo:
   - La compensación salda **siempre** la parte concurrente (400/430).
   - El **residual** (X−Y) queda como pendiente de la factura mayor y se concilia
     con **ese único movimiento neto** por la conciliación bancaria existente
     (el pendiente es ahora justo la diferencia, así que casa).
   - Este mismo modelo cubre gratis el caso raro de que el residual llegara en
     varias veces: el banco concilia lo que quede pendiente igual.
   - ⚠️ **Regla anti-doble-conteo**: la parte compensada NO puede volver a
     conciliarse por banco (ya está marcada pagada). Ejemplo: venta 1.000 +
     compra 300 → compensa 300, banco concilia +700; nunca +1.000.
5. **Documento de compensación**: generar un **justificante/acuerdo** (fecha,
   NIF, facturas compensadas, importe, saldo residual) como prueba legal.

## Fuentes

- [Código Civil art. 1196 — requisitos de la compensación](https://www.conceptosjuridicos.com/articulos/codigo-civil-articulo-1196/)
- [Código Civil — De la compensación (arts. 1195–1202)](https://codigocivilespana.com/de-la-compensacion/)
- [Compensación de créditos en el Derecho civil español (Lextium)](https://lextiumabogados.com/compensacion-de-creditos-en-el-derecho-civil-espanol/)
- [Tratamiento contable y fiscal de la compensación de facturas o netting (Contabilidad TK)](https://www.contabilidadtk.es/netting-compensacion-facturas-principio-contable-no-compensacion.html)
- [Contabilizar compensación cliente/proveedor en un solo asiento — cuenta puente 555 (Sage 50 Community)](https://communityhub.sage.com/es/sage-50/f/discusion-general/169106/contabilizar-un-cobro-pago-de-una-compensacion-con-un-cliente-proveedor-en-un-solo-asiento)
- [Cómo contabilizar una factura recibida con IRPF — cuenta 4751 (Software del Sol)](https://ayudadelsol.sdelsol.com/docs/c1310-como-contabilizar-una-factura-recibida-con-irpf)
- [Contrato de compensación de deudas — documentación y firma (Wonder.legal)](https://www.wonder.legal/es/modele/contrato-compensacion-deudas)
- [Modelo 347 — preguntas frecuentes AEAT](https://sede.agenciatributaria.gob.es/Sede/todas-gestiones/impuestos-tasas/declaraciones-informativas/modelo-347-decla_____racion-anual-operaciones-personas_/preguntas-frecuentes.html)
