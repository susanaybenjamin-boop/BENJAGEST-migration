-- V79 — Distinguir festivos reales de ajustes de jornada / cierres.
--
-- El tope legal de 14 festivos/año (Art. 37.2 ET) SOLO aplica a
-- festivos no laborables. Los AJUSTES DE JORNADA del convenio
-- colectivo (jueves/lunes de puente, viernes de pelusa, etc.) no son
-- festivos legales — son días en que se reduce o desplaza la jornada.
-- Por eso necesitamos distinguirlos para no rechazar volcados de PDF
-- con 14 festivos + 8-12 ajustes.
--
-- Valores: FESTIVO (default) | AJUSTE | CIERRE.
ALTER TABLE holidays
    ADD COLUMN holiday_type VARCHAR(20) NOT NULL DEFAULT 'FESTIVO';

-- Sin migración de datos: los registros existentes quedan como FESTIVO
-- (era el único caso real hasta hoy 2026-06-09).
