package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CV-2 + CV-ORQ — Baja / despido de un empleado. Orquesta el cese:
 *  - calcula el finiquito (reutiliza {@link PayslipService}),
 *  - calcula la indemnización según el tipo de baja (días por año de
 *    antigüedad, topes legales, parte exenta de IRPF),
 *  - pone el contrato en TERMINATED con el motivo.
 *
 * El asesor solo indica fecha de cese y motivo; el resto se extrae solo.
 */
@Service
public class TerminationService {

    /** RD-Ley 3/2012: el tramo de 45 días/año aplica a servicios anteriores. */
    private static final LocalDate REFORM_2012 = LocalDate.of(2012, 2, 12);
    private static final BigDecimal IRPF_EXEMPT_CAP = BigDecimal.valueOf(180_000);

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final PayslipService payslipService;

    public TerminationService(JdbcTemplate jdbc, TenantContext tenant, PayslipService payslipService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.payslipService = payslipService;
    }

    public record TerminationRequest(
            String employeeId, LocalDate ceseDate, String type,
            String extrasAccrual, BigDecimal otherDeductions, String notes) {}

    public record Severance(
            BigDecimal gross, BigDecimal exempt, BigDecimal taxable,
            BigDecimal days, BigDecimal antiquityYears, BigDecimal dailySalary,
            int antiqYears, int antiqMonths, int antiqDays) {}

    public record TerminationPreview(
            List<PayslipService.ExtraConcept> concepts,
            PayslipService.PreviewResult settlement, Severance severance) {}

    public record TerminationResult(PayslipService.PayslipView settlement, Severance severance) {}

    private record ActiveContract(String id, LocalDate startDate, BigDecimal grossSalary) {}

    private ActiveContract loadActiveContract(String employeeId, LocalDate ref) {
        return jdbc.query("""
                SELECT id, start_date, gross_salary FROM employment_contracts
                 WHERE company_id = ? AND employee_id = ?
                   AND start_date <= ?
                   AND (end_date IS NULL OR end_date >= ?)
                   AND status IN ('ACTIVE', 'SUSPENDED')
                 ORDER BY start_date DESC LIMIT 1
                """,
                (rs, n) -> new ActiveContract(rs.getString("id"),
                        rs.getDate("start_date") == null ? null : rs.getDate("start_date").toLocalDate(),
                        rs.getBigDecimal("gross_salary")),
                tenant.getCurrentCompanyId(), employeeId, ref, ref)
                .stream().findFirst().orElse(null);
    }

    /** Indemnización por tipo de despido. */
    public Severance computeSeverance(String type, BigDecimal grossAnnual,
                                       LocalDate start, LocalDate cese) {
        BigDecimal zero = BigDecimal.ZERO.setScale(2);
        if (grossAnnual == null || start == null || cese == null || cese.isBefore(start)) {
            return new Severance(zero, zero, zero, zero, zero, zero, 0, 0, 0);
        }
        BigDecimal dailySalary = grossAnnual.divide(BigDecimal.valueOf(365), 4, RoundingMode.HALF_UP);
        double totalDaysService = ChronoUnit.DAYS.between(start, cese) + 1;
        double years = totalDaysService / 365.0;
        BigDecimal antiquity = BigDecimal.valueOf(years).setScale(2, RoundingMode.HALF_UP);
        // Antigüedad legible (años, meses, días), cese incluido.
        java.time.Period per = java.time.Period.between(start, cese.plusDays(1));
        int antiqY = per.getYears(), antiqM = per.getMonths(), antiqD = per.getDays();

        double indemDays;
        boolean exempt;
        switch (type == null ? "" : type) {
            case "DISMISSAL_UNFAIR" -> {
                if (!start.isBefore(REFORM_2012)) {
                    indemDays = Math.min(33 * years, 720);
                } else {
                    double yearsBefore = (ChronoUnit.DAYS.between(start, REFORM_2012)) / 365.0;
                    double yearsAfter = (ChronoUnit.DAYS.between(REFORM_2012, cese) + 1) / 365.0;
                    double daysBefore = 45 * yearsBefore;
                    double daysAfter = 33 * yearsAfter;
                    double total = daysBefore + daysAfter;
                    double maxDays = daysBefore <= 720 ? 720 : Math.min(daysBefore, 1260);
                    indemDays = Math.min(total, maxDays);
                }
                exempt = true;
            }
            case "DISMISSAL_OBJECTIVE" -> { indemDays = Math.min(20 * years, 360); exempt = true; }
            case "END_OF_CONTRACT" -> { indemDays = 12 * years; exempt = false; }
            default -> { indemDays = 0; exempt = false; } // voluntaria, disciplinario, jubilación
        }
        BigDecimal days = BigDecimal.valueOf(indemDays).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = dailySalary.multiply(days).setScale(2, RoundingMode.HALF_UP);
        BigDecimal exemptAmt = exempt ? gross.min(IRPF_EXEMPT_CAP) : zero;
        BigDecimal taxable = gross.subtract(exemptAmt).max(zero);
        return new Severance(gross, exemptAmt.setScale(2), taxable.setScale(2),
                days, antiquity, dailySalary.setScale(2, RoundingMode.HALF_UP),
                antiqY, antiqM, antiqD);
    }

