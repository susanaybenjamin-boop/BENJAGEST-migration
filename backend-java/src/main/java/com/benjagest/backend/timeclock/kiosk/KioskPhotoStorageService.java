package com.benjagest.backend.timeclock.kiosk;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * FM-4 — Guarda en disco la foto-evidencia de un fichaje de kiosco. Mismo
 * patrón que {@link com.benjagest.backend.billing.pdf.InvoiceStorageService}:
 * raíz configurable ({@code benjagest.kiosk.photo-root}), fallback al home.
 *
 * <p>Ruta: {root}/{companyId}/{YYYY-MM}/{eventId}.jpg
 */
@Service
public class KioskPhotoStorageService {

    private static final String UNSAFE = "[\\\\/:*?\"<>|\\r\\n\\t]";
    private final String fallbackRoot;

    public KioskPhotoStorageService(
            @Value("${benjagest.kiosk.photo-root:}") String defaultRoot) {
        this.fallbackRoot = StringUtils.hasText(defaultRoot)
                ? defaultRoot
                : com.benjagest.backend.config.BenjagestHome.resolve("fichaje-fotos").toString();
    }

    /**
     * Decodifica el base64 (acepta data-URL "data:image/...;base64,XXX") y lo
     * escribe como JPG. Devuelve la ruta absoluta o null si no hay contenido.
     */
    public String savePhoto(String companyId, String eventId, String base64) throws IOException {
        if (!StringUtils.hasText(base64) || !StringUtils.hasText(eventId)) return null;
        String data = base64.contains(",") ? base64.substring(base64.indexOf(',') + 1) : base64;
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data.trim());
        } catch (IllegalArgumentException ex) {
            return null; // base64 inválido → no rompemos el fichaje
        }
        String safeEvent = eventId.replaceAll(UNSAFE, "_");
        String month = LocalDate.now().toString().substring(0, 7); // YYYY-MM
        Path target = Paths.get(fallbackRoot,
                companyId == null ? "_" : companyId.replaceAll(UNSAFE, "_"),
                month, safeEvent + ".jpg");
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
        return target.toAbsolutePath().toString();
    }

    public boolean exists(String path) {
        return StringUtils.hasText(path) && Files.exists(Paths.get(path));
    }

    public byte[] read(String path) throws IOException {
        return Files.readAllBytes(Paths.get(path));
    }
}
