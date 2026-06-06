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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Plantillas de asiento recurrentes.
 *
 * <p>Ejemplos típicos:
 * <ul>
 *   <li>Nómina mensual: 640 Salarios + 642 SS empresa / 476 SS / 4751
 *       Retenciones / 465 Remuneraciones pendientes.</li>
 *   <li>Pago seguros sociales TC1.</li>
 *   <li>Provisión IS estimado: 630 / 4752.</li>
 *   <li>Periodificación: 480/485 contra cuenta de gasto/ingreso.</li>
 * </ul>
 *
 * <p>Cada línea de la plantilla lleva {@code account_code} (no id, para
 * que la plantilla sea portable entre empresas con el mismo PGC), un
 * {@code amount_kind}:
 * <ul>
 *   <li>{@code FIXED}: importe constante guardado en {@code fixed_amount}.</li>
 *   <li>{@code VARIABLE}: el usuario lo introduce al aplicar la plantilla
 *       (típicamente "salario_bruto", "ss_empresa").</li>
 *   <li>{@code FORMULA}: expresión sencilla {@code base * 0.062} aplicada
 *       sobre variables previas (sub-slice: por ahora solo aceptamos
 *       referencia a una variable, no math).</li>
 * </ul>
 */
@Service
public class AccountingTemplateService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final ManualJournalEntryService manualEntries;
    private final CurrentUserService currentUserService;

    public AccountingTemplateService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                       ManualJournalEntryService manualEntries,
                                       CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.manualEntries = manualEntries;
        this.currentUserService = currentUserService;
    }

    // ====================================================================
    //  CRUD
    // ====================================================================

    @Transactional
    public TemplateView create(TemplateRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        String userId = safeUserId();
        jdbcTemplate.update("""
                INSERT INTO accounting_entry_templates (
                    id, company_id, code, name, category,
                    default_concept, description, active, created_by_user_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, TRUE, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.code().trim(), req.name().trim(), req.category(),
                blank(req.defaultConcept()), blank(req.description()), userId);
        insertLines(id, req.lines());
        return get(id);
    }

    @Transactional
    public TemplateView update(String id, TemplateRequest req) {
        validate(req);
        jdbcTemplate.update("""
                UPDATE accounting_entry_templates
                   SET name = ?, category = ?, default_concept = ?, description = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.name().trim(), req.category(),
                blank(req.defaultConcept()), blank(req.description()),
                id, tenantContext.getCurrentCompanyId());
        jdbcTemplate.update("DELETE FROM accounting_entry_template_lines WHERE template_id = ?", id);
        insertLines(id, req.lines());
        return get(id);
    }

    @Transactional
    public void setActive(String id, boolean active) {
        jdbcTemplate.update("""
                UPDATE accounting_entry_templates SET active = ?
                 WHERE id = ? AND company_id = ?
                """, active, id, tenantContext.getCurrentCompanyId());
    }

    public List<TemplateView> list(String category, Boolean activeOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT id FROM accounting_entry_templates
                 WHERE company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (category != null && !category.isBlank()) { sql.append(" AND category = ?"); args.add(category); }
        if (Boolean.TRUE.equals(activeOnly)) sql.append(" AND active = TRUE");
        sql.append(" ORDER BY active DESC, times_used DESC, code");
        List<String> ids = jdbcTemplate.query(sql.toString(),
                (rs, n) -> rs.getString("id"), args.toArray());
        List<TemplateView> out = new ArrayList<>();
        for (String id : ids) out.add(get(id));
        return out;
    }

    public TemplateView get(String id) {
        List<TemplateView> rows = jdbcTemplate.query("""
                SELECT id, company_id, code, name, category,
                       default_concept, description, active,
                       created_by_user_id, times_used, last_used_at,
                       created_at, updated_at
                  FROM accounting_entry_templates
                 WHERE id = ? AND company_id = ?
                """, this::mapHeader, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plantilla no encontrada");
        TemplateView header = rows.get(0);
        List<TemplateLineView> lines = jdbcTemplate.query("""
                SELECT id, line_order, account_code, description, side,
                       amount_kind, fixed_amount, formula, variable_name
                  FROM accounting_entry_template_lines
                 WHERE template_id = ?
                 ORDER BY line_order
                """, (rs, n) -> new TemplateLineView(
                        rs.getString("id"), rs.getInt("line_order"),
                        rs.getString("account_code"), rs.getString("description"),
                        rs.getString("side"), rs.getString("amount_kind"),
                        rs.getBigDecimal("fixed_amount"), rs.getString("formula"),
                        rs.getString("variable_name")),
                id);
        return new TemplateView(header.id(), header.companyId(), header.code(),
                header.name(), header.category(), header.defaultConcept(),
                header.description(), header.active(),
                header.createdByUserId(), header.timesUsed(), header.lastUsedAt(),
                header.createdAt(), header.updatedAt(), lines);
    }

    // ====================================================================
    //  Aplicar plantilla → genera asiento manual
    // ====================================================================

    @Transactional
    public ManualJournalEntryService.ManualEntryView apply(String templateId, ApplyRequest req) {
        TemplateView tpl = get(templateId);
        if (!tpl.active()) throw bad("La plantilla está desactivada.");
        if (req.entryDate() == null) throw bad("Fecha obligatoria.");

        Map<String, BigDecimal> vars = req.variables() == null ? new HashMap<>() : req.variables();
        List<ManualJournalEntryService.LineRequest> lines = new ArrayList<>();
        for (TemplateLineView ln : tpl.lines()) {
            BigDecimal amount = resolveAmount(ln, vars);
            String accountId = resolveAccount(ln.accountCode());
            if (accountId == null) {
                throw bad("Cuenta " + ln.accountCode() + " no existe en la empresa actual.");
            }
            BigDecimal debit = "DEBIT".equals(ln.side()) ? amount : BigDecimal.ZERO;
            BigDecimal credit = "CREDIT".equals(ln.side()) ? amount : BigDecimal.ZERO;
            lines.add(new ManualJournalEntryService.LineRequest(
                    accountId, ln.description(), debit, credit));
        }

        String concept = req.concept() != null && !req.concept().isBlank()
                ? req.concept()
                : (tpl.defaultConcept() != null ? tpl.defaultConcept() : tpl.name());

        ManualJournalEntryService.ManualEntryView entry = manualEntries.createDraft(
                new ManualJournalEntryService.ManualEntryRequest(
                        req.entryDate(), concept, lines, req.postNow()));

        jdbcTemplate.update("""
                UPDATE accounting_entry_templates
                   SET times_used = times_used + 1, last_used_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, templateId);
        return entry;
    }

    private BigDecimal resolveAmount(TemplateLineView ln, Map<String, BigDecimal> vars) {
        return switch (ln.amountKind()) {
            case "FIXED" -> ln.fixedAmount() == null ? BigDecimal.ZERO : ln.fixedAmount();
            case "VARIABLE" -> {
                BigDecimal v = vars.get(ln.variableName());
                if (v == null) throw bad("Falta variable " + ln.variableName());
                yield v;
            }
            case "FORMULA" -> {
                // Sub-slice: por ahora la fórmula es solo el nombre de
                // otra variable. Una mini-DSL llegará si Benjamin la pide.
                BigDecimal v = vars.get(ln.formula());
                if (v == null) throw bad("Fórmula no resuelta: " + ln.formula());
                yield v;
            }
            default -> BigDecimal.ZERO;
        };
    }

    private String resolveAccount(String code) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND code = ? AND active = TRUE
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), code);
        if (ids.isEmpty()) {
            // Fallback por prefijo si la subcuenta exacta no existe.
            ids = jdbcTemplate.query("""
                    SELECT id FROM accounting_accounts
                     WHERE company_id = ? AND code LIKE ? AND active = TRUE
                     ORDER BY LENGTH(code), code LIMIT 1
                    """, (rs, n) -> rs.getString("id"),
                    tenantContext.getCurrentCompanyId(), code + "%");
        }
        return ids.isEmpty() ? null : ids.get(0);
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private void insertLines(String templateId, List<TemplateLineRequest> lines) {
        int order = 1;
        for (TemplateLineRequest ln : lines) {
            jdbcTemplate.update("""
                    INSERT INTO accounting_entry_template_lines (
                        id, template_id, line_order, account_code, description,
                        side, amount_kind, fixed_amount, formula, variable_name
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), templateId, order++,
                    ln.accountCode(), blank(ln.description()),
                    ln.side(), ln.amountKind(),
                    ln.fixedAmount(), blank(ln.formula()), blank(ln.variableName()));
        }
    }

    private void validate(TemplateRequest req) {
        if (req.code() == null || req.code().isBlank()) throw bad("Código obligatorio.");
        if (req.name() == null || req.name().isBlank()) throw bad("Nombre obligatorio.");
        if (req.category() == null) throw bad("Categoría obligatoria.");
        if (req.lines() == null || req.lines().size() < 2) throw bad("Mínimo 2 líneas.");
    }

    private TemplateView mapHeader(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp lu = rs.getTimestamp("last_used_at");
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        return new TemplateView(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("code"), rs.getString("name"),
                rs.getString("category"), rs.getString("default_concept"),
                rs.getString("description"), rs.getBoolean("active"),
                rs.getString("created_by_user_id"),
                rs.getInt("times_used"),
                lu == null ? null : lu.toInstant(),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant(),
                List.of());
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record TemplateRequest(
            String code, String name, String category,
            String defaultConcept, String description,
            List<TemplateLineRequest> lines
    ) {}

    public record TemplateLineRequest(
            String accountCode, String description,
            String side, String amountKind,
            BigDecimal fixedAmount, String formula, String variableName
    ) {}

    public record ApplyRequest(
            LocalDate entryDate, String concept,
            Map<String, BigDecimal> variables, boolean postNow
    ) {}

    public record TemplateView(
            String id, String companyId, String code, String name,
            String category, String defaultConcept, String description,
            boolean active, String createdByUserId,
            int timesUsed, Instant lastUsedAt,
            Instant createdAt, Instant updatedAt,
            List<TemplateLineView> lines
    ) {}

    public record TemplateLineView(
            String id, int lineOrder, String accountCode, String description,
            String side, String amountKind,
            BigDecimal fixedAmount, String formula, String variableName
    ) {}
}
