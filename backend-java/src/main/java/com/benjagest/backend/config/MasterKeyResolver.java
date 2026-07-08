package com.benjagest.backend.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * RGPD (2026-07-08) — clave maestra de cifrado POR INSTALACION.
 *
 * Antes la clave salia de BENJAGEST_ENCRYPTION_PASSWORD con un default
 * de desarrollo visible en application.yml — toda instalacion sin env
 * var cifraba con la MISMA clave publica en GitHub (hallazgo de la
 * auditoria integral 2026-07-07).
 *
 * Resolucion, en orden:
 * <ol>
 *   <li>Si la propiedad llega con un valor DISTINTO del sentinel de
 *       desarrollo (o sea: el operador puso BENJAGEST_ENCRYPTION_PASSWORD),
 *       se respeta — comportamiento previo intacto.</li>
 *   <li>Si no, fichero de clave por instalacion en
 *       {@code %ProgramData%\BENJAGEST\secret\master.key} (o
 *       {@code ~/.benjagest/secret/master.key} fuera de Windows).
 *       Si no existe, se GENERA (48 bytes aleatorios seguros, base64)
 *       y en Windows se restringe el ACL con icacls (solo SYSTEM,
 *       Administradores y el usuario actual) — best effort.</li>
 * </ol>
 *
 * La clave de desarrollo queda SOLO como fallback de DESCIFRADO
 * (ver {@link FallbackStringEncryptor}): las instalaciones que ya
 * cifraron datos con ella (certificados, tokens Google, SMTP) los
 * siguen leyendo, y todo lo que se escriba a partir de ahora sale con
 * la clave nueva.
 */
public final class MasterKeyResolver {

    /** El default del application.yml — si llega este valor, NADIE configuro clave. */
    public static final String DEV_SENTINEL = "devEncryptionKeyBenjagestNotForProduction_2026";

    private MasterKeyResolver() {}

    public static String resolve(String configuredPassword) {
        if (configuredPassword != null && !configuredPassword.isBlank()
                && !DEV_SENTINEL.equals(configuredPassword)) {
            return configuredPassword;
        }
        try {
            Path keyFile = keyFilePath();
            if (Files.exists(keyFile)) {
                String key = Files.readString(keyFile, StandardCharsets.UTF_8).trim();
                if (!key.isBlank()) return key;
            }
            return generateAndStore(keyFile);
        } catch (Exception ex) {
            // Sin acceso al disco (raro): mejor arrancar con la clave de
            // desarrollo (comportamiento previo) que no arrancar. Se
            // avisa alto y claro en el log.
            org.slf4j.LoggerFactory.getLogger(MasterKeyResolver.class)
                    .error("No se pudo crear/leer el fichero de clave maestra ({}). "
                            + "Se usa la clave de DESARROLLO — configura "
                            + "BENJAGEST_ENCRYPTION_PASSWORD.", ex.getMessage());
            return DEV_SENTINEL;
        }
    }

    private static Path keyFilePath() {
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Path.of(programData, "BENJAGEST", "secret", "master.key");
        }
        return Path.of(System.getProperty("user.home"), ".benjagest", "secret", "master.key");
    }

    private static String generateAndStore(Path keyFile) throws IOException {
        byte[] raw = new byte[48];
        new SecureRandom().nextBytes(raw);
        String key = Base64.getEncoder().withoutPadding().encodeToString(raw);
        Files.createDirectories(keyFile.getParent());
        Files.writeString(keyFile, key, StandardCharsets.UTF_8);
        restrictAclBestEffort(keyFile);
        org.slf4j.LoggerFactory.getLogger(MasterKeyResolver.class)
                .info("Clave maestra de cifrado generada para esta instalacion: {}", keyFile);
        return key;
    }

    /**
     * Windows: deja el fichero legible solo por SYSTEM, Administradores
     * y el usuario actual. Si icacls falla (permisos, no-Windows), se
     * loguea y se sigue — el fichero al menos vive fuera del repo y del
     * directorio de la app.
     */
    private static void restrictAclBestEffort(Path keyFile) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        try {
            String user = System.getenv("USERNAME");
            Process p = new ProcessBuilder("icacls", keyFile.toString(),
                    "/inheritance:r",
                    "/grant:r", "*S-1-5-18:F",          // SYSTEM
                    "/grant:r", "*S-1-5-32-544:F",      // Administradores
                    "/grant:r", (user == null ? "%USERNAME%" : user) + ":F")
                    .redirectErrorStream(true).start();
            p.waitFor();
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(MasterKeyResolver.class)
                    .warn("No se pudo restringir el ACL de {}: {}", keyFile, ex.getMessage());
        }
    }
}
