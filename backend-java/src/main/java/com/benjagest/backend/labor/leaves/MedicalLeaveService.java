package com.benjagest.backend.labor.leaves;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Servicio de gestión de bajas médicas (Incapacidad Temporal).
 *
 * <p>CRUD básico, sin lógica fiscal por ahora — el cálculo de
 * prestaciones lo hace la mutua. Aquí solo guardamos el registro para
 * que aparezca en el listado del empleado y para que el módulo de
 * nóminas pueda restar los días de IT al calcular el salario.
 *
 * <p>Ámbito por empresa vía {@link TenantContext}.
 */
@Service
public class MedicalLeaveService {

    private final MedicalLeaveRepository repository;
    private final TenantContext tenant;

    public MedicalLeaveService(MedicalLeaveRepository repository, TenantContext tenant) {
        this.repository = repository;
        this.tenant = tenant;
    }

    public List<MedicalLeave> list(String employeeId) {
        String companyId = tenant.getCurrentCompanyId();
        return employeeId == null || employeeId.isBlank()
                ? repository.listByCompany(companyId)
                : repository.listByEmployee(companyId, employeeId);
    }

    public MedicalLeave getById(String id) {
        MedicalLeave m = repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Baja no encontrada"));
        if (!m.companyId().equals(tenant.getCurrentCompanyId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Baja no encontrada en tu empresa");
        }
        return m;
    }

    @Transactional
    public MedicalLeave create(CreateRequest req) {
        validate(req);
        String status = req.status() == null || req.status().isBlank()
                ? (req.endDate() != null ? MedicalLeave.STATUS_CLOSED : MedicalLeave.STATUS_OPEN)
                : req.status();
        MedicalLeave m = new MedicalLeave(
                UUID.randomUUID().toString(),
                tenant.getCurrentCompanyId(),
                req.employeeId(),
                req.leaveType(),
                req.startDate(),
                req.endDate(),
                status,
                blank(req.notes()),
                Instant.now(),
                Instant.now()
        );
        repository.insert(m);
        return m;
    }

    @Transactional
    public MedicalLeave update(String id, UpdateRequest req) {
        MedicalLeave current = getById(id);
        MedicalLeave updated = new MedicalLeave(
                current.id(),
                current.companyId(),
                current.employeeId(),
                req.leaveType() != null ? req.leaveType() : current.leaveType(),
                req.startDate() != null ? req.startDate() : current.startDate(),
                req.endDate() != null ? req.endDate() : current.endDate(),
                req.status() != null ? req.status() : current.status(),
                req.notes() != null ? blank(req.notes()) : current.notes(),
                current.createdAt(),
                Instant.now()
        );
        validateDates(updated.startDate(), updated.endDate());
        repository.update(updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        getById(id);  // valida tenant
        repository.delete(id);
    }

    private static void validate(CreateRequest req) {
        if (req.employeeId() == null || req.employeeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId obligatorio");
        }
        if (req.leaveType() == null || req.leaveType().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "leaveType obligatorio");
        }
        if (req.startDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate obligatoria");
        }
        validateDates(req.startDate(), req.endDate());
    }

    private static void validateDates(LocalDate start, LocalDate end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "endDate no puede ser anterior a startDate");
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public record CreateRequest(
            String employeeId,
            String leaveType,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String notes
    ) {}

    public record UpdateRequest(
            String leaveType,
            LocalDate startDate,
            LocalDate endDate,
            String status,
            String notes
    ) {}

    /**
     * Controller embebido para no fragmentar el paquete. Sigue el
     * mismo patrón que {@code ContractTemplateService}.
     */
    @RestController
    @RequestMapping("/api/labor/medical-leaves")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final MedicalLeaveService service;

        public Controller(MedicalLeaveService service) {
            this.service = service;
        }

        @GetMapping
        public List<MedicalLeave> list(@RequestParam(value = "employeeId", required = false) String employeeId) {
            return service.list(employeeId);
        }

        @GetMapping("/{id}")
        public MedicalLeave getById(@PathVariable("id") String id) {
            return service.getById(id);
        }

        @PostMapping
        @ResponseStatus(HttpStatus.CREATED)
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public MedicalLeave create(@RequestBody CreateRequest req) {
            return service.create(req);
        }

        @PutMapping("/{id}")
        @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
        public MedicalLeave update(@PathVariable("id") String id,
                                    @RequestBody UpdateRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        @RequiresRole({"OWNER", "ADMIN"})
        public void delete(@PathVariable("id") String id) {
            service.delete(id);
        }
    }
}
