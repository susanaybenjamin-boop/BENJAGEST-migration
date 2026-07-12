# ============================================================================
# WINSVC — Registra el backend BENJAGEST como SERVICIO de Windows (auto-arranque).
#
# Ejecutar UNA vez tras instalar. NO hace falta abrir una consola de admin: si
# no se ejecuta elevado, el propio script pide permisos (salta el aviso UAC) y
# se relanza como administrador.
#
#   - Doble clic en install-service.cmd  (recomendado), o
#   - Click derecho en este .ps1 -> "Ejecutar con PowerShell", o
#   - powershell -ExecutionPolicy Bypass -File install-service.ps1
# ============================================================================
$ErrorActionPreference = "Stop"

# --- Auto-elevacion: si no soy admin, me relanzo elevado (aviso UAC) ---------
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

Write-Host "==> Registrando el servicio BenjagestBackend..." -ForegroundColor Cyan
& $svc install
if ($LASTEXITCODE -ne 0) { throw "Fallo 'install' del servicio (codigo $LASTEXITCODE)." }

Write-Host "==> Arrancando el servicio..." -ForegroundColor Cyan
& $svc start
if ($LASTEXITCODE -ne 0) { throw "Fallo 'start' del servicio (codigo $LASTEXITCODE)." }

# Firewall: permitir el 8080 entrante para los PUESTOS de la LAN. Solo perfiles
# Privado y Dominio (red de confianza); NUNCA Publico. Idempotente. La BD (13307)
# NO se abre: sigue solo en localhost (DB-LOCK).
$fwName = "BENJAGEST Backend 8080"
if (-not (Get-NetFirewallRule -DisplayName $fwName -ErrorAction SilentlyContinue)) {
    New-NetFirewallRule -DisplayName $fwName -Direction Inbound -Protocol TCP `
        -LocalPort 8080 -Action Allow -Profile Private,Domain | Out-Null
    Write-Host "==> Firewall: permitido 8080 entrante en la LAN (Privado/Dominio)." -ForegroundColor Green
} else {
    Write-Host "==> Firewall: la regla de 8080 ya existe." -ForegroundColor Green
}

Write-Host "==> Listo. El backend arrancara solo con Windows." -ForegroundColor Green
Write-Host "    Estado:  sc query BenjagestBackend" -ForegroundColor Green
Write-Host "    Log:     %ProgramData%\BENJAGEST\logs\benjagest-backend.out.log" -ForegroundColor Green
