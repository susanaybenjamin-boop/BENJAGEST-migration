# ============================================================================
# BENJAGEST - PUESTO AUTOCONTENIDO (test del stack embebido, DEPLOY-PKG)
#
# Arranca TODO en local sin Docker ni MariaDB externa:
#   1. Backend fat jar con MariaDB EMBEBIDA (MariaDB4j, puerto 13307,
#      datos en ~/.benjagest/mariadb-data) + API en 8080.
#   2. La UI JavaFX apuntando a ese backend.
# Al cerrar la UI, para el backend y la MariaDB embebida.
#
# Esto reproduce como funcionara el instalable (.msi): "todo es un puesto".
# Uso:   .\run-embedded.ps1            (reusa el fat jar; lo construye si falta)
#        .\run-embedded.ps1 -Rebuild   (fuerza reconstruir el fat jar)
# Ver docs/despliegue-local.md
# ============================================================================
param(
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$jarPath = "backend-java\target\backend-java-0.1.0-SNAPSHOT.jar"

# --- 1/4  Fat jar del backend (autocontenido) -------------------------------
if ($Rebuild -or -not (Test-Path $jarPath)) {
    Write-Host "==> 1/4  Construyendo el fat jar del backend..." -ForegroundColor Cyan
    mvn -q -pl backend-java package -DskipTests
    if ($LASTEXITCODE -ne 0) { Write-Host "    Fallo el package del backend." -ForegroundColor Red; exit 1 }
} else {
    Write-Host "==> 1/4  Reusando fat jar existente ($jarPath)." -ForegroundColor Green
}

# --- 2/4  Arrancar backend con BD embebida ----------------------------------
Write-Host "==> 2/4  Arrancando backend + MariaDB embebida (BD: ~/.benjagest)..." -ForegroundColor Cyan
$backend = Start-Process -FilePath "java" `
    -ArgumentList "-Dbenjagest.db.embedded=true", "-jar", "`"$jarPath`"" `
    -PassThru -NoNewWindow

# --- 3/4  Esperar a que la API responda -------------------------------------
Write-Host "==> 3/4  Esperando a que la API (8080) este lista..." -ForegroundColor Cyan
$ready = $false
for ($i = 1; $i -le 60; $i++) {
    if ($backend.HasExited) {
        Write-Host "    El backend termino inesperadamente (codigo $($backend.ExitCode))." -ForegroundColor Red
        exit 1
    }
    try {
        # Cualquier respuesta HTTP (incluido 400/401/404) significa que Tomcat ya sirve.
        Invoke-WebRequest -Uri "http://localhost:8080/api/health" -TimeoutSec 2 -UseBasicParsing *> $null
        $ready = $true; break
    } catch {
        if ($_.Exception.Response) { $ready = $true; break }  # respondio con error HTTP -> esta vivo
    }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Host "    La API no respondio a tiempo. Parando backend." -ForegroundColor Red
    if (-not $backend.HasExited) { $backend | Stop-Process -Force }
    Get-Process mariadbd -ErrorAction SilentlyContinue | Stop-Process -Force
    exit 1
}
Write-Host "    Backend listo en http://localhost:8080" -ForegroundColor Green

# --- 4/4  Lanzar la UI (bloquea hasta que se cierre) ------------------------
Write-Host "==> 4/4  Lanzando la UI..." -ForegroundColor Cyan
$env:BENJAGEST_API_BASE_URL = "http://localhost:8080/api"
try {
    mvn -pl ui javafx:run
} finally {
    # Al cerrar la UI, parar backend + MariaDB embebida.
    Write-Host "==> Cerrando: parando backend y MariaDB embebida..." -ForegroundColor Cyan
    if ($backend -and -not $backend.HasExited) { $backend | Stop-Process -Force }
    Get-Process mariadbd -ErrorAction SilentlyContinue | Stop-Process -Force
    Write-Host "    Listo. Datos persistidos en ~/.benjagest/mariadb-data" -ForegroundColor Green
}
