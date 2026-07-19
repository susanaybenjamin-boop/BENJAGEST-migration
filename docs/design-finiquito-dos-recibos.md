# Diseño — Finiquito en DOS recibos (mensual + liquidación)

**Bloque F2R.** Estado: plan cerrado (2026-07-19), implementación por slices.
Origen: Benjamin quiere el finiquito **igual que su asesoría** (SH Asesores),
con datos reales cotejados (recibos de junio de CASTRO FERNANDEZ, RUBEN, baja
temporal alta 01/06/2026).

## Qué hace la asesoría (modelo objetivo)

El mes de cese produce **DOS recibos separados**:

1. **Nómina mensual** (periodo 01–23 junio, mes parcial por baja):
   - Devengos salariales prorrateados a los días: SALARIO BASE 930,60 +
     PLUS EXTRASALARIAL 128,00 + PLUS ASISTENCIA 216,80 + P.P. PAGA EXTRA 228,08
     = **A. TOTAL DEVENGADO 1.503,48**.
   - Deducciones: SS trabajador 98,49 + IRPF 30,07 → **líquido 1.374,92**.
   - **Base de cotización: 1.645,92** (¡mayor que el devengado!).
   - Observaciones del recibo: `PERMISO SIN RE.9-10` y `PERMISO SIN RE.17-17`
     = **3 días de permiso SIN retribución** (días 9, 10 y 17).

2. **Finiquito** ("Liquidación de vacaciones y pagas extraordinarias"):
   - Percepción NO salarial: **INDEM. FIN CONTRATO 56,93**.
   - (Aquí irían vacaciones no disfrutadas y pagas extra pendientes si las
     hubiera; en este caso no.)
   - **NO repite el sueldo** — ya está en la mensual.

**Clave:** el sueldo va en la MENSUAL (prorrateada a días trabajados); el
finiquito lleva SOLO los extras (vacaciones + pagas pendientes + indemnización).

## Qué hace BENJAGEST hoy (a cambiar)

- El mes de cese produce **solo un finiquito** (`payslip_type='SETTLEMENT'`) que
  mete DENTRO el sueldo prorrateado + vacaciones + prorrata extras
  (`PayslipService.settlementConcepts()`). La indemnización va aparte
  (`TerminationService.computeSeverance()`, NO en el recibo).
- La nómina MENSUAL **no sabe prorratear** por días trabajados: siempre calcula
  mes completo (`annual/divisor`). Solo el finiquito prorratea
  (`workedFactor = díasTrabajados / díasDelMes`).
- Tras la baja el contrato queda `TERMINATED`; `generateMonth` ya no lo ve
  (filtro `status IN ('ACTIVE','SUSPENDED')`).
- `createAccrual` trata SETTLEMENT y MONTHLY con el MISMO asiento.
- **Bien:** la clave única `payslips(company,employee,year,month,payslip_type)`
  ya admite MENSUAL + FINIQUITO del mismo mes → **sin cambios de schema**.

## El punto legal crítico — base de cotización del mes parcial

El **1.645,92 ≠ 1.503,48** NO es error: en un mes con **permisos SIN
retribución** (y/o alta/baja a mitad de mes) el devengo baja (no se cobran esos
días) pero la **base de cotización se mantiene** — durante el permiso sin sueldo
se sigue cotizando (obligación de cotizar por la base que corresponda). Reglas a
implementar POR LEY (no a ojo), verificando hasta reproducir el 1.645,92:

- Base de cotización de contingencias comunes de un mes parcial: prorrateo por
  días naturales sobre la base mensual, con **base mínima diaria del grupo** como
  suelo, y manteniendo la cotización de los días de permiso sin sueldo.
- Prorrata de pagas extra a efectos de COTIZACIÓN (art. 147 LGSS): va SIEMPRE,
  aunque el devengo del mes sea parcial.
- Los `permisos sin retribución` (3 días) hoy en BENJAGEST se modelan como
  incidencias `ABSENCE` no retribuidas (reducen devengo y base). El javadoc del
  motor ya avisa: *"la proración del MÍNIMO de base por mes parcial queda a
  validar"* — es exactamente esto lo que falta afinar.

## Slices

- **F2R-1 — MENSUAL prorrateable.** Añadir a la mensual un factor de días
  trabajados (worked/díasDelMes) que prorratea el salario base + complementos
  del contrato (NO los extras del mes tipo dietas). `computePayslip` es puro →
  test unitario. Threading: `CalculateRequest` + `EngineInputs` (1 solo sitio de
  construcción, `PayslipService:347`) + 4 call-sites.
- **F2R-SS — base de cotización parcial (POR LEY).** Implementar la regla de
  arriba y cuadrar el **1.645,92** con los datos reales antes de dar por bueno.
  Es el slice con más riesgo legal: se hace y se verifica solo.
- **F2R-2 — finiquito sin sueldo.** `settlementConcepts()` deja de emitir el
  salario de días trabajados; mantiene vacaciones no disfrutadas + pagas extra
  pendientes; y se le AÑADE la indemnización como percepción no salarial
  ("INDEM. FIN CONTRATO"), con su tratamiento IRPF (parte exenta) ya calculado en
  `TerminationService.computeSeverance()`.
- **F2R-3 — la baja genera los dos recibos.** `TerminationService.execute/preview`
  genera la MENSUAL prorrateada (sueldo) + el FINIQUITO (extras), en ese orden y
  ANTES de poner el contrato TERMINATED (para que `resolveActiveContract` lo
  encuentre). PDF: dos recibos.

## Verificación (objetivo)

Reproducir con los datos reales de Rubén (junio 2026):
- Mensual: devengado **1.503,48**, base cotización **1.645,92**, IRPF **30,07**,
  líquido **1.374,92**.
- Finiquito: **INDEM. FIN CONTRATO 56,93**.

Nada se da por bueno sin cuadrar estos números en ejecución.
