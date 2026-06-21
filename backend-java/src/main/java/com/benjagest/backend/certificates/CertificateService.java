package com.benjagest.backend.certificates;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de subida y consulta de certificados digitales.
 *
 * Como el campo certificate_data puede ser muy grande (varios MB en
 * base64), la lectura completa solo ocurre cuando el caller la pide
 * explicitamente (por ejemplo el modulo de firma VeriFactu); el
 * listado para la pantalla devuelve solo CertificateSummary, que
 * pesa kilobytes.
 */
@Service
public class CertificateService {

    private final CertificateRepository repository;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;
    private final AuditService auditService;
    private final KeystoreInspector keystoreInspector;
    private final JdbcTemplate jdbcTemplate;

    public CertificateService(CertificateRepository repository,
                                CurrentUserService currentUserService,
                                TenantContext tenantContext,
                                AuditService auditService,
                                KeystoreInspector keystoreInspector,
                                JdbcTemplate jdbcTemplate) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
        this.auditService = auditService;
        this.keystoreInspector = keystoreInspector;
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<CertificateSummary> list() {
        return repository.findAllActive().stream()
                .map(this::toSummary)
                .toList();
    }

    /**
     * Certificados activos del tenant actual CON los datos sensibles ya
     * descifrados (.p12 + contrasena). Uso interno (firma, importar al
     * almacen para el navegador). NUNCA se serializa al cliente.
     */
    public List<Certificate> listActiveDecrypted() {
        return repository.findAllActive();
    }

    public CertificateSummary getSummary(String id) {
        return repository.findById(id)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificado no encontrado"));
    }

    @Transactional
    public CertificateSummary upload(CertificateUploadRequest request) {
        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        String uploaderCompany = user.activeCompanyId();

        // Si quien sube NO es de la propia empresa del cert, debe ser
        // una asesoria vinculada (V26: companies.parent_company_id).
        // Esto evita que un usuario de otra empresa cualquiera abuse
        // del header X-Company-Id para subir certificados ajenos.
        boolean byAdvisory = !Objects.equals(tenant, uploaderCompany);
        if (byAdvisory) {
            verifyAdvisoryLink(tenant, uploaderCompany);
        }

        String id = UUID.randomUUID().toString();
        repository.insert(new Certificate(
                id,
                null,
                request.alias().trim(),
                request.certificateType().trim(),
                blankToNull(request.subjectName()),
                blankToNull(request.subjectTaxIdentifier()),
                blankToNull(request.password()),
                null,
                blankToNull(request.certificateDataBase64()),
                request.validFrom(),
                request.validTo(),
                true,
                user.userId(),
                uploaderCompany,
                null,
                null
        ));
        auditService.recordCertificateUploaded(
                user.userId(), tenant, id, byAdvisory, uploaderCompany);
        return getSummary(id);
    }

    public CertificateInspectResponse inspect(CertificateInspectRequest request) {
        return keystoreInspector.inspect(request.certificateDataBase64(), request.password());
    }

    @Transactional
    public void delete(String id) {
        AuthenticatedUser user = currentUserService.require();
        String tenant = tenantContext.getCurrentCompanyId();
        int affected = repository.softDelete(id);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificado no encontrado");
        }
        auditService.recordCertificateDeleted(user.userId(), tenant, id);
    }

    /**
     * Verifica que el {@code uploaderCompanyId} sea la asesoría del
     * tenant. La tabla companies tiene parent_company_id apuntando a
     * la asesoría desde el cliente. Si no encaja, 403.
     */
    private void verifyAdvisoryLink(String tenantClientId, String uploaderCompanyId) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM companies
                 WHERE id = ?
                   AND parent_company_id = ?
                """, Integer.class, tenantClientId, uploaderCompanyId);
        if (count == null || count == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El usuario no esta autorizado a subir certificados a esta empresa");
        }
    }

    /**
     * Acceso interno al certificado en claro para los modulos que lo
     * necesiten al firmar (VeriFactu, SII, DEHu). NO se expone al
     * cliente: lo invocan otros services del backend.
     */
    public Certificate loadDecryptedForSigning(String id) {
        return repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificado no encontrado"));
    }

    private CertificateSummary toSummary(Certificate cert) {
        boolean byAdvisory = StringUtils.hasText(cert.uploadedByCompanyId())
                && !Objects.equals(cert.uploadedByCompanyId(), cert.companyId());
        return new CertificateSummary(
                cert.id(),
                cert.alias(),
                cert.certificateType(),
                cert.subjectName(),
                cert.subjectTaxIdentifier(),
                StringUtils.hasText(cert.passwordPlaintext()),
                StringUtils.hasText(cert.certificateDataBase64()),
                cert.validFrom(),
                cert.validTo(),
                cert.active(),
                cert.uploadedByUserId(),
                cert.uploadedByCompanyId(),
                byAdvisory
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
