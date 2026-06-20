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
    private final com.benjagest.backend.labor.ss.SsGroupBasesService ssGroupBasesService;
    private final com.benjagest.backend.labor.irpf.IrpfRetentionService irpfService;
    private final com.benjagest.backend.settings.EmailSenderService emailSender;
    private final com.benjagest.backend.settings.CompanyDataService companyDataService;
    private final EmployeeVacationService vacationService;
    private final com.benjagest.backend.realtime.RealtimeService realtime;

    public PayslipService(JdbcTemplate jdbcTemplate,
                           TenantContext tenantContext,
                           TaxRulesService taxRulesService,
                           PayslipPdfGenerator pdfGenerator,
                           PayslipJournalEntryService journalService,
                           com.benjagest.backend.labor.ss.SsContributionRatesService ssRatesService,
                           com.benjagest.backend.labor.ss.SsGroupBasesService ssGroupBasesService,
                           com.benjagest.backend.labor.irpf.IrpfRetentionService irpfService,
                           com.benjagest.backend.settings.EmailSenderService emailSender,
                           com.benjagest.backend.settings.CompanyDataService companyDataService,
                           EmployeeVacationService vacationService,
                           com.benjagest.backend.realtime.RealtimeService realtime) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.taxRulesService = taxRulesService;
        this.pdfGenerator = pdfGenerator;
        this.journalService = journalService;
        this.ssRatesService = ssRatesService;
        this.ssGroupBasesService = ssGroupBasesService;
        this.irpfService = irpfService;
        this.emailSender = emailSender;
        this.companyDataService = companyDataService;
        this.vacationService = vacationService;
        this.realtime = realtime;
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
                       p.delivered_at, p.delivery_method, p.acknowledged_at,
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
        // Finiquito: los conceptos vienen calculados como extraConcepts (salario
        // de días trabajados, vacaciones no disfrutadas, prorrata de pagas extra).
        // No se añaden los conceptos del contrato como líneas; los totales anuales
        // SÍ se acumulan para calcular el tipo de IRPF (rate anual normal).
        boolean isSettlement = "SETTLEMENT".equals(type);

        // Prorrateo de pagas extras (art. 31 ET): 12 pagas -> anual/12; 14 pagas
        // (default legal) -> anual/(12+extras) y las extras como nóminas EXTRA_*.
        // Una nómina EXTRA es UNA mensualidad = anual/(12+nº pagas), siempre.
        // Prorrateo: lo manda el request (casilla) y si no, el del contrato.
        boolean prorated = req.includeExtraProrated() != null
                ? req.includeExtraProrated() : contract.extrasProrated;
        int divisor = isExtra
                ? (12 + contract.annualBonuses)
                : (prorated ? 12 : (12 + contract.annualBonuses));

        // 1) Devengos: una línea por concepto salarial del contrato.
        List<SalaryConcept> concepts = loadSalaryConcepts(contract.id);
        java.util.List<PayslipLine> lines = new java.util.ArrayList<>();
        BigDecimal cotizableAnnual = BigDecimal.ZERO; // base SS + tipo IRPF (anual completo)
        BigDecimal taxableAnnual = BigDecimal.ZERO;   // tipo IRPF (anual completo)
        BigDecimal taxableDevengo = BigDecimal.ZERO;  // tributable abonado ESTE periodo
        // Nombres de los complementos recurrentes simulados: REEMPLAZAN al
        // concepto del contrato con el mismo nombre (no se suman), para que el
        // solver de objetivo no cuente dos veces la "Mejora voluntaria" ya
        // guardada en el contrato.
        java.util.Set<String> recurringNames = new java.util.HashSet<>();
        if (req.recurringConcepts() != null) {
            for (ExtraConcept rc : req.recurringConcepts()) {
                if (rc.name() != null && !rc.name().isBlank()) recurringNames.add(rc.name().trim());
            }
        }
        if (!concepts.isEmpty()) {
            for (SalaryConcept c : concepts) {
                if (c.name() != null && recurringNames.contains(c.name().trim())) continue;
                boolean isBase = "SALARY_BASE".equals(c.kind());
                BigDecimal a = c.annual() == null ? BigDecimal.ZERO : c.annual();
                // Totales anuales para el tipo IRPF y la base SS: SIEMPRE completos
                // (base + complementos), también en pagas extra.
                if (c.cotizes()) cotizableAnnual = cotizableAnnual.add(a);
                if (c.taxable()) taxableAnnual = taxableAnnual.add(a);
                // El finiquito no añade líneas del contrato (las trae como
                // extraConcepts ya prorrateadas); solo usamos el anual para el tipo.
                if (isSettlement) continue;
                // En pagas extra solo se abona el salario base; los complementos
                // (mejoras, plus mensuales) no se incluyen en la extra.
                if (isExtra && !isBase) continue;
                // El salario base se prorratea entre las N pagas; los complementos
                // se cobran en 12 mensualidades (importe anual/12), no por pagas.
                int periodDivisor = isBase ? divisor : 12;
                BigDecimal lineAmount = a.divide(BigDecimal.valueOf(periodDivisor), 2, RoundingMode.HALF_UP);
                // En la paga extra el devengo del salario base se identifica como
                // gratificación extraordinaria (verano/Navidad), no "Salario base".
                String lineName = (isExtra && isBase) ? extraPagaLabel(type) : c.name();
                lines.add(new PayslipLine(lineName, c.kind(), lineAmount));
                if (c.taxable()) taxableDevengo = taxableDevengo.add(lineAmount);
            }
        } else {
            cotizableAnnual = contract.grossSalary;
            taxableAnnual = contract.grossSalary;
            if (!isSettlement) {
                BigDecimal base = contract.grossSalary.divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
                lines.add(new PayslipLine(isExtra ? extraPagaLabel(type) : "Salario bruto del periodo",
                        "SALARY_BASE", base));
                taxableDevengo = base;
            }
        }

        // Complementos recurrentes simulados (solver de objetivo): se tratan
        // como un complemento mensual del contrato -> anualizan en la base de
        // cotización y en el tipo IRPF (importe anual = mensual x 12).
        if (req.recurringConcepts() != null && !isExtra) {
            for (ExtraConcept rc : req.recurringConcepts()) {
                if (rc.name() == null || rc.name().isBlank()) continue;
                BigDecimal amt = rc.amount() == null ? BigDecimal.ZERO : rc.amount();
                BigDecimal annual = amt.multiply(BigDecimal.valueOf(12));
                boolean cot = rc.cotizes() == null || rc.cotizes();
                boolean tax = rc.taxable() == null || rc.taxable();
                if (cot) cotizableAnnual = cotizableAnnual.add(annual);
                if (tax) taxableAnnual = taxableAnnual.add(annual);
                lines.add(new PayslipLine(rc.name().trim(), "COMPLEMENT", amt));
                if (tax) taxableDevengo = taxableDevengo.add(amt);
            }
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
        } else if ("EXTRA_SUMMER".equals(type) || "EXTRA_CHRISTMAS".equals(type) || isSettlement) {
            // Pagas extra y finiquito: solo cotizan los conceptos marcados como
            // cotizables (la prorrata de extras del finiquito ya cotizó mes a mes).
            cotizationBase = extraCotizable;
        } else {
            cotizationBase = gross;
        }
        SsContributionRatesService.Rates rates = ssRatesService.ratesForYear(req.year());
        // Topes de cotización por GRUPO de cotización (V121/V122), leídos de la
        // tabla editable por año (no-code): base mínima del grupo del contrato
        // (default grupo 7) + tope máximo común. Si no hay tabla de grupos o el
        // contrato no tiene grupo, se mantiene el tope global de
        // ss_contribution_rates (comportamiento anterior).
        // NOTA (refinamientos pendientes — paso 4): para grupos 1-3 con base por
        // debajo de su mínimo (raro, p.ej. tiempo parcial) la ley usa el "tope
        // mínimo común" en las contingencias PROFESIONALES (desglose BCCC/BCCP);
        // y los grupos 8-11 (base diaria) + el tiempo parcial requieren cálculo
        // específico. Aquí se aplica base única [mín grupo, máx común], correcta
        // para la práctica totalidad de los casos a jornada completa.
        BigDecimal[] caps = resolveGroupCaps(req.year(), contract.ssContributionGroup);
        BigDecimal minCap = caps != null ? caps[0] : rates.baseMinMonthly();
        BigDecimal maxCap = caps != null ? caps[1] : rates.baseMaxMonthly();
        if (maxCap != null && maxCap.signum() > 0
                && cotizationBase.compareTo(maxCap) > 0) {
            cotizationBase = maxCap;
        }
        if (minCap != null && minCap.signum() > 0
                && cotizationBase.signum() > 0
                && cotizationBase.compareTo(minCap) < 0) {
            cotizationBase = minCap;
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
            // La SS anual para el motor de IRPF debe calcularse sobre la base ANUAL
            // ACOTADA por los mismos topes (mín del grupo / máx común), no sobre el
            // cotizable bruto: para salarios fuera del tope, usar el bruto desviaba
            // la retención (encontrado en auditoría 2026-06-16).
            BigDecimal annualBaseForSs = cotizableAnnual;
            if (maxCap != null && maxCap.signum() > 0) {
                BigDecimal annualMax = maxCap.multiply(BigDecimal.valueOf(12));
                if (annualBaseForSs.compareTo(annualMax) > 0) annualBaseForSs = annualMax;
            }
            if (minCap != null && minCap.signum() > 0
                    && annualBaseForSs.signum() > 0) {
                BigDecimal annualMin = minCap.multiply(BigDecimal.valueOf(12));
                if (annualBaseForSs.compareTo(annualMin) < 0) annualBaseForSs = annualMin;
            }
            BigDecimal annualSs = annualBaseForSs.multiply(eeRate)
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal legalMin = irpfService.computeRate(req.year(), taxableAnnual, annualSs, m145);
            // IRPF-VOL — Retención VOLUNTARIA del contrato: solo se aplica si
            // SUPERA el mínimo legal calculado (art. 88.5 RIRPF). Nunca por
            // debajo (el tipo calculado es el mínimo de obligado cumplimiento).
            irpfPct = (contract.irpfPercent != null && contract.irpfPercent.compareTo(legalMin) > 0)
                    ? contract.irpfPercent : legalMin;
        } else if (contract.irpfPercent != null && contract.irpfPercent.signum() > 0) {
            irpfPct = contract.irpfPercent;
        } else {
            irpfPct = computeIrpfPercent(contract.grossSalary, req.year());
        }
        // taxableDevengo ya acumula el tributable de los conceptos de este periodo
        // (base prorrateada + complementos/12); sumamos los extras del mes.
        taxableDevengo = taxableDevengo.add(extraTaxable);
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
        return toPreview(compute(req));
    }

    private PreviewResult toPreview(Computed c) {
        BigDecimal employerTotal = c.ss().employerTotal();
        List<LineView> dev = c.lines().stream()
                .map(l -> new LineView(l.name(), l.amount())).toList();
        return new PreviewResult(c.gross(), c.cotizationBase(), c.ssEmployee(), c.irpf(),
                c.irpfPct(), c.otherDeductions(), c.net(), employerTotal,
                c.gross().add(employerTotal), dev);
    }

    /** Nombre del complemento de mejora que el solver de objetivo guarda en el
     *  contrato (recurrente). Debe coincidir con la etiqueta usada por la UI. */
    public static final String MEJORA_CONCEPT = "Mejora voluntaria";

    public record TargetRequest(
            String employeeId, int year, int month, String payslipType,
            Boolean includeExtraProrated, String mode, BigDecimal target,
            List<ExtraConcept> extraConcepts) {}

    public record TargetResult(BigDecimal plus, PreviewResult preview) {}

    /**
     * Calcula la "mejora voluntaria" MENSUAL recurrente necesaria para llegar a
     * un sueldo objetivo BRUTO o NETO. La mejora se trata como un complemento
     * del contrato (anualiza en base SS e IRPF): en NETO el tipo de retención
     * sube con la propia mejora, así que la relación no es lineal y se resuelve
     * por bisección. Devuelve el importe mensual y la vista previa con él
     * aplicado, para que la UI lo guarde como complemento del contrato.
     */
    public TargetResult solveTarget(TargetRequest tr) {
        if (tr.target() == null || tr.target().signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Importe objetivo inválido");
        }
        java.util.function.Function<BigDecimal, Computed> withMejora = x -> {
            java.util.List<ExtraConcept> rec = java.util.List.of(
                    new ExtraConcept(MEJORA_CONCEPT, x, true, true));
            return compute(new CalculateRequest(tr.employeeId(), tr.year(), tr.month(),
                    tr.payslipType(), tr.includeExtraProrated(), null, null,
                    tr.extraConcepts(), rec));
        };

        BigDecimal plus;
        if ("GROSS".equalsIgnoreCase(tr.mode())) {
            // El bruto crece exactamente en el importe de la mejora.
            plus = tr.target().subtract(withMejora.apply(BigDecimal.ZERO).gross());
        } else {
            // NETO: el neto crece de forma monótona con la mejora; bisección.
            BigDecimal lo = BigDecimal.ZERO;
            BigDecimal hi = tr.target().max(BigDecimal.valueOf(1));
            int guard = 0;
            while (withMejora.apply(hi).net().compareTo(tr.target()) < 0 && guard++ < 40) {
                hi = hi.multiply(BigDecimal.valueOf(2));
            }
            for (int i = 0; i < 40; i++) {
                BigDecimal mid = lo.add(hi).divide(BigDecimal.valueOf(2), 6, RoundingMode.HALF_UP);
                if (withMejora.apply(mid).net().compareTo(tr.target()) < 0) lo = mid; else hi = mid;
            }
            plus = lo.add(hi).divide(BigDecimal.valueOf(2), 2, RoundingMode.HALF_UP);
        }
        if (plus.signum() < 0) plus = BigDecimal.ZERO;
        plus = plus.setScale(2, RoundingMode.HALF_UP);

        return new TargetResult(plus, toPreview(withMejora.apply(plus)));
    }

    // ===== CV-1 Finiquito / liquidación =====

    public record SettlementRequest(
            String employeeId, int year, int month, int ceseDay,
            BigDecimal vacationDays, String extrasAccrual,
            BigDecimal otherDeductions, String notes) {}

    /**
     * Construye los conceptos de un finiquito para revisar/editar antes de
     * generar el recibo (nómina tipo SETTLEMENT):
     *  - Salario de los días trabajados del mes del cese (÷ días naturales).
     *  - Vacaciones no disfrutadas = días naturales × (salario anual ÷ 365).
     *    N4 (2026-06-17): salario diario = anual/365 (día natural), criterio
     *    legal/estándar para valorar vacaciones no disfrutadas — igual que A3Nom,
     *    INEAF y CONTENDO, y coherente con la indemnización (TerminationService,
     *    también /365). El /30 (= anual/360) es el salario diario a efectos de
     *    COTIZACIÓN, no el de valoración de vacaciones en finiquito. Cotiza y tributa.
     *  - Prorrata de pagas extra devengadas no cobradas (si no prorrateadas):
     *    devengo semestral (verano ene–jun / Navidad jul–dic) o anual. Tributa
     *    IRPF; NO cotiza de nuevo (ya cotizó prorrateada mes a mes).
     */
    public List<ExtraConcept> settlementConcepts(SettlementRequest r) {
        ContractData c = resolveActiveContract(r.employeeId(), r.year(), r.month());
        if (c == null || c.grossSalary == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El empleado no tiene un contrato activo en ese periodo.");
        }
        int daysInMonth = java.time.YearMonth.of(r.year(), r.month()).lengthOfMonth();
        int worked = Math.max(1, Math.min(r.ceseDay() <= 0 ? daysInMonth : r.ceseDay(), daysInMonth));
        int divisor = c.extrasProrated ? 12 : (12 + c.annualBonuses);
        BigDecimal workedFactor = BigDecimal.valueOf(worked)
                .divide(BigDecimal.valueOf(daysInMonth), 8, RoundingMode.HALF_UP);

        java.util.List<ExtraConcept> out = new java.util.ArrayList<>();
        List<SalaryConcept> concepts = loadSalaryConcepts(c.id);
        BigDecimal baseAnnual = c.grossSalary;
        if (!concepts.isEmpty()) {
            for (SalaryConcept sc : concepts) {
                boolean isBase = "SALARY_BASE".equals(sc.kind());
                BigDecimal annual = sc.annual() == null ? BigDecimal.ZERO : sc.annual();
                if (isBase) baseAnnual = annual;
                BigDecimal monthly = annual.divide(BigDecimal.valueOf(isBase ? divisor : 12), 8, RoundingMode.HALF_UP);
                BigDecimal amt = monthly.multiply(workedFactor).setScale(2, RoundingMode.HALF_UP);
                out.add(new ExtraConcept(sc.name() + " (" + worked + " días)", amt, sc.cotizes(), sc.taxable()));
            }
        } else {
            BigDecimal monthly = c.grossSalary.divide(BigDecimal.valueOf(divisor), 8, RoundingMode.HALF_UP);
            out.add(new ExtraConcept("Salario (" + worked + " días)",
                    monthly.multiply(workedFactor).setScale(2, RoundingMode.HALF_UP), true, true));
        }

        // Vacaciones no disfrutadas. Si no se indican días, se calculan solos:
        // devengadas en el año hasta el cese (proporcional) − disfrutadas
        // (registro de vacaciones). Con días indicados (>=0) se respeta el valor.
        BigDecimal vacDays;
        if (r.vacationDays() != null && r.vacationDays().signum() >= 0) {
            vacDays = r.vacationDays();
        } else {
            java.time.LocalDate cese = java.time.LocalDate.of(r.year(), r.month(), worked);
            java.time.LocalDate yearStart = java.time.LocalDate.of(r.year(), 1, 1);
            java.time.LocalDate workStart = (c.startDate != null && c.startDate.isAfter(yearStart))
                    ? c.startDate : yearStart;
            long diasTrabajadosAnio = java.time.temporal.ChronoUnit.DAYS.between(workStart, cese) + 1;
            int diasAnio = java.time.Year.of(r.year()).length();
            BigDecimal devengadas = BigDecimal.valueOf(c.vacationDays)
                    .multiply(BigDecimal.valueOf(diasTrabajadosAnio))
                    .divide(BigDecimal.valueOf(diasAnio), 1, RoundingMode.HALF_UP);
            BigDecimal disfrutadas = vacationService.daysTakenInYear(r.employeeId(), r.year(), cese);
            vacDays = devengadas.subtract(disfrutadas);
            if (vacDays.signum() < 0) vacDays = BigDecimal.ZERO;
        }
        if (vacDays.signum() > 0) {
            BigDecimal salarioDiaVac = c.grossSalary.divide(BigDecimal.valueOf(365), 8, RoundingMode.HALF_UP);
            out.add(new ExtraConcept("Vacaciones no disfrutadas",
                    vacDays.multiply(salarioDiaVac).setScale(2, RoundingMode.HALF_UP), true, true));
        }

        // Prorrata de pagas extra devengadas no cobradas.
        if (!c.extrasProrated && c.annualBonuses > 0) {
            BigDecimal importePaga = baseAnnual.divide(
                    BigDecimal.valueOf(12 + c.annualBonuses), 8, RoundingMode.HALF_UP);
            java.time.LocalDate cese = java.time.LocalDate.of(r.year(), r.month(), worked);
            BigDecimal prorrata;
            if ("ANNUAL".equalsIgnoreCase(r.extrasAccrual())) {
                java.time.LocalDate y0 = java.time.LocalDate.of(r.year(), 1, 1);
                long dias = java.time.temporal.ChronoUnit.DAYS.between(y0, cese) + 1;
                int diasAnio = java.time.Year.of(r.year()).length();
                prorrata = importePaga.multiply(BigDecimal.valueOf(c.annualBonuses))
                        .multiply(BigDecimal.valueOf(dias))
                        .divide(BigDecimal.valueOf(diasAnio), 2, RoundingMode.HALF_UP);
            } else { // SEMIANNUAL (por defecto)
                java.time.LocalDate semStart = r.month() <= 6
                        ? java.time.LocalDate.of(r.year(), 1, 1) : java.time.LocalDate.of(r.year(), 7, 1);
                java.time.LocalDate semEnd = r.month() <= 6
                        ? java.time.LocalDate.of(r.year(), 6, 30) : java.time.LocalDate.of(r.year(), 12, 31);
                long dias = java.time.temporal.ChronoUnit.DAYS.between(semStart, cese) + 1;
                long diasSem = java.time.temporal.ChronoUnit.DAYS.between(semStart, semEnd) + 1;
                prorrata = importePaga.multiply(BigDecimal.valueOf(dias))
                        .divide(BigDecimal.valueOf(diasSem), 2, RoundingMode.HALF_UP);
            }
            if (prorrata.signum() > 0) {
                out.add(new ExtraConcept("Prorrata pagas extra", prorrata, false, true));
            }
        }
        return out;
    }

    // ===== PAY-RECURRENT: nóminas mensuales recurrentes =====

    public record MonthlyRunResult(int generated, int skipped, java.util.List<String> errors) {}

    /**
     * Genera la nómina MENSUAL de todos los empleados activos con contrato
     * vigente en el mes, saltando los que ya la tengan (idempotente). Sustituye
     * al "una a una": un clic al mes, como ventas/gastos recurrentes.
     */
    public MonthlyRunResult generateMonth(int year, int month) {
        LocalDate monthStart = LocalDate.of(year, month, 1);
        LocalDate monthEnd = monthStart.withDayOfMonth(monthStart.lengthOfMonth());
        List<String[]> emps = jdbcTemplate.query("""
                SELECT DISTINCT c.employee_id, e.full_name
                  FROM employment_contracts c
                  JOIN employees e ON e.id = c.employee_id AND e.company_id = c.company_id
                 WHERE c.company_id = ? AND e.active = TRUE
                   AND c.status IN ('ACTIVE', 'SUSPENDED')
                   AND c.start_date <= ? AND (c.end_date IS NULL OR c.end_date >= ?)
                 ORDER BY e.full_name
                """,
                (rs, n) -> new String[]{rs.getString("employee_id"), rs.getString("full_name")},
                tenantContext.getCurrentCompanyId(), monthEnd, monthStart);

        int generated = 0, skipped = 0;
        java.util.List<String> errors = new java.util.ArrayList<>();
        for (String[] e : emps) {
            Integer exists = jdbcTemplate.query("""
                    SELECT COUNT(*) FROM payslips
                     WHERE company_id = ? AND employee_id = ?
                       AND period_year = ? AND period_month = ? AND payslip_type = 'MONTHLY'
                    """, (rs, n) -> rs.getInt(1),
                    tenantContext.getCurrentCompanyId(), e[0], year, month)
                    .stream().findFirst().orElse(0);
            if (exists != null && exists > 0) { skipped++; continue; }
            try {
                calculate(new CalculateRequest(e[0], year, month, "MONTHLY",
                        null, null, null, null, null));
                generated++;
            } catch (Exception ex) {
                errors.add(e[1] + ": " + ex.getMessage());
            }
        }
        return new MonthlyRunResult(generated, skipped, errors);
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
        // la SS empresa) — nóminas mensuales y FINIQUITOS (que también cotizan:
        // salario de días trabajados + vacaciones). Las pagas extra NO (su
        // cotización ya va prorrateada en las mensuales).
        if ("MONTHLY".equals(type) || "SETTLEMENT".equals(type)) {
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
                // Provisión MENSUAL de pagas extra NO prorrateadas (devengo art. 38
                // CdC): reconoce cada mes 1/12 de las pagas que se pagarán aparte.
                if ("MONTHLY".equals(type) && isExtraPayProvisionEnabled()) {
                    ContractData cd = resolveActiveContract(req.employeeId(), req.year(), req.month());
                    if (cd != null && !cd.extrasProrated && cd.annualBonuses > 0) {
                        journalService.createExtraProvision(id, empName, req.year(), req.month(),
                                extraProvisionMonthly(cd), null);
                    }
                }
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
        String companyId = tenantContext.getCurrentCompanyId();
        // Bloquear re-pago de una nómina ya pagada (auditoría 2026-06-16): evita
        // regenerar el asiento de pago y descuadrar el Diario.
        String current = jdbcTemplate.query(
                "SELECT status FROM payslips WHERE id = ? AND company_id = ?",
                rs -> rs.next() ? rs.getString(1) : null, id, companyId);
        if (current == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada");
        if ("PAID".equals(current)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La nómina ya está pagada");
        }
        int n = jdbcTemplate.update("""
                UPDATE payslips
                   SET status = 'PAID',
                       paid_at = COALESCE(?, CURRENT_DATE())
                 WHERE id = ? AND company_id = ?
                """, paidAt, id, companyId);
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
        } else if ("EXTRA_SUMMER".equals(view.payslipType())
                || "EXTRA_CHRISTMAS".equals(view.payslipType())) {
            // Paga extra: el pago cancela la provisión acumulada (465 → 4751/572),
            // sin SS (ya cotizó prorrateada mes a mes).
            try {
                journalService.createExtraPayment(new PayslipJournalEntryService.PayslipAccrual(
                        view.id(), view.employeeId(), view.employeeName(),
                        view.periodYear(), view.periodMonth(),
                        view.grossAmount(), view.irpfAmount()),
                        view.paidAt(), null);
            } catch (Exception ex) {
                // No bloquea el marcado de pago.
            }
        }
        return view;
    }

    /** Registra la entrega del recibo de salarios al trabajador (fecha + vía). */
    public PayslipView markDelivered(String id, LocalDate deliveredAt, String method) {
        int n = jdbcTemplate.update("""
                UPDATE payslips
                   SET delivered_at = COALESCE(?, CURRENT_DATE()),
                       delivery_method = ?
                 WHERE id = ? AND company_id = ?
                """, deliveredAt, method, id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada");
        PayslipView view = findById(id);
        // NOTIF-RT — avisar al empleado de que tiene una nómina disponible.
        try {
            realtime.publishToEmployee(tenantContext.getCurrentCompanyId(), view.employeeId(),
                    "payslip", "{\"action\":\"delivered\",\"id\":\"" + id + "\"}");
        } catch (Exception ignored) {}
        return view;
    }

    /** Registra el acuse de recibo (firma) del trabajador sobre el recibo entregado. */
    public PayslipView markAcknowledged(String id, LocalDate acknowledgedAt) {
        int n = jdbcTemplate.update("""
                UPDATE payslips
                   SET acknowledged_at = COALESCE(?, CURRENT_DATE())
                 WHERE id = ? AND company_id = ?
                """, acknowledgedAt, id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada");
        return findById(id);
    }

    // ---- MEMP-5: acceso del propio empleado (portal PWA) -----------------

    /** Nóminas finalizadas (no DRAFT) del empleado, para que las vea en su app. */
    public List<PayslipView> listForEmployeeApp(String employeeId) {
        return list(null, null, employeeId).stream()
                .filter(p -> !"DRAFT".equalsIgnoreCase(p.status()))
                .toList();
    }

    /** Verifica que la nómina pertenece a ESE empleado (no solo al tenant). */
    private void assertOwnedBy(String id, String employeeId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM payslips WHERE id = ? AND company_id = ? AND employee_id = ?",
                Integer.class, id, tenantContext.getCurrentCompanyId(), employeeId);
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada");
        }
    }

    /** PDF de una nómina del propio empleado (con guarda de propiedad). */
    public byte[] pdfForEmployee(String id, String employeeId) {
        assertOwnedBy(id, employeeId);
        return pdfGenerator.generate(id);
    }

    /**
     * Acuse de recibo del propio empleado ("Recibí"). Marca acknowledged_at y, si
     * aún no constaba entregada, delivered_at + method APP (la entrega es la propia
     * disponibilidad en la app). Solo sobre nóminas suyas.
     */
    @Transactional
    public void acknowledgeOwn(String id, String employeeId) {
        int n = jdbcTemplate.update("""
                UPDATE payslips
                   SET acknowledged_at = COALESCE(acknowledged_at, CURRENT_DATE()),
                       delivered_at    = COALESCE(delivered_at, CURRENT_DATE()),
                       delivery_method = COALESCE(delivery_method, 'APP')
                 WHERE id = ? AND company_id = ? AND employee_id = ?
                """, id, tenantContext.getCurrentCompanyId(), employeeId);
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada");
        // NOTIF-RT — avisar a la empresa de que el empleado ha confirmado el recibí.
        try {
            realtime.publishToCompany(tenantContext.getCurrentCompanyId(), "payslip",
                    "{\"action\":\"acknowledged\",\"id\":\"" + id + "\",\"employeeId\":\"" + employeeId + "\"}");
        } catch (Exception ignored) {}
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
                       p.delivered_at, p.delivery_method, p.acknowledged_at,
                       p.created_at, p.updated_at
                  FROM payslips p
                  JOIN employees e ON e.id = p.employee_id
                 WHERE p.id = ? AND p.company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Nomina no encontrada"));
    }

    /** Etiqueta del devengo para una paga extraordinaria. */
    private static String extraPagaLabel(String type) {
        if ("EXTRA_SUMMER".equals(type)) return "Paga extra de verano";
        if ("EXTRA_CHRISTMAS".equals(type)) return "Paga extra de Navidad";
        return "Salario base";
    }

    private ContractData resolveActiveContract(String employeeId, int year, int month) {
        LocalDate ref = LocalDate.of(year, month, 1);
        // VIG-2: las condiciones variables (salario, grupo, IRPF, AT/EP, pagas…)
        // se leen de la VIGENCIA vigente a la fecha del periodo (la última con
        // effective_from <= ref); los datos fijos (tipo, fechas) del contrato.
        // Fallback al valor del contrato si no hubiera vigencia (no debería tras
        // el backfill V125). Así las nóminas pasadas no cambian al ascender.
        return jdbcTemplate.query("""
                SELECT c.id, c.contract_type, c.start_date, c.end_date,
                       COALESCE(v.gross_salary, c.gross_salary) AS gross_salary,
                       COALESCE(v.annual_bonuses, c.annual_bonuses) AS annual_bonuses,
                       COALESCE(v.extras_prorated, c.extras_prorated) AS extras_prorated,
                       COALESCE(v.vacation_days, c.vacation_days) AS vacation_days,
                       COALESCE(v.irpf_percent, c.irpf_percent) AS irpf_percent,
                       COALESCE(v.at_ep_percent, c.at_ep_percent) AS at_ep_percent,
                       COALESCE(v.ss_contribution_group, c.ss_contribution_group) AS ss_contribution_group
                  FROM employment_contracts c
                  LEFT JOIN contract_vigencias v ON v.id = (
                        SELECT v2.id FROM contract_vigencias v2
                         WHERE v2.contract_id = c.id AND v2.effective_from <= ?
                         ORDER BY v2.effective_from DESC LIMIT 1
                  )
                 WHERE c.company_id = ? AND c.employee_id = ?
                   AND c.start_date <= ?
                   AND (c.end_date IS NULL OR c.end_date >= ?)
                   AND c.status IN ('ACTIVE', 'SUSPENDED')
                 ORDER BY c.start_date DESC LIMIT 1
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
                    d.extrasProrated = rs.getBoolean("extras_prorated");
                    Integer vac = (Integer) rs.getObject("vacation_days");
                    d.vacationDays = vac == null ? 30 : vac;
                    d.irpfPercent = rs.getBigDecimal("irpf_percent");
                    d.atEpPercent = rs.getBigDecimal("at_ep_percent");
                    d.ssContributionGroup = rs.getObject("ss_contribution_group") instanceof Number sg
                            ? sg.intValue() : null;
                    return d;
                },
                ref, tenantContext.getCurrentCompanyId(), employeeId, ref, ref)
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
     * IRPF-VOL — Tipo de retención SUGERIDO (mínimo legal) para un empleado y un
     * bruto anual: con Modelo 145 usa el algoritmo oficial de la AEAT; si no, la
     * estimación por tramos. Lo usa el editor de contrato para mostrar el % auto
     * y avisar si la retención voluntaria que teclea el usuario es menor.
     */
    public BigDecimal suggestIrpfRate(String employeeId, BigDecimal annualGross, int year) {
        if (annualGross == null || annualGross.signum() <= 0) return BigDecimal.ZERO;
        var m145 = irpfService.findForEmployee(employeeId);
        if (m145 == null) return computeIrpfPercent(annualGross, year);
        var rates = ssRatesService.ratesForYear(year);
        BigDecimal eeRate = rates.eeCommon().add(rates.eeUnemployment())
                .add(rates.eeTraining()).add(rates.eeMei());
        BigDecimal annualSs = annualGross.multiply(eeRate)
                .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
        return irpfService.computeRate(year, annualGross, annualSs, m145);
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

    /**
     * Devuelve {@code [baseMin, baseMax]} del grupo de cotización para el año,
     * leídos de la tabla editable {@code ss_contribution_group_bases} (no-code,
     * V121/V122). Devuelve {@code null} si no procede aplicar el tope por grupo
     * (sin tabla para el año, contrato sin grupo, o grupo no encontrado), en cuyo
     * caso el llamador usa el tope global de {@code ss_contribution_rates}.
     * Para grupos de base diaria (8-11) se devuelve solo el tope máximo (mínimo 0)
     * porque el cálculo diario es un refinamiento pendiente (paso 4).
     */
    private BigDecimal[] resolveGroupCaps(int year, Integer group) {
        if (group == null) return null;
        var rows = ssGroupBasesService.listByYear(year);
        if (rows == null || rows.isEmpty()) return null;
        BigDecimal maxMonthly = null;
        BigDecimal groupMin = null;
        boolean groupIsDaily = false;
        boolean found = false;
        for (var g : rows) {
            if (!g.daily() && g.baseMax() != null
                    && (maxMonthly == null || g.baseMax().compareTo(maxMonthly) > 0)) {
                maxMonthly = g.baseMax();
            }
            if (g.cotizGroup() == group) {
                groupMin = g.baseMin();
                groupIsDaily = g.daily();
                found = true;
            }
        }
        if (!found) return null;
        if (groupIsDaily) return new BigDecimal[]{ BigDecimal.ZERO, maxMonthly };
        return new BigDecimal[]{ groupMin, maxMonthly };
    }

    /**
     * Importe mensual a provisionar de las pagas extra de un contrato NO
     * prorrateado: 1/12 de las pagas extra anuales. Una paga extra = salario
     * base anual / (12 + nº pagas) (las extra solo incluyen salario base, no
     * complementos). Fallback al bruto del contrato si no hay desglose de
     * conceptos (contratos antiguos). Devuelve 0 si no procede.
     */
    /** Política de empresa (V126): ¿provisionar mensualmente las pagas extra?
     *  Por defecto TRUE (criterio de devengo). Editable en Configuración. */
    private boolean isExtraPayProvisionEnabled() {
        Boolean v = jdbcTemplate.query("SELECT provision_extra_pay FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getBoolean("provision_extra_pay") : Boolean.TRUE,
                tenantContext.getCurrentCompanyId());
        return v == null || v;
    }

    private BigDecimal extraProvisionMonthly(ContractData c) {
        int n = c.annualBonuses;
        if (n <= 0) return BigDecimal.ZERO;
        BigDecimal baseAnnual = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(annual_amount), 0) FROM contract_salary_items
                 WHERE contract_id = ? AND company_id = ? AND kind = 'SALARY_BASE'
                """, BigDecimal.class, c.id, tenantContext.getCurrentCompanyId());
        if (baseAnnual == null || baseAnnual.signum() <= 0) baseAnnual = c.grossSalary;
        if (baseAnnual == null || baseAnnual.signum() <= 0) return BigDecimal.ZERO;
        int divisor = 12 + n;
        BigDecimal pagasAnual = baseAnnual.multiply(BigDecimal.valueOf(n))
                .divide(BigDecimal.valueOf(divisor), 2, RoundingMode.HALF_UP);
        return pagasAnual.divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
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
        boolean extrasProrated;
        int vacationDays;
        BigDecimal irpfPercent;
        BigDecimal atEpPercent;
        Integer ssContributionGroup;
    }

    private PayslipView mapView(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date pd = rs.getDate("paid_at");
        java.sql.Date dd = rs.getDate("delivered_at");
        java.sql.Date ad = rs.getDate("acknowledged_at");
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
                dd == null ? null : dd.toLocalDate(),
                rs.getString("delivery_method"),
                ad == null ? null : ad.toLocalDate(),
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
            LocalDate deliveredAt, String deliveryMethod, LocalDate acknowledgedAt,
            Instant createdAt, Instant updatedAt
    ) {}

    public record CalculateRequest(
            String employeeId, int year, int month, String payslipType,
            Boolean includeExtraProrated, BigDecimal otherDeductions, String notes,
            List<ExtraConcept> extraConcepts,
            // Complementos MENSUALES recurrentes (anualizan en base SS e IRPF).
            // Lo usa el solver de objetivo para simular la mejora antes de
            // guardarla en el contrato; null en el flujo normal de cálculo.
            List<ExtraConcept> recurringConcepts
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

    public record MarkDeliveredRequest(LocalDate deliveredAt, String method) {}

    public record MarkAcknowledgedRequest(LocalDate acknowledgedAt) {}

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

        /** IRPF-VOL — tipo de retención sugerido (mínimo legal) para mostrar en
         *  el editor de contrato. */
        @GetMapping("/suggest-irpf")
        public java.util.Map<String, BigDecimal> suggestIrpf(
                @RequestParam("employeeId") String employeeId,
                @RequestParam("annualGross") BigDecimal annualGross,
                @RequestParam(value = "year", required = false) Integer year) {
            int y = year == null ? java.time.LocalDate.now().getYear() : year;
            return java.util.Map.of("rate", service.suggestIrpfRate(employeeId, annualGross, y));
        }

        @PostMapping("/settlement-concepts")
        public List<ExtraConcept> settlementConcepts(@RequestBody SettlementRequest req) {
            return service.settlementConcepts(req);
        }

        @PostMapping("/generate-month")
        public MonthlyRunResult generateMonth(@RequestParam("year") int year,
                                               @RequestParam("month") int month) {
            return service.generateMonth(year, month);
        }

        @PutMapping("/{id}/pay")
        public PayslipView markPaid(@PathVariable("id") String id,
                                     @RequestBody MarkPaidRequest req) {
            return service.markPaid(id, req == null ? null : req.paidAt());
        }

        @PutMapping("/{id}/deliver")
        public PayslipView markDelivered(@PathVariable("id") String id,
                                          @RequestBody MarkDeliveredRequest req) {
            return service.markDelivered(id,
                    req == null ? null : req.deliveredAt(),
                    req == null ? null : req.method());
        }

        @PutMapping("/{id}/acknowledge")
        public PayslipView markAcknowledged(@PathVariable("id") String id,
                                             @RequestBody MarkAcknowledgedRequest req) {
            return service.markAcknowledged(id, req == null ? null : req.acknowledgedAt());
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
