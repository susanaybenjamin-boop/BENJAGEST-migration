# ============================================================================
# BENJAGEST - Construye el INSTALADOR .msi (jpackage + WiX)
#
# Genera un .msi que instala BENJAGEST con acceso directo en el menu Inicio y
# el escritorio, incluyendo su propia JRE.
#
# DOS VARIANTES (F1-INSTVAR, 2026-07-12):
#   -Variant advisory  (por defecto) = ASESORIA / servidor de oficina.
#       UI + backend + BD embebida + SERVICIO de Windows (winsw). El backend
#       corre como servicio (arranca con Windows); la UI se conecta a el.
#   -Variant puesto    = PUESTO de escritorio en la LAN (2o PC de la oficina).
#       SOLO UI. Sin backend, sin BD, sin servicio. Apunta al servidor de la
#       oficina via la variable BENJAGEST_API_BASE_URL (script set-server.ps1).
#       NOTA: NO es la app del empleado final (fichar/nominas) -> esa es la PWA
#       movil servida por el backend (bloque MEMP), sin instalador.
#
# REQUISITO: WiX Toolset 3.x en el PATH (candle.exe / light.exe). jpackage lo
#   usa para construir el .msi. Descarga: https://github.com/wixtoolset/wix3/releases
#   (Si WiX no esta en el PATH:  $env:PATH = 'C:\Program Files (x86)\WiX Toolset v3.14\bin;' + $env:PATH)
#
# Uso:   .\build-msi.ps1                         (Asesoria; reusa jars)
#        .\build-msi.ps1 -Rebuild                (Asesoria; reconstruye todo)
#        .\build-msi.ps1 -Variant puesto         (Puesto LAN; solo UI)
# ============================================================================
param(
    [switch]$Rebuild,
    [string]$Version = "0.1.0",
    [ValidateSet("advisory","puesto")]
    [string]$Variant = "advisory"
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

$version    = $Version
$uiJarName  = "ui-0.1.0-SNAPSHOT.jar"
$backendJar = "backend-java\target\backend-java-0.1.0-SNAPSHOT.jar"
$dist       = "dist"
$input      = "$dist\input"
$out        = "$dist\msi"

$jpackage = if ($env:JAVA_HOME) { Join-Path $env:JAVA_HOME "bin\jpackage.exe" } else { "jpackage" }

Write-Host "==> Variante: $Variant" -ForegroundColor Magenta

# Aviso temprano si WiX no esta.
if (-not (Get-Command candle.exe -ErrorAction SilentlyContinue)) {
    Write-Host "AVISO: no encuentro WiX (candle.exe) en el PATH." -ForegroundColor Yellow
    Write-Host "       `$env:PATH = 'C:\Program Files (x86)\WiX Toolset v3.14\bin;' + `$env:PATH" -ForegroundColor Yellow
}

# --- 1/4  Limpieza + (solo Asesoria) backend fat jar -------------------------
if (Test-Path $input) { Remove-Item -Recurse -Force $input }
New-Item -ItemType Directory -Force -Path $input | Out-Null
New-Item -ItemType Directory -Force -Path $out   | Out-Null

if ($Variant -eq "advisory") {
    if ($Rebuild -or -not (Test-Path $backendJar)) {
        Write-Host "==> 1/4  Empaquetando backend (fat jar)..." -ForegroundColor Cyan
        mvn -q -pl backend-java package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Fallo el package del backend." }
    } else {
        Write-Host "==> 1/4  Reusando backend fat jar." -ForegroundColor Green
    }
    Copy-Item $backendJar "$dist\backend.jar" -Force

    # Gestor-navegador (navegador embebido JCEF), va por --app-content.
    $gestorJar = "gestor-navegador\target\gestor-navegador.jar"
    if ($Rebuild -or -not (Test-Path $gestorJar)) {
        Write-Host "==> 1b/4 Empaquetando gestor-navegador (fat jar)..." -ForegroundColor Cyan
        mvn -q -pl gestor-navegador package -DskipTests
        if ($LASTEXITCODE -ne 0) { throw "Fallo el package del gestor-navegador." }
    } else {
        Write-Host "==> 1b/4 Reusando gestor-navegador fat jar." -ForegroundColor Green
    }
    Copy-Item $gestorJar "$dist\gestor-navegador.jar" -Force
} else {
    Write-Host "==> 1/4  Variante Puesto (cliente LAN): sin backend ni gestor (solo UI)." -ForegroundColor Green
}

# --- 2/4  UI jar + dependencias ---------------------------------------------
Write-Host "==> 2/4  Empaquetando UI y copiando dependencias..." -ForegroundColor Cyan
mvn -q -pl ui package -DskipTests
if ($LASTEXITCODE -ne 0) { throw "Fallo el package de la UI." }
mvn -q -pl ui dependency:copy-dependencies "-DincludeScope=runtime" "-DoutputDirectory=$PSScriptRoot\$input"
if ($LASTEXITCODE -ne 0) { throw "Fallo copy-dependencies de la UI." }
Copy-Item "ui\target\$uiJarName" "$input\$uiJarName" -Force

# --- 3/4  jpackage -> .msi ---------------------------------------------------
Write-Host "==> 3/4  Generando .msi con jpackage (necesita WiX)..." -ForegroundColor Cyan

# Parametros que difieren por variante.
if ($Variant -eq "advisory") {
    $appName     = "BENJAGEST"
    $appDesc     = "Gestion de asesoria fiscal y laboral (servidor de oficina)"
    $upgradeUuid = "172b518f-5b60-37cd-869c-e3df5dc99806"
    # backend + gestor + tessdata + los 4 ficheros del SERVICIO (winsw) en la
    # raiz de la instalacion (asi %BASE% de winsw = raiz, junto a runtime\).
    $appContent  = @(
        "$dist\backend.jar",
        "$dist\gestor-navegador.jar",
        "packaging\tessdata",
        "packaging\service\benjagest-backend.exe",
        "packaging\service\benjagest-backend.xml",
        "packaging\service\install-service.ps1",
        "packaging\service\install-service.cmd",
        "packaging\service\uninstall-service.ps1",
        "packaging\service\uninstall-service.cmd"
    ) -join ","
    # La UI intenta auto-arrancar el backend SOLO si el servicio no responde
    # (Launcher.isBackendUp); con el servicio activo es un no-op.
    $javaOptions = "-Dbenjagest.launch.backend=true"
} else {
    $appName     = "BENJAGEST Puesto"
    $appDesc     = "Puesto de escritorio BENJAGEST en la LAN (se conecta al servidor de la oficina)"
    $upgradeUuid = "9f3c2a17-4b8e-4c21-9d6a-2e7f10b4c355"
    # Solo el script para apuntar al servidor de la oficina. Sin backend.
    $appContent  = "packaging\service\set-server.ps1"
    $javaOptions = $null
}

$jpArgs = @(
    "--type", "msi",
    "--name", $appName,
    "--app-version", $version,
    "--icon", "packaging\benjagest.ico",
    "--vendor", "BENJAGEST",
    "--description", $appDesc,
    "--input", $input,
    "--main-jar", $uiJarName,
    "--main-class", "com.benjagest.ui.Launcher",
    "--add-modules", "ALL-MODULE-PATH",
    "--jlink-options", "--strip-debug --no-man-pages --no-header-files",
    "--app-content", $appContent,
    "--win-menu",
    "--win-menu-group", "BENJAGEST",
    "--win-shortcut",
    "--win-dir-chooser",
    "--win-upgrade-uuid", $upgradeUuid,
    "--dest", $out
)
if ($javaOptions) { $jpArgs += @("--java-options", $javaOptions) }

# Borra solo el MSI de ESTA variante (para que la otra conviva en dist\msi).
$targetMsi = Join-Path $out "$appName-$version.msi"
if (Test-Path $targetMsi) { Remove-Item -Force $targetMsi }

& $jpackage @jpArgs
if ($LASTEXITCODE -ne 0) { throw "jpackage fallo (revisa que WiX 3.x este instalado)." }

# --- 4/4  Listo -------------------------------------------------------------
$msi = Get-ChildItem "$out\*.msi" -ErrorAction SilentlyContinue | Select-Object -First 1
Write-Host "==> 4/4  Listo ($Variant)." -ForegroundColor Green
if ($msi) { Write-Host "    Instalador: $($msi.FullName)" -ForegroundColor Green }
if ($Variant -eq "advisory") {
    Write-Host "    Tras instalar: ejecutar (Admin) install-service.ps1 de la carpeta de instalacion." -ForegroundColor Green
} else {
    Write-Host "    Tras instalar: ejecutar (Admin) set-server.ps1 -Server <ip-servidor>." -ForegroundColor Green
}
