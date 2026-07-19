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

    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TerminationService.class);

    /** RD-Ley 3/2012: el tramo de 45 días/año aplica a servicios anteriores.
     *  Es un hito legal fijo (no parámetro por año), se queda en código. */
    private static final LocalDate REFORM_2012 = LocalDate.of(2012, 2, 12);

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final PayslipService payslipService;
    private final SeveranceParamsService severanceParamsService;

    public TerminationService(JdbcTemplate jdbc, TenantContext tenant, PayslipService payslipService,
                              SeveranceParamsService severanceParamsService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.payslipService = payslipService;
        this.severanceParamsService = severanceParamsService;
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

    private record ActiveContract(String id, LocalDate startDate, LocalDate seniorityDate,
                                   BigDecimal grossSalary) {
        /** Fecha desde la que cuenta la antigüedad para la indemnización. */
        LocalDate antiquityFrom() { return seniorityDate != null ? seniorityDate : startDate; }
    }

    private ActiveContract loadActiveContract(String employeeId, LocalDate ref) {
        return jdbc.query("""
                SELECT id, start_date, seniority_date, gross_salary FROM employment_contracts
                 WHERE company_id = ? AND employee_id = ?
                   AND start_date <= ?
                   AND (end_date IS NULL OR end_date >= ?)
                   AND status IN ('ACTIVE', 'SUSPENDED')
                 ORDER BY start_date DESC LIMIT 1
                """,
                (rs, n) -> new ActiveContract(rs.getString("id"),
                        rs.getDate("start_date") == null ? null : rs.getDate("start_date").toLocalDate(),
                        rs.getDate("seniority_date") == null ? null : rs.getDate("seniority_date").toLocalDate(),
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

        // N3(b) — días/año, topes y exención de IRPF leídos de tabla no-code
        // (severance_params) por el año del cese. Antes estaban a fuego.
        SeveranceParamsService.Params sp = severanceParamsService.paramsForYear(cese.getYear());
        double unfairPerYear = sp.unfairDaysPerYear().doubleValue();
        double unfairCap = sp.unfairCapDays();
        double pre2012PerYear = sp.unfairPre2012DaysPerYear().doubleValue();
        double pre2012Cap = sp.unfairPre2012CapDays();

        double indemDays;
        boolean exempt;
        switch (type == null ? "" : type) {
            case "DISMISSAL_UNFAIR" -> {
                if (!start.isBefore(REFORM_2012)) {
                    indemDays = Math.min(unfairPerYear * years, unfairCap);
                } else {
                    double yearsBefore = (ChronoUnit.DAYS.between(start, REFORM_2012)) / 365.0;
                    double yearsAfter = (ChronoUnit.DAYS.between(REFORM_2012, cese) + 1) / 365.0;
                    double daysBefore = pre2012PerYear * yearsBefore;
                    double daysAfter = unfairPerYear * yearsAfter;
                    double total = daysBefore + daysAfter;
                    double maxDays = daysBefore <= unfairCap ? unfairCap : Math.min(daysBefore, pre2012Cap);
                    indemDays = Math.min(total, maxDays);
                }
                exempt = true;
            }
            case "DISMISSAL_OBJECTIVE" -> {
                indemDays = Math.min(sp.objectiveDaysPerYear().doubleValue() * years, sp.objectiveCapDays());
                exempt = true;
            }
            case "END_OF_CONTRACT" -> { indemDays = sp.endContractDaysPerYear().doubleValue() * years; exempt = false; }
            default -> { indemDays = 0; exempt = false; } // voluntaria, disciplinario, jubilación
        }
        BigDecimal days = BigDecimal.valueOf(indemDays).setScale(2, RoundingMode.HALF_UP);
        BigDecimal gross = dailySalary.multiply(days).setScale(2, RoundingMode.HALF_UP);
        BigDecimal exemptAmt = exempt ? gross.min(sp.irpfExemptCap()) : zero;
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

    /** F2R: factor de prorrateo del mes de cese (días trabajados / días del mes). */
    private BigDecimal workedFactor(TerminationRequest r) {
        int daysInMonth = java.time.YearMonth.of(
                r.ceseDate().getYear(), r.ceseDate().getMonthValue()).lengthOfMonth();
        int worked = Math.max(1, Math.min(r.ceseDate().getDayOfMonth(), daysInMonth));
        return BigDecimal.valueOf(worked).divide(BigDecimal.valueOf(daysInMonth), 8, RoundingMode.HALF_UP);
    }

    /**
     * F2R-2/3: los EXTRAS del finiquito (vacaciones no disfrutadas + prorrata de
     * pagas extra, SIN el sueldo, que va en la mensual) + la INDEMNIZACIÓN como
     * percepción no salarial. La indemnización NO cotiza (art. 23.2 LGSS) y tributa
     * solo la parte NO exenta. OJO: el tratamiento exacto (cotización/IRPF) de la
     * indemnización queda a VERIFICAR en dev con los logs; el defecto legal es no
     * cotizar y tributar la parte gravable.
     */
    private List<PayslipService.ExtraConcept> finiquitoExtras(TerminationRequest r, Severance sev) {
        List<PayslipService.ExtraConcept> extras = new java.util.ArrayList<>(
                payslipService.settlementConcepts(settlementReq(r), true));
        if (sev != null && sev.gross() != null && sev.gross().signum() > 0) {
            boolean taxable = sev.taxable() != null && sev.taxable().signum() > 0;
            extras.add(new PayslipService.ExtraConcept(
                    "Indemnización fin de contrato", sev.gross(), false, taxable));
        }
        return extras;
    }

    public TerminationPreview preview(TerminationRequest r) {
        validate(r);
        ActiveContract c = loadActiveContract(r.employeeId(), r.ceseDate());
        if (c == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El empleado no tiene un contrato activo a la fecha de cese.");
        Severance sev = computeSeverance(r.type(), c.grossSalary(), c.antiquityFrom(), r.ceseDate());
        List<PayslipService.ExtraConcept> concepts = finiquitoExtras(r, sev);
        PayslipService.PreviewResult settlement = payslipService.preview(new PayslipService.CalculateRequest(
                r.employeeId(), r.ceseDate().getYear(), r.ceseDate().getMonthValue(), "SETTLEMENT",
                false, r.otherDeductions(), r.notes(), concepts, null, null));
        return new TerminationPreview(concepts, settlement, sev);
    }

    @Transactional
    public TerminationResult execute(TerminationRequest r) {
        validate(r);
        ActiveContract c = loadActiveContract(r.employeeId(), r.ceseDate());
        if (c == null) throw new ResponseStatusException(HttpStatus.CONFLICT,
                "El empleado no tiene un contrato activo a la fecha de cese.");
        int y = r.ceseDate().getYear(), m = r.ceseDate().getMonthValue();
        BigDecimal factor = workedFactor(r);
        log.info("F2R baja: emp={} cese={} factor={} (dos recibos: mensual prorrateada + finiquito)",
                r.employeeId(), r.ceseDate(), factor);

        // 1) NÓMINA MENSUAL del mes de cese, prorrateada a los días trabajados (el
        //    sueldo). Se genera con el contrato aún ACTIVO (antes del TERMINATED).
        payslipService.calculate(new PayslipService.CalculateRequest(
                r.employeeId(), y, m, "MONTHLY", null, null, r.notes(), null, null, factor));

        // 2) FINIQUITO: solo extras (vacaciones + prorrata) + indemnización. Sin sueldo.
        Severance sev = computeSeverance(r.type(), c.grossSalary(), c.antiquityFrom(), r.ceseDate());
        List<PayslipService.ExtraConcept> concepts = finiquitoExtras(r, sev);
        PayslipService.PayslipView settlement = payslipService.calculate(new PayslipService.CalculateRequest(
                r.employeeId(), y, m, "SETTLEMENT",
                false, r.otherDeductions(), r.notes(), concepts, null, null));
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

    // ====================================================================
    //  CL-3 — Cese de empresa (extinción colectiva)
    // ====================================================================

    /**
     * CL-3 — Cierre de la empresa: extinción de TODOS los contratos activos en
     * una fecha común. La indemnización es la objetiva/colectiva (20 días/año,
     * tope 12 mensualidades, exenta de IRPF) — art. 51/52 ET — que ya calcula
     * {@link #computeSeverance} con el tipo {@code DISMISSAL_OBJECTIVE} (también
     * vale fuerza mayor, mismo cómputo). Reutiliza el cese individual validado.
     */
    private List<EmployeeRef> activeEmployeesAt(LocalDate ceseDate) {
        return jdbc.query("""
                SELECT DISTINCT e.id, e.full_name
                  FROM employees e
                  JOIN employment_contracts c
                    ON c.employee_id = e.id AND c.company_id = e.company_id
                 WHERE e.company_id = ?
                   AND c.status IN ('ACTIVE', 'SUSPENDED')
                   AND c.start_date <= ?
                   AND (c.end_date IS NULL OR c.end_date >= ?)
                 ORDER BY e.full_name
                """,
                (rs, n) -> new EmployeeRef(rs.getString("id"), rs.getString("full_name")),
                tenant.getCurrentCompanyId(), ceseDate, ceseDate);
    }

    private String closureType(String type) {
        // Por defecto, cese de empresa = extinción objetiva/colectiva (20 días/año).
        return StringUtils.hasText(type) ? type : "DISMISSAL_OBJECTIVE";
    }

    /** Vista previa SIN efectos: a quién afecta y la indemnización total. */
    public ClosureResult previewCompanyClosure(ClosureRequest r) {
        if (r.ceseDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha de cese requerida");
        }
        String type = closureType(r.type());
        List<EmployeeRef> emps = activeEmployeesAt(r.ceseDate());
        java.util.List<ClosureLine> lines = new java.util.ArrayList<>();
        BigDecimal totalSev = BigDecimal.ZERO;
        for (EmployeeRef e : emps) {
            ActiveContract c = loadActiveContract(e.id(), r.ceseDate());
            if (c == null) {
                lines.add(new ClosureLine(e.id(), e.name(), BigDecimal.ZERO, "Sin contrato activo a la fecha"));
                continue;
            }
            Severance sev = computeSeverance(type, c.grossSalary(), c.antiquityFrom(), r.ceseDate());
            totalSev = totalSev.add(sev.gross());
            lines.add(new ClosureLine(e.id(), e.name(), sev.gross(), null));
        }
        long failed = lines.stream().filter(l -> l.error() != null).count();
        return new ClosureResult(lines.size(), (int) (lines.size() - failed), (int) failed,
                totalSev.setScale(2, RoundingMode.HALF_UP), lines);
    }

    /**
     * Ejecuta el cese de empresa: termina TODOS los contratos activos a la fecha,
     * generando el finiquito + indemnización de cada uno. Todo en UNA transacción
     * (all-or-nothing): si un empleado falla, no se cierra a medias la empresa.
     */
    @Transactional
    public ClosureResult executeCompanyClosure(ClosureRequest r) {
        if (r.ceseDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Fecha de cese requerida");
        }
        String type = closureType(r.type());
        List<EmployeeRef> emps = activeEmployeesAt(r.ceseDate());
        if (emps.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No hay empleados con contrato activo a la fecha de cese.");
        }
        java.util.List<ClosureLine> lines = new java.util.ArrayList<>();
        BigDecimal totalSev = BigDecimal.ZERO;
        for (EmployeeRef e : emps) {
            TerminationResult res = execute(new TerminationRequest(
                    e.id(), r.ceseDate(), type, null, null,
                    StringUtils.hasText(r.notes()) ? r.notes() : "Cese de empresa"));
            BigDecimal sev = res.severance().gross();
            totalSev = totalSev.add(sev);
            lines.add(new ClosureLine(e.id(), e.name(), sev, null));
        }
        return new ClosureResult(lines.size(), lines.size(), 0,
                totalSev.setScale(2, RoundingMode.HALF_UP), lines);
    }

    public record EmployeeRef(String id, String name) {}
    public record ClosureRequest(LocalDate ceseDate, String type, String notes) {}
    public record ClosureLine(String employeeId, String employeeName,
                              BigDecimal severanceGross, String error) {}
    public record ClosureResult(int total, int ok, int failed,
                                BigDecimal totalSeverance, List<ClosureLine> lines) {}

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

        @PostMapping("/company-closure/preview")
        public ClosureResult previewClosure(@RequestBody ClosureRequest req) {
            return service.previewCompanyClosure(req);
        }

        @PostMapping("/company-closure/execute")
        public ClosureResult executeClosure(@RequestBody ClosureRequest req) {
            return service.executeCompanyClosure(req);
        }
    }
}
