package com.benjagest.backend.billing.migration;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.billing.series.SeriesRepository;
import com.benjagest.backend.purchases.pdfimport.InvoiceFieldsExtractor;
import com.benjagest.backend.purchases.pdfimport.LayoutDocument;
import com.benjagest.backend.purchases.pdfimport.PdfTextExtractor;
import com.benjagest.backend.tenant.TenantContext;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * MIG-1 — Punto de partida de facturación al migrar desde otro programa.
 *
 * <p>Sube la ÚLTIMA factura emitida en el sistema anterior; con OCR (reusa
 * {@link PdfTextExtractor} + {@link InvoiceFieldsExtractor}) se autorellenan
 * serie/número/fecha/cliente para que el usuario los CONFIRME. Al confirmar:
 * <ol>
 *   <li>Se fija {@code next_number} de la serie = último + 1 (continúa la
 *       correlatividad sin huecos).</li>
 *   <li>Se guarda el PDF como prueba + un registro con la declaración firmada
 *       de responsabilidad.</li>
 * </ol>
 *
 * <p>IMPORTANTE (legal): la factura importada NO se contabiliza (pertenece al
 * sistema anterior) y NO genera cadena VeriFactu/SIF. La cadena SIF de
 * BENJAGEST empieza limpia en la primera factura emitida AQUÍ.
 */
@Service
public class MigrationBaselineService {

    private final PdfTextExtractor textExtractor;
    private final InvoiceFieldsExtractor fieldsExtractor;
    private final SeriesRepository seriesRepository;
    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUser;
    private final Path storageRoot;

    public MigrationBaselineService(PdfTextExtractor textExtractor,
                                    InvoiceFieldsExtractor fieldsExtractor,
                                    SeriesRepository seriesRepository,
                                    JdbcTemplate jdbc,
                                    TenantContext tenant,
                                    CurrentUserService currentUser,
                                    @Value("${benjagest.invoices.storage-root:}") String storageRootCfg) {
        this.textExtractor = textExtractor;
        this.fieldsExtractor = fieldsExtractor;
        this.seriesRepository = seriesRepository;
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.storageRoot = Paths.get(storageRootCfg == null || storageRootCfg.isBlank()
                ? com.benjagest.backend.config.BenjagestHome.resolve("facturas").toString()
                : storageRootCfg);
    }

    /** Campos autorellenados del PDF para que el usuario los confirme. */
    public record Extracted(String emitterNif, String customerNif, String customerName,
                            String invoiceNumber, String invoiceDateIso,
                            BigDecimal totalAmount, String confidence, String seriesCodeGuess) {}

    /** Extrae (OCR) sin persistir nada. */
    public Extracted extract(byte[] pdf) {
        if (pdf == null || pdf.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF vacío.");
        }
        try {
            LayoutDocument layout = textExtractor.extractLayout(pdf);
            InvoiceFieldsExtractor.ExtractionResult r = fieldsExtractor.extractFromLayout(layout, pdf);
            // OCR-TESSERACT: si por layout no salió nada (PDF escaneado), reintentamos
            // con texto plano, que cae a OCR si el PDF no tenía texto seleccionable.
            if (r.invoiceNumber() == null && r.invoiceDate() == null
                    && r.emitterNif() == null && r.receiverNif() == null) {
                String plain = textExtractor.extract(pdf);
                if (plain != null && !plain.isBlank()) {
                    InvoiceFieldsExtractor.ExtractionResult r2 = fieldsExtractor.extract(plain);
                    if (r2 != null) r = r2;
                }
            }
            return new Extracted(r.emitterNif(), r.receiverNif(), r.receiverName(),
                    r.invoiceNumber(), r.invoiceDateIso(), r.totalAmount(),
                    r.confidence() == null ? null : r.confidence().name(),
                    guessSeriesCode(r.invoiceNumber()));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se pudo leer el PDF: " + ex.getMessage());
        }
    }

