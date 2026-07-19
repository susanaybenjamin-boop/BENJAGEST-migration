package com.benjagest.backend.labor;

import com.benjagest.backend.accounting.FiscalYearGuardService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * LIQ-SS — El puente contable de la liquidación mensual de la Seguridad Social.
 *
 * <p><b>El hueco que tapa.</b> Es el gemelo del que arreglaron LIQ-303 (IVA) y
 * LIQ-111 (retenciones IRPF): la cuenta <b>476</b> (Organismos de la Seguridad
 * Social, acreedores) se ABONABA en cada nómina
 * ({@link PayslipJournalEntryService}) y NUNCA se saldaba. El único "pago" que
 * existía era el líquido al trabajador ({@code 465 → 572}); la cuota a la TGSS
 * se quedaba colgada creciendo sin fin.
 *
 * <p><b>Por qué NO es un modelo AEAT.</b> A diferencia del IRPF, la SS no se
 * presenta en {@code tax_filings}: es la liquidación MENSUAL (RLC/TC, Sistema de
 * Liquidación Directa) que la TGSS carga por domiciliación el último día del mes
 * siguiente. Por eso se paga por MES, con todos los empleados juntos.
 *
 * <p><b>Importe.</b> Σ {@code contribution_amount} de
 * {@code social_security_contributions} del mes (empresa + trabajador) — la
 * misma fuente de verdad que la nómina usa para el 642/476. Asiento de PAGO:
 * {@code Debe 476 / Haber 572}. Idempotente por
 * {@code (source_type=SS_PAYMENT, source_id="SS-YYYY-MM")}: pagar dos veces no
 * duplica el asiento.
 *
 * <p>Como los demás puentes fiscales, el pago se puede regularizar hacia atrás
 * (meses ya pagados de verdad pero sin asiento) con la fecha de cargo real (fin
 * del mes siguiente), no la de hoy.
 */
@Service
public class SocialSecurityLedgerService {

    private static final Logger log = LoggerFactory.getLogger(SocialSecurityLedgerService.class);

    public static final String SRC_SS_PAYMENT = "SS_PAYMENT";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final FiscalYearGuardService fiscalGuard;

