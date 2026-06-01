package com.benjagest.backend.settings;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class CompanyDataService {

    private final CompanyDataRepository repository;

    public CompanyDataService(CompanyDataRepository repository) {
        this.repository = repository;
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
        return getCurrent();
    }
}
