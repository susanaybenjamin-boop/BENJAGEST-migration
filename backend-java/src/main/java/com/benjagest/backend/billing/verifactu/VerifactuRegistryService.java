package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.settings.CompanyDataRepository;
import com.benjagest.backend.settings.CompanyDataResponse;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
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
        // Tras VF-OFF-DEPRECATE (RD 1007/2023): la cadena hash se genera
        // SIEMPRE, en ambas modalidades. Solo se omite si:
        //   - todavía no hay fila en companies (situación de bootstrap),
        //   - o si quedan empresas con mode/modality null por migración
        //     defectuosa (defensa en profundidad).
        if (config == null || config.mode() == null || config.modality() == null) {
            return Optional.empty();
        }

        // mode aquí es el ENVIRONMENT (TEST/PROD), no la modalidad. La
        // cadena se mantiene segmentada por environment para que la
        // cadena de TEST no contamine la de PROD si una empresa cambia.
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
        // CRÍTICO para verificabilidad: el hash incluye la marca temporal
        // como string con offset; la columna generated_at es TIMESTAMP
        // (precisión segundo). Si el hash se calcula con OffsetDateTime.now()
        // (precisión nano) y la columna guarda otra cosa (DEFAULT
        // CURRENT_TIMESTAMP del INSERT), al verificar la cadena
        // reconstruiríamos el hash con un timestamp distinto y daría falso
        // positivo de "cadena rota". Truncamos a segundo y pasamos el
        // mismo valor tanto al hash como al INSERT explícito.
        OffsetDateTime generationTime = OffsetDateTime.now(ZoneId.of("Europe/Madrid"))
                .truncatedTo(ChronoUnit.SECONDS);

        String hashCurrent = hashService.computeHash(
                company.taxIdentifier(),
                invoice.invoiceNumber(),
                invoice.invoiceDate(),
                invoice.vatTotal(),
                invoice.total(),
                previousHash,
                generationTime
        );

        registryRepository.insertWithGenerationTime(
                UUID.randomUUID().toString(),
                invoice.id(),
                mode,
                hashCurrent,
                previousHash,
                generationTime
        );
        return registryRepository.findByInvoiceAndMode(invoice.id(), mode);
    }

    /**
     * Verifica la integridad de la cadena hash para la empresa actual
     * en el modo indicado. Recorre cada registro en orden cronológico
     * y recalcula su hash con el mismo input que se usó al validar.
     * Si algún hash no coincide, devuelve el id del primero que falla
     * — todo lo que venga después es sospechoso por construcción.
     *
     * Coste: O(N) facturas validadas. Para empresas con miles, pasar a
     * un job nocturno; para BENJAGEST típico (cientos / año) es trivial.
     */
    public IntegrityReport verifyChain(String mode) {
        CompanyDataResponse company = companyRepository.findCurrent()
                .orElseThrow(() -> new IllegalStateException("Empresa actual no encontrada"));
        if (!StringUtils.hasText(company.taxIdentifier())) {
            return new IntegrityReport(false, 0, null, null,
                    "La empresa no tiene NIF/CIF (companies.tax_identifier).");
        }
        List<VerifactuRegistryRepository.ChainRow> chain =
                registryRepository.findChainOrderedAsc(mode);
        String expectedPrev = "";
        int checked = 0;
        for (VerifactuRegistryRepository.ChainRow row : chain) {
            checked++;
            OffsetDateTime gen = row.generatedAt().atZoneSameInstant(ZoneId.of("Europe/Madrid"))
                    .toOffsetDateTime();
            String recomputed = hashService.computeHash(
                    company.taxIdentifier(),
                    row.invoiceNumber(),
                    row.invoiceDate(),
                    row.vatTotal(),
                    row.total(),
                    expectedPrev,
                    gen
            );
            if (!recomputed.equalsIgnoreCase(row.hashCurrent())) {
                return new IntegrityReport(false, checked, row.invoiceId(), row.invoiceNumber(),
                        "Hash recalculado no coincide. Posible manipulación o desincronización de generation_time.");
            }
            if (!row.hashPrevious().equalsIgnoreCase(expectedPrev)) {
                return new IntegrityReport(false, checked, row.invoiceId(), row.invoiceNumber(),
                        "hash_previous almacenado no coincide con la cadena.");
            }
            expectedPrev = row.hashCurrent();
        }
        return new IntegrityReport(true, checked, null, null, null);
    }

    /**
     * Hash actual de una factura validada. Devuelve Optional.empty si
     * la factura no tiene aún registro (no debería pasar tras VF-OFF-
     * DEPRECATE: el hash se genera siempre al validar). Lo usa el
     * generador de PDF para imprimir los primeros 16 chars bajo el QR.
     */
    public Optional<String> findCurrentHashForPdf(String invoiceId) {
        VerifactuConfig config = configRepository.findCurrent().orElse(null);
        if (config == null || config.mode() == null) {
            return Optional.empty();
        }
        return registryRepository.findByInvoiceAndMode(invoiceId, config.mode())
                .map(VerifactuRegistryEntry::hashCurrent);
    }

    public record IntegrityReport(
            boolean ok,
            int totalChecked,
            String brokenInvoiceId,
            String brokenInvoiceNumber,
            String reason
    ) {
    }

    public List<VerifactuRegistryEntry> list(String modeFilter, String statusFilter, int limit) {
        return registryRepository.findForCompany(modeFilter, statusFilter, limit);
    }
}
