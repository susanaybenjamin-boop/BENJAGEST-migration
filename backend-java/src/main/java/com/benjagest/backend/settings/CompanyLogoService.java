package com.benjagest.backend.settings;

import com.benjagest.backend.tenant.TenantContext;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * PORT-4 LOGO (sesión 2026-06-10 tarde) — Logo de empresa.
 *
 * <p>Sustituye al placeholder actual de {@code InvoicePdfGenerator}. Se
 * guarda en {@code {storageRoot}/{companyId}/_brand/logo.png}:
 * <ul>
 *   <li>{@code companyId} en la ruta garantiza aislamiento aunque dos
 *       empresas compartan {@code storageRoot}.</li>
 *   <li>Subcarpeta {@code _brand/} separa lo cosmético de lo legal
 *       (facturas). El underscore inicial las distingue de carpetas
 *       de años (2024, 2025...).</li>
 * </ul>
 *
 * <p>Procesamiento (decisión Benjamin):
 * <ul>
 *   <li>Solo se acepta PNG / JPG.</li>
 *   <li>Si supera 2 MB → recomprime con calidad 0.8 hasta caber.</li>
 *   <li>Se redimensiona automáticamente: ancho máx 400 px manteniendo
 *       proporción (suficiente para PDF de factura A4 + salvapantallas).</li>
 *   <li>Resultado siempre se guarda como PNG (transparencia).</li>
 * </ul>
 */
@Service
public class CompanyLogoService {

    private static final long MAX_INPUT_BYTES = 5L * 1024 * 1024; // hard cap pre-compresion
    private static final long MAX_FINAL_BYTES = 2L * 1024 * 1024; // limite final tras recompresion
    private static final int MAX_WIDTH_PX = 400;

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final String fallbackRoot;

    public CompanyLogoService(JdbcTemplate jdbcTemplate,
                               TenantContext tenantContext,
                               @Value("${benjagest.invoices.storage-root:}") String defaultRoot) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.fallbackRoot = StringUtils.hasText(defaultRoot)
                ? defaultRoot
                : com.benjagest.backend.config.BenjagestHome.resolve("facturas").toString();
    }

    /**
     * Guarda el logo subido y devuelve la ruta absoluta donde quedó.
     * Actualiza {@code companies.logo_path}.
     */
    @Transactional
    public LogoInfo upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        }
        String original = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (!(original.endsWith(".png") || original.endsWith(".jpg") || original.endsWith(".jpeg"))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se aceptan archivos PNG o JPG.");
        }
        long size = file.getSize();
        if (size > MAX_INPUT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "El archivo supera 5 MB. Reduce su tamaño antes de subirlo.");
        }
        String companyId = tenantContext.getCurrentCompanyId();
        try {
            byte[] originalBytes = file.getBytes();
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(originalBytes));
            if (img == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No se pudo leer la imagen. ¿Es un PNG/JPG válido?");
            }
            // Redimensionar si supera el ancho máximo.
            BufferedImage resized = img;
            if (img.getWidth() > MAX_WIDTH_PX) {
                int newW = MAX_WIDTH_PX;
                int newH = Math.round((float) img.getHeight() * MAX_WIDTH_PX / img.getWidth());
                resized = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = resized.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                        RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.setRenderingHint(RenderingHints.KEY_RENDERING,
                        RenderingHints.VALUE_RENDER_QUALITY);
                g.drawImage(img, 0, 0, newW, newH, null);
                g.dispose();
            }
            // Codificar a PNG. Si pesa más de 2MB tras eso, recurrimos a
            // JPEG con calidad descendente.
            byte[] finalBytes = encodePng(resized);
            float quality = 0.8f;
            while (finalBytes.length > MAX_FINAL_BYTES && quality >= 0.4f) {
                finalBytes = encodeJpeg(resized, quality);
                quality -= 0.1f;
            }
            // Persistir en disco.
            Path dir = Paths.get(fallbackRoot, companyId, "_brand");
            Files.createDirectories(dir);
            // Atomic-ish: escribimos a tmp y movemos.
            String ext = (finalBytes == originalBytes
                    || isPng(finalBytes)) ? "png" : "jpg";
            Path tmp = dir.resolve("logo-" + UUID.randomUUID() + "." + ext);
            Path target = dir.resolve("logo." + ext);
            Files.write(tmp, finalBytes);
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);

            // Actualizar BD.
            String abs = target.toAbsolutePath().toString();
            jdbcTemplate.update("UPDATE companies SET logo_path = ? WHERE id = ?",
                    abs, companyId);
            return new LogoInfo(abs, resized.getWidth(), resized.getHeight(),
                    finalBytes.length);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo procesar el logo: " + ex.getMessage());
        }
    }

    /** Lee el logo actual (bytes) o devuelve null si no hay. */
    public byte[] read() {
        String companyId = tenantContext.getCurrentCompanyId();
        String path = jdbcTemplate.query(
                "SELECT logo_path FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                companyId);
        if (path == null || path.isBlank()) return null;
        try {
            Path p = Paths.get(path);
            if (!Files.exists(p)) return null;
            return Files.readAllBytes(p);
        } catch (IOException ex) {
            return null;
        }
    }

    /** Borra el logo (archivo + columna). */
    @Transactional
    public void delete() {
        String companyId = tenantContext.getCurrentCompanyId();
        String path = jdbcTemplate.query(
                "SELECT logo_path FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                companyId);
        if (path != null && !path.isBlank()) {
            try { Files.deleteIfExists(Paths.get(path)); } catch (IOException ignore) {}
        }
        jdbcTemplate.update("UPDATE companies SET logo_path = NULL WHERE id = ?", companyId);
    }

    private static byte[] encodePng(BufferedImage img) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        return out.toByteArray();
    }

    private static byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
        // JPEG no soporta alpha — convertimos a RGB.
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(),
                BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.drawImage(img, 0, 0, java.awt.Color.WHITE, null);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (MemoryCacheImageOutputStream mcios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(mcios);
            writer.write(null, new IIOImage(rgb, null, null), param);
        }
        writer.dispose();
        return out.toByteArray();
    }

    private static boolean isPng(byte[] bytes) {
        return bytes.length >= 8
                && (bytes[0] & 0xff) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47;
    }

    public record LogoInfo(String path, int width, int height, long bytes) {}
}
