package com.benjagest.backend.advisory.documents;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Upload / download multipart para documentos asesoría↔cliente.
 *
 * <p>Slice 2026-06-10 noche — completa el "Upload multipart real
 * pendiente" anotado en V78. Decisiones Benjamin P3b:
 * <ul>
 *   <li>Max 20 MB por archivo.</li>
 *   <li>Formatos PDF / PNG / JPG / DOCX / XLSX.</li>
 *   <li>Storage {@code {storageRoot}/{companyId}/_shared/{YYYY}/{MM}/{uuid}-{filename}}.</li>
 * </ul>
 *
 * <p>El upload llama a {@link AdvisoryDocumentService#register} para
 * persistir la fila de {@code advisory_documents} con la ruta
 * absoluta y los metadatos extraídos del MultipartFile.
 */
@RestController
@RequestMapping("/api/advisory/documents")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class AdvisoryDocumentUploadController {

    private static final long MAX_BYTES = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXT =
            Set.of("pdf", "png", "jpg", "jpeg", "docx", "xlsx");

    private final AdvisoryDocumentService service;
    private final TenantContext tenantContext;
    private final String fallbackRoot;

    public AdvisoryDocumentUploadController(AdvisoryDocumentService service,
                                              TenantContext tenantContext,
                                              @Value("${benjagest.invoices.storage-root:}") String defaultRoot) {
        this.service = service;
        this.tenantContext = tenantContext;
        this.fallbackRoot = StringUtils.hasText(defaultRoot)
                ? defaultRoot
                : com.benjagest.backend.config.BenjagestHome.resolve("facturas").toString();
    }

    @PostMapping(value = "/threads/{otherCompanyId}/upload",
                  consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public AdvisoryDocument upload(@PathVariable("otherCompanyId") String otherCompanyId,
                                    @RequestPart("file") MultipartFile file,
                                    @RequestPart(value = "title", required = false) String title) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Archivo vacío");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "El archivo supera 20 MB.");
        }
        String original = file.getOriginalFilename();
        if (original == null || original.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Sin nombre de archivo");
        }
        String ext = extOf(original).toLowerCase(Locale.ROOT);
        if (!ALLOWED_EXT.contains(ext)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "Formato no permitido. Acepta: " + String.join(", ", ALLOWED_EXT));
        }
        String companyId = tenantContext.getCurrentCompanyId();
        LocalDate today = LocalDate.now();
        Path dir = Paths.get(fallbackRoot, companyId, "_shared",
                String.valueOf(today.getYear()),
                String.format("%02d", today.getMonthValue()));
        try {
            Files.createDirectories(dir);
            String safeName = sanitize(original);
            String storedName = UUID.randomUUID() + "-" + safeName;
            Path target = dir.resolve(storedName);
            file.transferTo(target.toFile());
            AdvisoryDocumentService.RegisterRequest req =
                    new AdvisoryDocumentService.RegisterRequest(
                            title == null || title.isBlank() ? original : title,
                            target.toAbsolutePath().toString(),
                            file.getSize(),
                            file.getContentType(),
                            null);
            return service.register(otherCompanyId, req);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo guardar: " + ex.getMessage());
        }
    }

    @GetMapping("/{id}/download")
    public ResponseEntity<byte[]> download(@PathVariable("id") String id) {
        AdvisoryDocument doc = service.findById(id);
        if (doc == null || doc.filePath() == null || doc.filePath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Documento sin archivo");
        }
        try {
            byte[] bytes = Files.readAllBytes(Paths.get(doc.filePath()));
            MediaType type = guessMediaType(doc.filePath(), doc.mimeType());
            String filename = Paths.get(doc.filePath()).getFileName().toString();
            // Quitar el UUID inicial al ofrecer descarga (UX humana).
            int dash = filename.indexOf('-');
            if (dash > 0 && dash < 40) filename = filename.substring(dash + 1);
            return ResponseEntity.ok()
                    .contentType(type)
                    .header("Content-Disposition",
                            "attachment; filename=\"" + filename + "\"")
                    .body(bytes);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo leer: " + ex.getMessage());
        }
    }

    private static String extOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 || dot == name.length() - 1 ? "" : name.substring(dot + 1);
    }

    private static String sanitize(String name) {
        // Evitar separadores y control chars; mantener resto razonable.
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
    }

    private static MediaType guessMediaType(String path, String mimeType) {
        if (mimeType != null && !mimeType.isBlank()) {
            try { return MediaType.parseMediaType(mimeType); } catch (Exception ignore) {}
        }
        String low = path.toLowerCase(Locale.ROOT);
        if (low.endsWith(".pdf")) return MediaType.APPLICATION_PDF;
        if (low.endsWith(".png")) return MediaType.IMAGE_PNG;
        if (low.endsWith(".jpg") || low.endsWith(".jpeg")) return MediaType.IMAGE_JPEG;
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
