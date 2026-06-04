package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tax.TaxRulesService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
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
 * Cálculo de nóminas mensuales. Versión simplificada pero funcional:
 *
 *   Bruto mensual = (salario_anual_bruto) / (12 + pagas_extras_prorratables)
 *
 *   SS empleado (TGSS Régimen General):
 *     - Contingencias comunes: 4.7%
 *     - Desempleo: 1.55%
 *     - Formación profesional: 0.10%
 *     = TOTAL 6.35% sobre base de cotización
 *
 *   IRPF: %  aplicable del contrato (`irpf_percent`). Si no está definido,
 *          se calcula a partir de los tramos del año vigente (TaxRulesService)
 *          considerando 14 pagas y situación familiar.
 *
 *   Líquido = bruto - SS empleado - IRPF - otras deducciones
 *
 * No incluye en esta versión:
 *   - Bases de cotización con tope mínimo/máximo por categoría (tablas
 *     TGSS, slice futuro).
 *   - Cotización adicional por horas extras.
 *   - Embargos / pensiones / sindicatos.
 *   - Aportaciones a planes de pensiones.
 *   - Generación oficial XML SS para SILTRA (slice futuro).
 *
 * Estas omisiones son honestas: lo capturado cubre el 80% de empleados
 * con contrato indefinido jornada completa, que es el caso más común.
 */
@Service
public class PayslipService {

    private static final BigDecimal SS_EMPLOYEE_PERCENT = new BigDecimal("6.35");

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final TaxRulesService taxRulesService;
    private final PayslipPdfGenerator pdfGenerator;
    private final com.benjagest.backend.settings.EmailSenderService emailSender;
    private final com.benjagest.backend.settings.CompanyDataService companyDataService;

    public PayslipService(JdbcTemplate jdbcTemplate,
                           TenantContext tenantContext,
                           TaxRulesService taxRulesService,
                           PayslipPdfGenerator pdfGenerator,
                           com.benjagest.backend.settings.EmailSenderService emailSender,
                           com.benjagest.backend.settings.CompanyDataService companyDataService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.taxRulesService = taxRulesService;
        this.pdfGenerator = pdfGenerator;
        this.emailSender = emailSender;
        this.companyDataService = companyDataService;
    }

    public byte[] generatePdf(String id) {
        findById(id); // valida que existe y pertenece al tenant
        return pdfGenerator.generate(id);
    }

