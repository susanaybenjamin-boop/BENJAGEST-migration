package com.benjagest.ui.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * BROWSER-CERT-STORE-FIX (2026-07-16) — Importa/quita un .p12 en el almacen de
 * certificados de Windows del USUARIO QUE EJECUTA LA UI ({@code Cert:\CurrentUser\My}).
 *
 * <p><b>Por que esta AQUI y no solo en el backend.</b> El backend
 * ({@code WindowsCertStoreService}) hace lo mismo, pero cuando corre como
 * SERVICIO (LocalSystem, el instalable) importa al almacen de la cuenta del
 * servicio. El gestor-navegador (Chromium) corre como el usuario interactivo y
 * mira SU almacen -> no encontraba el certificado ("no se detecta" en AEAT/DEHu/
 * SS). Esta clase corre dentro de la UI, que SI es el usuario interactivo, asi
 * que el cert acaba donde el navegador lo busca.
 *
 * <p>Misma tecnica que el backend: .NET puro (X509Certificate2 + X509Store), NO
 * {@code Import-PfxCertificate} (autoload de modulos falla en algunos equipos).
 * Contrasena por {@code $env:BG_PFX_PW} (no en argv, no en el listado de
 * procesos). El .pfx temporal se borra tras importar (la clave ya esta en el
 * almacen). Solo Windows.
 */
public final class WindowsCertImporter {

    private static final long TIMEOUT_SECONDS = 60;

    private WindowsCertImporter() {}

    /** Importa el .p12 a Cert:\CurrentUser\My del usuario y devuelve la huella (40 hex), o null. */
    public static String importToUserStore(byte[] p12, String password) {
        if (!isWindows()) {
            return null;
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("bg-uicert-", ".pfx");
            Files.write(tmp, p12);
            String path = tmp.toAbsolutePath().toString();
            String script = "$ErrorActionPreference='Stop';"
                    + "$bytes=[System.IO.File]::ReadAllBytes('" + path + "');"
                    + "$flags=[System.Security.Cryptography.X509Certificates.X509KeyStorageFlags]'PersistKeySet';"
                    + "$cert=New-Object System.Security.Cryptography.X509Certificates.X509Certificate2"
                    + " -ArgumentList $bytes,$env:BG_PFX_PW,$flags;"
                    + "$store=New-Object System.Security.Cryptography.X509Certificates.X509Store"
                    + " -ArgumentList 'My','CurrentUser';"
                    + "$store.Open('ReadWrite');$store.Add($cert);$store.Close();"
                    + "Write-Output $cert.Thumbprint";
            String out = runPowerShell(script, password, true);
            String thumb = lastNonEmptyLine(out);
            if (thumb == null || !thumb.matches("[0-9A-Fa-f]{40}")) {
                System.err.println("[ui-cert] Import al almacen sin huella valida.");
                return null;
            }
            return thumb.toUpperCase(Locale.ROOT);
        } catch (IOException ex) {
            System.err.println("[ui-cert] Error preparando el certificado: " + ex.getMessage());
            return null;
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ex) {
                    System.err.println("[ui-cert] No se pudo borrar el .pfx temporal " + tmp);
                }
            }
        }
    }

    /** Quita la huella del almacen del usuario (best-effort, al cerrar el gestor). */
    public static void removeFromUserStore(String thumbprint) {
        if (!isWindows() || thumbprint == null || !thumbprint.matches("[0-9A-Fa-f]{40}")) {
            return;
        }
        String script = "$ErrorActionPreference='SilentlyContinue';"
                + "$store=New-Object System.Security.Cryptography.X509Certificates.X509Store"
                + " -ArgumentList 'My','CurrentUser';"
                + "$store.Open('ReadWrite');"
                + "$found=$store.Certificates.Find("
                + "[System.Security.Cryptography.X509Certificates.X509FindType]::FindByThumbprint,'"
                + thumbprint + "',$false);"
                + "foreach($c in $found){$store.Remove($c)};"
                + "$store.Close()";
        try {
            runPowerShell(script, null, false);
        } catch (RuntimeException ex) {
            System.err.println("[ui-cert] No se pudo quitar la huella " + thumbprint + " del almacen");
        }
    }

    private static String runPowerShell(String script, String pfxPassword, boolean strict) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "powershell", "-NoProfile", "-NonInteractive",
                    "-ExecutionPolicy", "Bypass", "-Command", script);
            if (pfxPassword != null) {
                pb.environment().put("BG_PFX_PW", pfxPassword);
            }
            pb.redirectErrorStream(true);
            Process process = pb.start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean finished = process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                throw new RuntimeException("PowerShell no respondio al gestionar el certificado");
            }
            if (strict && process.exitValue() != 0) {
                throw new RuntimeException("PowerShell devolvio error: " + out.trim());
            }
            return out;
        } catch (IOException ex) {
            throw new RuntimeException("No se pudo ejecutar PowerShell: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Interrumpido gestionando el certificado", ex);
        }
    }

    private static String lastNonEmptyLine(String out) {
        if (out == null) {
            return null;
        }
        String result = null;
        for (String line : out.split("\\R")) {
            String trimmed = line.trim();
            if (!trimmed.isEmpty()) {
                result = trimmed;
            }
        }
        return result;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }
}
