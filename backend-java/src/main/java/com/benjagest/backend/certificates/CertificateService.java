package com.benjagest.backend.certificates;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
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

    public CertificateService(CertificateRepository repository) {
        this.repository = repository;
    }

    public List<CertificateSummary> list() {
        return repository.findAllActive().stream()
                .map(this::toSummary)
                .toList();
    }

    public CertificateSummary getSummary(String id) {
        return repository.findById(id)
                .map(this::toSummary)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificado no encontrado"));
    }

    @Transactional
    public CertificateSummary upload(CertificateUploadRequest request) {
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
                null,
                null
        ));
        return getSummary(id);
    }

    @Transactional
    public void delete(String id) {
        int affected = repository.softDelete(id);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificado no encontrado");
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
                cert.active()
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    // Helper temporal para evitar unused warning hasta que llegue el slice de firma.
    @SuppressWarnings("unused")
    private Instant unusedSentinel() { return Instant.now(); }
}
