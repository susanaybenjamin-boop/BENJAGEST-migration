package com.benjagest.backend.labor;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Bloque NOM — genera los asientos contables de una nómina. Espejo de
 * {@code SalesJournalEntryService} / {@code PurchaseJournalEntryService}
 * pero con las cuentas de personal del PGC.
 *
 * <p>Decisiones de Benjamin (2026-06-13, barriendo Orden PJC/297/2026 y
 * ejemplos de Sage/Cegid/Wolters Kluwer):
 * <ul>
 *   <li>El importe de la SS a cargo de la empresa (642) y el acreedor a
 *       la TGSS (476) se leen <b>sumando las filas de
 *       {@code social_security_contributions}</b> del periodo (la tabla
 *       de cuotas TC es la fuente de verdad; la nómina la alimenta).</li>
 *   <li>Se generan <b>dos asientos</b>: devengo (al calcular) y pago (al
 *       marcar pagada).</li>
 * </ul>
 *
 * <p>Asiento de DEVENGO (PGC):
 * <pre>
 *   Debe   640 Sueldos y salarios            = bruto
 *   Debe   642 SS a cargo de la empresa       = Σ EMPLOYER_* (cuotas TC)
 *                Haber 476 Org. Seg. Social acreedores = Σ EMPLOYER_* + Σ EMPLOYEE_*
 *                Haber 4751 HP acreedora retenciones    = IRPF retenido
 *                Haber 465 Remuneraciones pdtes de pago  = bruto - SS trab. - IRPF
 * </pre>
 *
 * <p>Asiento de PAGO:
 * <pre>
 *   Debe   465 Remuneraciones pdtes de pago   = líquido
 *                Haber 572 Bancos                       = líquido
 * </pre>
 *
 * <p>Igual que ventas/compras: si falta alguna cuenta o no hay
 * {@code fiscal_year} OPEN para la fecha, devuelve {@code null} y el
 * caller continúa sin asiento (la nómina es independiente del asiento).
 * Las "otras deducciones" (embargos/anticipos) quedan fuera de este
 * asiento MVP; si existen, requieren un apunte manual adicional.
 */
@Service
public class PayslipJournalEntryService {

    public static final String SRC_ACCRUAL = "PAYSLIP_ACCRUAL";
    public static final String SRC_PAYMENT = "PAYSLIP_PAYMENT";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public PayslipJournalEntryService(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /** Datos mínimos de la nómina que necesita el asiento. */
    public record PayslipAccrual(
            String payslipId, String employeeId, String employeeName,
            int year, int month,
            BigDecimal gross, BigDecimal irpf) {}

    /**
     * Crea (o regenera) el asiento de devengo de la nómina. Borra primero
     * cualquier asiento de devengo previo de esta nómina para ser
     * idempotente ante recálculos. Devuelve el id del asiento o
     * {@code null} si no se pudo crear.
     */
    @Transactional
    public String createAccrual(PayslipAccrual p, String userId) {
        if (p.gross() == null) return null;
        String companyId = tenantContext.getCurrentCompanyId();

        // Devengo el último día del mes del periodo.
        LocalDate entryDate = LocalDate.of(p.year(), p.month(), 1)
                .withDayOfMonth(LocalDate.of(p.year(), p.month(), 1).lengthOfMonth());
        String fiscalYearId = findOpenFiscalYearId(companyId, entryDate);
        if (fiscalYearId == null) return null;

        // SS a cargo de la empresa y del trabajador → suma de cuotas TC.
        BigDecimal employerSs = sumContributions(companyId, p.employeeId(), p.year(), p.month(), "EMPLOYER");
        BigDecimal employeeSs = sumContributions(companyId, p.employeeId(), p.year(), p.month(), "EMPLOYEE");
        BigDecimal irpf = p.irpf() == null ? BigDecimal.ZERO : p.irpf();
        BigDecimal totalSs = employerSs.add(employeeSs);
        BigDecimal liquido = p.gross().subtract(employeeSs).subtract(irpf);

        String acc640 = findAccountByPrefix(companyId, "640");
        String acc642 = findAccountByPrefix(companyId, "642");
        String acc476 = findAccountByPrefix(companyId, "476");
        String acc465 = findAccountByPrefix(companyId, "465");
        String acc4751 = findAccountByPrefix(companyId, "4751");
        if (acc4751 == null) acc4751 = findAccountByPrefix(companyId, "475");
        if (acc640 == null || acc476 == null || acc465 == null || acc4751 == null) {
            return null;
        }
        // 642 solo si hay SS empresa (siempre debería haberla, pero
        // toleramos su ausencia para no romper el asiento).
        if (employerSs.signum() > 0 && acc642 == null) {
            return null;
        }

        // Idempotencia: borrar devengo previo (solo si está en DRAFT).
        reverseBySource(companyId, p.payslipId(), SRC_ACCRUAL);

        String name = (p.employeeName() == null || p.employeeName().isBlank())
                ? "Empleado" : p.employeeName();
        String period = String.format("%02d/%d", p.month(), p.year());
        String concept = "Nómina " + period + " — " + name;

        String entryId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                """,
                entryId, companyId, fiscalYearId,
                Date.valueOf(entryDate), concept, SRC_ACCRUAL, p.payslipId(), userId);

        insertLine(entryId, acc640, "Sueldos y salarios — " + name, p.gross(), BigDecimal.ZERO);
        if (employerSs.signum() > 0) {
            insertLine(entryId, acc642, "SS a cargo de la empresa — " + name, employerSs, BigDecimal.ZERO);
        }
        insertLine(entryId, acc476, "Org. Seg. Social acreedores — " + period, BigDecimal.ZERO, totalSs);
        if (irpf.signum() > 0) {
            insertLine(entryId, acc4751, "Retención IRPF — " + name, BigDecimal.ZERO, irpf);
        }
        insertLine(entryId, acc465, "Líquido a percibir — " + name, BigDecimal.ZERO, liquido);
        return entryId;
    }

    /**
     * Crea el asiento de pago de la nómina (465 → 572). Idempotente.
     */
    @Transactional
    public String createPayment(PayslipAccrual p, LocalDate paidAt, String userId) {
        if (p.gross() == null) return null;
        String companyId = tenantContext.getCurrentCompanyId();
        LocalDate entryDate = paidAt != null ? paidAt
                : LocalDate.of(p.year(), p.month(), 1)
                        .withDayOfMonth(LocalDate.of(p.year(), p.month(), 1).lengthOfMonth());
        String fiscalYearId = findOpenFiscalYearId(companyId, entryDate);
        if (fiscalYearId == null) return null;

        BigDecimal employeeSs = sumContributions(companyId, p.employeeId(), p.year(), p.month(), "EMPLOYEE");
        BigDecimal irpf = p.irpf() == null ? BigDecimal.ZERO : p.irpf();
        BigDecimal liquido = p.gross().subtract(employeeSs).subtract(irpf);
        if (liquido.signum() <= 0) return null;

        String acc465 = findAccountByPrefix(companyId, "465");
        String acc572 = findAccountByPrefix(companyId, "572");
        if (acc572 == null) acc572 = findAccountByPrefix(companyId, "57");
        if (acc465 == null || acc572 == null) return null;

        reverseBySource(companyId, p.payslipId(), SRC_PAYMENT);

        String name = (p.employeeName() == null || p.employeeName().isBlank())
                ? "Empleado" : p.employeeName();
        String period = String.format("%02d/%d", p.month(), p.year());
        String concept = "Pago nómina " + period + " — " + name;

        String entryId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence,
                    created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                """,
                entryId, companyId, fiscalYearId,
                Date.valueOf(entryDate), concept, SRC_PAYMENT, p.payslipId(), userId);

        insertLine(entryId, acc465, "Líquido a percibir — " + name, liquido, BigDecimal.ZERO);
        insertLine(entryId, acc572, "Pago nómina " + period + " — " + name, BigDecimal.ZERO, liquido);
        return entryId;
    }

