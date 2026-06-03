package com.benjagest.backend.billing.sif;

import com.benjagest.backend.billing.sign.XmlSignerService;
import com.benjagest.backend.billing.verifactu.VerifactuConfig;
import com.benjagest.backend.billing.verifactu.VerifactuConfigRepository;
import com.benjagest.backend.settings.CompanyDataRepository;
import com.benjagest.backend.settings.CompanyDataResponse;
import com.benjagest.backend.tenant.TenantContext;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Punto de entrada para anyadir un evento al Registro de Eventos del
 * SIF (RD 1007/2023 + Orden HAC/1177/2024). Cadena hash independiente
 * de la de facturas.
 *
 * Regla de activacion: solo se registran eventos en empresas con
 * modalidad NO_VERIFACTU. En VeriFactu AEAT ya tiene los datos en
 * tiempo real, asi que la Orden NO exige este registro local.
 *
 * Dos modos de invocacion:
 *   - record(eventType, payload): usa TenantContext (empresa actual,
 *     consulta su modalidad).
 *   - recordForCompany(companyId, eventType, payload): se usa en los
 *     hooks de sistema (arranque/parada Spring, jobs Scheduled) donde
 *     no hay TenantContext y hay que recorrer todas las empresas.
 *
 * Hash y persistencia se hacen como en VerifactuRegistryService: hora
 * truncada a segundos antes del hash, persistida explicita en
 * generated_at, para que la cadena sea reproducible al verificar.
 */
@Service
public class SifEventService {

    private final VerifactuConfigRepository configRepository;
    private final CompanyDataRepository companyRepository;
    private final SifEventRepository eventRepository;
    private final SifEventHashService hashService;
    private final TenantContext tenantContext;
    private final XmlSignerService signerService;

    public SifEventService(VerifactuConfigRepository configRepository,
                           CompanyDataRepository companyRepository,
                           SifEventRepository eventRepository,
                           SifEventHashService hashService,
                           TenantContext tenantContext,
                           XmlSignerService signerService) {
        this.configRepository = configRepository;
        this.companyRepository = companyRepository;
        this.eventRepository = eventRepository;
        this.hashService = hashService;
        this.tenantContext = tenantContext;
        this.signerService = signerService;
    }

    /**
     * Variante con TenantContext. Si la empresa activa esta en
     * modalidad VeriFactu, no se hace nada (no rompe la cadena de
     * facturas: simplemente la cadena de eventos no se inicia).
     */
    @Transactional
    public void record(String eventType, String payload) {
        VerifactuConfig config = configRepository.findCurrent().orElse(null);
        if (config == null || !"NO_VERIFACTU".equals(config.modality())) {
            return;
        }
        CompanyDataResponse company = companyRepository.findCurrent()
                .orElseThrow(() -> new IllegalStateException("Empresa actual no encontrada"));
        appendEvent(company.id(), company.taxIdentifier(), eventType, payload);
    }

    /**
     * Variante para hooks de sistema sin TenantContext (arranque,
     * parada, jobs cron). Requiere companyId explicito. La modalidad
     * se vuelve a comprobar aqui (defensa: companies puede haber
     * cambiado de modalidad entre que el hook se llama y se ejecuta).
     */
    @Transactional
    public void recordForCompany(String companyId, String eventType, String payload) {
        if (!isCompanyInNoVerifactu(companyId)) {
            return;
        }
        String nif = eventRepository.findTaxIdentifier(companyId);
        if (nif == null || nif.isBlank()) {
            return;
        }
        appendEvent(companyId, nif, eventType, payload);
    }

    public List<String> listCompaniesInNoVerifactu() {
        return eventRepository.findCompaniesInNoVerifactu();
    }

    private boolean isCompanyInNoVerifactu(String companyId) {
        return eventRepository.findCompaniesInNoVerifactu().contains(companyId);
    }

    private void appendEvent(String companyId, String nif, String eventType, String payload) {
        if (!StringUtils.hasText(nif)) {
            // Defensa: sin NIF el hash no es verificable contra un
            // emisor concreto. Lanzamos para no enmascarar un bug.
            throw new IllegalStateException(
                    "La empresa " + companyId + " no tiene NIF/CIF. El SIF lo requiere.");
        }
        String previousHash = eventRepository.findLastHashForCompany(companyId);
        OffsetDateTime generationTime = OffsetDateTime.now(ZoneId.of("Europe/Madrid"))
                .truncatedTo(ChronoUnit.SECONDS);

        String hashCurrent = hashService.computeHash(
                nif, eventType, payload, previousHash, generationTime
        );

        String eventId = UUID.randomUUID().toString();
        eventRepository.insert(
                eventId,
                companyId,
                eventType,
                payload,
                hashCurrent,
                previousHash,
                generationTime
        );

        // VF-SIGN: firmar el evento si hay certificado configurado.
        // Si falla o no hay cert, el evento queda con status PENDING
        // — el job VF4 podra reintentar firma mas adelante.
        String eventXml = buildEventXml(nif, eventType, payload,
                hashCurrent, previousHash, generationTime);
        signerService.sign(eventXml).ifPresent(signed ->
                eventRepository.setSignature(eventId, signed));
    }

    /**
     * XML minimo del evento para firma local (igual que el de facturas:
     * formato estable de los campos canonicos, no formato AEAT — AEAT
     * solo recibe registros de facturas, no eventos).
     */
    private String buildEventXml(String nif, String eventType, String payload,
                                  String huellaActual, String huellaAnterior,
                                  java.time.OffsetDateTime generacion) {
        StringBuilder sb = new StringBuilder();
        sb.append("<RegistroEvento xmlns=\"urn:benjagest:sif-event\">");
        sb.append("<IDEmisor>").append(xml(nif)).append("</IDEmisor>");
        sb.append("<TipoEvento>").append(xml(eventType)).append("</TipoEvento>");
        sb.append("<Payload>").append(xml(payload == null ? "" : payload)).append("</Payload>");
        sb.append("<HuellaActual>").append(xml(huellaActual)).append("</HuellaActual>");
        sb.append("<HuellaAnterior>").append(xml(huellaAnterior == null ? "" : huellaAnterior)).append("</HuellaAnterior>");
        sb.append("<FechaHoraGenRegistro>").append(generacion).append("</FechaHoraGenRegistro>");
        sb.append("</RegistroEvento>");
        return sb.toString();
    }

    private String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
