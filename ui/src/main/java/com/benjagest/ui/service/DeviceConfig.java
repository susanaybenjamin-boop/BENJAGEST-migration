package com.benjagest.ui.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/**
 * L4-3 — Persistencia local del emparejado del PC con la asesoría.
 *
 * <p>Vive en {@code %APPDATA%\benjagest\device.json} (Windows) o
 * {@code ~/.config/benjagest/device.json} (Linux/Mac). Cada usuario
 * Windows del PC tiene su propio emparejado — útil si el PC lo
 * comparten varias asesorías.
 *
 * <p>Contiene:
 * <ul>
 *   <li>{@code deviceId} — UUID del registro en BD, para poder revocar
 *       desde la pantalla "Mis equipos" del OWNER.</li>
 *   <li>{@code deviceSecret} — secret en plano que se manda al backend
 *       en cada login PIN. <strong>Sensible.</strong> El backend solo
 *       tiene su hash bcrypt; el plano vive aquí y en RAM mientras
 *       la app corre.</li>
 *   <li>{@code companyId} + {@code companyName} — caché para que la
 *       pantalla de PIN pueda decir "Asesoría XYZ" sin hablar con el
 *       backend antes del login.</li>
 * </ul>
 *
 * <p>JSON minimalista para no añadir Jackson aquí — son 4 campos string.
 */
public final class DeviceConfig {

    private static final String FILE_NAME = "device.json";

    private final String deviceId;
    private final String deviceSecret;
    private final String companyId;
    private final String companyName;

    private DeviceConfig(String deviceId, String deviceSecret,
                          String companyId, String companyName) {
        this.deviceId = deviceId;
        this.deviceSecret = deviceSecret;
        this.companyId = companyId;
        this.companyName = companyName;
    }

    public String deviceId() { return deviceId; }
    public String deviceSecret() { return deviceSecret; }
    public String companyId() { return companyId; }
    public String companyName() { return companyName; }

    /**
     * Lee la configuración local si existe. Devuelve Optional vacío si
     * el archivo no existe o está corrupto — el caller asume "PC no
     * emparejado" y muestra la pantalla de emparejado.
     */
    public static Optional<DeviceConfig> load() {
        Path path = configPath();
        if (!Files.exists(path)) return Optional.empty();
        try {
            String json = Files.readString(path);
            String deviceId = extract(json, "deviceId");
            String deviceSecret = extract(json, "deviceSecret");
            String companyId = extract(json, "companyId");
            String companyName = extract(json, "companyName");
            if (deviceId == null || deviceSecret == null) return Optional.empty();
            return Optional.of(new DeviceConfig(deviceId, deviceSecret,
                    companyId, companyName == null ? "" : companyName));
        } catch (IOException ex) {
            return Optional.empty();
        }
    }

    /**
     * Guarda la configuración tras un emparejado exitoso. Crea el
     * directorio padre si no existe. El archivo se escribe entero
     * (no incremental) para que un corte de luz no deje partes.
     */
    public static void save(String deviceId, String deviceSecret,
                              String companyId, String companyName) throws IOException {
        Path path = configPath();
        Files.createDirectories(path.getParent());
        String json = "{\n"
                + "  \"deviceId\": \"" + escape(deviceId) + "\",\n"
                + "  \"deviceSecret\": \"" + escape(deviceSecret) + "\",\n"
                + "  \"companyId\": \"" + escape(companyId == null ? "" : companyId) + "\",\n"
                + "  \"companyName\": \"" + escape(companyName == null ? "" : companyName) + "\"\n"
                + "}\n";
        Files.writeString(path, json);
    }

    /**
     * Borra el archivo de emparejado. Lo llama "Olvidar este equipo".
     * Idempotente: si el archivo ya no existe, no pasa nada.
     */
    public static void clear() {
        try {
            Files.deleteIfExists(configPath());
        } catch (IOException ignored) {
            // Si no podemos borrar (permisos), al menos no peta —
            // el siguiente arranque mostrará pantalla de PIN igualmente
            // pero el siguiente login fallará y forzará re-emparejado.
        }
    }

    /**
     * Ubicación absoluta del archivo. Windows: %APPDATA%\benjagest\;
     * resto: $HOME/.config/benjagest/. {@code APPDATA} se respeta
     * cuando viene del entorno (caso normal Windows); si no, se cae
     * al hogar del usuario para que el código siga corriendo en
     * Linux/Mac durante desarrollo.
     */
    private static Path configPath() {
        String appData = System.getenv("APPDATA");
        if (appData != null && !appData.isBlank()) {
            return Path.of(appData, "benjagest", FILE_NAME);
        }
        String home = System.getProperty("user.home");
        return Path.of(home, ".config", "benjagest", FILE_NAME);
    }

    private static String extract(String json, String key) {
        // Parser JSON minimalista para no añadir Jackson en la UI.
        // Asume valores string (sin escapes complejos más allá de \").
        int keyIdx = json.indexOf("\"" + key + "\"");
        if (keyIdx < 0) return null;
        int colon = json.indexOf(':', keyIdx);
        if (colon < 0) return null;
        int firstQuote = json.indexOf('"', colon);
        if (firstQuote < 0) return null;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = firstQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escape) {
                sb.append(c == 'n' ? '\n' : c);
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return null;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
