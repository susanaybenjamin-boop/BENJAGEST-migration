package com.benjagest.backend.billing.texts;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/billing/invoice-texts")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class InvoiceTextsController {

    private final InvoiceTextsService service;

    public InvoiceTextsController(InvoiceTextsService service) {
        this.service = service;
    }

    @GetMapping
    public InvoiceTexts get() {
        return service.get();
    }

    @PutMapping
    public InvoiceTexts update(@RequestBody InvoiceTexts request) {
        return service.update(request);
    }

    @Service
    public static class InvoiceTextsService {
        private final InvoiceTextsRepository repository;

        public InvoiceTextsService(InvoiceTextsRepository repository) {
            this.repository = repository;
        }

        public InvoiceTexts get() {
            return repository.findCurrent().orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada"));
        }

        public InvoiceTexts update(InvoiceTexts texts) {
            repository.update(texts);
            return get();
        }
    }
}