    /**
     * Sugerencia SIMPLE de código de serie = letras iniciales del número
     * (p. ej. "FRA-2026-0007" → "FRA"). NO intenta adivinar todos los
     * formatos posibles a propósito: la serie la confirma/edita el usuario,
     * que es quien conoce su numeración. Si el número no empieza por letras,
     * devuelve null y el usuario la escribe.
     */
    private static String guessSeriesCode(String invoiceNumber) {
        if (invoiceNumber == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\s*([A-Za-z]{1,10})").matcher(invoiceNumber);
        return m.find() ? m.group(1).toUpperCase() : null;
    }

    public record ConfirmRequest(String seriesId, String declaredSeriesCode, String declaredFullNumber,
                                 Integer declaredNumber, String declaredDate, Integer declaredYear,
                                 String emitterNif, String customerNif, String customerName,
                                 BigDecimal totalAmount, String ocrConfidence, boolean declarationSigned,
                                 String declarationText, byte[] pdfBytes) {}

    @Transactional
    public String confirm(ConfirmRequest req) {
        if (!req.declarationSigned()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Debes firmar la declaración de responsabilidad para continuar.");
        }
        String companyId = tenant.getCurrentCompanyId();
        String userId = currentUser.require().userId();

        // 1) Continuar la numeración de la serie (si se indicó). El AÑO confirmado
        //    por el usuario manda (numeración por año); si no, el de la fecha.
        LocalDate declaredDate = parseDate(req.declaredDate());
        if (req.seriesId() != null && !req.seriesId().isBlank() && req.declaredNumber() != null) {
            Integer year = req.declaredYear() != null ? req.declaredYear()
                    : (declaredDate == null ? null : declaredDate.getYear());
            seriesRepository.setNextNumber(req.seriesId(), req.declaredNumber() + 1, year);
        }

        // 2) Guardar el PDF como prueba.
        String id = UUID.randomUUID().toString();
        String pdfPath = null;
        String sha = null;
        if (req.pdfBytes() != null && req.pdfBytes().length > 0) {
            try {
                Path dir = storageRoot.resolve(companyId).resolve("_migration");
                Files.createDirectories(dir);
                Path target = dir.resolve(id + ".pdf");
                Files.write(target, req.pdfBytes());
                pdfPath = target.toString();
                sha = sha256(req.pdfBytes());
            } catch (IOException ex) {
                throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "No se pudo guardar el PDF de prueba: " + ex.getMessage());
            }
        }

        // 3) Registrar la baseline + declaración firmada.
        jdbc.update("""
                INSERT INTO invoice_migration_baseline
                    (id, company_id, series_id, declared_series_code, declared_full_number,
                     declared_number, declared_date, emitter_nif, customer_nif, customer_name,
                     total_amount, ocr_confidence, evidence_pdf_path, evidence_sha256,
                     declaration_signed, declaration_text, declared_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, ?, ?)
                """,
                id, companyId, blankToNull(req.seriesId()), req.declaredSeriesCode(),
                req.declaredFullNumber(), req.declaredNumber(),
                declaredDate == null ? null : java.sql.Date.valueOf(declaredDate),
                req.emitterNif(), req.customerNif(), req.customerName(), req.totalAmount(),
                req.ocrConfidence(), pdfPath, sha, req.declarationText(), userId);
        return id;
    }

    public record BaselineRow(String id, String declaredSeriesCode, String declaredFullNumber,
                              Integer declaredNumber, String declaredDate, String customerName,
                              BigDecimal totalAmount, boolean hasEvidence, String createdAt) {}

    public List<BaselineRow> list() {
        return jdbc.query("""
                SELECT id, declared_series_code, declared_full_number, declared_number,
                       declared_date, customer_name, total_amount, evidence_pdf_path, created_at
                  FROM invoice_migration_baseline
                 WHERE company_id = ?
                 ORDER BY created_at DESC
                """, (rs, n) -> new BaselineRow(
                        rs.getString("id"), rs.getString("declared_series_code"),
                        rs.getString("declared_full_number"),
                        (Integer) rs.getObject("declared_number"),
                        rs.getDate("declared_date") == null ? null : rs.getDate("declared_date").toString(),
                        rs.getString("customer_name"), rs.getBigDecimal("total_amount"),
                        rs.getString("evidence_pdf_path") != null,
                        rs.getTimestamp("created_at") == null ? null : rs.getTimestamp("created_at").toInstant().toString()),
                tenant.getCurrentCompanyId());
    }

    /** MIG-3 — Bytes del PDF de prueba guardado para una baseline de la empresa. */
    public byte[] evidence(String id) {
        String companyId = tenant.getCurrentCompanyId();
        String path = jdbc.query(
                "SELECT evidence_pdf_path FROM invoice_migration_baseline WHERE id = ? AND company_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id, companyId);
        if (path == null || path.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Esa migración no tiene PDF de prueba.");
        }
        try {
            return Files.readAllBytes(Paths.get(path));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo leer el PDF de prueba: " + ex.getMessage());
        }
    }

    private static LocalDate parseDate(String iso) {
        if (iso == null || iso.isBlank()) return null;
        try { return LocalDate.parse(iso.substring(0, 10)); }
        catch (Exception ex) { return null; }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s; }

    private static String sha256(byte[] data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(data));
        } catch (Exception ex) {
            return null;
        }
    }
}
