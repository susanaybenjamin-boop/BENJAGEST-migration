package com.benjagest.backend.settings;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompanyDataService {

    private final CompanyDataRepository repository;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final TenantContext tenantContext;

    public CompanyDataService(CompanyDataRepository repository,
                              AuditService auditService,
                              CurrentUserService currentUserService,
                              TenantContext tenantContext) {
        this.repository = repository;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
    }

    public CompanyDataResponse getCurrent() {
        return repository.findCurrent().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada"));
    }

    @Transactional
    public CompanyDataResponse update(CompanyDataUpdateRequest request) {
        try {
            int affected = repository.updateCurrent(request);
            if (affected == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada");
            }
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "Ya existe otra empresa con ese identificador fiscal"
            );
        }
        auditService.recordCompanyDataUpdated(
                currentUserService.require().userId(),
                tenantContext.getCurrentCompanyId()
        );
        return getCurrent();
    }
}
