package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.settings.CompanyDataRepository;
import com.benjagest.backend.settings.CompanyDataResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Punto de entrada para registrar una factura validada bajo VeriFactu.
 *
 * Responsabilidades:
 *   - Saber si la empresa tiene VeriFactu activo (mode != OFF).
 *   - Recuperar el NIF emisor (companies.tax_identifier) y la huella
 *     anterior de la cadena.
 *   - Llamar a VerifactuHashService para calcular el hash encadenado.
 *   - Persistir el nuevo registro con status PENDING (para que VF3
 *     pueda recogerlo y mandarlo a AEAT cuando llegue ese slice).
 *
 * Idempotencia: si ya hay registro para (invoice_id, mode), no se crea
 * otro. Esto protege contra dobles validaciones (la regla en
 * SalesInvoiceService.validate evita que se pueda validar dos veces,
 * pero la defensa duplicada vale la pena en regulacion).
 *
 * El metodo registerIfActive devuelve Optional vacio si el modo es OFF,
 * o el registro creado/existente en caso contrario.
 */
@Service
public class VerifactuRegistryService {

    private final VerifactuConfigRepository configRepository;
    private final CompanyDataRepository companyRepository;
    private final VerifactuRegistryRepository registryRepository;
    private final VerifactuHashService hashService;

    public VerifactuRegistryService(VerifactuConfigRepository configRepository,
                                    CompanyDataRepository companyRepository,
                                    VerifactuRegistryRepository registryRepository,
                                    VerifactuHashService hashService) {
        this.configRepository = configRepository;
        this.companyRepository = companyRepository;
        this.registryRepository = registryRepository;
        this.hashService = hashService;
    }

    @Transactional
    public Optional<VerifactuRegistryEntry> registerIfActive(SalesInvoice invoice) {
        VerifactuConfig config = configRepository.findCurrent().orElse(null);
        if (config == null || config.mode() == null || "OFF".equals(config.mode())) {
            return Optional.empty();
        }

        String mode = config.mode();
        Optional<VerifactuRegistryEntry> existing = registryRepository.findByInvoiceAndMode(invoice.id(), mode);
        if (existing.isPresent()) {
            return existing;
        }

        CompanyDataResponse company = companyRepository.findCurrent()
                .orElseThrow(() -> new IllegalStateException("Empresa actual no encontrada"));
        if (!StringUtils.hasText(company.taxIdentifier())) {
            throw new IllegalStateException(
                    "La empresa no tiene NIF/CIF (companies.tax_identifier). VeriFactu lo exige.");
        }

        String previousHash = registryRepository.findLastHash(mode);
        OffsetDateTime generationTime = OffsetDateTime.now(ZoneId.of("Europe/Madrid"));

        String hashCurrent = hashService.computeHash(
                company.taxIdentifier(),
                invoice.invoiceNumber(),
                invoice.invoiceDate(),
                invoice.vatTotal(),
                invoice.total(),
                previousHash,
                generationTime
        );

        VerifactuRegistryEntry entry = new VerifactuRegistryEntry(
                UUID.randomUUID().toString(),
                null,
                invoice.id(),
                invoice.invoiceNumber(),
                mode,
                hashCurrent,
                previousHash,
                null,
                null,
                null,
                "PENDING",
                0,
                null,
                null,
                null
        );
        registryRepository.insert(entry);
        return registryRepository.findByInvoiceAndMode(invoice.id(), mode);
    }

    public List<VerifactuRegistryEntry> list(String modeFilter, String statusFilter, int limit) {
        return registryRepository.findForCompany(modeFilter, statusFilter, limit);
    }
}
