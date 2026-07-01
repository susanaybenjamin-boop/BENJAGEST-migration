-- V161 — El hash del token del kiosco se rellena al ACTIVAR el dispositivo
-- (KioskService.activate: UPDATE ... SET device_token_hash = ?), no al crearlo.
-- V129 lo definió NOT NULL, pero el INSERT de creación mete NULL (dispositivo
-- "sin activar"; la vista deriva `activated = device_token_hash IS NOT NULL`).
-- Resultado: crear un kiosco fallaba con "Column 'device_token_hash' cannot be
-- null". Se hace la columna NULLABLE para permitir el estado "creado, sin activar".
ALTER TABLE kiosk_devices MODIFY device_token_hash VARCHAR(255) NULL;
