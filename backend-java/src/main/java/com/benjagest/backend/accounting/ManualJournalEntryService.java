package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Asientos manuales libres — la pieza fundamental que permite al asesor
 * llevar contabilidad completa más allá de las facturas. Es la base sobre
 * la que se montan:
 *
 * <ul>
 *   <li>Periodificaciones (gastos/ingresos anticipados).</li>
 *   <li>Provisiones (insolvencias, IS estimado, vacaciones devengadas).</li>
 *   <li>Ajustes de cierre (revalorizaciones, deterioros).</li>
 *   <li>Movimientos bancarios manuales (préstamos, transferencias).</li>
 *   <li>Cualquier asiento atípico del PGC.</li>
 * </ul>
 *
 * <p>Reglas de negocio:
 * <ul>
 *   <li><b>Balance obligatorio</b>: sum(Debe) == sum(Haber) hasta 1 céntimo.</li>
 *   <li><b>Mínimo 2 líneas</b>: un asiento con 1 línea es contable inválido.</li>
 *   <li><b>Fiscal guard</b>: si la fecha cae en LOCKED/CLOSED, lanza 409.</li>
 *   <li><b>Cuentas activas</b>: las account_id referenciadas deben existir,
 *       pertenecer a la empresa actual y estar activas.</li>
 *   <li><b>Idempotencia de número</b>: entry_number se asigna desde el
 *       servidor, no lo aporta el cliente. Race conditions toleradas para
 *       el caso 95% (PYMES) — el slice contable serio usará secuencia
 *       reservada FOR UPDATE.</li>
 * </ul>
 *
 * <p>Estados:
 * <ul>
 *   <li>{@code DRAFT}: editable, sin efecto en libros oficiales.</li>
 *   <li>{@code POSTED}: validado, computa en libros. Solo se puede anular
 *       con un asiento de signo opuesto (no se borra).</li>
 *   <li>{@code VOIDED}: anulado. La línea original queda visible pero
 *       no computa en saldos.</li>
 * </ul>
 *
 * <p>Trazabilidad: source_type=null para distinguirlos de los asientos
 * auto-generados (SALES_INVOICE/PURCHASE_INVOICE/YEAR_CLOSE_*).
 */
