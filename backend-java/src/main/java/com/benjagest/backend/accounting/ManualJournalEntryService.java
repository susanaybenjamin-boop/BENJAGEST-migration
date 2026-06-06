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
    private final TerceroAccountResolverService terceroResolver;

    public ManualJournalEntryService(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext,
                                       CurrentUserService currentUserService,
                                       FiscalYearGuardService fiscalGuard,
                                       TerceroAccountResolverService terceroResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.fiscalGuard = fiscalGuard;
        this.terceroResolver = terceroResolver;
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

        // entry_number: si entra en POSTED directamente, asignar; si entra
        // en DRAFT, dejar NULL hasta que se valide. Así el Diario solo
        // numera los POSTED y van en orden de validación, sin huecos.
        boolean postingNow = req.postNow();
        Integer entryNumber = postingNow
                ? nextPostedEntryNumber(companyId, fiscalYearId)
                : null;
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
                postingNow ? "POSTED" : "DRAFT",
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

    /**
     * Valida un lote de asientos DRAFT pasándolos a POSTED. Ideal para
     * el flujo "el sistema auto-propuso 20 asientos esta semana, voy a
     * validar los 10 que ya estaban bien": el asesor marca varios y
     * dispara el endpoint batch.
     *
     * <p>Recorre cada id y reusa {@link #post(String)}. Si uno falla
     * (fecha en periodo CLOSED, validaciones de cuenta, etc.) lo cuenta
     * como error y sigue con los demás — la respuesta lleva el detalle
     * por id.
     */
    @Transactional
    public BatchPostResult postBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BatchPostResult(0, 0, 0, 0, List.of());
        }
        int total = ids.size();
        int posted = 0, skipped = 0, errors = 0;
        java.util.List<BatchPostItem> items = new java.util.ArrayList<>();
        for (String id : ids) {
            try {
                ManualEntryView cur = get(id);
                if (!"DRAFT".equals(cur.status())) {
                    skipped++;
                    items.add(new BatchPostItem(id, "SKIPPED",
                            "status=" + cur.status(), cur.entryNumber()));
                    continue;
                }
                ManualEntryView posted_ = post(id);
                posted++;
                items.add(new BatchPostItem(id, posted_.status(), null,
                        posted_.entryNumber()));
            } catch (Exception ex) {
                errors++;
                items.add(new BatchPostItem(id, "ERROR",
                        ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                        0));
            }
        }
        return new BatchPostResult(total, posted, skipped, errors, items);
    }

    public record BatchPostRequest(List<String> ids) {}
    public record BatchPostItem(
            String id, String status, String message, int entryNumber
    ) {}
    public record BatchPostResult(
            int total, int posted, int skipped, int errors,
            List<BatchPostItem> items
    ) {}

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
        String companyId = tenantContext.getCurrentCompanyId();

        // Asignar entry_number AHORA — el siguiente correlativo entre los
        // POSTED del fiscal year. Así el Diario queda en orden de
        // validación, sin huecos vacíos por DRAFTs intermedios.
        int newNumber = nextPostedEntryNumber(companyId, current.fiscalYearId());
        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'POSTED', reviewed = TRUE, entry_number = ?
                 WHERE id = ? AND company_id = ?
                """, newNumber, entryId, companyId);
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
        // Concepto del asiento — lo usamos como nombre del tercero si la
        // UI mandó un código 4000/4300 desconocido y hay que auto-crear.
        String concept = jdbcTemplate.query("""
                SELECT concept FROM journal_entries WHERE id = ?
                """, (rs, n) -> rs.getString("concept"), entryId)
                .stream().findFirst().orElse(null);

        for (LineRequest ln : lines) {
            String accountId = resolveAccountId(ln, companyId, concept);
            BigDecimal d = ln.debit() == null ? BigDecimal.ZERO : ln.debit();
            BigDecimal c = ln.credit() == null ? BigDecimal.ZERO : ln.credit();
            jdbcTemplate.update("""
                    INSERT INTO journal_entry_lines (
                        id, journal_entry_id, account_id, description, debit, credit
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), entryId, accountId,
                    truncate(ln.description(), 240), d, c);
        }
    }

    /**
     * Resuelve el id de cuenta de una línea con tolerancia y autocreación:
     * <ol>
     *   <li>Si {@code accountId} es UUID válido y existe activa → úsalo.</li>
     *   <li>Si no, intenta resolver por {@code accountCode} (el código que
     *       el asesor tecleó en el combo).</li>
     *   <li>Si tampoco existe por código y el código pinta a tercero
     *       (4000xxx proveedor / 4300xxx cliente) → llama al
     *       {@link TerceroAccountResolverService} con el concepto del
     *       asiento como nombre del tercero. Esto autocrea
     *       4000NNN / 4300NNN sin pedir nada al asesor.</li>
     *   <li>Si no es tercero y no existe → 400 BAD_REQUEST con mensaje
     *       claro.</li>
     * </ol>
     */
    private String resolveAccountId(LineRequest ln, String companyId, String concept) {
        // 1. Por UUID (caso normal cuando la UI ya resolvió la cuenta).
        if (ln.accountId() != null && !ln.accountId().isBlank()) {
            Integer ok = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM accounting_accounts
                     WHERE id = ? AND company_id = ? AND active = TRUE
                    """, Integer.class, ln.accountId(), companyId);
            if (ok != null && ok > 0) return ln.accountId();
        }

        // 2. Por código (el asesor tecleó "628" o "4000007").
        String rawCode = ln.accountCode() != null && !ln.accountCode().isBlank()
                ? ln.accountCode().trim()
                : (ln.accountId() != null ? ln.accountId().trim() : null);
        if (rawCode != null && !rawCode.isBlank()) {
            List<String> ids = jdbcTemplate.query("""
                    SELECT id FROM accounting_accounts
                     WHERE company_id = ? AND code = ? AND active = TRUE
                     LIMIT 1
                    """,
                    (rs, n) -> rs.getString("id"),
                    companyId, rawCode);
            if (!ids.isEmpty()) return ids.get(0);

            // 3. Si pinta a tercero, autocrear via resolver.
            if (looksLikeTerceroCode(rawCode)) {
                TerceroAccountResolverService.TerceroType type =
                        rawCode.startsWith("4000")
                                ? TerceroAccountResolverService.TerceroType.PROVEEDOR
                                : TerceroAccountResolverService.TerceroType.CLIENTE;
                // Nombre del tercero: el concepto del asiento (sin
                // "Fra. 123 - " si aparece). El resolver normaliza para
                // futuras búsquedas.
                String terceroName = guessTerceroName(concept);
                TerceroAccountResolverService.ResolvedAccount r =
                        terceroResolver.getOrCreate(type, null, terceroName);
                if (r != null && r.accountId() != null) return r.accountId();
            }
        }

        throw bad("Cuenta " + (rawCode != null ? rawCode : ln.accountId())
                + " no existe en esta empresa o no está activa.");
    }

    private static boolean looksLikeTerceroCode(String code) {
        if (code == null) return false;
        String c = code.trim();
        if (c.length() < 4) return false;
        if (!c.startsWith("4000") && !c.startsWith("4300")) return false;
        return c.chars().allMatch(Character::isDigit);
    }

    /**
     * Extrae un nombre razonable de tercero a partir del concepto del
     * asiento. Ejemplos:
     *   "Fra. 2024-001 - Mapfre" → "Mapfre"
     *   "Venta 2024-007 a Marcos SL" → "Marcos SL"
     *   "Compra Amazon EU" → "Amazon EU"
     * Si no detecta separador, devuelve el concepto completo (mejor algo
     * que nada — el asesor lo podrá renombrar después).
     */
    private static String guessTerceroName(String concept) {
        if (concept == null || concept.isBlank()) return "Tercero";
        // " - " separador habitual en Fra./Compra.
        int dash = concept.indexOf(" - ");
        if (dash > 0 && dash < concept.length() - 3) {
            return concept.substring(dash + 3).trim();
        }
        // " a " separador habitual en Venta a X.
        int aSep = concept.toLowerCase().indexOf(" a ");
        if (aSep > 0 && aSep < concept.length() - 3) {
            return concept.substring(aSep + 3).trim();
        }
        return concept.trim();
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

    /**
     * Siguiente entry_number considerando SOLO los asientos POSTED. Usado
     * al validar un DRAFT — así el Diario sale en orden de validación y
     * los DRAFTs intermedios no "consumen" números.
     */
    private int nextPostedEntryNumber(String companyId, String fiscalYearId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                   AND status = 'POSTED'
                   AND entry_number IS NOT NULL
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
            String accountCode,
            String description,
            BigDecimal debit,
            BigDecimal credit
    ) {
        // Constructor compacto retro-compatible: si solo viene accountId.
        public LineRequest(String accountId, String description,
                            BigDecimal debit, BigDecimal credit) {
            this(accountId, null, description, debit, credit);
        }
    }

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
