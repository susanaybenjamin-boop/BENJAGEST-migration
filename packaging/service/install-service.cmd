@echo off
REM Doble clic aqui para registrar el servicio BENJAGEST. Pedira permisos de
REM administrador (aviso UAC) automaticamente.
powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0install-service.ps1"
