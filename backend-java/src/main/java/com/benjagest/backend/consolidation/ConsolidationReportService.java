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

    /**
     * CONSOL-3 — Balance CONSOLIDADO: el agregado pero ELIMINANDO los saldos
     * recíprocos intragrupo. Una subcuenta de tercero (430 cliente / 400
     * proveedor) cuyo {@code tercero_nif} es OTRA empresa del grupo representa una
     * deuda/crédito interno: en el consolidado no existe (te debes a ti mismo).
     * Se excluyen esas subcuentas del balance y se listan como eliminaciones.
     *
     * <p>Las eliminaciones de PÉRDIDAS Y GANANCIAS intragrupo (ventas A→B en 70 vs
     * compras en 60) quedan como refinamiento (CONSOL-3b): el asiento de 70/60 no
     * lleva el NIF del tercero, así que requiere trazar facturas (bloque REFLEJO).
     */
    public ConsolidatedResult consolidated(String groupId, LocalDate asOf) {
        if (asOf == null) asOf = LocalDate.now();
        List<CompanyGroupService.MemberView> members = groups.membersOf(groupId);
        List<String> memberIds = members.stream().map(CompanyGroupService.MemberView::companyId).toList();
        List<String> memberNifs = members.stream()
                .map(m -> normNif(m.taxIdentifier()))
                .filter(s -> !s.isEmpty()).distinct().toList();
        if (memberIds.isEmpty()) {
            return new ConsolidatedResult(asOf, List.of(), BigDecimal.ZERO, BigDecimal.ZERO,
                    BigDecimal.ZERO, 0, List.of(), BigDecimal.ZERO, BigDecimal.ZERO);
        }
        String memberPh = memberIds.stream().map(x -> "?").collect(Collectors.joining(","));

        // 1) Eliminaciones: subcuentas de tercero (cliente/proveedor) de empresas
        //    del grupo cuyo NIF es otra empresa del grupo.
        List<EliminationRow> eliminations = List.of();
        if (!memberNifs.isEmpty()) {
            String nifPh = memberNifs.stream().map(x -> "?").collect(Collectors.joining(","));
            Object[] elimArgs = new Object[1 + memberIds.size() + memberNifs.size()];
            int i = 0;
            elimArgs[i++] = java.sql.Date.valueOf(asOf);
            for (String id : memberIds) elimArgs[i++] = id;
            for (String nif : memberNifs) elimArgs[i++] = nif;
            eliminations = jdbc.query("""
                    SELECT c.legal_name AS company_name, a.code AS code, a.name AS name,
                           a.tercero_nif AS nif, a.tercero_type AS type,
                           COALESCE(SUM(l.debit), 0) AS d, COALESCE(SUM(l.credit), 0) AS c
                      FROM accounting_accounts a
                      JOIN companies c ON c.id = a.company_id
                      LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                      LEFT JOIN journal_entries je ON je.id = l.journal_entry_id
                                                  AND je.status = 'POSTED'
                                                  AND je.entry_date <= ?
                     WHERE a.company_id IN (""" + memberPh + """
                    )
                       AND a.tercero_type IN ('cliente', 'proveedor')
                       AND UPPER(a.tercero_nif) IN (""" + nifPh + """
                    )
                     GROUP BY a.id, c.legal_name, a.code, a.name, a.tercero_nif, a.tercero_type
                    HAVING COALESCE(SUM(l.debit), 0) <> 0 OR COALESCE(SUM(l.credit), 0) <> 0
                     ORDER BY company_name, a.code
                    """,
                    (rs, n) -> new EliminationRow(rs.getString("company_name"), rs.getString("code"),
                            rs.getString("name"), rs.getString("nif"), rs.getString("type"),
                            rs.getBigDecimal("d").subtract(rs.getBigDecimal("c"))),
                    elimArgs);
        }

        BigDecimal receivablesElim = BigDecimal.ZERO;  // 430 clientes intragrupo
        BigDecimal payablesElim = BigDecimal.ZERO;     // 400 proveedores intragrupo
        for (EliminationRow e : eliminations) {
            if ("cliente".equalsIgnoreCase(e.terceroType())) receivablesElim = receivablesElim.add(e.balance());
            else payablesElim = payablesElim.add(e.balance());
        }

        // 2) Balance consolidado: agregado por código EXCLUYENDO esas subcuentas.
        StringBuilder sql = new StringBuilder("""
                SELECT a.code AS code, MAX(a.name) AS name,
                       COALESCE(SUM(l.debit), 0) AS d, COALESCE(SUM(l.credit), 0) AS c
                  FROM accounting_accounts a
                  LEFT JOIN journal_entry_lines l ON l.account_id = a.id
                  LEFT JOIN journal_entries je ON je.id = l.journal_entry_id
                                              AND je.status = 'POSTED'
                                              AND je.entry_date <= ?
                 WHERE a.company_id IN (""" + memberPh + ")");
        List<Object> args = new java.util.ArrayList<>();
        args.add(java.sql.Date.valueOf(asOf));
        args.addAll(memberIds);
        if (!memberNifs.isEmpty()) {
            String nifPh = memberNifs.stream().map(x -> "?").collect(Collectors.joining(","));
            sql.append(" AND NOT (a.tercero_type IN ('cliente','proveedor') AND UPPER(a.tercero_nif) IN (")
               .append(nifPh).append("))");
            args.addAll(memberNifs);
        }
        sql.append("""
                 GROUP BY a.code
                HAVING COALESCE(SUM(l.debit), 0) <> 0 OR COALESCE(SUM(l.credit), 0) <> 0
                 ORDER BY a.code
                """);
        List<Line> lines = jdbc.query(sql.toString(),
                (rs, n) -> new Line(rs.getString("code"), rs.getString("name"),
                        rs.getBigDecimal("d"), rs.getBigDecimal("c")),
                args.toArray());

        BigDecimal totalDebit = BigDecimal.ZERO, totalCredit = BigDecimal.ZERO;
        BigDecimal ingresos = BigDecimal.ZERO, gastos = BigDecimal.ZERO;
        for (Line ln : lines) {
            totalDebit = totalDebit.add(ln.debit());
            totalCredit = totalCredit.add(ln.credit());
            if (ln.code() != null && ln.code().startsWith("7")) ingresos = ingresos.add(ln.credit().subtract(ln.debit()));
            else if (ln.code() != null && ln.code().startsWith("6")) gastos = gastos.add(ln.debit().subtract(ln.credit()));
        }
        return new ConsolidatedResult(asOf, lines, totalDebit, totalCredit,
                ingresos.subtract(gastos), memberIds.size(),
                eliminations, receivablesElim, payablesElim);
    }

    private static String normNif(String s) {
        return s == null ? "" : s.trim().toUpperCase().replaceAll("[\\s\\-.]", "");
    }

    public record EliminationRow(String companyName, String code, String name,
                                 String terceroNif, String terceroType, BigDecimal balance) {}

    public record ConsolidatedResult(
            LocalDate asOf, List<Line> lines,
            BigDecimal totalDebit, BigDecimal totalCredit, BigDecimal resultado, int companyCount,
            List<EliminationRow> eliminations,
            BigDecimal receivablesEliminated, BigDecimal payablesEliminated) {}

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

        @GetMapping("/{id}/consolidated")
        public ConsolidatedResult consolidated(
                @PathVariable("id") String groupId,
                @RequestParam(value = "asOf", required = false)
                @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
            return service.consolidated(groupId, asOf);
        }
    }
}