    public SocialSecurityLedgerService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
            CurrentUserService currentUserService, FiscalYearGuardService fiscalGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.fiscalGuard = fiscalGuard;
    }

    /** Un mes con cuota de SS, tal como se muestra antes de pagar. */
    public record PendingSsMonth(int year, int month, BigDecimal amount,
            LocalDate paymentDate, boolean alreadyPaid, String motivo) {}

    /**
     * Cuota total de SS del mes (empresa + trabajador). Solo cuenta las
     * cotizaciones respaldadas por una NÓMINA viva del mismo empleado y periodo:
     * si se borró la nómina pero quedaron sus cotizaciones huérfanas (borrar una
     * nómina no las limpiaba — bug histórico), NO se pagan. Así la liquidación de
     * SS refleja lo que de verdad hay que ingresar, no restos de nóminas
     * deshechas.
     */
    public BigDecimal amountForMonth(String companyId, int year, int month) {
        BigDecimal v = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(c.contribution_amount), 0)
                  FROM social_security_contributions c
                 WHERE c.company_id = ? AND c.period_year = ? AND c.period_month = ?
                   AND EXISTS (
                        SELECT 1 FROM payslips p
                         WHERE p.company_id = c.company_id
                           AND p.employee_id = c.employee_id
                           AND p.period_year = c.period_year
                           AND p.period_month = c.period_month
                           AND p.status <> 'CANCELLED')
                """, BigDecimal.class, companyId, year, month);
        return v == null ? BigDecimal.ZERO : v;
    }

    /**
     * Meses del año con cuota de SS &gt; 0, marcando cuáles ya tienen asiento de
     * pago y por qué alguno no se podría contabilizar (falta cuenta o ejercicio
     * cerrado). La UI lo enseña antes de pagar (igual que el backfill fiscal).
     */
    public List<PendingSsMonth> previewPending(int year) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<PendingSsMonth> out = new ArrayList<>();
        for (int m = 1; m <= 12; m++) {
            BigDecimal amount = amountForMonth(companyId, year, m);
            if (amount.signum() <= 0) continue;
            boolean paid = existingPayment(companyId, year, m) != null;
            LocalDate date = chargeDate(year, m);
            String motivo = null;
            if (accountByPrefix(companyId, "476") == null) {
                motivo = "Falta la cuenta 476 en el plan contable.";
            } else if (accountByPrefix(companyId, "572") == null) {
                motivo = "Falta la cuenta 572 (bancos) en el plan contable.";
            } else if (!"OPEN".equals(fiscalYearStatus(companyId, date))) {
                motivo = "El ejercicio de " + date + " no está abierto.";
            }
            out.add(new PendingSsMonth(year, m, amount, date, paid, motivo));
        }
        return out;
    }

    /**
     * Paga la SS de un mes: asiento {@code Debe 476 / Haber 572} por la cuota
     * total del mes. Idempotente. {@code date} null = hoy (flujo normal); para
     * regularizar hacia atrás se pasa la fecha de cargo real.
     *
     * <p>Nunca revienta por la contabilidad si la cuota existe: si falta una
     * cuenta o el ejercicio está cerrado, {@link FiscalYearGuardService} o el
     * propio guard de cuentas lanzan y el caller decide.
     */
    @Transactional
    public String payMonth(int year, int month, LocalDate date) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (existingPayment(companyId, year, month) != null) {
            log.info("LIQ-SS: la SS de {}/{} ya tiene asiento de pago; no creo otro.", month, year);
            return null;
        }
        BigDecimal amount = amountForMonth(companyId, year, month);
        if (amount.signum() <= 0) {
            log.info("LIQ-SS: {}/{} sin cuota de SS; nada que pagar.", month, year);
            return null;
        }
        LocalDate entryDate = date != null ? date : LocalDate.now();
        fiscalGuard.requireOpenForDate(companyId, entryDate,
                "contabilizar el pago de la Seguridad Social");

        String acc476 = accountByPrefix(companyId, "476");
        String acc572 = accountByPrefix(companyId, "572");
        if (acc476 == null || acc572 == null) {
            throw new IllegalStateException("faltan cuentas para el pago de SS (476 / 572)");
        }

        String period = String.format("%02d/%d", month, year);
        String concept = "Pago Seguridad Social " + period;
        String entryId = insertEntry(companyId, entryDate, concept, sourceIdFor(year, month));
        insertLine(entryId, acc476, concept, amount, BigDecimal.ZERO);
        insertLine(entryId, acc572, concept, BigDecimal.ZERO, amount);
        log.info("LIQ-SS: asiento de pago {} creado para la SS de {} (importe {})",
                entryId, period, amount);
        return entryId;
    }

    /**
     * Paga TODOS los meses del año con cuota pendiente y sin asiento, cada uno
     * con su fecha de cargo real (fin del mes siguiente). Devuelve cuántos se
     * pagaron. Los meses con impedimento (cuenta/ejercicio) se saltan.
     */
    @Transactional
    public int payAllPending(int year) {
        int n = 0;
        for (PendingSsMonth p : previewPending(year)) {
            if (p.alreadyPaid() || p.motivo() != null) continue;
            String id = payMonth(p.year(), p.month(), p.paymentDate());
            if (id != null) n++;
        }
        return n;
    }

    // ---- helpers ----------------------------------------------------------

    /** Fecha de cargo del RLC: último día del mes SIGUIENTE al de devengo. */
    private LocalDate chargeDate(int year, int month) {
        LocalDate next = LocalDate.of(year, month, 1).plusMonths(1);
        return next.withDayOfMonth(next.lengthOfMonth());
    }

    private static String sourceIdFor(int year, int month) {
        return String.format("SS-%04d-%02d", year, month);
    }

    private String existingPayment(String companyId, int year, int month) {
        return jdbcTemplate.query("""
                SELECT id FROM journal_entries
                 WHERE company_id = ? AND source_type = ? AND source_id = ?
                   AND status <> 'VOIDED'
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, SRC_SS_PAYMENT, sourceIdFor(year, month))
                .stream().findFirst().orElse(null);
    }

    private String fiscalYearStatus(String companyId, LocalDate date) {
        return jdbcTemplate.query("""
                SELECT status FROM fiscal_years
                 WHERE company_id = ? AND year_number = ? LIMIT 1
                """, (rs, n) -> rs.getString("status"), companyId, date.getYear())
                .stream().findFirst().orElse("OPEN"); // sin fila = permisivo (como FiscalYearGuardService)
    }

    private String insertEntry(String companyId, LocalDate date, String concept, String sourceId) {
        String fiscalYearId = jdbcTemplate.query("""
                SELECT id FROM fiscal_years WHERE company_id = ? AND year_number = ? LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, date.getYear())
                .stream().findFirst().orElse(null);
        if (fiscalYearId == null) {
            throw new IllegalStateException("no hay ejercicio fiscal " + date.getYear());
        }
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0) FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ? AND entry_number IS NOT NULL
                """, Integer.class, companyId, fiscalYearId);
        int number = (max == null ? 0 : max) + 1;
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', TRUE, FALSE, ?)
                """,
                id, companyId, fiscalYearId, number, Date.valueOf(date),
                concept, SRC_SS_PAYMENT, sourceId, safeUserId());
        return id;
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

    private String accountByPrefix(String companyId, String prefix) {
        return jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%")
                .stream().findFirst().orElse(null);
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    // ====================================================================
    //  API REST — Seguridad Social (liquidación mensual)
    // ====================================================================

    @RestController
    @RequestMapping("/api/labor/social-security")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class SocialSecurityLedgerController {
        private final SocialSecurityLedgerService service;

        public SocialSecurityLedgerController(SocialSecurityLedgerService service) {
            this.service = service;
        }

        /** Meses del año con cuota de SS y su estado (pagada o no). */
        @GetMapping("/pending")
        public List<PendingSsMonth> pending(@RequestParam("year") int year) {
            return service.previewPending(year);
        }

        /** Paga la SS de un mes (asiento 476 → 572) con fecha de hoy. */
        @PostMapping("/pay")
        public Map<String, Object> pay(@RequestParam("year") int year,
                @RequestParam("month") int month) {
            String id = service.payMonth(year, month, null);
            return Map.of("created", id != null, "entryId", id == null ? "" : id);
        }

        /** Paga TODOS los meses pendientes del año, con su fecha de cargo real. */
        @PostMapping("/pay-all")
        public Map<String, Object> payAll(@RequestParam("year") int year) {
            return Map.of("paid", service.payAllPending(year));
        }
    }
}