    private PayslipService.SettlementRequest settlementReq(TerminationRequest r) {
        return new PayslipService.SettlementRequest(
                r.employeeId(), r.ceseDate().getYear(), r.ceseDate().getMonthValue(),
                r.ceseDate().getDayOfMonth(), null, // vacaciones: auto (devengadas − disfrutadas)
                r.extrasAccrual(), r.otherDeductions(), r.notes());
    }

    public TerminationPreview preview(TerminationRequest r) {
        validate(r);
        ActiveContract c = loadActiveContract(r.employeeId(), r.ceseDate());
        if (c == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El empleado no tiene un contrato activo a la fecha de cese.");
        List<PayslipService.ExtraConcept> concepts = payslipService.settlementConcepts(settlementReq(r));
        PayslipService.PreviewResult settlement = payslipService.preview(new PayslipService.CalculateRequest(
                r.employeeId(), r.ceseDate().getYear(), r.ceseDate().getMonthValue(), "SETTLEMENT",
                false, r.otherDeductions(), r.notes(), concepts, null));
        Severance sev = computeSeverance(r.type(), c.grossSalary(), c.startDate(), r.ceseDate());
        return new TerminationPreview(concepts, settlement, sev);
    }

    @Transactional
    public TerminationResult execute(TerminationRequest r) {
        validate(r);
        ActiveContract c = loadActiveContract(r.employeeId(), r.ceseDate());
        if (c == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El empleado no tiene un contrato activo a la fecha de cese.");
        List<PayslipService.ExtraConcept> concepts = payslipService.settlementConcepts(settlementReq(r));
        PayslipService.PayslipView settlement = payslipService.calculate(new PayslipService.CalculateRequest(
                r.employeeId(), r.ceseDate().getYear(), r.ceseDate().getMonthValue(), "SETTLEMENT",
                false, r.otherDeductions(), r.notes(), concepts, null));
        Severance sev = computeSeverance(r.type(), c.grossSalary(), c.startDate(), r.ceseDate());
        // Cierra el contrato con el motivo de baja.
        jdbc.update("""
                UPDATE employment_contracts
                   SET status = 'TERMINATED', end_date = ?, termination_reason = ?
                 WHERE id = ? AND company_id = ?
                """, r.ceseDate(), r.type(), c.id(), tenant.getCurrentCompanyId());
        // Si no le queda ningún contrato activo, el empleado pasa a baja
        // (deja de aparecer como activo y no se le puede volver a despedir).
        Integer otros = jdbc.query("""
                SELECT COUNT(*) FROM employment_contracts
                 WHERE company_id = ? AND employee_id = ? AND status IN ('ACTIVE', 'SUSPENDED')
                """, (rs, n) -> rs.getInt(1), tenant.getCurrentCompanyId(), r.employeeId())
                .stream().findFirst().orElse(0);
        if (otros == null || otros == 0) {
            jdbc.update("UPDATE employees SET active = FALSE WHERE id = ? AND company_id = ?",
                    r.employeeId(), tenant.getCurrentCompanyId());
        }
        return new TerminationResult(settlement, sev);
    }

    private void validate(TerminationRequest r) {
        if (!StringUtils.hasText(r.employeeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (r.ceseDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha de cese requerida");
        }
        if (!StringUtils.hasText(r.type())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Motivo de baja requerido");
        }
    }

    @RestController
    @RequestMapping("/api/labor/terminations")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final TerminationService service;

        public Controller(TerminationService service) { this.service = service; }

        @PostMapping("/preview")
        public TerminationPreview preview(@RequestBody TerminationRequest req) { return service.preview(req); }

        @PostMapping("/execute")
        public TerminationResult execute(@RequestBody TerminationRequest req) { return service.execute(req); }
    }
}
