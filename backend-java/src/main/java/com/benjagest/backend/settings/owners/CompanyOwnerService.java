package com.benjagest.backend.settings.owners;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Logica + endpoints de titulares.
 *
 * Decision de empaquetado: el "controller" vive aqui dentro de la
 * clase de servicio para mantener el slice de un solo archivo
 * funcionalmente. Es el mismo patron que ya usamos en
 * `billing/texts/InvoiceTextsController` (controller anidado en
 * service para slices pequenos).
 */
@Service
public class CompanyOwnerService {

    private final CompanyOwnerRepository repository;

    public CompanyOwnerService(CompanyOwnerRepository repository) {
        this.repository = repository;
    }

    public List<CompanyOwner> list() {
        return repository.findAllForCurrentCompany();
    }

    @Transactional
    public CompanyOwner create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        repository.insert(id, req.fullName().trim(), req.taxIdentifier().trim().toUpperCase(),
                req.role(),
                req.ownershipPercent() == null ? BigDecimal.ZERO : req.ownershipPercent(),
                req.ssRegime(),
                req.appointmentDate(), req.terminationDate(),
                blankToNull(req.email()), blankToNull(req.phone()), blankToNull(req.notes()));
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public CompanyOwner update(String id, UpsertRequest req) {
        validate(req);
        if (repository.findById(id).isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Titular no encontrado");
        }
        repository.update(id, req.fullName().trim(), req.role(),
                req.ownershipPercent() == null ? BigDecimal.ZERO : req.ownershipPercent(),
                req.ssRegime(),
                req.appointmentDate(), req.terminationDate(),
                blankToNull(req.email()), blankToNull(req.phone()), blankToNull(req.notes()),
                req.active() == null || req.active());
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public void delete(String id) {
        int n = repository.delete(id);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Titular no encontrado");
        }
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.fullName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "fullName requerido");
        }
        if (!StringUtils.hasText(req.taxIdentifier())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "taxIdentifier requerido");
        }
        if (!List.of("ADMINISTRATOR", "JOINT_ADMINISTRATOR", "SOLE_ADMINISTRATOR",
                "BOARD_MEMBER", "PARTNER", "AUTONOMOUS").contains(req.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "role debe ser ADMINISTRATOR/JOINT_ADMINISTRATOR/SOLE_ADMINISTRATOR/BOARD_MEMBER/PARTNER/AUTONOMOUS");
        }
        if (req.ownershipPercent() != null
                && (req.ownershipPercent().compareTo(BigDecimal.ZERO) < 0
                        || req.ownershipPercent().compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ownershipPercent debe estar entre 0 y 100");
        }
        if (req.ssRegime() != null
                && !List.of("RETA", "GENERAL", "AUTONOMO_SOCIETARIO", "NO_COTIZA", "OTHER")
                        .contains(req.ssRegime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "ssRegime invalido");
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    public record UpsertRequest(
            String fullName,
            String taxIdentifier,
            String role,
            BigDecimal ownershipPercent,
            String ssRegime,
            LocalDate appointmentDate,
            LocalDate terminationDate,
            String email,
            String phone,
            String notes,
            Boolean active
    ) {}

    // -----------------------------------------------------------------
    // Controller anidado para no fragmentar el slice en demasiados ficheros.
    // -----------------------------------------------------------------
    @RestController
    @RequestMapping("/api/settings/owners")
    @RequiresModule("settings")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class CompanyOwnerController {

        private final CompanyOwnerService service;

        public CompanyOwnerController(CompanyOwnerService service) {
            this.service = service;
        }

        @GetMapping
        public List<CompanyOwner> list() { return service.list(); }

        @PostMapping
        public CompanyOwner create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public CompanyOwner update(@PathVariable("id") String id,
                                   @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
