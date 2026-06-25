# Diseño — Ciclo laboral: suspensiones/excedencias, atrasos, cese de empresa

> Bloque pedido por Benjamin (2026-06-25, "haz los tres... lo que la ley diga
> siempre"). Es el bloque más sensible: toca **devengo, cotización e
> indemnización**. Por §11.2 (no rehacer motor de cálculo/legal sin Benjamin) y
> porque Benjamin no es asesor para validar matices, se construye la **base de
> backend segura** ahora y se cierra **con él** la parte de dinero/UI.
> Estado: **CL-1 hecho** (modelo de suspensión + guarda de nómina). CL-2/CL-3
> diseñados, pendientes de construir con Benjamin.

## 1. Suspensión / excedencia del contrato (art. 45–48 ET)

Durante la **suspensión** del contrato se exoneran las obligaciones recíprocas
de trabajar y remunerar (art. 45.2 ET): **no hay devengo ni nómina ordinaria**.
Tipos y efectos:

| Tipo | Reserva de puesto | Cotiza la empresa | Genera nómina |
|------|-------------------|-------------------|---------------|
| Excedencia voluntaria (art. 46.2) | No (reingreso preferente) | No | No |
| Excedencia forzosa / cargo público (46.1) | Sí | No (situación asimilada al alta) | No |
| Excedencia cuidado de hijo/familiar (46.3) | Sí (1er año) | No (bonificada) | No |
| Suspensión de empleo y sueldo (disciplinaria) | Sí | No | No |
| IT / maternidad / paternidad | Sí | Sí (con prestación) | Caso aparte (ya hay `MedicalLeave`) |
| ERTE suspensión | Sí | Parcial | Caso aparte (futuro) |

**Modelo de datos (CL-1, hecho):** tabla `contract_suspensions`
(`contract_id`, `type`, `start_date`, `end_date` nullable, `reserva_puesto`,
`reason`). Una suspensión "abierta" (`end_date` NULL) dura hasta que se cierre.

**Efecto en la nómina (CL-1, hecho — GUARDA, no cálculo):** si el periodo de la
nómina ordinaria cae **íntegramente** dentro de una suspensión **sin
remuneración**, el cálculo **se niega con mensaje claro** ("empleado en
excedencia/suspensión sin sueldo en {periodo}") en vez de generar una nómina
incorrecta. Los meses **parciales** (alta/baja a mitad de mes por
suspensión/reingreso) NO se prorratean aún → quedan a validar con Benjamin
(igual que el resto de proración del motor). La IT/maternidad siguen su camino
actual (`MedicalLeave`), no las toca esta guarda.

**Antigüedad y cotización:** la excedencia voluntaria **no computa** antigüedad
ni cotización; la forzosa/cuidado sí computan a efectos de reserva. Esto afecta
al finiquito (días de antigüedad) y al cálculo de indemnización → se afina al
construir CL-2/CL-3 con Benjamin.

**Pendiente (con Benjamin):** UI para registrar/cerrar suspensiones en la ficha
del contrato; proración de meses parciales; efecto fino en antigüedad del
finiquito.

## 2. Atrasos retroactivos (CL-2 — diseñado, pendiente)

Subida de convenio con efecto pasado (p.ej. convenio firmado en junio con
efectos desde enero) → hay que pagar la **diferencia** de los meses ya cobrados
y **cotizar** esa diferencia en liquidación complementaria (claves L00/L13 del
sistema RED).

**Diseño:**
- Acción "Calcular atrasos" sobre un empleado/colectivo: nuevo salario vs.
  salario cobrado, por cada mes del periodo retroactivo → diferencia bruta.
- Genera un **recibo de atrasos** (concepto "Atrasos convenio {año}") y su
  **asiento** (640 devengo + 465/476 + 642 SS empresa sobre la diferencia).
- Cotización: la diferencia cotiza por separado (liquidación complementaria L13);
  el IRPF de atrasos de ejercicios anteriores va al **tipo del 15%** fijo
  (art. 101.1 RIRPF) — **a confirmar con Benjamin**.

**Decisiones para Benjamin:** ¿se aplica el 15% IRPF de atrasos de años
anteriores? ¿Atrasos del mismo ejercicio van al tipo normal? ¿Se generan como
nómina aparte o como complemento de la del mes de pago?

## 3. Cese de empresa / extinción colectiva (CL-3 — diseñado, pendiente)

Cierre de la empresa → extinción de **todos** los contratos. Indemnización:
- Despido objetivo / colectivo (art. 51/52 ET): **20 días por año**, máx 12
  mensualidades.
- Distinto del cese individual ya implementado (`TerminationService`, que ya
  calcula indemnización por tipo).

**Diseño:** acción "Cese de empresa" que recorre los empleados ACTIVE y, por
cada uno, ejecuta el cese individual existente con tipo OBJETIVO/COLECTIVO
(reutiliza `TerminationService` + finiquito + recibo ya hechos), en lote, con
una fecha de cese común. Genera el finiquito de cada uno.

**Decisiones para Benjamin:** ¿la causa (económica/productiva/fuerza mayor)
cambia algo del cálculo? ¿Preaviso/falta de preaviso se refleja? ¿Se quiere un
documento único de comunicación colectiva además de los finiquitos individuales?

## 4. Slices

- **CL-1** ✅ — modelo `contract_suspensions` + guarda en la nómina (no genera
  nómina en excedencia/suspensión sin sueldo) + CRUD API. Backend, aditivo.
- **CL-2** ✅ (cálculo) — `BackPayService.preview`: atrasos desde las vigencias,
  con IRPF 15% en tramos de ejercicios anteriores y SS sobre la diferencia.
  Solo cálculo (sin efectos). **Pendiente con Benjamin**: generar recibo de
  atrasos + asiento + liquidación L13; diferencia sobre pagas extra.
- **CL-3** ✅ (backend) — `TerminationService.{preview,execute}CompanyClosure`:
  cese de empresa en lote (20 días/año, reutiliza el cese individual validado).
  Preview sin efectos + ejecución all-or-nothing.
- **CL-4** (con Benjamin) — UI: registrar/cerrar suspensiones en la ficha;
  pantalla de cese de empresa (confirmación + listado); pantalla de atrasos;
  proración de meses parciales; efecto en antigüedad del finiquito.
