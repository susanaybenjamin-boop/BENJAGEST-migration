package com.benjagest.backend.labor;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

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
    /** Provisión mensual de la parte devengada de pagas extra NO prorrateadas. */
    public static final String SRC_EXTRA_PROVISION = "PAYSLIP_EXTRA_PROVISION";
    /** Pago de la paga extra: cancela la provisión acumulada (465 → 4751/572). */
    public static final String SRC_EXTRA_PAYMENT = "PAYSLIP_EXTRA_PAYMENT";
    /** Devengo COMPLETO de la paga extra cuando NO se provisiona mensualmente
     *  (640 → 465 por el bruto). El pago lo cancela igual (465 → 4751/572). */
    public static final String SRC_EXTRA_ACCRUAL = "PAYSLIP_EXTRA_ACCRUAL";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard;

    public PayslipJournalEntryService(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext,
                                       com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.fiscalGuard = fiscalGuard;
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
     *
     * <p>NO lleva {@code @Transactional}: se invoca siempre dentro de la
     * transacción del caller ({@code PayslipService.calculate/markPaid/
     * delete}). Si fuera transaccional y lanzara una excepción, Spring
     * marcaría la transacción de la nómina como rollback-only y el cálculo
     * reventaría aunque la nómina fuese correcta.
     */
    public String createAccrual(PayslipAccrual p, String userId) {
        return createAccrual(p, BigDecimal.ZERO, userId);
    }

    /**
     * Variante para el FINIQUITO: cancela la provisión de pagas extra ya
     * acumulada (465) en vez de volver a gastarla en 640. La parte de paga extra
     * del finiquito se reconoció como gasto mes a mes ({@link #createExtraProvision}
     * 640→465); aquí se liquida esa provisión (465 Debe) y el 640 solo recoge lo
     * NO provisionado (días trabajados + vacaciones + diferencia de prorrata).
     * Así no se duplica el gasto ni queda la provisión colgada.
     * {@code extraProvisionToCancel} = saldo de provisión pendiente del empleado
     * (ver {@link #outstandingExtraProvision}); 0 = comportamiento normal.
     */
    public String createAccrual(PayslipAccrual p, BigDecimal extraProvisionToCancel, String userId) {
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

        // Finiquito con provisión de pagas extra acumulada: la parte ya
        // provisionada se cancela (465 Debe) en vez de re-gastarla en 640, para
        // no duplicar el gasto ni dejar la provisión colgada (descuadre). El 640
        // solo recoge lo no provisionado; si se sobre-provisionó, se regulariza
        // abonando gasto (640 Haber). El asiento cuadra siempre.
        BigDecimal cancel = extraProvisionToCancel == null ? BigDecimal.ZERO : extraProvisionToCancel;
        BigDecimal net640 = p.gross().subtract(cancel);
        if (net640.signum() >= 0) {
            insertLine(entryId, acc640, "Sueldos y salarios — " + name, net640, BigDecimal.ZERO);
        } else {
            insertLine(entryId, acc640, "Regularización provisión pagas extra — " + name,
                    BigDecimal.ZERO, net640.negate());
        }
        if (cancel.signum() > 0) {
            insertLine(entryId, acc465, "Cancelación provisión pagas extra — " + name,
                    cancel, BigDecimal.ZERO);
        }
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
     * Sin {@code @Transactional} por el mismo motivo que {@link #createAccrual}.
     */
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
     * Provisión MENSUAL de la parte devengada de las pagas extra cuando NO se
     * prorratean (se pagan aparte en junio/Navidad). Reconoce el gasto por
     * devengo cada mes (art. 38 CdC): {@code 640 Sueldos y salarios → 465
     * Remuneraciones pendientes de pago}. La cotización de la paga ya circula
     * mes a mes por (642) — aquí solo se provisiona el salario. Idempotente.
     * Devuelve el id del asiento o {@code null} si no procede.
     */
    public String createExtraProvision(String payslipId, String employeeName,
            int year, int month, BigDecimal monthlyAmount, String userId) {
        if (monthlyAmount == null || monthlyAmount.signum() <= 0) return null;
        String companyId = tenantContext.getCurrentCompanyId();
        LocalDate entryDate = LocalDate.of(year, month, 1)
                .withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
        String fiscalYearId = findOpenFiscalYearId(companyId, entryDate);
        if (fiscalYearId == null) return null;
        String acc640 = findAccountByPrefix(companyId, "640");
        String acc465 = findAccountByPrefix(companyId, "465");
        if (acc640 == null || acc465 == null) return null;

        reverseBySource(companyId, payslipId, SRC_EXTRA_PROVISION);
        String name = (employeeName == null || employeeName.isBlank()) ? "Empleado" : employeeName;
        String period = String.format("%02d/%d", month, year);
        String entryId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence, created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                """,
                entryId, companyId, fiscalYearId, Date.valueOf(entryDate),
                "Provisión pagas extra " + period + " — " + name,
                SRC_EXTRA_PROVISION, payslipId, userId);
        insertLine(entryId, acc640, "Provisión pagas extra — " + name, monthlyAmount, BigDecimal.ZERO);
        insertLine(entryId, acc465, "Pagas extra pendientes de pago — " + name, BigDecimal.ZERO, monthlyAmount);
        return entryId;
    }

    /**
     * INC-4 — Devengo COMPLETO de la paga extra cuando la empresa NO provisiona
     * mensualmente (no hay 465 acumulado). Reconoce el gasto de la paga extra de
     * una vez: {@code 640 Sueldos y salarios → 465 Remuneraciones pendientes de
     * pago} por el BRUTO. El pago ({@link #createExtraPayment}) cancela ese 465
     * (465 → 4751/572), exactamente igual que en el modelo con provisión, así que
     * no hay doble conteo de IRPF. SIN línea de SS (ya cotizó prorrateada mes a
     * mes). Idempotente.
     */
    public String createExtraAccrual(String payslipId, String employeeName,
            int year, int month, BigDecimal gross, String userId) {
        if (gross == null || gross.signum() <= 0) return null;
        String companyId = tenantContext.getCurrentCompanyId();
        LocalDate entryDate = LocalDate.of(year, month, 1)
                .withDayOfMonth(LocalDate.of(year, month, 1).lengthOfMonth());
        String fiscalYearId = findOpenFiscalYearId(companyId, entryDate);
        if (fiscalYearId == null) return null;
        String acc640 = findAccountByPrefix(companyId, "640");
        String acc465 = findAccountByPrefix(companyId, "465");
        if (acc640 == null || acc465 == null) return null;

        reverseBySource(companyId, payslipId, SRC_EXTRA_ACCRUAL);
        String name = (employeeName == null || employeeName.isBlank()) ? "Empleado" : employeeName;
        String period = String.format("%02d/%d", month, year);
        String entryId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence, created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                """,
                entryId, companyId, fiscalYearId, Date.valueOf(entryDate),
                "Devengo paga extra " + period + " — " + name,
                SRC_EXTRA_ACCRUAL, payslipId, userId);
        insertLine(entryId, acc640, "Paga extra — " + name, gross, BigDecimal.ZERO);
        insertLine(entryId, acc465, "Pagas extra pendientes de pago — " + name, BigDecimal.ZERO, gross);
        return entryId;
    }

    /**
     * Pago de la PAGA EXTRA (no prorrateada): cancela la provisión acumulada.
     * {@code 465 Debe = bruto paga; 4751 Haber = IRPF; 572 Haber = neto}. SIN
     * línea de Seguridad Social (ya cotizó prorrateada mes a mes). Idempotente.
     */
    public String createExtraPayment(PayslipAccrual p, LocalDate paidAt, String userId) {
        if (p.gross() == null || p.gross().signum() <= 0) return null;
        String companyId = tenantContext.getCurrentCompanyId();
        LocalDate entryDate = paidAt != null ? paidAt
                : LocalDate.of(p.year(), p.month(), 1)
                        .withDayOfMonth(LocalDate.of(p.year(), p.month(), 1).lengthOfMonth());
        String fiscalYearId = findOpenFiscalYearId(companyId, entryDate);
        if (fiscalYearId == null) return null;
        BigDecimal irpf = p.irpf() == null ? BigDecimal.ZERO : p.irpf();
        BigDecimal neto = p.gross().subtract(irpf);
        if (neto.signum() <= 0) return null;

        String acc465 = findAccountByPrefix(companyId, "465");
        String acc4751 = findAccountByPrefix(companyId, "4751");
        if (acc4751 == null) acc4751 = findAccountByPrefix(companyId, "475");
        String acc572 = findAccountByPrefix(companyId, "572");
        if (acc572 == null) acc572 = findAccountByPrefix(companyId, "57");
        if (acc465 == null || acc572 == null || acc4751 == null) return null;

        reverseBySource(companyId, p.payslipId(), SRC_EXTRA_PAYMENT);
        String name = (p.employeeName() == null || p.employeeName().isBlank()) ? "Empleado" : p.employeeName();
        String period = String.format("%02d/%d", p.month(), p.year());
        String entryId = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, proposed_confidence, created_by
                ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, 'DRAFT', FALSE, TRUE, NULL, ?)
                """,
                entryId, companyId, fiscalYearId, Date.valueOf(entryDate),
                "Pago paga extra " + period + " — " + name,
                SRC_EXTRA_PAYMENT, p.payslipId(), userId);
        insertLine(entryId, acc465, "Pagas extra pendientes de pago — " + name, p.gross(), BigDecimal.ZERO);
        if (irpf.signum() > 0) {
            insertLine(entryId, acc4751, "Retención IRPF paga extra — " + name, BigDecimal.ZERO, irpf);
        }
        insertLine(entryId, acc572, "Pago paga extra " + period + " — " + name, BigDecimal.ZERO, neto);
        return entryId;
    }

    /**
     * Borra los asientos (devengo y pago) vinculados a una nómina cuando
     * esta se elimina. Mismo trato que ventas/compras: hueco en el Diario
     * en lugar de VOIDED. Sin {@code @Transactional} (corre en la tx del caller).
     */
    public void reverseAll(String payslipId) {
        String companyId = tenantContext.getCurrentCompanyId();
        reverseOrContraBySource(companyId, payslipId, SRC_ACCRUAL);
        reverseOrContraBySource(companyId, payslipId, SRC_PAYMENT);
        reverseOrContraBySource(companyId, payslipId, SRC_EXTRA_PROVISION);
        reverseOrContraBySource(companyId, payslipId, SRC_EXTRA_ACCRUAL);
        reverseOrContraBySource(companyId, payslipId, SRC_EXTRA_PAYMENT);
    }

    /**
     * Borrar la nómina revierte sus asientos: si están por validar (DRAFT) se
     * borran limpio; si ya están VALIDADOS (POSTED) NO se borran (RD 1007/2023 /
     * integridad del Diario) — se genera un CONTRASIENTO por validar (débitos y
     * créditos invertidos). Mismo patrón que REFLEJO. Así, tras borrar y
     * regenerar, no quedan asientos validados huérfanos ni se duplica el gasto.
     */
    private void reverseOrContraBySource(String companyId, String payslipId, String sourceType) {
        List<Object[]> entries = jdbcTemplate.query("""
                SELECT id, status, fiscal_year_id, entry_date FROM journal_entries
                 WHERE company_id = ? AND source_type = ? AND source_id = ?
                """, (rs, n) -> new Object[]{rs.getString("id"), rs.getString("status"),
                        rs.getString("fiscal_year_id"), rs.getDate("entry_date")},
                companyId, sourceType, payslipId);
        for (Object[] e : entries) {
            String entryId = (String) e[0];
            String status = (String) e[1];
            // LOCK (2026-07-07): un asiento POSTED de un ejercicio
            // LOCKED/CLOSED ni se borra ni se contrarresta con un
            // contraasiento fechado dentro de ese ejercicio (jamás podría
            // validarse). Borrar una nómina de un ejercicio cerrado se
            // bloquea entero con 409.
            if ("POSTED".equals(status) && e[3] != null) {
                fiscalGuard.requireOpenForDate(companyId,
                        ((java.sql.Date) e[3]).toLocalDate(),
                        "revertir el asiento de esta nómina");
            }
            if (!"POSTED".equals(status)) {
                jdbcTemplate.update("DELETE FROM accounting_learning_events WHERE journal_entry_id = ? AND company_id = ?",
                        entryId, companyId);
                jdbcTemplate.update("DELETE FROM journal_entry_lines WHERE journal_entry_id = ?", entryId);
                jdbcTemplate.update("DELETE FROM journal_entries WHERE id = ? AND company_id = ?", entryId, companyId);
            } else {
                // Contrasiento por validar: no se borra un asiento validado.
                String revId = UUID.randomUUID().toString();
                jdbcTemplate.update("""
                        INSERT INTO journal_entries (
                            id, company_id, fiscal_year_id, entry_number, entry_date, concept,
                            source_type, source_id, status, reviewed, auto_proposed, proposed_confidence, created_by
                        ) VALUES (?, ?, ?, NULL, ?, ?, 'MANUAL_REVERSAL', ?, 'DRAFT', FALSE, TRUE, NULL, NULL)
                        """,
                        revId, companyId, (String) e[2], (java.sql.Date) e[3],
                        "Contraasiento nómina", entryId);
                jdbcTemplate.query("""
                        SELECT account_id, description, debit, credit FROM journal_entry_lines
                         WHERE journal_entry_id = ?
                        """, rs -> {
                    insertLine(revId, rs.getString("account_id"), rs.getString("description"),
                            rs.getBigDecimal("credit"), rs.getBigDecimal("debit"));
                    return null;
                }, entryId);
            }
        }
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
    /**
     * Saldo de provisión de pagas extra pendiente de un empleado: abonos a 465
     * por provisiones/devengos de extra ({@code PAYSLIP_EXTRA_PROVISION}/
     * {@code _ACCRUAL}) menos cargos por sus pagos ({@code PAYSLIP_EXTRA_PAYMENT}).
     * Es lo que el finiquito debe cancelar para no duplicar gasto ni dejar la
     * provisión colgada. Cero si no hay cuenta 465, sin provisiones o si (por
     * datos raros) el neto es negativo.
     */
    public BigDecimal outstandingExtraProvision(String employeeId) {
        String companyId = tenantContext.getCurrentCompanyId();
        String acc465 = findAccountByPrefix(companyId, "465");
        if (acc465 == null) return BigDecimal.ZERO;
        BigDecimal p = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(
                    CASE WHEN je.source_type IN (?, ?) THEN jl.credit
                         WHEN je.source_type = ? THEN -jl.debit
                         ELSE 0 END), 0)
                  FROM journal_entry_lines jl
                  JOIN journal_entries je ON je.id = jl.journal_entry_id
                  JOIN payslips ps ON ps.id = je.source_id
                 WHERE je.company_id = ? AND ps.employee_id = ?
                   AND jl.account_id = ?
                   AND je.source_type IN (?, ?, ?)
                """, BigDecimal.class,
                SRC_EXTRA_PROVISION, SRC_EXTRA_ACCRUAL, SRC_EXTRA_PAYMENT,
                companyId, employeeId, acc465,
                SRC_EXTRA_PROVISION, SRC_EXTRA_ACCRUAL, SRC_EXTRA_PAYMENT);
        return (p == null || p.signum() < 0) ? BigDecimal.ZERO : p;
    }

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
