# ============================================================================
# INSTVAR (Empleado) — Apunta esta instalacion al SERVIDOR de la oficina (LAN).
#
# La UI lee la direccion del backend de la variable de entorno
# BENJAGEST_API_BASE_URL (si no, usa localhost). Este script la fija a nivel de
# MAQUINA. Si no se ejecuta elevado, se relanza como administrador (aviso UAC).
#
# Uso:
#   powershell -ExecutionPolicy Bypass -File set-server.ps1 -Server 192.168.1.50
#   powershell -ExecutionPolicy Bypass -File set-server.ps1 -Url http://host:8080/api
# ============================================================================
param(
    [string]$Server,
    [int]$Port = 8080,
    [string]$Url
)
$ErrorActionPreference = "Stop"

# --- Auto-elevacion: reenvia los mismos parametros al proceso elevado --------
$admin = ([Security.Principal.WindowsPrincipal] [Security.Principal.WindowsIdentity]::GetCurrent()
         ).IsInRole([Security.Principal.WindowsBuiltinRole]::Administrator)
if (-not $admin) {
    Write-Host "Solicitando permisos de administrador (acepta el aviso de Windows)..." -ForegroundColor Yellow
    $fwd = @("-NoExit", "-ExecutionPolicy", "Bypass", "-NoProfile", "-File", "`"$PSCommandPath`"")
    foreach ($k in $PSBoundParameters.Keys) { $fwd += "-$k"; $fwd += "$($PSBoundParameters[$k])" }
    Start-Process powershell.exe -Verb RunAs -ArgumentList $fwd
    return
}

if (-not $Url) {
    if (-not $Server) { throw "Indica -Server <ip-o-nombre> (o una -Url completa)." }
    $Url = "http://$($Server):$Port/api"
}

Write-Host "==> Fijando BENJAGEST_API_BASE_URL = $Url (maquina)..." -ForegroundColor Cyan
[Environment]::SetEnvironmentVariable("BENJAGEST_API_BASE_URL", $Url, "Machine")
Write-Host "==> Listo. Cierra y reabre BENJAGEST para que tome el nuevo servidor." -ForegroundColor Green
Write-Host "    (Comprobar:  [Environment]::GetEnvironmentVariable('BENJAGEST_API_BASE_URL','Machine') )" -ForegroundColor Green
