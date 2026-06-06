package com.benjagest.backend.accounting.importpdf;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Almacenamiento en disco de los PDFs que la asesoría importa para crear
 * asientos directos (multi-import). Distinto del de facturas validadas:
 * estos no son facturas legales emitidas por la empresa, son documentos
 * de entrada (gastos recibidos, ventas que el cliente ya emitió en otro
 * sistema) que se conservan junto al asiento.
 *
 * <p>Ruta: {@code {root}/imported-pdfs/{companyId}/{year}/{sha256}.pdf}.
 * Usar sha256 como nombre del archivo da dos cosas gratis:
 * <ul>
 *   <li>Deduplicación automática: subir dos veces el mismo PDF no crea
 *       dos copias.</li>
 *   <li>Verificación de integridad: el cliente puede chequear que el
 *       archivo no ha sido alterado.</li>
 * </ul>
 *
 * <p>Root: por defecto {@code ~/benjagest-pdfs-importados}. Puede
 * sobreescribirse con la propiedad {@code benjagest.imported-pdfs.root}.
 */
@Service
public class ImportedPdfStorageService {

    private final String root;

    public ImportedPdfStorageService(
            @Value("${benjagest.imported-pdfs.root:}") String configuredRoot) {
        this.root = StringUtils.hasText(configuredRoot)
                ? configuredRoot
                : Paths.get(System.getProperty("user.home"),
                        "benjagest-pdfs-importados").toString();
    }

    /** Calcula el SHA-256 hex del contenido. */
    public String sha256Hex(byte[] bytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(bytes);
            StringBuilder sb = new StringBuilder(64);
            for (byte b : hash) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 not available", ex);
        }
    }

    /**
     * Guarda el PDF (idempotente: si el sha ya existe, no reescribe) y
     * devuelve el path absoluto.
     */
    public Stored store(String companyId, int year, byte[] pdfBytes) throws IOException {
        String sha = sha256Hex(pdfBytes);
        Path dir = Paths.get(root, "imported-pdfs", companyId, String.valueOf(year));
        Files.createDirectories(dir);
        Path target = dir.resolve(sha + ".pdf");
        if (!Files.exists(target)) {
            Files.write(target, pdfBytes);
        }
        return new Stored(target.toAbsolutePath().toString(), sha);
    }

    /** Lee los bytes del PDF previamente almacenado. */
    public byte[] read(String absolutePath) throws IOException {
        Path p = Paths.get(absolutePath);
        if (!Files.exists(p)) {
            throw new IOException("PDF no encontrado: " + absolutePath);
        }
        return Files.readAllBytes(p);
    }

    public record Stored(String absolutePath, String sha256) {}
}
