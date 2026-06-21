package com.benjagest.backend.certificates;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Importa/quita un .p12 en el ALMACEN DE WINDOWS del usuario actual
 * (Cert:\CurrentUser\My) para que Chromium (el gestor-navegador) lo
 * ofrezca en su dialogo nativo de certificado de cliente al entrar en
 * AEAT/DEHu/SS. JCEF (127) no expone onSelectClientCertificate, asi que
 * NO se puede auto-seleccionar por codigo: lo unico viable es dejar el
 * cert del cliente en el almacen y que el dialogo nativo lo use.
 *
 * <p>La clave privada NO viaja por HTTP: el .p12 se descifra aqui (mismo
 * proceso/usuario que lanza el backend on-premise) y se importa via
 * PowerShell. La contrasena se pasa por VARIABLE DE ENTORNO (BG_PFX_PW),
 * nunca en la linea de comandos (no queda en el listado de procesos).
 * El .pfx temporal se borra inmediatamente tras importar.
 *
 * <p>Solo Windows. En otros SO lanza 501 (el instalable es Windows
 * autocontenido, decision de despliegue del proyecto).
 */
@Service
public class WindowsCertStoreService {

    private static final Logger log = LoggerFactory.getLogger(WindowsCertStoreService.class);
    private static final long TIMEOUT_SECONDS = 60;

    /** Importa el .p12 a Cert:\CurrentUser\My y devuelve la huella (40 hex, mayuscula). */
    public String importToUserStore(byte[] p12, String password) {
        if (!isWindows()) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED,
                    "Importar al almacen de certificados solo esta soportado en Windows");
        }
        Path tmp = null;
        try {
            tmp = Files.createTempFile("bg-cert-", ".pfx");
            Files.write(tmp, p12);
            // Contrasena por $env:BG_PFX_PW (no en argv). Ruta entre comillas simples
            // (literal PowerShell: no interpola, las barras invertidas no escapan).
            String script = "$ErrorActionPreference='Stop';"
                    + "$pw = ConvertTo-SecureString -String $env:BG_PFX_PW -AsPlainText -Force;"
                    + "$c = Import-PfxCertificate -FilePath '" + tmp.toAbsolutePath()
                    + "' -CertStoreLocation Cert:\\CurrentUser\\My -Password $pw;"
                    + "Write-Output $c.Thumbprint";
            String out = runPowerShell(script, password, true);
            String thumb = lastNonEmptyLine(out);
            if (thumb == null || !thumb.matches("[0-9A-Fa-f]{40}")) {
                log.warn("[cert-store] Import-PfxCertificate sin huella valida. Salida: {}", out);
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "No se pudo importar el certificado al almacen de Windows");
            }
            return thumb.toUpperCase(Locale.ROOT);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error preparando el certificado para el almacen: " + ex.getMessage(), ex);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp); // la clave privada ya esta en el almacen
                } catch (IOException ex) {
                    log.warn("[cert-store] No se pudo borrar el .pfx temporal {}", tmp, ex);
                }
            }
        }
    }

    /** Quita la huella del almacen del usuario (best-effort: al cerrar el gestor). */
    public void removeFromUserStore(String thumbprint) {
        if (!isWindows() || thumbprint == null || !thumbprint.matches("[0-9A-Fa-f]{40}")) {
            return;
        }
        String script = "$ErrorActionPreference='SilentlyContinue';"
                + "Remove-Item -Path ('Cert:\\CurrentUser\\My\\" + thumbprint + "') -Force";
        try {
            runPowerShell(script, null, false);
        } catch (RuntimeException ex) {
            // Best-effort: si no se pudo quitar, queda en el almacen pero no rompe nada.
            log.warn("[cert-store] No se pudo quitar la huella {} del almacen", thumbprint, ex);
        }
    }

    private String runPowerShell(String script, String pfxPassword, boolean strict) {
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
                throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                        "PowerShell no respondio al gestionar el certificado");
            }
            if (strict && process.exitValue() != 0) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "PowerShell devolvio error gestionando el certificado: " + out.trim());
            }
            return out;
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo ejecutar PowerShell: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Interrumpido gestionando el certificado", ex);
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