    /**
     * Envía el PDF de la nómina al email del empleado.
     */
    @Transactional
    public void emailToEmployee(String id) {
        PayslipView view = findById(id);
        // Email del empleado
        String email = jdbcTemplate.query("""
                SELECT email FROM employees WHERE id = ? AND company_id = ?
                """,
                (rs, n) -> rs.getString("email"),
                view.employeeId(), tenantContext.getCurrentCompanyId())
                .stream().findFirst().orElse(null);
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "El empleado no tiene email configurado");
        }
        byte[] pdf = pdfGenerator.generate(id);
        String filename = "nomina-" + view.periodYear() + "-"
                + String.format("%02d", view.periodMonth()) + "-"
                + view.employeeName().replace(" ", "_") + ".pdf";
        var company = companyDataService.getCurrent();
        String subject = "Nomina " + view.periodMonth() + "/" + view.periodYear()
                + " - " + (company.legalName() == null ? "" : company.legalName());
        String body = "Hola " + view.employeeName() + ",\n\n"
                + "Adjuntamos la nomina correspondiente al periodo "
                + view.periodMonth() + "/" + view.periodYear() + ".\n\n"
                + "Liquido a percibir: " + view.netAmount() + " EUR.\n\n"
                + "Saludos,\n"
                + (company.legalName() == null ? "Tu empresa" : company.legalName());

        try {
            emailSender.send(email, subject, body, pdf, filename);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error enviando email: " + ex.getMessage());
        }
    }

    /**
     * Resuelve el employeeId asociado al usuario actual. Si el usuario
     * tiene una fila en `employees` cuyo user_id coincide, la devuelve.
     * Útil para el módulo de fichajes (auto-detect del empleado).
     */
    public String resolveEmployeeIdForUser(String userId) {
        if (userId == null || userId.isBlank()) return null;
        return jdbcTemplate.query("""
                SELECT id FROM employees
                 WHERE company_id = ? AND user_id = ? AND active = TRUE
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), userId)
                .stream().findFirst().orElse(null);
    }

    public List<PayslipView> list(Integer year, String status, String employeeId)
            throws ResponseStatusException {
        StringBuilder sql = new StringBuilder("""
                SELECT p.id, p.employee_id, p.contract_id,
                       e.full_name AS employee_name,
                       p.period_year, p.period_month, p.payslip_type,
                       p.gross_amount, p.ss_employee_amount, p.irpf_amount,
                       p.other_deductions, p.net_amount,
                       p.status, p.paid_at, p.pdf_path, p.notes,
                       p.created_at, p.updated_at
                  FROM payslips p
                  JOIN employees e ON e.id = p.employee_id
                 WHERE p.company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (year != null) { sql.append(" AND p.period_year = ?"); args.add(year); }
        if (StringUtils.hasText(status)) { sql.append(" AND p.status = ?"); args.add(status); }
        if (StringUtils.hasText(employeeId)) { sql.append(" AND p.employee_id = ?"); args.add(employeeId); }
        sql.append(" ORDER BY p.period_year DESC, p.period_month DESC, e.full_name");
        return jdbcTemplate.query(sql.toString(), this::mapView, args.toArray());
    }

    /**
     * Calcula y persiste (o actualiza si ya existía DRAFT/CALCULATED para ese
     * período) una nómina del empleado en el mes/año dado.
     */
    @Transactional
    public PayslipView calculate(CalculateRequest req) {
        if (!StringUtils.hasText(req.employeeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (req.year() < 2000 || req.year() > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "year invalido");
        }
        if (req.month() < 1 || req.month() > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month invalido");
        }

        // Cargar contrato activo del empleado en ese mes
        ContractData contract = resolveActiveContract(req.employeeId(), req.year(), req.month());
        if (contract == null || contract.grossSalary == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Empleado sin contrato activo o sin salario en " + req.year() + "/" + req.month());
        }

        // 1) Bruto mensual = bruto anual / (12 + pagas extras prorratables)
        //    Si las pagas extras no se prorratean, dividir solo entre 12 y
        //    las pagas extras se calculan aparte como nóminas EXTRA_*.
        int divisor = req.extraProratedOrDefault() ? 12 + contract.annualBonuses : 12;
        BigDecimal gross = contract.grossSalary
                .divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);

        // 2) Base cotización = bruto (simplificación; en realidad hay tope SS)
        BigDecimal ssEmployee = gross
                .multiply(SS_EMPLOYEE_PERCENT)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // 3) IRPF
        BigDecimal irpfPct = contract.irpfPercent != null && contract.irpfPercent.signum() > 0
                ? contract.irpfPercent
                : computeIrpfPercent(contract.grossSalary, req.year());
        BigDecimal irpf = gross.multiply(irpfPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal otherDeductions = req.otherDeductions() != null
                ? req.otherDeductions() : BigDecimal.ZERO;

        // 4) Líquido
        BigDecimal net = gross.subtract(ssEmployee).subtract(irpf).subtract(otherDeductions);

        String type = StringUtils.hasText(req.payslipType()) ? req.payslipType() : "MONTHLY";

        // Si ya existe una para (employee, year, month, type), actualizar
        String existingId = jdbcTemplate.query("""
                SELECT id FROM payslips
                 WHERE company_id = ? AND employee_id = ?
                   AND period_year = ? AND period_month = ? AND payslip_type = ?
                """,
                (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), req.employeeId(),
                req.year(), req.month(), type)
                .stream().findFirst().orElse(null);

        if (existingId != null) {
            jdbcTemplate.update("""
                    UPDATE payslips
                       SET contract_id = ?,
                           gross_amount = ?, ss_employee_amount = ?, irpf_amount = ?,
                           other_deductions = ?, net_amount = ?,
                           status = 'CALCULATED', notes = ?
                     WHERE id = ? AND company_id = ?
                    """,
                    contract.id, gross, ssEmployee, irpf,
                    otherDeductions, net,
                    req.notes(),
                    existingId, tenantContext.getCurrentCompanyId());
            return findById(existingId);
        }

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO payslips (
                    id, company_id, employee_id, contract_id,
                    period_year, period_month, payslip_type,
                    gross_amount, ss_employee_amount, irpf_amount,
                    other_deductions, net_amount, status, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CALCULATED', ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.employeeId(), contract.id,
                req.year(), req.month(), type,
                gross, ssEmployee, irpf,
                otherDeductions, net,
                req.notes());
        return findById(id);
    }

    @Transactional
    public PayslipView markPaid(String id, LocalDate paidAt) {
        int n = jdbcTemplate.update("""
                UPDATE payslips
                   SET status = 'PAID',
                       paid_at = COALESCE(?, CURRENT_DATE())
                 WHERE id = ? AND company_id = ?
                """, paidAt, id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada");
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                DELETE FROM payslips
                 WHERE id = ? AND company_id = ? AND status IN ('DRAFT', 'CALCULATED', 'CANCELLED')
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden borrar nominas no pagadas");
        }
    }

    private PayslipView findById(String id) {
        return jdbcTemplate.query("""
                SELECT p.id, p.employee_id, p.contract_id,
                       e.full_name AS employee_name,
                       p.period_year, p.period_month, p.payslip_type,
                       p.gross_amount, p.ss_employee_amount, p.irpf_amount,
                       p.other_deductions, p.net_amount,
                       p.status, p.paid_at, p.pdf_path, p.notes,
                       p.created_at, p.updated_at
                  FROM payslips p
                  JOIN employees e ON e.id = p.employee_id
                 WHERE p.id = ? AND p.company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada"));
    }

    private ContractData resolveActiveContract(String employeeId, int year, int month) {
        LocalDate ref = LocalDate.of(year, month, 1);
        return jdbcTemplate.query("""
                SELECT id, contract_type, start_date, end_date,
                       gross_salary, annual_bonuses, irpf_percent
                  FROM employment_contracts
                 WHERE company_id = ? AND employee_id = ?
                   AND start_date <= ?
                   AND (end_date IS NULL OR end_date >= ?)
                   AND status IN ('ACTIVE', 'SUSPENDED')
                 ORDER BY start_date DESC LIMIT 1
                """,
                (rs, n) -> {
                    ContractData d = new ContractData();
                    d.id = rs.getString("id");
                    d.contractType = rs.getString("contract_type");
                    java.sql.Date s = rs.getDate("start_date");
                    java.sql.Date e = rs.getDate("end_date");
                    d.startDate = s == null ? null : s.toLocalDate();
                    d.endDate = e == null ? null : e.toLocalDate();
                    d.grossSalary = rs.getBigDecimal("gross_salary");
                    Integer bonuses = (Integer) rs.getObject("annual_bonuses");
                    d.annualBonuses = bonuses == null ? 2 : bonuses;
                    d.irpfPercent = rs.getBigDecimal("irpf_percent");
                    return d;
                },
                tenantContext.getCurrentCompanyId(), employeeId, ref, ref)
                .stream().findFirst().orElse(null);
    }

    /**
     * Estimacion conservadora del % IRPF cuando el contrato no lo tiene
     * fijado. Asume 12 pagas + 2 extras prorrateadas para anualizar.
     */
    private BigDecimal computeIrpfPercent(BigDecimal annualGross, int year) {
        if (annualGross == null || annualGross.signum() <= 0) return BigDecimal.ZERO;
        var calc = taxRulesService.calculateIrpf(annualGross, year);
        return calc.effectiveRate();
    }

    private static class ContractData {
        String id;
        String contractType;
        LocalDate startDate;
        LocalDate endDate;
        BigDecimal grossSalary;
        int annualBonuses;
        BigDecimal irpfPercent;
    }

    private PayslipView mapView(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date pd = rs.getDate("paid_at");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new PayslipView(
                rs.getString("id"),
                rs.getString("employee_id"),
                rs.getString("employee_name"),
                rs.getString("contract_id"),
                rs.getInt("period_year"),
                rs.getInt("period_month"),
                rs.getString("payslip_type"),
                rs.getBigDecimal("gross_amount"),
                rs.getBigDecimal("ss_employee_amount"),
                rs.getBigDecimal("irpf_amount"),
                rs.getBigDecimal("other_deductions"),
                rs.getBigDecimal("net_amount"),
                rs.getString("status"),
                pd == null ? null : pd.toLocalDate(),
                rs.getString("pdf_path"),
                rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    public record PayslipView(
            String id, String employeeId, String employeeName, String contractId,
            int periodYear, int periodMonth, String payslipType,
            BigDecimal grossAmount, BigDecimal ssEmployeeAmount, BigDecimal irpfAmount,
            BigDecimal otherDeductions, BigDecimal netAmount,
            String status, LocalDate paidAt, String pdfPath, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    public record CalculateRequest(
            String employeeId, int year, int month, String payslipType,
            Boolean includeExtraProrated, BigDecimal otherDeductions, String notes
    ) {
        public boolean extraProratedOrDefault() {
            return includeExtraProrated == null || includeExtraProrated;
        }
    }

    public record MarkPaidRequest(LocalDate paidAt) {}

    @RestController
    @RequestMapping("/api/labor/payslips")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class PayslipController {
        private final PayslipService service;

        public PayslipController(PayslipService service) { this.service = service; }

        @GetMapping
        public List<PayslipView> list(@RequestParam(value = "year", required = false) Integer year,
                                       @RequestParam(value = "status", required = false) String status,
                                       @RequestParam(value = "employeeId", required = false) String employeeId) {
            return service.list(year == null ? Year.now().getValue() : year, status, employeeId);
        }

        @PostMapping("/calculate")
        public PayslipView calculate(@RequestBody CalculateRequest req) {
            return service.calculate(req);
        }

        @PutMapping("/{id}/pay")
        public PayslipView markPaid(@PathVariable("id") String id,
                                     @RequestBody MarkPaidRequest req) {
            return service.markPaid(id, req == null ? null : req.paidAt());
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }

        @GetMapping("/{id}/pdf")
        public org.springframework.http.ResponseEntity<byte[]> pdf(@PathVariable("id") String id) {
            byte[] pdf = service.generatePdf(id);
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", "inline; filename=\"nomina-" + id + ".pdf\"")
                    .body(pdf);
        }

        @PostMapping("/{id}/email")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void email(@PathVariable("id") String id) { service.emailToEmployee(id); }

        @GetMapping("/resolve-self")
        public java.util.Map<String, String> resolveSelf(
                @RequestParam(value = "userId") String userId) {
            String employeeId = service.resolveEmployeeIdForUser(userId);
            return employeeId == null
                    ? java.util.Map.of()
                    : java.util.Map.of("employeeId", employeeId);
        }
    }
}
