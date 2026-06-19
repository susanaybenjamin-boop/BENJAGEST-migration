# Plan — FICHAJE POR JORNADA (botones según el horario asignado, estilo CONTENDO)

> **Pedido Benjamin 2026-06-19:** que el fichaje se comporte según la **jornada
> asignada** al empleado (modelo CONTENDO). Ejemplo: jornada 8-18 con bloques
> 8:00-10:00 (trabajo), 10:00-10:30 (pausa), …:
> - A las **7:45** aparece **"Fichar entrada"** (15 min antes del inicio del 1er bloque).
> - A las **9:45** aparece **"Fichar pausa"** (15 min antes del bloque de pausa).
> - Siempre **15 minutos de margen** antes y después de cada transición.

## Base que ya existe (reutilizar)
- **JOR-2 (V131)**: `work_schedule_templates` + `work_schedule_blocks` (weekday 1-7,
  `block_type` WORK|BREAK, `start_time`/`end_time`) + `work_schedule_assignments`
  (empleado → plantilla con vigencia `effective_from/to`). **Aquí está la jornada.**
- **Fichaje**: `TimeClockService.punch` (escritorio + kiosco + MEMP-2 móvil) con tipos
  IN / OUT / BREAK_START / BREAK_END. `recent(employeeId, n)` para saber lo ya fichado hoy.

## Lógica (núcleo): "¿qué toca fichar ahora?"
Servicio `ScheduleFichajeService.suggestNextPunch(employeeId, now)`:
1. Resolver la plantilla asignada al empleado para HOY
   (`work_schedule_assignments` cuya vigencia cubre `now`, la más reciente).
2. Cargar los bloques de hoy (`work_schedule_blocks` del template + `weekday` ISO de hoy),
   ordenados por `start_time`. Si no hay bloques hoy = día libre → sin sugerencia.
3. Construir las **transiciones** esperadas en orden:
   - inicio del 1er bloque WORK → **IN** (Entrada).
   - fin de un WORK seguido de BREAK → **BREAK_START** (Pausa).
   - fin de un BREAK (vuelta a WORK) → **BREAK_END** (Volver de pausa).
   - fin del último bloque WORK → **OUT** (Salida).
4. Mirar lo ya fichado hoy (`recent`) para saber en qué transición va el empleado
   (cuántos IN/OUT/pausas lleva) y cuál es la **siguiente** esperada.
5. Devolver `{ action, label, scheduledTime, windowFrom = scheduledTime-15m,
   windowTo = scheduledTime+15m, inWindow }` o `null` si hoy no hay jornada.
   Margen configurable (default 15 min; candidato a parámetro por empresa).

## Slices
- **FJ-1** `ScheduleFichajeService` (backend) con la lógica de arriba + tests mentales
  con un par de jornadas. Tenant-scoped.
- **FJ-2** endpoint `GET /api/empleado/fichaje/sugerencia` (móvil) y reutilizable por
  escritorio (o método directo en la UI de escritorio que ya tiene el employeeId).
- **FJ-3** **Escritorio** (`showTimeClock`): mostrar **destacado** el botón sugerido
  cuando `inWindow` (y su hora); el resto de botones quedan disponibles pero
  secundarios (permitir desviaciones reales). Fuera de ventana, aviso suave
  "fuera de tu horario" pero sin bloquear.
- **FJ-4** **Móvil (PWA)**: la pantalla de fichaje pide la sugerencia y muestra el
  botón que toca (Entrada/Pausa/Vuelta/Salida) destacado; los demás, secundarios.
- **FJ-5** **Incidencia schedule-aware + revisión** (resuelve el punto 2 de Benjamin):
  la incidencia de Auditoría se calcula comparando lo **esperado por jornada** vs lo
  **fichado** (falta entrada/salida/pausa de un día cerrado; no marca el día en curso).
  Acción de **revisar/corregir**: desde la fila de incidencia, abrir un diálogo para
  **añadir una corrección** (`TimeClockService.requestCorrection` ya existe en backend)
  — hoy "Revisar" no lleva a ningún sitio donde actuar.

## Notas
- **No bloquear**: la vida real se desvía del horario; el horario **sugiere y destaca**,
  no impide fichar a otra hora (se marca como desviación).
- El margen de 15 min y el comportamiento "solo el botón que toca" son de CONTENDO;
  mantener configurable el margen.
- Día sin jornada asignada → comportamiento actual (todos los botones), sin sugerencia.
