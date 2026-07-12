# ============================================================================
# WINSVC — Da de baja el servicio de Windows del backend BENJAGEST.
# Si no se ejecuta elevado, se relanza como administrador (aviso UAC).
# Necesario antes de desinstalar o de instalar una version nueva del servicio.
# Los datos en %ProgramData%\BENJAGEST NO se tocan.
# ============================================================================
$ErrorActionPreference = "Stop"

$admin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
         ).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $admin) {
    Write-Host "Solicitando permisos de administrador (acepta el aviso de Windows)..." -ForegroundColor Yellow
    Start-Process powershell.exe -Verb RunAs -ArgumentList @(
        "-NoExit", "-ExecutionPolicy", "Bypass", "-NoProfile", "-File", "`"$PSCommandPath`""
    )
    return
}

$here = $PSScriptRoot
$svc  = Join-Path $here "benjagest-backend.exe"
if (-not (Test-Path $svc)) { throw "No encuentro benjagest-backend.exe junto a este script ($here)." }

Write-Host "==> Parando el servicio..." -ForegroundColor Cyan
& $svc stop 2>$null
Write-Host "==> Dando de baja el servicio..." -ForegroundColor Cyan
& $svc uninstall
Write-Host "==> Listo (los datos en %ProgramData%\BENJAGEST NO se tocan)." -ForegroundColor Green
