# ============================================================================
# BENJAGEST - Arranque de un PUESTO (app de escritorio JavaFX)
# Apunta la UI al servidor de la oficina por la LAN y la lanza.
# Uso:   .\start-ui.ps1                          (servidor = localhost)
#        .\start-ui.ps1 -ServerIp 192.168.1.10   (servidor de la oficina)
# Ver docs/despliegue-local.md
# ============================================================================
param(
    [string]$ServerIp = "localhost",
    [string]$Port = "8080"
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$env:BENJAGEST_API_BASE_URL = "http://${ServerIp}:${Port}/api"
Write-Host "==> Conectando la UI a $env:BENJAGEST_API_BASE_URL" -ForegroundColor Cyan
Write-Host "    (cámbialo con -ServerIp <IP del servidor de la oficina>)" -ForegroundColor DarkGray

mvn -pl ui javafx:run
