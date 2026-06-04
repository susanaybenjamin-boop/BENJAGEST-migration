package com.benjagest.backend.billing.taxes;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

@Service
public class VatRateService {

    private final VatRateRepository repository;

    public VatRateService(VatRateRepository repository) {
        this.repository = repository;
    }

    public List<VatRate> listActive(String kindFilter) {
        return repository.findActiveForCurrentCompany(kindFilter);
    }

    public List<VatRate> listAll() {
        return repository.findAllForCurrentCompany();
    }

    @Transactional
    public VatRate create(UpsertRequest req) {
        validateUpsert(req);
        String id = UUID.randomUUID().toString();
        repository.insert(id, req.kind(), req.code(), req.label(),
                req.percent(), req.isDefault(), req.notes());
        if (req.isDefault()) {
            repository.clearDefaultsExcept(req.kind(), id);
        }
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public VatRate update(String id, UpsertRequest req) {
        validateUpsert(req);
        VatRate existing = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo no encontrado"));
        // Code y kind no se editan (la combinacion es UNIQUE; si quieres
        // otra, crea un tipo nuevo y desactiva este).
        repository.update(id, req.label(), req.percent(),
                req.isDefault(), req.active(), req.notes());
        if (req.isDefault()) {
            repository.clearDefaultsExcept(existing.kind(), id);
        }
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public void delete(String id) {
        VatRate existing = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Tipo no encontrado"));
        if (existing.isDefault()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede borrar el tipo por defecto. Marca otro como default primero.");
        }
        // TODO ALTA-FUTURO: comprobar referencias en sales_invoice_lines
        // y, si existen, prohibir borrar (debe ir a active=FALSE para
        // mantener historial). Por ahora la unica vinculacion es por
        // percent (no por id), asi que borrar no rompe nada legalmente
        // pero pierde la traza humana.
        int n = repository.delete(id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se pudo borrar el tipo");
        }
    }

    private void validateUpsert(UpsertRequest req) {
        if (req.kind() == null
                || !List.of("VAT", "WITHHOLDING").contains(req.kind())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "kind debe ser VAT o WITHHOLDING");
        }
        if (!StringUtils.hasText(req.code())
                || !StringUtils.hasText(req.label())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "code y label son obligatorios");
        }
        if (req.percent() == null
                || req.percent().compareTo(BigDecimal.ZERO) < 0
                || req.percent().compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "percent debe estar entre 0 y 100");
        }
    }

    public record UpsertRequest(
            String kind,
            String code,
            String label,
            BigDecimal percent,
            boolean isDefault,
            boolean active,
            String notes
    ) {}
}
