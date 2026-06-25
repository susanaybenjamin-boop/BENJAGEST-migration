package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.labor.ss.SsContributionRatesService;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CL-2 — ATRASOS retroactivos (subida de convenio con efecto pasado).
 *
 * <p>Cuando se pacta una subida con efecto desde una fecha pasada (p.ej. convenio
 * firmado en junio con efectos desde enero), los meses ya cobrados se pagaron con
 * el salario antiguo: se debe la DIFERENCIA. Este servicio la calcula a partir de
 * las VIGENCIAS del contrato (la última = salario nuevo con su fecha de efecto; la
 * anterior = salario viejo), aplicando la ley:
 * <ul>
 *   <li><b>IRPF</b>: los atrasos imputables a <b>ejercicios anteriores</b> al del
 *       pago retienen al <b>15 %</b> fijo (art. 101.1 / 80.1.5 RIRPF). Los del
 *       ejercicio en curso, al tipo de retención del contrato.</li>
 *   <li><b>Cotización</b>: la diferencia cotiza (liquidación complementaria L13);
 *       se descuenta la aportación del trabajador sobre la diferencia.</li>
 * </ul>
 *
 * <p>Esto es SOLO cálculo (preview, sin efectos). La generación del recibo de
 * atrasos + su asiento + la liquidación L13 se monta con Benjamin (escritura
 * irreversible). Refinamiento marcado: diferencia sobre PAGAS EXTRA no prorrateadas.
 */
@RestController
@RequestMapping("/api/labor/back-pay")
@RequiresModule("labor")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class BackPayService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final SsContributionRatesService ssRates;

    public BackPayService(JdbcTemplate jdbc, TenantContext tenant, SsContributionRatesService ssRates) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.ssRates = ssRates;
    }

    @GetMapping("/preview")
    public BackPayPreview preview(@RequestParam("employeeId") String employeeId,
                                  @RequestParam(value = "throughYear", required = false) Integer throughYear,
                                  @RequestParam(value = "throughMonth", required = false) Integer throughMonth) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        LocalDate now = LocalDate.now();
        int ty = throughYear != null ? throughYear : now.getYear();
        int tm = throughMonth != null ? throughMonth : now.getMonthValue();
        int paymentYear = now.getYear();

        String contractId = jdbc.query("""
                SELECT id FROM employment_contracts
                 WHERE company_id = ? AND employee_id = ? AND status IN ('ACTIVE', 'SUSPENDED')
                 ORDER BY start_date DESC LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                tenant.getCurrentCompanyId(), employeeId)
                .stream().findFirst().orElse(null);
        if (contractId == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El empleado no tiene un contrato activo.");
        }

        // Las dos vigencias más recientes: la última (salario nuevo) y la anterior.
        List<Vig> vigs = jdbc.query("""
                SELECT effective_from, gross_salary, irpf_percent
                  FROM contract_vigencias
                 WHERE contract_id = ? AND company_id = ?
                 ORDER BY effective_from DESC LIMIT 2
                """,
                (rs, n) -> new Vig(rs.getDate("effective_from").toLocalDate(),
                        rs.getBigDecimal("gross_salary"),
                        rs.getBigDecimal("irpf_percent")),
                contractId, tenant.getCurrentCompanyId());

        if (vigs.size() < 2) {
            return BackPayPreview.none("No hay una subida con vigencia previa: no hay atrasos que calcular.");
        }
        Vig nueva = vigs.get(0);
        Vig vieja = vigs.get(1);
        if (nueva.gross() == null || vieja.gross() == null
                || nueva.gross().compareTo(vieja.gross()) <= 0) {
            return BackPayPreview.none("El salario nuevo no es mayor que el anterior: no hay atrasos.");
        }

        YearMonth from = YearMonth.from(nueva.effectiveFrom());
        YearMonth to = YearMonth.of(ty, tm);
        if (from.isAfter(to)) {
            return BackPayPreview.none("La fecha de efecto es posterior al periodo: no hay atrasos.");
        }

        int priorMonths = 0, currentMonths = 0;
        for (YearMonth m = from; !m.isAfter(to); m = m.plusMonths(1)) {
            if (m.getYear() < paymentYear) priorMonths++; else currentMonths++;
        }
        int totalMonths = priorMonths + currentMonths;

        BigDecimal monthlyDiff = nueva.gross().subtract(vieja.gross())
                .divide(BigDecimal.valueOf(12), 2, RoundingMode.HALF_UP);
        BigDecimal grossPrior = monthlyDiff.multiply(BigDecimal.valueOf(priorMonths)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossCurrent = monthlyDiff.multiply(BigDecimal.valueOf(currentMonths)).setScale(2, RoundingMode.HALF_UP);
        BigDecimal grossTotal = grossPrior.add(grossCurrent);

        // IRPF: 15 % sobre el tramo de ejercicios anteriores; tipo del contrato
        // sobre el tramo del ejercicio en curso.
        BigDecimal irpfRate = nueva.irpfPercent() != null ? nueva.irpfPercent() : BigDecimal.ZERO;
        BigDecimal irpfPrior = pct(grossPrior, new BigDecimal("15.00"));
        BigDecimal irpfCurrent = pct(grossCurrent, irpfRate);
        BigDecimal irpf = irpfPrior.add(irpfCurrent);

        // Cotización del trabajador sobre la diferencia (rates del año de pago).
        SsContributionRatesService.Rates r = ssRates.ratesForYear(paymentYear);
        BigDecimal eePct = nz(r.eeCommon()).add(nz(r.eeUnemployment()))
                .add(nz(r.eeTraining())).add(nz(r.eeMei()));
        BigDecimal employeeSs = pct(grossTotal, eePct);

        BigDecimal net = grossTotal.subtract(irpf).subtract(employeeSs).setScale(2, RoundingMode.HALF_UP);

        return new BackPayPreview(true, null,
                vieja.gross(), nueva.gross(), nueva.effectiveFrom(),
                monthlyDiff, totalMonths, priorMonths, currentMonths,
                grossPrior, grossCurrent, grossTotal,
                irpfPrior, irpfCurrent, irpf, employeeSs, net);
    }

    private static BigDecimal pct(BigDecimal base, BigDecimal percent) {
        if (base == null || percent == null) return BigDecimal.ZERO.setScale(2);
        return base.multiply(percent).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
    }

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    private record Vig(LocalDate effectiveFrom, BigDecimal gross, BigDecimal irpfPercent) {}

    public record BackPayPreview(
            boolean hasBackPay, String message,
            BigDecimal oldAnnual, BigDecimal newAnnual, LocalDate effectiveFrom,
            BigDecimal monthlyDiff, int totalMonths, int priorYearMonths, int currentYearMonths,
            BigDecimal grossPriorYears, BigDecimal grossCurrentYear, BigDecimal grossTotal,
            BigDecimal irpfPriorYears, BigDecimal irpfCurrentYear, BigDecimal irpfTotal,
            BigDecimal employeeSs, BigDecimal net) {

        static BackPayPreview none(String message) {
            BigDecimal z = BigDecimal.ZERO.setScale(2);
            return new BackPayPreview(false, message, z, z, null, z, 0, 0, 0,
                    z, z, z, z, z, z, z, z);
        }
    }
}