@Service
public class ManualJournalEntryService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final FiscalYearGuardService fiscalGuard;

    public ManualJournalEntryService(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext,
                                       CurrentUserService currentUserService,
                                       FiscalYearGuardService fiscalGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.fiscalGuard = fiscalGuard;
    }

    // ====================================================================
    //  Crear / actualizar / postear
    // ====================================================================

    @Transactional
    public ManualEntryView createDraft(ManualEntryRequest req) {
        validateRequest(req);
        String companyId = tenantContext.getCurrentCompanyId();
        fiscalGuard.requireOpenForDate(req.entryDate(), "crear asiento contable");

        String fiscalYearId = resolveFiscalYearId(companyId, req.entryDate());

        int entryNumber = nextEntryNumber(companyId, fiscalYearId);
        String entryId = UUID.randomUUID().toString();
        String userId = safeUserId();

        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, NULL, NULL, ?, FALSE, FALSE, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(req.entryDate()),
                truncate(req.concept(), 240),
                req.postNow() ? "POSTED" : "DRAFT",
                userId);
        insertLines(entryId, req.lines(), companyId);
        return get(entryId);
    }

    /**
     * Reemplaza completamente las líneas y la cabecera de un asiento DRAFT.
     * No permite editar asientos POSTED — para corregirlos hay que anular
     * y crear uno nuevo (norma contable básica).
     */
    @Transactional
    public ManualEntryView updateDraft(String entryId, ManualEntryRequest req) {
        validateRequest(req);
        ManualEntryView current = get(entryId);
        if (!"DRAFT".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden editar asientos en DRAFT. Para corregir uno POSTED, créa un asiento de signo opuesto o usa la opción Anular.");
        }
        fiscalGuard.requireOpenForDate(req.entryDate(), "modificar asiento contable");

        String companyId = tenantContext.getCurrentCompanyId();
        String newFiscalYearId = resolveFiscalYearId(companyId, req.entryDate());

        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET entry_date = ?, concept = ?,
                       fiscal_year_id = ?,
                       status = ?
                 WHERE id = ? AND company_id = ?
                """,
                Date.valueOf(req.entryDate()), truncate(req.concept(), 240),
                newFiscalYearId,
                req.postNow() ? "POSTED" : "DRAFT",
                entryId, companyId);

        jdbcTemplate.update("""
                DELETE FROM journal_entry_lines WHERE journal_entry_id = ?
                """, entryId);
        insertLines(entryId, req.lines(), companyId);
        return get(entryId);
    }

    /** Pasa el asiento de DRAFT a POSTED. */
    @Transactional
    public ManualEntryView post(String entryId) {
        ManualEntryView current = get(entryId);
        if ("POSTED".equals(current.status())) return current;
        if ("VOIDED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El asiento está anulado, no se puede postear.");
        }
        fiscalGuard.requireOpenForDate(current.entryDate(), "validar asiento contable");

        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'POSTED', reviewed = TRUE
                 WHERE id = ? AND company_id = ?
                """, entryId, tenantContext.getCurrentCompanyId());
        return get(entryId);
    }

    /**
     * Anula un asiento POSTED creando un asiento espejo de signo opuesto
     * (contraasiento). El asiento original se marca como VOIDED para que
     * no compute en saldos, pero queda visible en el Libro Diario por
     * trazabilidad legal.
     */
    @Transactional
    public ManualEntryView voidEntry(String entryId, String reason) {
        ManualEntryView original = get(entryId);
        if ("VOIDED".equals(original.status())) return original;
        fiscalGuard.requireOpenForDate(original.entryDate(), "anular asiento contable");

        String companyId = tenantContext.getCurrentCompanyId();
        String userId = safeUserId();

        // 1) Marcar el original como VOIDED.
        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'VOIDED'
                 WHERE id = ? AND company_id = ?
                """, entryId, companyId);

        // 2) Crear contraasiento con líneas invertidas.
        String reverseId = UUID.randomUUID().toString();
        int reverseNumber = nextEntryNumber(companyId, original.fiscalYearId());
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, 'MANUAL_REVERSAL', ?, 'POSTED', TRUE, FALSE, ?)
                """,
                reverseId, companyId, original.fiscalYearId(), reverseNumber,
                Date.valueOf(LocalDate.now()),
                truncate("Anulación asiento " + original.entryNumber()
                        + (reason == null || reason.isBlank() ? "" : " — " + reason), 240),
                entryId, userId);

        for (ManualEntryLine ln : original.lines()) {
            jdbcTemplate.update("""
                    INSERT INTO journal_entry_lines (
                        id, journal_entry_id, account_id, description, debit, credit
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), reverseId, ln.accountId(),
                    truncate("Anulación " + safe(ln.description()), 240),
                    // Inversión: lo que era Debe pasa a Haber y viceversa.
                    ln.credit(), ln.debit());
        }
        return get(entryId);
    }

    // ====================================================================
    //  Consulta
    // ====================================================================

    public ManualEntryView get(String entryId) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<ManualEntryHeader> headers = jdbcTemplate.query("""
                SELECT je.id, je.company_id, je.fiscal_year_id, je.entry_number,
                       je.entry_date, je.concept, je.source_type, je.source_id,
                       je.status, je.reviewed, je.auto_proposed,
                       je.proposed_confidence, je.created_by,
                       je.created_at, je.updated_at
                  FROM journal_entries je
                 WHERE je.id = ? AND je.company_id = ?
                """, this::mapHeader, entryId, companyId);
        if (headers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asiento no encontrado");
        }
        ManualEntryHeader h = headers.get(0);
        List<ManualEntryLine> lines = jdbcTemplate.query("""
                SELECT l.id, l.account_id, a.code AS account_code, a.name AS account_name,
                       l.description, l.debit, l.credit
                  FROM journal_entry_lines l
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ?
                 ORDER BY l.created_at, l.id
                """,
                (rs, n) -> new ManualEntryLine(
                        rs.getString("id"), rs.getString("account_id"),
                        rs.getString("account_code"), rs.getString("account_name"),
                        rs.getString("description"),
                        rs.getBigDecimal("debit"), rs.getBigDecimal("credit")),
                entryId);
        return new ManualEntryView(
                h.id(), h.companyId(), h.fiscalYearId(), h.entryNumber(),
                h.entryDate(), h.concept(), h.sourceType(), h.sourceId(),
                h.status(), h.reviewed(), h.autoProposed(),
                h.proposedConfidence(), h.createdBy(),
                h.createdAt(), h.updatedAt(), lines);
    }

    // ====================================================================
    //  Validación
    // ====================================================================

    private void validateRequest(ManualEntryRequest req) {
        if (req.entryDate() == null) {
            throw bad("La fecha del asiento es obligatoria.");
        }
        if (req.concept() == null || req.concept().isBlank()) {
            throw bad("El concepto del asiento es obligatorio.");
        }
        if (req.lines() == null || req.lines().size() < 2) {
            throw bad("Un asiento contable debe tener al menos 2 líneas.");
        }
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        int idx = 0;
        for (LineRequest ln : req.lines()) {
            idx++;
            if (ln.accountId() == null || ln.accountId().isBlank()) {
                throw bad("Línea " + idx + ": falta la cuenta.");
            }
            BigDecimal d = ln.debit() == null ? BigDecimal.ZERO : ln.debit();
            BigDecimal c = ln.credit() == null ? BigDecimal.ZERO : ln.credit();
            if (d.signum() < 0 || c.signum() < 0) {
                throw bad("Línea " + idx + ": importes negativos no permitidos. Usa la cuenta opuesta.");
            }
            if (d.signum() > 0 && c.signum() > 0) {
                throw bad("Línea " + idx + ": no puede tener Debe y Haber simultáneamente.");
            }
            if (d.signum() == 0 && c.signum() == 0) {
                throw bad("Línea " + idx + ": la línea está vacía.");
            }
            totalDebit = totalDebit.add(d);
            totalCredit = totalCredit.add(c);
        }
        if (totalDebit.subtract(totalCredit).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw bad("El asiento no cuadra: Debe=" + totalDebit + " Haber=" + totalCredit
                    + " (diferencia " + totalDebit.subtract(totalCredit) + ")");
        }
    }

    private void insertLines(String entryId, List<LineRequest> lines, String companyId) {
        for (LineRequest ln : lines) {
            // Validación tardía: la cuenta debe existir y ser de la empresa.
            Integer ok = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM accounting_accounts
                     WHERE id = ? AND company_id = ? AND active = TRUE
                    """, Integer.class, ln.accountId(), companyId);
            if (ok == null || ok == 0) {
                throw bad("Cuenta " + ln.accountId() + " no existe en esta empresa o no está activa.");
            }
            BigDecimal d = ln.debit() == null ? BigDecimal.ZERO : ln.debit();
            BigDecimal c = ln.credit() == null ? BigDecimal.ZERO : ln.credit();
            jdbcTemplate.update("""
                    INSERT INTO journal_entry_lines (
                        id, journal_entry_id, account_id, description, debit, credit
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), entryId, ln.accountId(),
                    truncate(ln.description(), 240), d, c);
        }
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String resolveFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ?
                   AND start_date <= ?
                   AND end_date >= ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, Date.valueOf(date), Date.valueOf(date));
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No existe ejercicio fiscal para la fecha " + date
                            + ". Crea uno en Configuración → Contabilidad → Ejercicios.");
        }
        return ids.get(0);
    }

    private int nextEntryNumber(String companyId, String fiscalYearId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        return (max == null ? 0 : max) + 1;
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private ManualEntryHeader mapHeader(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        BigDecimal pc = rs.getBigDecimal("proposed_confidence");
        return new ManualEntryHeader(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("fiscal_year_id"), rs.getInt("entry_number"),
                rs.getDate("entry_date").toLocalDate(),
                rs.getString("concept"),
                rs.getString("source_type"), rs.getString("source_id"),
                rs.getString("status"), rs.getBoolean("reviewed"),
                rs.getBoolean("auto_proposed"), pc,
                rs.getString("created_by"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    // ====================================================================
    //  DTOs públicos
    // ====================================================================

    public record ManualEntryRequest(
            LocalDate entryDate,
            String concept,
            List<LineRequest> lines,
            boolean postNow
    ) {}

    public record LineRequest(
            String accountId,
            String description,
            BigDecimal debit,
            BigDecimal credit
    ) {}

    public record ManualEntryLine(
            String id,
            String accountId,
            String accountCode,
            String accountName,
            String description,
            BigDecimal debit,
            BigDecimal credit
    ) {}

    public record ManualEntryView(
            String id, String companyId, String fiscalYearId, int entryNumber,
            LocalDate entryDate, String concept,
            String sourceType, String sourceId,
            String status, boolean reviewed, boolean autoProposed,
            BigDecimal proposedConfidence, String createdBy,
            Instant createdAt, Instant updatedAt,
            List<ManualEntryLine> lines
    ) {}

    private record ManualEntryHeader(
            String id, String companyId, String fiscalYearId, int entryNumber,
            LocalDate entryDate, String concept,
            String sourceType, String sourceId,
            String status, boolean reviewed, boolean autoProposed,
            BigDecimal proposedConfidence, String createdBy,
            Instant createdAt, Instant updatedAt
    ) {}

    /** Sentinel para futura validación cruzada con CO/CA. */
    @SuppressWarnings("unused")
    private List<Object> _sentinel() { return new ArrayList<>(); }
}
