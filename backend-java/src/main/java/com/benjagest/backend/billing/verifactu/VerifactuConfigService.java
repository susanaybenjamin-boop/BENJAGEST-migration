package com.benjagest.backend.billing.verifactu;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VerifactuConfigService {

    private final VerifactuConfigRepository repository;

    public VerifactuConfigService(VerifactuConfigRepository repository) {
        this.repository = repository;
    }

    public VerifactuConfig get() {
        return repository.findCurrent().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Configuracion no encontrada"));
    }

    @Transactional
    public VerifactuConfig update(VerifactuConfigUpdateRequest request) {
        // Regla de negocio: PROD sin certificado es invalido (AEAT
        // rechazaria todos los envios). TEST permite no tener
        // certificado todavia, pero a la hora de firmar fallara.
        if ("PROD".equals(request.mode()) && !StringUtils.hasText(request.certificateId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para activar VeriFactu en PROD necesitas seleccionar un certificado .p12");
        }
        repository.update(request.mode(), request.certificateId(), request.invoiceFooterTemplate());
        return get();
    }
}
