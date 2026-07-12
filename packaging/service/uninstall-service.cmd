@echo off
REM Doble clic aqui para dar de baja el servicio BENJAGEST. Pedira permisos de
REM administrador (aviso UAC) automaticamente. Los datos NO se tocan.
powershell -ExecutionPolicy Bypass -NoProfile -File "%~dp0uninstall-service.ps1"
