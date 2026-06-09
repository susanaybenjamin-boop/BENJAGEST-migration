-- CAL-FIX 4 (Benjamin 2026-06-09) — vincular empleado a un calendario
-- laboral concreto. Útil para:
--   1) Fichaje: en festivos/ajustes del calendario asignado, contar las
--      horas como extra (o impedir fichar si la empresa lo configura).
--   2) Nómina: aplicar los festivos pagados según el calendario del
--      empleado (puede haber empleados con convenios diferentes en la
--      misma empresa: oficina vs almacén, etc.).
--
-- ON DELETE SET NULL: si el calendario se elimina, los empleados
-- vinculados quedan sin calendario (no se borran los empleados).
ALTER TABLE employees
    ADD COLUMN work_calendar_id CHAR(36) NULL AFTER ss_regime;

ALTER TABLE employees
    ADD CONSTRAINT fk_employees_work_calendar
        FOREIGN KEY (work_calendar_id)
        REFERENCES work_calendars (id)
        ON DELETE SET NULL;

CREATE INDEX ix_employees_work_calendar
    ON employees (work_calendar_id);
