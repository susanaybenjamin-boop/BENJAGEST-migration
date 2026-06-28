# ============================================================================
# BENJAGEST - Construye el INSTALADOR .msi (jpackage + WiX)
#
# Igual que build-app-image.ps1 pero genera un .msi que instala BENJAGEST con
# acceso directo en el menu Inicio y el escritorio. Al instalar y abrir, arranca
# backend con MariaDB embebida + UI (igual que el app-image). Incluye su JRE.
#
# REQUISITO: WiX Toolset 3.x instalado y en el PATH (candle.exe / light.exe).
#   Descarga: https://github.com/wixtoolset/wix3/releases  (wix314.exe)
#   jpackage usa WiX para construir el .msi en Windows.
#
# Uso:   .\build-msi.ps1            (reusa jars; reconstruye lo que falte)
#        .\build-msi.ps1 -Rebuild   (limpia y reconstruye todo)
# ============================================================================
param(
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$version    = "0.1.0"
$uiJarName  = "ui-0.1.0-SNAPSHOT.jar"
$backendJar = "backend-java\target\backend-java-0.1.0-SNAPSHOT.jar"
$dist       = "dist"
$input      = "$dist\input"
$out        = "$dist\msi"

$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jpackage.exe" } else { "jpackage" }

# Aviso temprano si WiX no esta (jpackage fallaria igual, pero el mensaje es mas claro).
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    Write-Host "AVISO: no encuentro WiX (candle.exe) en el PATH." -ForegroundColor Yellow
    Write-Host "       Instala WiX 3.x (wix314.exe) y reabre la terminal." -ForegroundColor Yellow
    Write-Host "       https://github.com/wixtoolset/wix3/releases" -ForegroundColor Yellow
}

# --- 1/4  Limpieza + backend fat jar ----------------------------------------
if (Test-Path $input) { Remove-Item -Recurse -Force $input }
if (Test-Path $out)   { Remove-Item -Recurse -Force $out }
New-Item -ItemType Directory -Force -Path $input | Out-Null

if ($Rebuild -or -not (Test-Path $backendJar)) {
    Write-Host "==> 1/4  Empaquetando backend (fat jar)..." -ForegroundColor Cyan
    mvn -q -pl backend-java package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Fallo el package del backend." }
} else {
    Write-Host "==> 1/4  Reusando backend fat jar." -ForegroundColor Green
}
Copy-Item $backendJar "$dist\backend.jar" -Force

# --- 2/4  UI jar + dependencias ---------------------------------------------
Write-Host "==> 2/4  Empaquetando UI y copiando dependencias..." -ForegroundColor Cyan
mvn -q -pl ui package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Fallo el package de la UI." }
mvn -q -pl ui dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$PSScriptRoot\$input"
if ($LASTEXITCODE -ne 0) { throw "Fallo copy-dependencies de la UI." }
Copy-Item "ui\target\$uiJarName" "$input\$uiJarName" -Force

# --- 3/4  jpackage -> .msi ---------------------------------------------------
Write-Host "==> 3/4  Generando .msi con jpackage (necesita WiX)..." -ForegroundColor Cyan
& $jpackage `
    --type msi `
    --name BENJAGEST `
    --app-version $version `
    --vendor "BENJAGEST" `
    --description "Gestion de asesoria fiscal y laboral" `
    --input $input `
    --main-jar $uiJarName `
    --main-class com.benjagest.ui.Launcher `
    --java-options "-Dbenjagest.launch.backend=true" `
    --add-modules ALL-MODULE-PATH `
    --jlink-options "--strip-debug --no-man-pages --no-header-files" `
    --app-content "$dist\backend.jar" `
    --win-menu `
    --win-menu-group "BENJAGEST" `
    --win-shortcut `
    --win-dir-chooser `
    --dest $out
if ($LASTEXITCODE -ne 0) { throw "jpackage fallo (revisa que WiX 3.x este instalado)." }

# --- 4/4  Listo -------------------------------------------------------------
$msi = Get-ChildItem "$out\*.msi" -ErrorAction SilentlyContinue | Select-Object -First 1
Write-Host "==> 4/4  Listo." -ForegroundColor Green
if ($msi) { Write-Host "    Instalador: $($msi.FullName)" -ForegroundColor Green }
