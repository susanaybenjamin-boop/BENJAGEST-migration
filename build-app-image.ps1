# ============================================================================
# BENJAGEST - Construye el INSTALABLE autocontenido (app-image jpackage)
#
# Genera una carpeta ejecutable (dist\out\BENJAGEST\BENJAGEST.exe) que arranca
# con DOBLE CLIC: lanza el backend Spring Boot con MariaDB EMBEBIDA como proceso
# hijo y abre la UI JavaFX. Incluye su propia JRE -> NO necesita Java instalado.
#
# El app-image NO necesita WiX. Para empaquetarlo como .msi/.exe, ver
# build-msi.ps1 (requiere WiX 3.x instalado).
#
# Uso:   .\build-app-image.ps1            (reusa jars; reconstruye lo que falte)
#        .\build-app-image.ps1 -Rebuild   (limpia y reconstruye todo)
# ============================================================================
param(
    [switch]$Rebuild
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$version   = "0.1.0"
$uiJarName = "ui-0.1.0-SNAPSHOT.jar"
$backendJar = "backend-java\target\backend-java-0.1.0-SNAPSHOT.jar"
$dist      = "dist"
$input     = "$dist\input"
$out       = "$dist\out"

# jpackage de la JDK (Temurin). Usa JAVA_HOME si esta, si no el PATH.
$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jpackage.exe" } else { "jpackage" }

# --- 1/5  Limpieza ----------------------------------------------------------
if (Test-Path $dist) { Remove-Item -Recurse -Force $dist }
New-Item -ItemType Directory -Force -Path $input | Out-Null

# --- 2/5  Backend fat jar (con MariaDB embebida) ----------------------------
if ($Rebuild -or -not (Test-Path $backendJar)) {
    Write-Host "==> 2/5  Empaquetando backend (fat jar)..." -ForegroundColor Cyan
    mvn -q -pl backend-java package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Fallo el package del backend." }
} else {
    Write-Host "==> 2/5  Reusando backend fat jar." -ForegroundColor Green
}
Copy-Item $backendJar "$dist\backend.jar" -Force

# --- 3/5  UI jar + dependencias de runtime en el dir de entrada -------------
Write-Host "==> 3/5  Empaquetando UI y copiando dependencias..." -ForegroundColor Cyan
mvn -q -pl ui package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Fallo el package de la UI." }
mvn -q -pl ui dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$PSScriptRoot\$input"
if ($LASTEXITCODE -ne 0) { throw "Fallo copy-dependencies de la UI." }
Copy-Item "ui\target\$uiJarName" "$input\$uiJarName" -Force

# --- 4/5  jpackage -> app-image ---------------------------------------------
Write-Host "==> 4/5  Generando app-image con jpackage..." -ForegroundColor Cyan
& $jpackage `
    --type app-image `
    --name BENJAGEST `
    --app-version $version `
    --icon "packaging\benjagest.ico" `
    --input $input `
    --main-jar $uiJarName `
    --main-class com.benjagest.ui.Launcher `
    --java-options "-Dbenjagest.launch.backend=true" `
    --add-modules ALL-MODULE-PATH `
    --jlink-options "--strip-debug --no-man-pages --no-header-files" `
    --app-content "$dist\backend.jar,packaging\tessdata" `
    --dest $out
if ($LASTEXITCODE -ne 0) { throw "jpackage fallo." }

# --- 5/5  Listo -------------------------------------------------------------
$exe = "$out\BENJAGEST\BENJAGEST.exe"
Write-Host "==> 5/5  Listo." -ForegroundColor Green
Write-Host "    App-image: $PSScriptRoot\$exe" -ForegroundColor Green
Write-Host "    Doble clic en ese .exe arranca backend embebido + UI." -ForegroundColor Green
