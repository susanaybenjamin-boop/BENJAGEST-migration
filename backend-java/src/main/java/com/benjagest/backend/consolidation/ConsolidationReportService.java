package com.benjagest.backend.consolidation;

import com.benjagest.backend.auth.RequiresRole;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * CONSOL-2 — Balance de comprobación AGREGADO de un grupo de empresas.
 *
 * <p>Suma, cuenta a cuenta (por CÓDIGO del PGC), los saldos {@code debe/haber} de
 * los asientos POSTED de TODAS las empresas del grupo a una fecha. Es la
 * "agregación" previa a la consolidación: todavía NO elimina las operaciones
 * intragrupo (eso es CONSOL-3). Reusa exactamente el criterio de
 * {@code FinancialReportsService} (status POSTED, saldo = debe − haber).
 *
 * <p>Multi-empresa: lee los libros de cada empresa del grupo directamente por
 * {@code company_id}. La pertenencia/propiedad se valida vía
 * {@link CompanyGroupService#membersOf} (asegura que el grupo es del usuario).
 */
@Service
public class ConsolidationReportService {

    private final JdbcTemplate jdbc;
    private final CompanyGroupService groups;

    public ConsolidationReportService(JdbcTemplate jdbc, CompanyGroupService groups) {
        this.jdbc = jdbc;
        this.groups = groups;
    }

    public ConsolidatedTrialBalance aggregatedTrialBalance(String groupId, LocalDate asOf) {
        if (asOf == null) asOf = LocalDate.now();
        List<String> memberIds = groups.membersOf(groupId).stream()
                .map(CompanyGroupService.MemberView::companyId).toList();
        if (memberIds.isEmpty()) {
            return new ConsolidatedTrialBalance(asOf, List.of(),
                    BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, 0);
        }
        String placeholders = memberIds.stream().map(x -> "?").collect(Collectors.joining(","));
        Object[] args = new Object[memberIds.size() + 1];
        args[0] = java.sql.Date.valueOf(asOf);
        for (int i = 0; i < memberIds.size(); i++) args[i + 1] = memberIds.get(i);

        List<Line> lines = jdbc.query("""
                SELECT a.code AS code, MAX(a.name) AS name,
                       COALESCE(SUM(l.debit), 0)  AS d,
                       COALESCE(SUM(l.credit), 0) AS c
                  FROM accounting_accounts a
                  LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                  LEFT JOIN journal_entries je ON je.id = l.journal_entry_id
                                              AND je.status = 'POSTED'
                                              AND je.entry_date <= ?
                 WHERE a.company_id IN (""" + placeholders + """
                )
                 GROUP BY a.code
                HAVING COALESCE(SUM(l.debit), 0) <> 0 OR COALESCE(SUM(l.credit), 0) <> 0
                 ORDER BY a.code
                """,
                (rs, n) -> new Line(rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("d"), rs.getBigDecimal("c")),
                args);

        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        BigDecimal ingresos = BigDecimal.ZERO;   // grupo 7 (haber − debe)
        BigDecimal gastos = BigDecimal.ZERO;     // grupo 6 (debe − haber)
        for (Line ln : lines) {
            totalDebit = totalDebit.add(ln.debit());
            totalCredit = totalCredit.add(ln.credit());
            if (ln.code() != null && ln.code().startsWith("7")) {
                ingresos = ingresos.add(ln.credit().subtract(ln.debit()));
            } else if (ln.code() != null && ln.code().startsWith("6")) {
                gastos = gastos.add(ln.debit().subtract(ln.credit()));
            }
        }
        BigDecimal resultado = ingresos.subtract(gastos);
        return new ConsolidatedTrialBalance(asOf, lines, totalDebit, totalCredit, resultado, memberIds.size());
    }

    public record Line(String code, String name, BigDecimal debit, BigDecimal credit) {
        public BigDecimal balance() { return debit.subtract(credit); }
    }

    public record ConsolidatedTrialBalance(
            LocalDate asOf, List<Line> lines,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal resultado, int companyCount) {}

    @RestController
    @RequestMapping("/api/company-groups")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final ConsolidationReportService service;

        public Controller(ConsolidationReportService service) { this.service = service; }

        @GetMapping("/{id}/trial-balance")
        public ConsolidatedTrialBalance trialBalance(
                @PathVariable("id") String groupId,
                @RequestParam(value = "asOf", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
            return service.aggregatedTrialBalance(groupId, asOf);
        }
    }
}
