# ============================================================================
# BENJAGEST - Arranque del SERVIDOR local (oficina)
# Levanta MariaDB (Docker), espera a que esté lista y arranca el backend.
# Uso:   .\start-local-server.ps1            (arranca con Maven)
#        .\start-local-server.ps1 -Jar       (arranca el .jar empaquetado)
# Ver docs/despliegue-local.md
# ============================================================================
param(
    [switch]$Jar,
    [string]$DbPort = "3307"
)

$ErrorActionPreference = "Stop"
Set-Location -Path $PSScriptRoot

Write-Host "==> 1/3  Levantando MariaDB (Docker)..." -ForegroundColor Cyan
docker compose up -d

Write-Host "==> 2/3  Esperando a que la base de datos esté lista..." -ForegroundColor Cyan
$ready = $false
for ($i = 1; $i -le 30; $i++) {
    try {
        docker compose exec -T mariadb mariadb -ubenjagest -pbenjagest benjagest -e "SELECT 1;" *> $null
        if ($LASTEXITCODE -eq 0) { $ready = $true; break }
    } catch { }
    Start-Sleep -Seconds 2
}
if (-not $ready) {
    Write-Host "    La BD no respondió a tiempo. Revisa 'docker compose logs mariadb'." -ForegroundColor Red
    exit 1
}
Write-Host "    Base de datos lista." -ForegroundColor Green

# Variables de conexión del backend (puerto local de MariaDB).
$env:BENJAGEST_DB_URL = "jdbc:mariadb://localhost:$DbPort/benjagest?connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true"
$env:BENJAGEST_DB_USER = "benjagest"
$env:BENJAGEST_DB_PASSWORD = "benjagest"

Write-Host "==> 3/3  Arrancando backend (puerto 8080)..." -ForegroundColor Cyan
if ($Jar) {
    $jar = Get-ChildItem -Path "backend-java\target" -Filter "*.jar" -ErrorAction SilentlyContinue |
           Where-Object { $_.Name -notlike "*-sources.jar" -and $_.Name -notlike "*-javadoc.jar" } |
           Select-Object -First 1
    if (-not $jar) {
        Write-Host "    No hay .jar. Empaquétalo antes: mvn -pl backend-java -am clean package -DskipTests" -ForegroundColor Red
        exit 1
    }
    Write-Host "    Ejecutando $($jar.FullName)" -ForegroundColor Green
    java -jar $jar.FullName
} else {
    mvn -pl backend-java spring-boot:run
}
