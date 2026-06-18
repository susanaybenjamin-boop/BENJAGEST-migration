package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
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
 * CRUD de contratos de trabajo (employment_contracts).
 *
 * Un empleado puede tener varios contratos (uno tras otro tras cambios
 * de modalidad o tras una baja+alta). En cualquier momento un empleado
 * con un contrato ACTIVE es el "vigente"; las nominas se calculan
 * contra ese.
 */
@Service
public class EmploymentContractService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public EmploymentContractService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<ContractView> list(String employeeId) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, employee_id, contract_type, sepe_contract_code,
                       collective_agreement, professional_category, professional_group,
                       start_date, seniority_date, end_date, weekly_hours, gross_salary,
                       annual_bonuses, extras_prorated, vacation_days, irpf_percent, at_ep_percent,
                       ss_contribution_group,
                       workplace_address, status, termination_reason,
                       probation_days, pdf_model
                  FROM employment_contracts
                 WHERE company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (StringUtils.hasText(employeeId)) {
            sql.append(" AND employee_id = ?");
            args.add(employeeId);
        }
        sql.append(" ORDER BY start_date DESC");
        return jdbcTemplate.query(sql.toString(), this::mapView, args.toArray())
                .stream().map(this::withItems).toList();
    }

    @Transactional
    public ContractView create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO employment_contracts (
                    id, company_id, employee_id, contract_type, sepe_contract_code,
                    collective_agreement, professional_category, professional_group,
                    start_date, seniority_date, end_date, weekly_hours, gross_salary,
                    annual_bonuses, extras_prorated, vacation_days, irpf_percent, at_ep_percent,
                    ss_contribution_group,
                    workplace_address, status, termination_reason,
                    probation_days, pdf_model
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.employeeId(), req.contractType(), blank(req.sepeContractCode()),
                blank(req.collectiveAgreement()), blank(req.professionalCategory()),
                blank(req.professionalGroup()),
                req.startDate(), req.seniorityDate(), req.endDate(),
                req.weeklyHours(), req.grossSalary(),
                req.annualBonuses() == null ? 2 : req.annualBonuses(),
                req.extrasProrated() != null && req.extrasProrated(),
                req.vacationDays() == null ? 30 : req.vacationDays(),
                req.irpfPercent(),
                req.atEpPercent() == null ? new BigDecimal("1.50") : req.atEpPercent(),
                req.ssContributionGroup() == null ? 7 : req.ssContributionGroup(),
                blank(req.workplaceAddress()),
                StringUtils.hasText(req.status()) ? req.status() : "ACTIVE",
                blank(req.terminationReason()),
                req.probationDays(),
                blank(req.pdfModel())
        );
        BigDecimal recomputed = replaceSalaryItems(id, req.salaryItems());
        if (recomputed != null) {
            jdbcTemplate.update("UPDATE employment_contracts SET gross_salary = ? WHERE id = ? AND company_id = ?",
                    recomputed, id, tenantContext.getCurrentCompanyId());
        }
        // VIG-3: vigencia inicial (effective_from = inicio del contrato).
        syncVigencia(id, tenantContext.getCurrentCompanyId(), req.startDate(), req,
                recomputed != null ? recomputed : req.grossSalary(), "Alta inicial", true);
        return findById(id);
    }

    @Transactional
    public ContractView update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbcTemplate.update("""
                UPDATE employment_contracts
                   SET contract_type = ?, sepe_contract_code = ?,
                       collective_agreement = ?, professional_category = ?, professional_group = ?,
                       start_date = ?, seniority_date = ?, end_date = ?, weekly_hours = ?, gross_salary = ?,
                       annual_bonuses = ?, extras_prorated = ?, vacation_days = ?, irpf_percent = ?, at_ep_percent = ?,
                       ss_contribution_group = ?,
                       workplace_address = ?, status = ?, termination_reason = ?,
                       probation_days = ?, pdf_model = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.contractType(), blank(req.sepeContractCode()),
                blank(req.collectiveAgreement()), blank(req.professionalCategory()),
                blank(req.professionalGroup()),
                req.startDate(), req.seniorityDate(), req.endDate(),
                req.weeklyHours(), req.grossSalary(),
                req.annualBonuses(), req.extrasProrated() != null && req.extrasProrated(),
                req.vacationDays(), req.irpfPercent(),
                req.atEpPercent() == null ? new BigDecimal("1.50") : req.atEpPercent(),
                req.ssContributionGroup() == null ? 7 : req.ssContributionGroup(),
                blank(req.workplaceAddress()),
                StringUtils.hasText(req.status()) ? req.status() : "ACTIVE",
                blank(req.terminationReason()),
                req.probationDays(), blank(req.pdfModel()),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado");
        }
        BigDecimal recomputed = replaceSalaryItems(id, req.salaryItems());
        if (recomputed != null) {
            jdbcTemplate.update("UPDATE employment_contracts SET gross_salary = ? WHERE id = ? AND company_id = ?",
                    recomputed, id, tenantContext.getCurrentCompanyId());
        }
        // VIG-3: editar el contrato sincroniza la vigencia MÁS RECIENTE (corrección
        // de las condiciones actuales). Un ascenso con fecha de efecto usa promote().
        syncVigencia(id, tenantContext.getCurrentCompanyId(), null, req,
                recomputed != null ? recomputed : req.grossSalary(), null, false);
        return findById(id);
    }

    /**
     * VIG-3: ASCENSO / cambio de condiciones con fecha de efecto. Crea una nueva
     * vigencia (la novación del mismo contrato; la antigüedad NO se toca) y
     * actualiza la fila del contrato a las condiciones nuevas (= condición actual).
     * Las nóminas de periodos anteriores siguen usando la vigencia previa.
     */
    @Transactional
    public ContractView promote(String id, java.time.LocalDate effectiveFrom,
                                 UpsertRequest req, String reason) {
        validate(req);
        if (effectiveFrom == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta la fecha de efecto del ascenso");
        }
        String companyId = tenantContext.getCurrentCompanyId();
        // 1) La fila del contrato pasa a reflejar las condiciones nuevas (actuales);
        //    NO tocamos start_date ni seniority_date (la antigüedad se conserva).
        int n = jdbcTemplate.update("""
                UPDATE employment_contracts
                   SET collective_agreement = ?, professional_category = ?, professional_group = ?,
                       weekly_hours = ?, gross_salary = ?, annual_bonuses = ?, extras_prorated = ?,
                       vacation_days = ?, irpf_percent = ?, at_ep_percent = ?, ss_contribution_group = ?
                 WHERE id = ? AND company_id = ?
                """,
                blank(req.collectiveAgreement()), blank(req.professionalCategory()),
                blank(req.professionalGroup()), req.weeklyHours(), req.grossSalary(),
                req.annualBonuses(), req.extrasProrated() != null && req.extrasProrated(),
                req.vacationDays(), req.irpfPercent(),
                req.atEpPercent() == null ? new BigDecimal("1.50") : req.atEpPercent(),
                req.ssContributionGroup() == null ? 7 : req.ssContributionGroup(),
                id, companyId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado");
        }
        BigDecimal recomputed = replaceSalaryItems(id, req.salaryItems());
        if (recomputed != null) {
            jdbcTemplate.update("UPDATE employment_contracts SET gross_salary = ? WHERE id = ? AND company_id = ?",
                    recomputed, id, companyId);
        }
        // 2) Nueva vigencia con la fecha de efecto.
        syncVigencia(id, companyId, effectiveFrom, req,
                recomputed != null ? recomputed : req.grossSalary(),
                reason == null || reason.isBlank() ? "Cambio de condiciones" : reason.trim(), true);
        return findById(id);
    }

    /**
     * VIG: sincroniza la tabla de vigencias con las condiciones del contrato.
     * {@code createNew=true} inserta (o upserta por fecha) una vigencia con
     * {@code effectiveFrom}; {@code createNew=false} actualiza la vigencia MÁS
     * RECIENTE (corrección de condiciones actuales sin cambiar de fecha).
     */
    private void syncVigencia(String contractId, String companyId, java.time.LocalDate effectiveFrom,
                               UpsertRequest req, BigDecimal grossSalary, String reason, boolean createNew) {
        int ssGroup = req.ssContributionGroup() == null ? 7 : req.ssContributionGroup();
        BigDecimal atEp = req.atEpPercent() == null ? new BigDecimal("1.50") : req.atEpPercent();
        boolean prorated = req.extrasProrated() != null && req.extrasProrated();
        if (createNew) {
            jdbcTemplate.update("""
                    INSERT INTO contract_vigencias
                        (id, company_id, contract_id, effective_from, professional_group,
                         professional_category, ss_contribution_group, gross_salary, weekly_hours,
                         annual_bonuses, extras_prorated, vacation_days, irpf_percent, at_ep_percent,
                         change_reason)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE
                        professional_group = VALUES(professional_group),
                        professional_category = VALUES(professional_category),
                        ss_contribution_group = VALUES(ss_contribution_group),
                        gross_salary = VALUES(gross_salary), weekly_hours = VALUES(weekly_hours),
                        annual_bonuses = VALUES(annual_bonuses), extras_prorated = VALUES(extras_prorated),
                        vacation_days = VALUES(vacation_days), irpf_percent = VALUES(irpf_percent),
                        at_ep_percent = VALUES(at_ep_percent), change_reason = VALUES(change_reason)
                    """,
                    UUID.randomUUID().toString(), companyId, contractId, effectiveFrom,
                    blank(req.professionalGroup()), blank(req.professionalCategory()), ssGroup,
                    grossSalary, req.weeklyHours(), req.annualBonuses(), prorated,
                    req.vacationDays(), req.irpfPercent(), atEp, reason);
        } else {
            jdbcTemplate.update("""
                    UPDATE contract_vigencias SET
                        professional_group = ?, professional_category = ?, ss_contribution_group = ?,
                        gross_salary = ?, weekly_hours = ?, annual_bonuses = ?, extras_prorated = ?,
                        vacation_days = ?, irpf_percent = ?, at_ep_percent = ?
                     WHERE contract_id = ? AND effective_from = (
                           SELECT mx FROM (SELECT MAX(effective_from) AS mx FROM contract_vigencias
                                            WHERE contract_id = ?) t)
                    """,
                    blank(req.professionalGroup()), blank(req.professionalCategory()), ssGroup,
                    grossSalary, req.weeklyHours(), req.annualBonuses(), prorated,
                    req.vacationDays(), req.irpfPercent(), atEp, contractId, contractId);
        }
    }

    /**
     * VIG-3 (menor): ¿el contrato tiene nóminas calculadas? La UI lo usa para
     * bloquear la edición de fecha de inicio / antigüedad (cambiarlas tras correr
     * nóminas corrompe antigüedad e indemnización; el cambio de condiciones se
     * hace con "Ascender", que crea una vigencia sin tocar la antigüedad).
     */
    public boolean hasPayslips(String id) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payslips WHERE contract_id = ? AND company_id = ?",
                Integer.class, id, tenantContext.getCurrentCompanyId());
        return n != null && n > 0;
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                DELETE FROM employment_contracts
                 WHERE id = ? AND company_id = ? AND status = 'DRAFT'
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden borrar contratos en borrador. Pasalo a TERMINATED para cerrarlo.");
        }
    }

    /**
     * Añade (o actualiza si ya existe por nombre) un complemento salarial
     * MENSUAL recurrente en el contrato ACTIVE del empleado. El importe se
     * guarda anual (mensual x 12) y se recalcula gross_salary. Lo usa "llegar a
     * un objetivo": la mejora voluntaria vive en el contrato para que anualice
     * en la base de cotización y en el tipo de IRPF.
     */
    @Transactional
    public ContractView upsertRecurringComplement(String employeeId, String conceptName,
                                                   BigDecimal monthlyAmount) {
        if (!StringUtils.hasText(employeeId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (!StringUtils.hasText(conceptName)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conceptName requerido");
        }
        BigDecimal monthly = monthlyAmount == null ? BigDecimal.ZERO : monthlyAmount;
        BigDecimal annual = monthly.multiply(BigDecimal.valueOf(12));
        String contractId = jdbcTemplate.query("""
                SELECT id FROM employment_contracts
                 WHERE company_id = ? AND employee_id = ? AND status = 'ACTIVE'
                 ORDER BY start_date DESC
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), employeeId)
                .stream().findFirst().orElse(null);
        if (contractId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El empleado no tiene un contrato ACTIVE al que añadir el complemento.");
        }
        int updated = jdbcTemplate.update("""
                UPDATE contract_salary_items
                   SET annual_amount = ?, cotizes = 1, taxable = 1, kind = 'COMPLEMENT'
                 WHERE contract_id = ? AND company_id = ? AND concept_name = ?
                """, annual, contractId, tenantContext.getCurrentCompanyId(), conceptName.trim());
        if (updated == 0) {
            Integer maxOrder = jdbcTemplate.query("""
                    SELECT COALESCE(MAX(sort_order), 0) FROM contract_salary_items
                     WHERE contract_id = ? AND company_id = ?
                    """, (rs, n) -> rs.getInt(1), contractId, tenantContext.getCurrentCompanyId())
                    .stream().findFirst().orElse(0);
            jdbcTemplate.update("""
                    INSERT INTO contract_salary_items
                           (id, company_id, contract_id, concept_name, kind,
                            annual_amount, cotizes, taxable, sort_order)
                    VALUES (?, ?, ?, ?, 'COMPLEMENT', ?, 1, 1, ?)
                    """, UUID.randomUUID().toString(), tenantContext.getCurrentCompanyId(),
                    contractId, conceptName.trim(), annual, maxOrder + 1);
        }
        BigDecimal total = jdbcTemplate.query("""
                SELECT COALESCE(SUM(annual_amount), 0) FROM contract_salary_items
                 WHERE contract_id = ? AND company_id = ?
                """, (rs, n) -> rs.getBigDecimal(1), contractId, tenantContext.getCurrentCompanyId())
                .stream().findFirst().orElse(BigDecimal.ZERO);
        jdbcTemplate.update("UPDATE employment_contracts SET gross_salary = ? WHERE id = ? AND company_id = ?",
                total, contractId, tenantContext.getCurrentCompanyId());
        return findById(contractId);
    }

    private ContractView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, employee_id, contract_type, sepe_contract_code,
                       collective_agreement, professional_category, professional_group,
                       start_date, seniority_date, end_date, weekly_hours, gross_salary,
                       annual_bonuses, extras_prorated, vacation_days, irpf_percent, at_ep_percent,
                       ss_contribution_group,
                       workplace_address, status, termination_reason,
                       probation_days, pdf_model
                  FROM employment_contracts
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .map(this::withItems)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.employeeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (!StringUtils.hasText(req.contractType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "contractType requerido");
        }
        if (req.startDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "startDate requerido");
        }
        if (req.irpfPercent() != null
                && (req.irpfPercent().signum() < 0
                        || req.irpfPercent().compareTo(BigDecimal.valueOf(50)) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "irpfPercent fuera de rango");
        }
        if (req.atEpPercent() != null
                && (req.atEpPercent().signum() < 0
                        || req.atEpPercent().compareTo(BigDecimal.valueOf(20)) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "atEpPercent fuera de rango");
        }
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    private ContractView mapView(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date s = rs.getDate("start_date");
        java.sql.Date sen = rs.getDate("seniority_date");
        java.sql.Date e = rs.getDate("end_date");
        return new ContractView(
                rs.getString("id"),
                rs.getString("employee_id"),
                rs.getString("contract_type"),
                rs.getString("sepe_contract_code"),
                rs.getString("collective_agreement"),
                rs.getString("professional_category"),
                rs.getString("professional_group"),
                s == null ? null : s.toLocalDate(),
                sen == null ? null : sen.toLocalDate(),
                e == null ? null : e.toLocalDate(),
                rs.getBigDecimal("weekly_hours"),
                rs.getBigDecimal("gross_salary"),
                (Integer) rs.getObject("annual_bonuses"),
                rs.getBoolean("extras_prorated"),
                (Integer) rs.getObject("vacation_days"),
                rs.getBigDecimal("irpf_percent"),
                rs.getBigDecimal("at_ep_percent"),
                rs.getObject("ss_contribution_group") instanceof Number sg ? sg.intValue() : null,
                rs.getString("workplace_address"),
                rs.getString("status"),
                rs.getString("termination_reason"),
                (Integer) rs.getObject("probation_days"),
                rs.getString("pdf_model"),
                List.of() // se rellena en withItems (carga en 2 fases)
        );
    }

    /** Carga los conceptos salariales de un contrato y devuelve una copia
     *  de la vista con la lista rellena (evita query anidada en el mapper). */
    private ContractView withItems(ContractView c) {
        List<SalaryItemView> items = jdbcTemplate.query("""
                SELECT id, concept_name, kind, annual_amount, cotizes, taxable, sort_order
                  FROM contract_salary_items
                 WHERE contract_id = ? AND company_id = ?
                 ORDER BY sort_order, concept_name
                """,
                (rs, n) -> new SalaryItemView(
                        rs.getString("id"), rs.getString("concept_name"), rs.getString("kind"),
                        rs.getBigDecimal("annual_amount"), rs.getBoolean("cotizes"),
                        rs.getBoolean("taxable"), rs.getInt("sort_order")),
                c.id(), tenantContext.getCurrentCompanyId());
        return new ContractView(c.id(), c.employeeId(), c.contractType(), c.sepeContractCode(),
                c.collectiveAgreement(), c.professionalCategory(), c.professionalGroup(),
                c.startDate(), c.seniorityDate(), c.endDate(), c.weeklyHours(), c.grossSalary(),
                c.annualBonuses(), c.extrasProrated(), c.vacationDays(), c.irpfPercent(), c.atEpPercent(),
                c.ssContributionGroup(),
                c.workplaceAddress(), c.status(), c.terminationReason(),
                c.probationDays(), c.pdfModel(), items);
    }

    /** Borra y reescribe los conceptos salariales del contrato. Devuelve el
     *  bruto anual recalculado (suma de conceptos) o null si no se enviaron. */
    private BigDecimal replaceSalaryItems(String contractId, List<SalaryItem> items) {
        if (items == null) return null; // null = no tocar los conceptos existentes
        jdbcTemplate.update("DELETE FROM contract_salary_items WHERE contract_id = ? AND company_id = ?",
                contractId, tenantContext.getCurrentCompanyId());
        if (items.isEmpty()) return null;
        BigDecimal total = BigDecimal.ZERO;
        int order = 0;
        for (SalaryItem it : items) {
            if (it.conceptName() == null || it.conceptName().isBlank()) continue;
            BigDecimal amt = it.annualAmount() == null ? BigDecimal.ZERO : it.annualAmount();
            String kind = it.kind() == null || it.kind().isBlank() ? "COMPLEMENT" : it.kind();
            jdbcTemplate.update("""
                    INSERT INTO contract_salary_items
                           (id, company_id, contract_id, concept_name, kind,
                            annual_amount, cotizes, taxable, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), tenantContext.getCurrentCompanyId(),
                    contractId, it.conceptName().trim(), kind, amt,
                    it.cotizes() == null || it.cotizes(),
                    it.taxable() == null || it.taxable(), order++);
            total = total.add(amt);
        }
        return total;
    }

    public record ContractView(
            String id, String employeeId, String contractType, String sepeContractCode,
            String collectiveAgreement, String professionalCategory, String professionalGroup,
            LocalDate startDate, LocalDate seniorityDate, LocalDate endDate,
            BigDecimal weeklyHours, BigDecimal grossSalary,
            Integer annualBonuses, Boolean extrasProrated, Integer vacationDays, BigDecimal irpfPercent,
            BigDecimal atEpPercent, Integer ssContributionGroup,
            String workplaceAddress, String status, String terminationReason,
            Integer probationDays, String pdfModel,
            List<SalaryItemView> salaryItems
    ) {}

    public record UpsertRequest(
            String employeeId, String contractType, String sepeContractCode,
            String collectiveAgreement, String professionalCategory, String professionalGroup,
            LocalDate startDate, LocalDate seniorityDate, LocalDate endDate,
            BigDecimal weeklyHours, BigDecimal grossSalary,
            Integer annualBonuses, Boolean extrasProrated, Integer vacationDays, BigDecimal irpfPercent,
            BigDecimal atEpPercent, Integer ssContributionGroup,
            String workplaceAddress, String status, String terminationReason,
            Integer probationDays, String pdfModel,
            List<SalaryItem> salaryItems
    ) {}

    /** VIG-3: petición de ascenso/cambio de condiciones con fecha de efecto. */
    public record PromoteRequest(String effectiveFrom, String reason, UpsertRequest contract) {}

    /** Concepto salarial recibido del editor de contrato. */
    public record SalaryItem(
            String id, String conceptName, String kind,
            BigDecimal annualAmount, Boolean cotizes, Boolean taxable
    ) {}

    /** Concepto salarial devuelto al editor. */
    public record SalaryItemView(
            String id, String conceptName, String kind,
            BigDecimal annualAmount, boolean cotizes, boolean taxable, int sortOrder
    ) {}

    @RestController
    @RequestMapping("/api/labor/contracts")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class ContractController {
        private final EmploymentContractService service;

        public ContractController(EmploymentContractService service) { this.service = service; }

        @GetMapping
        public List<ContractView> list(@RequestParam(value = "employeeId", required = false) String employeeId) {
            return service.list(employeeId);
        }

        @PostMapping
        public ContractView create(@RequestBody UpsertRequest req) { return service.create(req); }

        public record RecurringComplementRequest(
                String employeeId, String conceptName, BigDecimal monthlyAmount) {}

        @PostMapping("/recurring-complement")
        public ContractView upsertRecurringComplement(@RequestBody RecurringComplementRequest req) {
            return service.upsertRecurringComplement(
                    req.employeeId(), req.conceptName(), req.monthlyAmount());
        }

        @PutMapping("/{id}")
        public ContractView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        /** VIG-3 (menor): ¿tiene nóminas? La UI bloquea editar fechas/antigüedad. */
        @GetMapping("/{id}/has-payslips")
        public java.util.Map<String, Boolean> hasPayslips(@PathVariable("id") String id) {
            return java.util.Map.of("hasPayslips", service.hasPayslips(id));
        }

        /** VIG-3: ascenso/cambio de condiciones con fecha de efecto (nueva vigencia). */
        @PostMapping("/{id}/promote")
        public ContractView promote(@PathVariable("id") String id, @RequestBody PromoteRequest req) {
            java.time.LocalDate eff = req.effectiveFrom() == null || req.effectiveFrom().isBlank()
                    ? null : java.time.LocalDate.parse(req.effectiveFrom());
            return service.promote(id, eff, req.contract(), req.reason());
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
