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
        // Reglas de negocio (slice VF-OFF-DEPRECATE, RD 1007/2023):
        //   1. Ambas modalidades son legales — no rechazamos ninguna.
        //   2. Solo se exige certificado cuando modality=VERIFACTU Y
        //      mode=PROD. En NO_VERIFACTU no hay envio que firmar.
        //      En VERIFACTU+TEST se permite sin cert (entorno pruebas
        //      AEAT acepta firmas dummy).
        boolean isVerifactuProd = "VERIFACTU".equals(request.modality())
                && "PROD".equals(request.mode());
        if (isVerifactuProd && !StringUtils.hasText(request.certificateId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para activar VeriFactu en PROD necesitas seleccionar un certificado .p12");
        }
        repository.update(
                request.modality(),
                request.mode(),
                request.certificateId(),
                request.invoiceFooterTemplate(),
                request.invoiceStorageRoot());
        return get();
    }
}
