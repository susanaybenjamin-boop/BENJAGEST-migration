package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CL-1 — Periodos de suspensión/excedencia del contrato (art. 45–48 ET).
 *
 * <p>Registra los periodos SIN remuneración (excedencias, suspensión de empleo y
 * sueldo) para que la nómina no genere un recibo a quien está en excedencia
 * (la guarda vive en {@code PayslipService.isMonthFullySuspended}). La IT/
 * maternidad NO van aquí (tienen su propio flujo en {@code medical_leaves}).
 *
 * <p>La UI en la ficha del contrato (registrar/cerrar) es CL-4 (con Benjamin);
 * por ahora se opera vía estos endpoints.
 */
@Service
public class ContractSuspensionService {

    private static final List<String> TYPES = List.of(
            "EXCEDENCIA_VOLUNTARIA", "EXCEDENCIA_FORZOSA", "EXCEDENCIA_CUIDADO",
            "SUSPENSION_EMPLEO_SUELDO", "OTRA");

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public ContractSuspensionService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    @Transactional
    public SuspensionView register(CreateRequest r) {
        if (r.employeeId() == null || r.employeeId().isBlank()) throw bad("Empleado requerido");
        if (r.startDate() == null) throw bad("Fecha de inicio requerida");
        // Si no se indica contrato, se resuelve el activo del empleado.
        String contractId = r.contractId();
        if (contractId == null || contractId.isBlank()) {
            contractId = jdbc.query("""
                    SELECT id FROM employment_contracts
                     WHERE company_id = ? AND employee_id = ? AND status IN ('ACTIVE', 'SUSPENDED')
                     ORDER BY start_date DESC LIMIT 1
                    """, (rs, n) -> rs.getString("id"),
                    tenant.getCurrentCompanyId(), r.employeeId())
                    .stream().findFirst().orElse(null);
            if (contractId == null) throw bad("El empleado no tiene un contrato activo");
        }
        final String resolvedContractId = contractId;
        String type = r.type() == null ? "OTRA" : r.type().toUpperCase();
        if (!TYPES.contains(type)) throw bad("Tipo de suspensión no válido: " + r.type());
        if (r.endDate() != null && r.endDate().isBefore(r.startDate())) {
            throw bad("La fecha de fin es anterior a la de inicio");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO contract_suspensions
                       (id, company_id, contract_id, employee_id, type,
                        start_date, end_date, reserva_puesto, reason)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenant.getCurrentCompanyId(), resolvedContractId, r.employeeId(), type,
                java.sql.Date.valueOf(r.startDate()),
                r.endDate() == null ? null : java.sql.Date.valueOf(r.endDate()),
                Boolean.TRUE.equals(r.reservaPuesto()), blank(r.reason()));
        return findById(id);
    }

    public List<SuspensionView> listForEmployee(String employeeId) {
        return jdbc.query("""
                SELECT id, contract_id, employee_id, type, start_date, end_date,
                       reserva_puesto, reason
                  FROM contract_suspensions
                 WHERE company_id = ? AND employee_id = ?
                 ORDER BY start_date DESC
                """, this::map, tenant.getCurrentCompanyId(), employeeId);
    }

    /** Cierra una suspensión abierta con la fecha de reingreso. */
    @Transactional
    public SuspensionView close(String id, LocalDate endDate) {
        if (endDate == null) throw bad("Fecha de reingreso requerida");
        int n = jdbc.update("""
                UPDATE contract_suspensions SET end_date = ?
                 WHERE id = ? AND company_id = ?
                """, java.sql.Date.valueOf(endDate), id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Suspensión no encontrada");
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        jdbc.update("DELETE FROM contract_suspensions WHERE id = ? AND company_id = ?",
                id, tenant.getCurrentCompanyId());
    }

    private SuspensionView findById(String id) {
        List<SuspensionView> rows = jdbc.query("""
                SELECT id, contract_id, employee_id, type, start_date, end_date,
                       reserva_puesto, reason
                  FROM contract_suspensions
                 WHERE id = ? AND company_id = ?
                """, this::map, id, tenant.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Suspensión no encontrada");
        return rows.get(0);
    }

    private SuspensionView map(ResultSet rs, int n) throws SQLException {
        java.sql.Date s = rs.getDate("start_date");
        java.sql.Date e = rs.getDate("end_date");
        return new SuspensionView(
                rs.getString("id"), rs.getString("contract_id"), rs.getString("employee_id"),
                rs.getString("type"),
                s == null ? null : s.toLocalDate(), e == null ? null : e.toLocalDate(),
                rs.getBoolean("reserva_puesto"), rs.getString("reason"));
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    public record CreateRequest(String employeeId, String contractId, String type,
                                LocalDate startDate, LocalDate endDate,
                                Boolean reservaPuesto, String reason) {}

    public record CloseRequest(LocalDate endDate) {}

    public record SuspensionView(String id, String contractId, String employeeId, String type,
                                 LocalDate startDate, LocalDate endDate,
                                 boolean reservaPuesto, String reason) {}

    @RestController
    @RequestMapping("/api/labor/contract-suspensions")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final ContractSuspensionService service;

        public Controller(ContractSuspensionService service) { this.service = service; }

        @GetMapping
        public List<SuspensionView> list(@RequestParam("employeeId") String employeeId) {
            return service.listForEmployee(employeeId);
        }

        @PostMapping
        public SuspensionView register(@RequestBody CreateRequest req) {
            return service.register(req);
        }

        @PostMapping("/{id}/close")
        public SuspensionView close(@PathVariable("id") String id, @RequestBody CloseRequest req) {
            return service.close(id, req == null ? null : req.endDate());
        }

        @DeleteMapping("/{id}")
        public void delete(@PathVariable("id") String id) {
            service.delete(id);
        }
    }
}
