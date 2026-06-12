package com.benjagest.backend.billing.tpb;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.settings.SessionPinService;
import com.benjagest.backend.tenant.TenantContext;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Service del acuerdo previo de facturación por tercero — RD 1619/2012 art. 5.
 *
 * <p>Reglas:
 * <ul>
 *   <li>Solo puede existir UN acuerdo no revocado por par (asesoría,
 *       cliente). El service rechaza una segunda propuesta hasta que la
 *       primera se revoque.</li>
 *   <li>El alcance debe tener al menos un scope marcado.</li>
 *   <li>Solo el lado adecuado puede firmar según método:
 *       PIN_SESSION lo firma el cliente desde su tenant;
 *       OFFLINE_PDF lo firma la asesoría tras subir el PDF escaneado.</li>
 *   <li>Cualquiera de las dos partes puede revocar un acuerdo activo.</li>
 * </ul>
 */
@Service
public class ThirdPartyBillingAgreementService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ThirdPartyBillingAgreementService.class);

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUserService;
    private final SessionPinService pinService;
    private final ThirdPartyBillingAgreementPdfGenerator pdfGenerator;
    private final com.benjagest.backend.billing.series.SeriesService seriesService;
    private final String storageRoot;

    public ThirdPartyBillingAgreementService(JdbcTemplate jdbc,
                                              TenantContext tenant,
                                              CurrentUserService currentUserService,
                                              SessionPinService pinService,
                                              ThirdPartyBillingAgreementPdfGenerator pdfGenerator,
                                              com.benjagest.backend.billing.series.SeriesService seriesService,
                                              @Value("${benjagest.invoices.storage-root:}") String defaultRoot) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUserService = currentUserService;
        this.pinService = pinService;
        this.pdfGenerator = pdfGenerator;
        this.seriesService = seriesService;
        this.storageRoot = defaultRoot == null || defaultRoot.isBlank()
                ? Paths.get(System.getProperty("user.home"), "benjagest-facturas").toString()
                : defaultRoot;
    }

    public List<ThirdPartyBillingAgreement> listForCurrentTenant() {
        String me = tenant.getCurrentCompanyId();
        return jdbc.query("""
                SELECT * FROM third_party_billing_agreements
                 WHERE advisory_company_id = ? OR client_company_id = ?
                 ORDER BY created_at DESC
                """, MAPPER, me, me);
    }

    public Optional<ThirdPartyBillingAgreement> findCurrent(String otherCompanyId) {
        String me = tenant.getCurrentCompanyId();
        Optional<ThirdPartyBillingAgreement> result = jdbc.query("""
                SELECT * FROM third_party_billing_agreements
                 WHERE ((advisory_company_id = ? AND client_company_id = ?)
                     OR (advisory_company_id = ? AND client_company_id = ?))
                   AND status IN ('PROPOSED', 'ACTIVE')
                 ORDER BY created_at DESC
                 LIMIT 1
                """, MAPPER, me, otherCompanyId, otherCompanyId, me).stream().findFirst();
        // Auto-reparacion: si el acuerdo esta ACTIVE y cubre ventas pero
        // la serie TPB no se creo (caso Benjamin 2026-06-12), la creamos
        // aqui. Idempotente — ensureTpbSeries retorna la existente si ya
        // hay. Tragamos cualquier excepcion para no romper la lectura.
        result.ifPresent(a -> {
            if (ThirdPartyBillingAgreement.STATUS_ACTIVE.equals(a.status())
                    && a.scopeSales()) {
                try {
                    seriesService.ensureTpbSeries(
                            a.clientCompanyId(), a.advisoryCompanyId());
                } catch (RuntimeException ex) {
                    log.debug("TPB: auto-reparacion serie fallo silenciosamente "
                            + "(advisory={}, client={})",
                            a.advisoryCompanyId(), a.clientCompanyId(), ex);
                }
            }
        });
        return result;
    }

    @Transactional
    public ThirdPartyBillingAgreement propose(ProposeRequest req) {
        if (req.advisoryCompanyId() == null || req.clientCompanyId() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "advisoryCompanyId y clientCompanyId obligatorios");
        }
        if (!req.scopeSales() && !req.scopePurchases() && !req.scopeTaxModels()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Marca al menos un alcance del acuerdo (ventas, compras o modelos)");
        }
        // Bloqueo de duplicados activos
        Integer existing = jdbc.queryForObject("""
                SELECT COUNT(*) FROM third_party_billing_agreements
                 WHERE advisory_company_id = ? AND client_company_id = ?
                   AND status IN ('PROPOSED', 'ACTIVE')
                """, Integer.class, req.advisoryCompanyId(), req.clientCompanyId());
        if (existing != null && existing > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe un acuerdo activo o pendiente para este par. Revócalo primero.");
        }
        // Iniciador por activeCompanyId del usuario (JWT estable) — el
        // tenant del header puede ser el cliente si la asesoría está
        // "actuando como" el cliente; pero el usuario real sigue siendo
        // de la asesoría.
        String myActiveCompanyId = activeCompanyId();
        boolean initiatedByAdvisory = req.advisoryCompanyId().equals(myActiveCompanyId);
        // Verificar que el usuario es parte del acuerdo
        if (!initiatedByAdvisory && !req.clientCompanyId().equals(myActiveCompanyId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No eres parte de este acuerdo");
        }
        String id = UUID.randomUUID().toString();
        String userId = safeUserId();
        jdbc.update("""
                INSERT INTO third_party_billing_agreements
                       (id, advisory_company_id, client_company_id,
                        scope_sales, scope_purchases, scope_tax_models,
                        status, initiated_by_advisory,
                        created_by_user_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, 'PROPOSED', ?, ?, NOW(6))
                """,
                id, req.advisoryCompanyId(), req.clientCompanyId(),
                req.scopeSales(), req.scopePurchases(), req.scopeTaxModels(),
                initiatedByAdvisory, userId);
        return getById(id);
    }

    @Transactional
    public ThirdPartyBillingAgreement signWithPin(String agreementId, String pin) {
        ThirdPartyBillingAgreement a = getById(agreementId);
        if (!ThirdPartyBillingAgreement.STATUS_PROPOSED.equals(a.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El acuerdo no está en estado PROPOSED");
        }
        // Solo el CLIENTE (no la asesoría) puede firmar con PIN
        if (!a.clientCompanyId().equals(tenant.getCurrentCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el cliente vinculado puede firmar con PIN");
        }
        if (!pinService.verify(pin)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "PIN incorrecto");
        }
        String pdfPath = generatePdf(a, ThirdPartyBillingAgreement.METHOD_PIN_SESSION);
        jdbc.update("""
                UPDATE third_party_billing_agreements
                   SET status = 'ACTIVE', signed_at = NOW(6),
                       signed_method = 'PIN_SESSION', signed_pdf_path = ?
                 WHERE id = ?
                """, pdfPath, agreementId);
        // TPB-2: serie TPB obligatoria si el acuerdo cubre ventas.
        if (a.scopeSales()) {
            try {
                seriesService.ensureTpbSeries(a.clientCompanyId(), a.advisoryCompanyId());
            } catch (RuntimeException ex) {
                // No bloqueamos la firma — el acuerdo es legalmente
                // válido aunque la serie tarde un poco más. Pero
                // logueamos para diagnosticar: sin esto el problema es
                // invisible (caso real Benjamin 2026-06-12: firmó pero
                // la serie nunca apareció). findCurrent() auto-repara
                // en la siguiente consulta.
                log.warn("TPB: no se pudo crear la serie TPB tras signWithPin "
                        + "(advisory={}, client={}). Reintento diferido al "
                        + "proximo findCurrent.",
                        a.advisoryCompanyId(), a.clientCompanyId(), ex);
            }
        }
        return getById(agreementId);
    }

    @Transactional
    public ThirdPartyBillingAgreement signWithOfflinePdf(String agreementId, MultipartFile signedPdf) {
        ThirdPartyBillingAgreement a = getById(agreementId);
        if (!ThirdPartyBillingAgreement.STATUS_PROPOSED.equals(a.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El acuerdo no está en estado PROPOSED");
        }
        // Solo la ASESORÍA puede subir el PDF firmado físicamente por el cliente.
        // Comparamos contra activeCompanyId del JWT — la asesoría puede estar
        // operando "como" cliente y el tenant del header ser el cliente.
        if (!a.advisoryCompanyId().equals(activeCompanyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo la asesoría puede subir el PDF firmado offline");
        }
        if (signedPdf == null || signedPdf.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "PDF vacío");
        }
        if (signedPdf.getSize() > 20L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,
                    "El PDF supera 20 MB");
        }
        try {
            LocalDate today = LocalDate.now();
            Path dir = Paths.get(storageRoot, a.advisoryCompanyId(), "_tpb",
                    String.valueOf(today.getYear()),
                    String.format("%02d", today.getMonthValue()));
            Files.createDirectories(dir);
            String safeName = UUID.randomUUID() + "-signed-" + sanitize(
                    signedPdf.getOriginalFilename() == null
                            ? "agreement.pdf" : signedPdf.getOriginalFilename());
            Path target = dir.resolve(safeName);
            signedPdf.transferTo(target.toFile());
            jdbc.update("""
                    UPDATE third_party_billing_agreements
                       SET status = 'ACTIVE', signed_at = NOW(6),
                           signed_method = 'OFFLINE_PDF', signed_pdf_path = ?
                     WHERE id = ?
                    """, target.toAbsolutePath().toString(), agreementId);
            if (a.scopeSales()) {
                try {
                    seriesService.ensureTpbSeries(a.clientCompanyId(), a.advisoryCompanyId());
                } catch (RuntimeException ex) {
                    log.warn("TPB: no se pudo crear la serie TPB tras "
                            + "signWithOfflinePdf (advisory={}, client={}). "
                            + "Reintento diferido al proximo findCurrent.",
                            a.advisoryCompanyId(), a.clientCompanyId(), ex);
                }
            }
            return getById(agreementId);
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo guardar el PDF: " + ex.getMessage());
        }
    }

    /**
     * Diagnostico / reparacion manual: garantiza que existe la serie
     * TPB del acuerdo dado. A diferencia del catch silencioso de
     * signWithPin/signWithOfflinePdf, este metodo propaga cualquier
     * excepcion al caller. La UI lo usa para diagnosticar acuerdos
     * firmados antes del fix de auto-repair.
     */
    public EnsureSeriesResult ensureSeriesForAgreement(String agreementId) {
        ThirdPartyBillingAgreement a = getById(agreementId);
        if (!a.scopeSales()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El acuerdo no cubre ventas; no requiere serie TPB.");
        }
        if (!ThirdPartyBillingAgreement.STATUS_ACTIVE.equals(a.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El acuerdo no esta ACTIVE (estado actual: " + a.status() + ").");
        }
        boolean existedBefore = seriesService
                .findTpbSeriesPublic(a.clientCompanyId(), a.advisoryCompanyId())
                .isPresent();
        com.benjagest.backend.billing.series.Series series =
                seriesService.ensureTpbSeries(a.clientCompanyId(), a.advisoryCompanyId());
        return new EnsureSeriesResult(series.id(), series.code(),
                series.nextNumber(), !existedBefore);
    }

    public record EnsureSeriesResult(String seriesId, String code,
                                      int nextNumber, boolean created) {}

    /**
     * Genera el PDF "propuesta" descargable para el caso OFFLINE
     * (cliente sin acceso a BENJAGEST imprime, firma y la asesoría
     * sube el escaneado). Se genera bajo demanda — no se persiste
     * hasta que el flujo termine con OFFLINE_PDF.
     */
    public byte[] generateProposalPdf(String agreementId) {
        ThirdPartyBillingAgreement a = getById(agreementId);
        return pdfGenerator.generate(a, null, null);
    }

    public byte[] downloadSignedPdf(String agreementId) {
        ThirdPartyBillingAgreement a = getById(agreementId);
        if (a.signedPdfPath() == null || a.signedPdfPath().isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Sin PDF firmado");
        }
        try {
            return Files.readAllBytes(Paths.get(a.signedPdfPath()));
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo leer el PDF: " + ex.getMessage());
        }
    }

    @Transactional
    public ThirdPartyBillingAgreement revoke(String agreementId, String reason) {
        ThirdPartyBillingAgreement a = getById(agreementId);
        if (ThirdPartyBillingAgreement.STATUS_REVOKED.equals(a.status())) {
            return a;
        }
        String me = activeCompanyId();
        boolean byAdvisory = a.advisoryCompanyId().equals(me);
        boolean byClient   = a.clientCompanyId().equals(me);
        if (!byAdvisory && !byClient) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "No eres parte de este acuerdo");
        }
        jdbc.update("""
                UPDATE third_party_billing_agreements
                   SET status = 'REVOKED', revoked_at = NOW(6),
                       revoked_by_advisory = ?, revoked_reason = ?
                 WHERE id = ?
                """, byAdvisory,
                reason == null || reason.isBlank() ? null : reason.trim(),
                agreementId);
        return getById(agreementId);
    }

    private String generatePdf(ThirdPartyBillingAgreement a, String method) {
        try {
            byte[] pdf = pdfGenerator.generate(a, method, Instant.now());
            LocalDate today = LocalDate.now();
            Path dir = Paths.get(storageRoot, a.advisoryCompanyId(), "_tpb",
                    String.valueOf(today.getYear()),
                    String.format("%02d", today.getMonthValue()));
            Files.createDirectories(dir);
            Path target = dir.resolve(UUID.randomUUID() + "-agreement.pdf");
            Files.write(target, pdf);
            return target.toAbsolutePath().toString();
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo generar el PDF: " + ex.getMessage());
        }
    }

    private ThirdPartyBillingAgreement getById(String id) {
        return jdbc.query("""
                SELECT * FROM third_party_billing_agreements WHERE id = ?
                """, MAPPER, id).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Acuerdo no encontrado"));
    }

    private String safeUserId() {
        try {
            return currentUserService.require().userId();
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * Empresa REAL del usuario logueado, leída del JWT. Estable frente
     * a "actuar como cliente" (donde tenantContext apunta al cliente
     * pero el usuario sigue siendo de la asesoría).
     */
    private String activeCompanyId() {
        return currentUserService.require().activeCompanyId();
    }

    private static String sanitize(String name) {
        return name.replaceAll("[\\\\/:*?\"<>|\\r\\n\\t]", "_");
    }

    public record ProposeRequest(
            String advisoryCompanyId,
            String clientCompanyId,
            boolean scopeSales,
            boolean scopePurchases,
            boolean scopeTaxModels
    ) {}

    private static final RowMapper<ThirdPartyBillingAgreement> MAPPER = (rs, i) -> {
        Timestamp signed = rs.getTimestamp("signed_at");
        Timestamp revoked = rs.getTimestamp("revoked_at");
        Timestamp created = rs.getTimestamp("created_at");
        Boolean revokedBy = rs.getObject("revoked_by_advisory") == null ? null
                : rs.getBoolean("revoked_by_advisory");
        return new ThirdPartyBillingAgreement(
                rs.getString("id"),
                rs.getString("advisory_company_id"),
                rs.getString("client_company_id"),
                rs.getBoolean("scope_sales"),
                rs.getBoolean("scope_purchases"),
                rs.getBoolean("scope_tax_models"),
                rs.getString("status"),
                rs.getBoolean("initiated_by_advisory"),
                signed == null ? null : signed.toInstant(),
                rs.getString("signed_method"),
                rs.getString("signed_pdf_path"),
                revoked == null ? null : revoked.toInstant(),
                revokedBy,
                rs.getString("revoked_reason"),
                rs.getString("created_by_user_id"),
                created == null ? null : created.toInstant()
        );
    };
}
