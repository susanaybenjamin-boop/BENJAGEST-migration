package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.labor.ss.SsContributionRatesService;
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

    // Los tipos de cotización SS ya NO van a fuego aquí: se leen por año de
    // ss_contribution_rates (SsContributionRatesService), bloque PARAM-YEAR.

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final TaxRulesService taxRulesService;
    private final PayslipPdfGenerator pdfGenerator;
    private final PayslipJournalEntryService journalService;
    private final com.benjagest.backend.labor.ss.SsContributionRatesService ssRatesService;
    private final com.benjagest.backend.labor.irpf.IrpfRetentionService irpfService;
    private final com.benjagest.backend.settings.EmailSenderService emailSender;
    private final com.benjagest.backend.settings.CompanyDataService companyDataService;

    public PayslipService(JdbcTemplate jdbcTemplate,
                           TenantContext tenantContext,
                           TaxRulesService taxRulesService,
                           PayslipPdfGenerator pdfGenerator,
                           PayslipJournalEntryService journalService,
                           com.benjagest.backend.labor.ss.SsContributionRatesService ssRatesService,
                           com.benjagest.backend.labor.irpf.IrpfRetentionService irpfService,
                           com.benjagest.backend.settings.EmailSenderService emailSender,
                           com.benjagest.backend.settings.CompanyDataService companyDataService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.taxRulesService = taxRulesService;
        this.pdfGenerator = pdfGenerator;
        this.journalService = journalService;
        this.ssRatesService = ssRatesService;
        this.irpfService = irpfService;
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

    /**
     * Reporte de coste de empresa por empleado en un año: bruto anual
     * (suma de todas las nóminas del periodo) + SS a cargo de la empresa
     * (suma de las cuotas TC EMPLOYER_*). El coste total para la empresa
     * es la suma de ambos. Solo aparecen empleados con datos en el año.
     */
    public List<EmployerCostRow> employerCost(int year) {
        return jdbcTemplate.query("""
                SELECT e.id AS employee_id, e.full_name AS employee_name,
                       COALESCE(p.gross, 0) AS gross,
                       COALESCE(s.er, 0) AS employer_ss
                  FROM employees e
                  LEFT JOIN (
                        SELECT employee_id, SUM(gross_amount) AS gross
                          FROM payslips
                         WHERE company_id = ? AND period_year = ?
                         GROUP BY employee_id
                  ) p ON p.employee_id = e.id
                  LEFT JOIN (
                        SELECT employee_id, SUM(contribution_amount) AS er
                          FROM social_security_contributions
                         WHERE company_id = ? AND period_year = ?
                           AND contribution_type LIKE 'EMPLOYER%'
                         GROUP BY employee_id
                  ) s ON s.employee_id = e.id
                 WHERE e.company_id = ?
                   AND (p.gross IS NOT NULL OR s.er IS NOT NULL)
                 ORDER BY e.full_name
                """,
                (rs, n) -> {
                    BigDecimal gross = rs.getBigDecimal("gross");
                    BigDecimal er = rs.getBigDecimal("employer_ss");
                    return new EmployerCostRow(
                            rs.getString("employee_id"),
                            rs.getString("employee_name"),
                            gross, er, gross.add(er));
                },
                tenantContext.getCurrentCompanyId(), year,
                tenantContext.getCurrentCompanyId(), year,
                tenantContext.getCurrentCompanyId());
    }

    public record EmployerCostRow(
            String employeeId, String employeeName,
            BigDecimal grossTotal, BigDecimal employerSsTotal, BigDecimal costTotal
    ) {}

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
    /**
     * Cálculo puro de la nómina (sin persistir). Lo usan {@link #calculate}
     * (para guardar), {@link #preview} (vista previa) y el solver de objetivo
     * de sueldo. Lanza 409 si el empleado no tiene contrato activo.
     */
    private Computed compute(CalculateRequest req) {
        if (!StringUtils.hasText(req.employeeId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (req.year() < 2000 || req.year() > 2100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "year invalido");
        }
        if (req.month() < 1 || req.month() > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "month invalido");
        }

        ContractData contract = resolveActiveContract(req.employeeId(), req.year(), req.month());
        if (contract == null || contract.grossSalary == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El empleado seleccionado no tiene un contrato ACTIVO con salario que cubra "
                    + req.year() + "/" + String.format("%02d", req.month())
                    + ". Comprueba que has elegido el empleado correcto y que su contrato"
                    + " esté activo y empiece antes de ese mes.");
        }

        String type = StringUtils.hasText(req.payslipType()) ? req.payslipType() : "MONTHLY";
        boolean isExtra = "EXTRA_SUMMER".equals(type) || "EXTRA_CHRISTMAS".equals(type);

        // Prorrateo de pagas extras (art. 31 ET): 12 pagas -> anual/12; 14 pagas
        // (default legal) -> anual/(12+extras) y las extras como nóminas EXTRA_*.
        // Una nómina EXTRA es UNA mensualidad = anual/(12+nº pagas), siempre.
        int divisor = isExtra
                ? (12 + contract.annualBonuses)
                : (req.extraProratedOrDefault() ? 12 : (12 + contract.annualBonuses));

        // 1) Devengos: una línea por concepto salarial del contrato.
        List<SalaryConcept> concepts = loadSalaryConcepts(contract.id);
        java.util.List<PayslipLine> lines = new java.util.ArrayList<>();
        BigDecimal cotizableAnnual = BigDecimal.ZERO;
        BigDecimal taxableAnnual = BigDecimal.ZERO;
        if (!concepts.isEmpty()) {
            for (SalaryConcept c : concepts) {
                BigDecimal a = c.annual() == null ? BigDecimal.ZERO : c.annual();
                if (c.cotizes()) cotizableAnnual = cotizableAnnual.add(a);
                if (c.taxable()) taxableAnnual = taxableAnnual.add(a);
                lines.add(new PayslipLine(c.name(), c.kind(),
                        a.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP)));
            }
        } else {
            cotizableAnnual = contract.grossSalary;
            taxableAnnual = contract.grossSalary;
            lines.add(new PayslipLine("Salario bruto del periodo", "SALARY_BASE",
                    contract.grossSalary.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP)));
        }

        // Complementos extra de ESTA nómina (dietas, kilometraje, asistencia…):
        // importe del mes (no anual). Solo suman a la base SS/IRPF si cotizan/tributan.
        BigDecimal extraCotizable = BigDecimal.ZERO;
        BigDecimal extraTaxable = BigDecimal.ZERO;
        if (req.extraConcepts() != null) {
            for (ExtraConcept ec : req.extraConcepts()) {
                if (ec.name() == null || ec.name().isBlank()) continue;
                BigDecimal amt = ec.amount() == null ? BigDecimal.ZERO : ec.amount();
                lines.add(new PayslipLine(ec.name().trim(), "COMPLEMENT", amt));
                if (ec.cotizes() == null || ec.cotizes()) extraCotizable = extraCotizable.add(amt);
                if (ec.taxable() == null || ec.taxable()) extraTaxable = extraTaxable.add(amt);
            }
        }

        BigDecimal gross = lines.stream().map(PayslipLine::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // 2) Base de cotización a la SS = (cotizable anual)/12 (incluye prorrata,
        //    art. 147 LGSS) + extras cotizables del mes. EXTRA_* no cotizan aparte.
        BigDecimal cotizationBase;
        if ("MONTHLY".equals(type)) {
            cotizationBase = cotizableAnnual.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP)
                    .add(extraCotizable);
        } else if ("EXTRA_SUMMER".equals(type) || "EXTRA_CHRISTMAS".equals(type)) {
            cotizationBase = extraCotizable;
        } else {
            cotizationBase = gross;
        }
        SsContributionRatesService.Rates rates = ssRatesService.ratesForYear(req.year());
        // Topes de cotización: limitar la base a [mínimo, máximo] del año.
        if (rates.baseMaxMonthly() != null && rates.baseMaxMonthly().signum() > 0
                && cotizationBase.compareTo(rates.baseMaxMonthly()) > 0) {
            cotizationBase = rates.baseMaxMonthly();
        }
        if (rates.baseMinMonthly() != null && rates.baseMinMonthly().signum() > 0
                && cotizationBase.signum() > 0
                && cotizationBase.compareTo(rates.baseMinMonthly()) < 0) {
            cotizationBase = rates.baseMinMonthly();
        }
        BigDecimal atEp = contract.atEpPercent != null && contract.atEpPercent.signum() >= 0
                ? contract.atEpPercent : rates.defaultAtEp();
        SsBreakdown ss = computeSs(cotizationBase, atEp, rates);
        BigDecimal ssEmployee = ss.employeeTotal();

        // 3) IRPF. Si el empleado tiene datos del modelo 145, se calcula el
        //    tipo con el motor de retención (como A3); si no, el % fijo del
        //    contrato; en último término, estimación por tramos.
        BigDecimal irpfPct;
        com.benjagest.backend.labor.irpf.IrpfRetentionService.Modelo145 m145 =
                irpfService.findForEmployee(req.employeeId());
        if (m145 != null) {
            BigDecimal eeRate = rates.eeCommon().add(rates.eeUnemployment())
                    .add(rates.eeTraining()).add(rates.eeMei());
            BigDecimal annualSs = cotizableAnnual.multiply(eeRate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            irpfPct = irpfService.computeRate(req.year(), taxableAnnual, annualSs, m145);
        } else if (contract.irpfPercent != null && contract.irpfPercent.signum() > 0) {
            irpfPct = contract.irpfPercent;
        } else {
            irpfPct = computeIrpfPercent(contract.grossSalary, req.year());
        }
        BigDecimal taxableDevengo = taxableAnnual.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP)
                .add(extraTaxable);
        BigDecimal irpf = taxableDevengo.multiply(irpfPct)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        BigDecimal otherDeductions = req.otherDeductions() != null
                ? req.otherDeductions() : BigDecimal.ZERO;

        // 4) Líquido
        BigDecimal net = gross.subtract(ssEmployee).subtract(irpf).subtract(otherDeductions);

        return new Computed(contract.id, type, lines, gross, cotizationBase, ss,
                ssEmployee, irpf, irpfPct, otherDeductions, net);
    }

    /** Vista previa de una nómina sin guardarla. */
    public PreviewResult preview(CalculateRequest req) {
        Computed c = compute(req);
        BigDecimal employerTotal = c.ss().employerTotal();
        List<LineView> dev = c.lines().stream()
                .map(l -> new LineView(l.name(), l.amount())).toList();
        return new PreviewResult(c.gross(), c.cotizationBase(), c.ssEmployee(), c.irpf(),
                c.irpfPct(), c.otherDeductions(), c.net(), employerTotal,
                c.gross().add(employerTotal), dev);
    }

    public record TargetRequest(
            String employeeId, int year, int month, String payslipType,
            Boolean includeExtraProrated, String mode, BigDecimal target,
            List<ExtraConcept> extraConcepts) {}

    public record TargetResult(BigDecimal plus, PreviewResult preview) {}

    /**
     * Calcula el "plus" (mejora voluntaria del mes) necesario para llegar a un
     * sueldo objetivo: BRUTO (resta directa) o NETO (modelo lineal con el % de
     * IRPF del contrato). Devuelve el importe del plus y la vista previa con él
     * aplicado, para que el asesor lo añada como complemento.
     */
    public TargetResult solveTarget(TargetRequest tr) {
        if (tr.target() == null || tr.target().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importe objetivo inválido");
        }
        CalculateRequest base = new CalculateRequest(tr.employeeId(), tr.year(), tr.month(),
                tr.payslipType(), tr.includeExtraProrated(), null, null, tr.extraConcepts());
        Computed c = compute(base);

        BigDecimal plus;
        if ("GROSS".equalsIgnoreCase(tr.mode())) {
            plus = tr.target().subtract(c.gross());
        } else {
            // NETO: cada € de mejora (cotiza+tributa) deja (1 - eeRate - irpfFrac)
            // de neto. Modelo lineal con el % IRPF del contrato.
            SsContributionRatesService.Rates r = ssRatesService.ratesForYear(tr.year());
            BigDecimal eeRate = r.eeCommon().add(r.eeUnemployment()).add(r.eeTraining()).add(r.eeMei())
                    .divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal irpfFrac = c.irpfPct().divide(BigDecimal.valueOf(100), 6, RoundingMode.HALF_UP);
            BigDecimal factor = BigDecimal.ONE.subtract(eeRate).subtract(irpfFrac);
            if (factor.signum() <= 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Los tipos no permiten el cálculo inverso (SS+IRPF >= 100%).");
            }
            plus = tr.target().subtract(c.net()).divide(factor, 2, RoundingMode.HALF_UP);
        }
        if (plus.signum() < 0) plus = BigDecimal.ZERO;
        plus = plus.setScale(2, RoundingMode.HALF_UP);

        java.util.List<ExtraConcept> withPlus = new java.util.ArrayList<>();
        if (tr.extraConcepts() != null) withPlus.addAll(tr.extraConcepts());
        withPlus.add(new ExtraConcept("Mejora voluntaria", plus, true, true));
        CalculateRequest full = new CalculateRequest(tr.employeeId(), tr.year(), tr.month(),
                tr.payslipType(), tr.includeExtraProrated(), null, null, withPlus);
        return new TargetResult(plus, preview(full));
    }

    /**
     * Calcula y persiste (o actualiza) una nómina del empleado en el mes/año.
     */
    @Transactional
    public PayslipView calculate(CalculateRequest req) {
        Computed cmp = compute(req);
        String type = cmp.type();
        String contractId = cmp.contractId();
        java.util.List<PayslipLine> lines = cmp.lines();
        BigDecimal gross = cmp.gross();
        BigDecimal cotizationBase = cmp.cotizationBase();
        SsBreakdown ss = cmp.ss();
        BigDecimal ssEmployee = cmp.ssEmployee();
        BigDecimal irpf = cmp.irpf();
        BigDecimal otherDeductions = cmp.otherDeductions();
        BigDecimal net = cmp.net();

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

        String id;
        if (existingId != null) {
            jdbcTemplate.update("""
                    UPDATE payslips
                       SET contract_id = ?,
                           gross_amount = ?, ss_employee_amount = ?, irpf_amount = ?,
                           other_deductions = ?, net_amount = ?,
                           status = 'CALCULATED', notes = ?
                     WHERE id = ? AND company_id = ?
                    """,
                    contractId, gross, ssEmployee, irpf,
                    otherDeductions, net,
                    req.notes(),
                    existingId, tenantContext.getCurrentCompanyId());
            id = existingId;
        } else {
            id = UUID.randomUUID().toString();
            jdbcTemplate.update("""
                    INSERT INTO payslips (
                        id, company_id, employee_id, contract_id,
                        period_year, period_month, payslip_type,
                        gross_amount, ss_employee_amount, irpf_amount,
                        other_deductions, net_amount, status, notes
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'CALCULATED', ?)
                    """,
                    id, tenantContext.getCurrentCompanyId(),
                    req.employeeId(), contractId,
                    req.year(), req.month(), type,
                    gross, ssEmployee, irpf,
                    otherDeductions, net,
                    req.notes());
        }

        // Líneas de devengo (snapshot de los conceptos de este recibo).
        try {
            persistPayslipLines(id, lines);
        } catch (Exception ex) {
            // El PDF degrada a línea única si fallara; no bloquea la nómina.
        }

        // La nómina alimenta la tabla de cuotas TC (fuente de verdad de
        // la SS empresa) — solo para nóminas mensuales ordinarias. Las
        // pagas extra cotizan prorrateadas y se afinarán en otro slice.
        if ("MONTHLY".equals(type)) {
            // La tabla TC y el asiento son derivados de la nómina: si
            // algo falla (falta plan contable / ejercicio cerrado), la
            // nómina se calcula igual. Por eso van dentro de try/catch y
            // PayslipJournalEntryService NO es @Transactional (si lo
            // fuera, una excepción suya marcaría la transacción de la
            // nómina como rollback-only y reventaría el cálculo).
            try {
                upsertContributions(req.employeeId(), req.year(), req.month(), cotizationBase, ss);
                String empName = employeeName(req.employeeId());
                journalService.createAccrual(new PayslipJournalEntryService.PayslipAccrual(
                        id, req.employeeId(), empName, req.year(), req.month(), gross, irpf),
                        null);
            } catch (Exception ex) {
                // No bloquea la nómina.
            }
        }
        return findById(id);
    }

    private String employeeName(String employeeId) {
        return jdbcTemplate.query("""
                SELECT full_name FROM employees WHERE id = ? AND company_id = ?
                """, (rs, n) -> rs.getString("full_name"),
                employeeId, tenantContext.getCurrentCompanyId())
                .stream().findFirst().orElse(null);
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
        PayslipView view = findById(id);
        // Asiento de pago (465 → 572). Solo nóminas mensuales ordinarias.
        if ("MONTHLY".equals(view.payslipType())) {
            try {
                journalService.createPayment(new PayslipJournalEntryService.PayslipAccrual(
                        view.id(), view.employeeId(), view.employeeName(),
                        view.periodYear(), view.periodMonth(),
                        view.grossAmount(), view.irpfAmount()),
                        view.paidAt(), null);
            } catch (Exception ex) {
                // El asiento es independiente; si falla, la nómina queda pagada igual.
            }
        }
        return view;
    }

    @Transactional
    public void delete(String id) {
        // Revertir asientos (devengo + pago) antes de borrar la nómina.
        try {
            journalService.reverseAll(id);
        } catch (Exception ex) {
            // Si no había asientos, seguimos con el borrado.
        }
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
                       gross_salary, annual_bonuses, irpf_percent, at_ep_percent
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
                    d.atEpPercent = rs.getBigDecimal("at_ep_percent");
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

    /**
     * Desglose de cotización a la SS de un periodo. Cada importe es el
     * resultado de aplicar el tipo correspondiente sobre la base (= bruto
     * del periodo, simplificación sin topes).
     */
    private record SsBreakdown(
            BigDecimal eeCommon, BigDecimal eeUnemployment, BigDecimal eeTraining, BigDecimal eeMei,
            BigDecimal erCommon, BigDecimal erUnemployment, BigDecimal erFogasa,
            BigDecimal erTraining, BigDecimal erMei, BigDecimal erAtEp) {
        BigDecimal employeeTotal() {
            return eeCommon.add(eeUnemployment).add(eeTraining).add(eeMei);
        }
        BigDecimal employerTotal() {
            return erCommon.add(erUnemployment).add(erFogasa).add(erTraining).add(erMei).add(erAtEp);
        }
    }

    /** Resultado del cálculo puro (sin persistir). */
    private record Computed(
            String contractId, String type, List<PayslipLine> lines,
            BigDecimal gross, BigDecimal cotizationBase, SsBreakdown ss,
            BigDecimal ssEmployee, BigDecimal irpf, BigDecimal irpfPct,
            BigDecimal otherDeductions, BigDecimal net) {}

    /** Vista previa de una nómina (no se guarda). */
    public record PreviewResult(
            BigDecimal gross, BigDecimal cotizationBase, BigDecimal ssEmployee,
            BigDecimal irpf, BigDecimal irpfPct, BigDecimal otherDeductions,
            BigDecimal net, BigDecimal employerTotal, BigDecimal employerCost,
            List<LineView> devengos) {}

    public record LineView(String concept, BigDecimal amount) {}

    private static BigDecimal pct(BigDecimal base, BigDecimal percent) {
        return base.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private SsBreakdown computeSs(BigDecimal base, BigDecimal atEpPercent,
                                  SsContributionRatesService.Rates r) {
        return new SsBreakdown(
                pct(base, r.eeCommon()), pct(base, r.eeUnemployment()),
                pct(base, r.eeTraining()), pct(base, r.eeMei()),
                pct(base, r.erCommon()), pct(base, r.erUnemployment()),
                pct(base, r.erFogasa()), pct(base, r.erTraining()),
                pct(base, r.erMei()), pct(base, atEpPercent));
    }

    /**
     * Escribe (o actualiza) las filas de {@code social_security_contributions}
     * derivadas de la nómina, una por tipo de cotización. Solo toca filas en
     * estado DRAFT: si una cuota ya está FILED/PAID (presentada al sistema
     * RED), se respeta y no se sobrescribe.
     */
    private void upsertContributions(String employeeId, int year, int month,
                                      BigDecimal base, SsBreakdown ss) {
        upsertContribution(employeeId, year, month, "EMPLOYEE_COMMON", base, ss.eeCommon());
        upsertContribution(employeeId, year, month, "EMPLOYEE_UNEMPLOYMENT", base, ss.eeUnemployment());
        upsertContribution(employeeId, year, month, "EMPLOYEE_TRAINING", base, ss.eeTraining());
        upsertContribution(employeeId, year, month, "EMPLOYEE_MEI", base, ss.eeMei());
        upsertContribution(employeeId, year, month, "EMPLOYER_COMMON", base, ss.erCommon());
        upsertContribution(employeeId, year, month, "EMPLOYER_UNEMPLOYMENT", base, ss.erUnemployment());
        upsertContribution(employeeId, year, month, "EMPLOYER_FOGASA", base, ss.erFogasa());
        upsertContribution(employeeId, year, month, "EMPLOYER_TRAINING", base, ss.erTraining());
        upsertContribution(employeeId, year, month, "EMPLOYER_MEI", base, ss.erMei());
        upsertContribution(employeeId, year, month, "EMPLOYER_AT_EP", base, ss.erAtEp());
    }

    private void upsertContribution(String employeeId, int year, int month,
                                     String type, BigDecimal base, BigDecimal amount) {
        String companyId = tenantContext.getCurrentCompanyId();
        String existing = jdbcTemplate.query("""
                SELECT id FROM social_security_contributions
                 WHERE company_id = ? AND employee_id = ?
                   AND period_year = ? AND period_month = ?
                   AND contribution_type = ? AND status = 'DRAFT'
                """, (rs, n) -> rs.getString("id"),
                companyId, employeeId, year, month, type)
                .stream().findFirst().orElse(null);
        if (existing != null) {
            jdbcTemplate.update("""
                    UPDATE social_security_contributions
                       SET base_amount = ?, contribution_amount = ?
                     WHERE id = ?
                    """, base, amount, existing);
            return;
        }
        // Si ya existe pero NO en DRAFT (FILED/PAID), no tocamos nada.
        Integer nonDraft = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM social_security_contributions
                 WHERE company_id = ? AND employee_id = ?
                   AND period_year = ? AND period_month = ?
                   AND contribution_type = ?
                """, Integer.class,
                companyId, employeeId, year, month, type);
        if (nonDraft != null && nonDraft > 0) return;
        jdbcTemplate.update("""
                INSERT INTO social_security_contributions
                       (id, company_id, employee_id, period_year, period_month,
                        contribution_type, base_amount, contribution_amount, status, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', NOW())
                """,
                UUID.randomUUID().toString(), companyId, employeeId,
                year, month, type, base, amount);
    }

    /** Concepto salarial del contrato (leído de contract_salary_items). */
    private record SalaryConcept(String name, String kind, BigDecimal annual,
                                  boolean cotizes, boolean taxable) {}

    /** Línea de devengo calculada para una nómina. */
    private record PayslipLine(String name, String kind, BigDecimal amount) {}

    private List<SalaryConcept> loadSalaryConcepts(String contractId) {
        return jdbcTemplate.query("""
                SELECT concept_name, kind, annual_amount, cotizes, taxable
                  FROM contract_salary_items
                 WHERE contract_id = ? AND company_id = ?
                 ORDER BY sort_order, concept_name
                """,
                (rs, n) -> new SalaryConcept(
                        rs.getString("concept_name"), rs.getString("kind"),
                        rs.getBigDecimal("annual_amount"),
                        rs.getBoolean("cotizes"), rs.getBoolean("taxable")),
                contractId, tenantContext.getCurrentCompanyId());
    }

    private void persistPayslipLines(String payslipId, List<PayslipLine> lines) {
        jdbcTemplate.update("DELETE FROM payslip_lines WHERE payslip_id = ? AND company_id = ?",
                payslipId, tenantContext.getCurrentCompanyId());
        int order = 0;
        for (PayslipLine l : lines) {
            jdbcTemplate.update("""
                    INSERT INTO payslip_lines (id, company_id, payslip_id, concept_name, kind, amount, sort_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), tenantContext.getCurrentCompanyId(), payslipId,
                    l.name(), l.kind() == null ? "COMPLEMENT" : l.kind(), l.amount(), order++);
        }
    }

    private static class ContractData {
        String id;
        String contractType;
        LocalDate startDate;
        LocalDate endDate;
        BigDecimal grossSalary;
        int annualBonuses;
        BigDecimal irpfPercent;
        BigDecimal atEpPercent;
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
            Boolean includeExtraProrated, BigDecimal otherDeductions, String notes,
            List<ExtraConcept> extraConcepts
    ) {
        public boolean extraProratedOrDefault() {
            // Default legal: 14 pagas (NO prorrateado) salvo que el convenio
            // lo permita y se marque la casilla. Art. 31 ET.
            return includeExtraProrated != null && includeExtraProrated;
        }
    }

    /** Complemento de devengo añadido en la propia nómina (importe del mes,
     *  no anual): dietas, kilometraje, plus asistencia, etc. */
    public record ExtraConcept(String name, BigDecimal amount,
                                Boolean cotizes, Boolean taxable) {}

    public record MarkPaidRequest(LocalDate paidAt) {}

    @RestController
    @RequestMapping("/api/labor/payslips")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
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

        @PostMapping("/preview")
        public PreviewResult preview(@RequestBody CalculateRequest req) {
            return service.preview(req);
        }

        @PostMapping("/solve-target")
        public TargetResult solveTarget(@RequestBody TargetRequest req) {
            return service.solveTarget(req);
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

        @GetMapping("/employer-cost")
        public List<EmployerCostRow> employerCost(
                @RequestParam(value = "year", required = false) Integer year) {
            return service.employerCost(year == null ? Year.now().getValue() : year);
        }

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