    /**
     * Borra los asientos (devengo y pago) vinculados a una nómina cuando
     * esta se elimina. Mismo trato que ventas/compras: hueco en el Diario
     * en lugar de VOIDED.
     */
    @Transactional
    public void reverseAll(String payslipId) {
        String companyId = tenantContext.getCurrentCompanyId();
        reverseBySource(companyId, payslipId, SRC_ACCRUAL);
        reverseBySource(companyId, payslipId, SRC_PAYMENT);
    }

    // ---- helpers ----------------------------------------------------------

    private void reverseBySource(String companyId, String payslipId, String sourceType) {
        List<String> entryIds = jdbcTemplate.query("""
                SELECT id FROM journal_entries
                 WHERE source_type = ? AND source_id = ? AND company_id = ?
                   AND status = 'DRAFT'
                """, (rs, n) -> rs.getString("id"),
                sourceType, payslipId, companyId);
        for (String entryId : entryIds) {
            jdbcTemplate.update("DELETE FROM journal_entry_lines WHERE journal_entry_id = ?", entryId);
            jdbcTemplate.update("DELETE FROM journal_entries WHERE id = ? AND company_id = ?",
                    entryId, companyId);
        }
    }

    /**
     * Suma las cuotas TC del periodo cuyo {@code contribution_type}
     * empieza por el prefijo dado ({@code EMPLOYER} o {@code EMPLOYEE}).
     */
    private BigDecimal sumContributions(String companyId, String employeeId,
                                         int year, int month, String typePrefix) {
        BigDecimal sum = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(contribution_amount), 0)
                  FROM social_security_contributions
                 WHERE company_id = ? AND employee_id = ?
                   AND period_year = ? AND period_month = ?
                   AND contribution_type LIKE ?
                """, BigDecimal.class,
                companyId, employeeId, year, month, typePrefix + "%");
        return sum == null ? BigDecimal.ZERO : sum;
    }

    private String findOpenFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND status = 'OPEN'
                   AND ? BETWEEN start_date AND end_date
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, Date.valueOf(date));
        return ids.isEmpty() ? null : ids.get(0);
    }

    private String findAccountByPrefix(String companyId, String prefix) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%");
        return ids.isEmpty() ? null : ids.get(0);
    }

    private void insertLine(String entryId, String accountId, String description,
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId, description, debit, credit);
    }
}
