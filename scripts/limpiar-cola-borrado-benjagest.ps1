# ===========================================================================
# BENJAGEST — Limpia la cola de BORRADO AL REINICIAR que dejaron las
# actualizaciones fallidas (incidente 2026-07-15).
#
# QUE ES ESTO
#   Cuando msiexec no puede reemplazar un fichero porque esta en uso, apunta la
#   operacion en el registro para hacerla en el proximo arranque
#   (PendingFileRenameOperations). Tras los intentos fallidos de actualizar a la
#   0.1.42, quedaron 41 entradas apuntando a ficheros de BENJAGEST que SI hacen
#   falta: los jar de app\ y el runtime\lib\modules (el JRE).
#
#   Todas esas entradas son de tipo BORRAR (destino vacio) — no hay ni una de
#   reemplazar. O sea: al reiniciar, Windows BORRARIA la app. Es exactamente el
#   modo de fallo del incidente 0.1.36 ("Failed setting boot class path").
#
# QUE HACE
#   Quita SOLO las entradas que apuntan a C:\Program Files\BENJAGEST. El resto
#   de la cola (Windows Update, otros programas) se queda intacta.
#   Primero ENSEÑA lo que va a quitar y pide confirmacion.
#
# COMO SE USA
#   Clic derecho -> "Ejecutar con PowerShell" como Administrador. O:
#   powershell -NoProfile -ExecutionPolicy Bypass -File limpiar-cola-borrado-benjagest.ps1
#
# SEGURIDAD
#   Lo peor que puede pasar si sobra alguna entrada legitima es que un fichero
#   temporal no se borre en el arranque. Comparado con quedarse sin la app, es
#   un intercambio evidente.
# ===========================================================================
$ErrorActionPreference = 'Stop'

$esAdmin = ([Security.Principal.WindowsPrincipal] `
    [Security.Principal.WindowsIdentity]::GetCurrent()).IsInRole(
    [Security.Principal.WindowsBuiltInRole]::Administrator)
if (-not $esAdmin) {
    Write-Host "Hace falta ejecutarlo como ADMINISTRADOR (escribe en HKLM)." -ForegroundColor Red
    Read-Host "Pulsa Intro para cerrar"
    exit 1
}

$ruta = 'HKLM:\SYSTEM\CurrentControlSet\Control\Session Manager'
$prop = Get-ItemProperty -Path $ruta -Name PendingFileRenameOperations -ErrorAction SilentlyContinue
if (-not $prop) {
    Write-Host "La cola ya esta vacia. No hay nada que limpiar." -ForegroundColor Green
    Read-Host "Pulsa Intro para cerrar"
    exit 0
}

$raw = @($prop.PendingFileRenameOperations)
Write-Host "Entradas en la cola: $($raw.Count)"

# La cola son PARES: [origen, destino]. Destino vacio = borrar al arrancar.
# Se recorre de dos en dos y se descarta el par si el origen es de BENJAGEST.
$nuevo = New-Object System.Collections.Generic.List[string]
$quitados = New-Object System.Collections.Generic.List[string]
for ($i = 0; $i -lt $raw.Count; $i += 2) {
    $src = $raw[$i]
    $dst = if ($i + 1 -lt $raw.Count) { $raw[$i + 1] } else { '' }
    if ($src -match 'Program Files\\BENJAGEST') {
        $quitados.Add(($src -replace '^\*?1?\\\?\?\\', ''))
    } else {
        $nuevo.Add($src); $nuevo.Add($dst)
    }
}

if ($quitados.Count -eq 0) {
    Write-Host "No hay entradas de BENJAGEST en la cola. Nada que hacer." -ForegroundColor Green
    Read-Host "Pulsa Intro para cerrar"
    exit 0
}

Write-Host ""
Write-Host "Se van a QUITAR de la cola estos $($quitados.Count) borrados:" -ForegroundColor Yellow
$quitados | ForEach-Object { Write-Host "   $_" }
Write-Host ""
Write-Host "Es decir: estos ficheros NO se borraran en el proximo arranque." -ForegroundColor Green
Write-Host "Se conservan $($nuevo.Count / 2) entradas de otros programas." -ForegroundColor Gray
Write-Host ""

$r = Read-Host "Escribe SI para aplicar (cualquier otra cosa cancela)"
if ($r -ne 'SI') { Write-Host "Cancelado. No se ha tocado nada."; Read-Host "Pulsa Intro"; exit 0 }

# Copia de seguridad del valor original, por si acaso.
$backup = Join-Path $env:USERPROFILE ("PendingFileRenameOperations-backup-" + (Get-Date -Format 'yyyyMMdd-HHmmss') + ".txt")
$raw | Set-Content -LiteralPath $backup -Encoding UTF8
Write-Host "Copia del valor original en: $backup" -ForegroundColor Gray

if ($nuevo.Count -eq 0) {
    Remove-ItemProperty -Path $ruta -Name PendingFileRenameOperations
    Write-Host "Cola vaciada por completo (no quedaban otras entradas)." -ForegroundColor Green
} else {
    Set-ItemProperty -Path $ruta -Name PendingFileRenameOperations -Value $nuevo.ToArray() -Type MultiString
    Write-Host "Cola limpiada." -ForegroundColor Green
}

Write-Host ""
Write-Host "Listo. Ya puedes reiniciar sin que se borre la app." -ForegroundColor Green
Read-Host "Pulsa Intro para cerrar"
