package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Aprendizaje contable por feedback humano.
 *
 * <p>Lógica:
 * <ol>
 *   <li>El sistema {@link #proposeExpenseAccount} / {@link #proposeIncomeAccount}
 *       consulta las reglas activas de la empresa. Si encuentra una con
 *       confianza suficiente, devuelve la cuenta sugerida + un valor de
 *       confianza para que el caller decida si la aplica directamente
 *       o la marca como "propuesta a revisar".</li>
 *   <li>Cuando el asesor valida un asiento {@link #recordAcceptance},
 *       todas las reglas que se aplicaron suman a {@code times_applied}
 *       y la confianza sube.</li>
 *   <li>Cuando el asesor corrige una línea {@link #recordCorrection},
 *       la regla original (si existía) suma a {@code times_overridden}
 *       y se crea una nueva regla con el criterio inferido del contexto
 *       (NIF proveedor/cliente, palabra clave).</li>
 *   <li>Reglas con confianza {@code < 30%} y {@code overridden > 3} se
 *       desactivan automáticamente para no estorbar.</li>
 * </ol>
 *
 * <p>Semántica de la confianza:
 * <pre>
 *   confidence = applied / (applied + overridden) × 100
 * </pre>
 *
 * <p>Reglas iniciales (sin historial) tienen confianza 50% — son una
 * propuesta razonable pero el asesor debería verificarla. Tras 10 usos
 * sin corrección, la confianza sube a ~95% y la UI puede mostrarla como
 * "automática".
 */
@Service
public class AccountingLearningService {

    public static final String KIND_EXPENSE_BY_NIF = "EXPENSE_ACCOUNT_BY_SUPPLIER_NIF";
    public static final String KIND_EXPENSE_BY_KEYWORD = "EXPENSE_ACCOUNT_BY_KEYWORD";
    public static final String KIND_INCOME_BY_NIF = "INCOME_ACCOUNT_BY_CUSTOMER_NIF";
    public static final String KIND_INCOME_BY_KEYWORD = "INCOME_ACCOUNT_BY_KEYWORD";
    public static final String KIND_VAT_BY_NIF = "VAT_RATE_BY_SUPPLIER_NIF";

    /** Confianza por defecto para una regla recién creada. */
    public static final BigDecimal INITIAL_CONFIDENCE = new BigDecimal("50.00");
    /** Confianza desde la que la propuesta se considera fuerte. */
    public static final BigDecimal STRONG_CONFIDENCE = new BigDecimal("80.00");
    /** Confianza por debajo de la cual la regla se desactiva si overridden>3. */
    public static final BigDecimal WEAK_CONFIDENCE = new BigDecimal("30.00");

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public AccountingLearningService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    // ====================================================================
    //  Propuesta — consulta reglas
    // ====================================================================

    /**
     * Propone una cuenta de gasto (grupo 6) para una factura recibida.
     * Estrategia: primero busca por NIF del proveedor; si no, por keyword
     * en la concatenación de nombre+concepto. Devuelve {@link Optional#empty}
     * si no hay ninguna regla aplicable — el caller debe usar su fallback
     * (típicamente la cuenta 600 genérica).
     */
    public Optional<AccountProposal> proposeExpenseAccount(
            String supplierNif, String supplierName, String concept, BigDecimal amount) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (supplierNif != null && !supplierNif.isBlank()) {
            Optional<AccountProposal> byNif = findByNif(companyId,
                    KIND_EXPENSE_BY_NIF, "match_supplier_nif", supplierNif.toUpperCase());
            if (byNif.isPresent()) return byNif;
        }
        String searchText = (safe(supplierName) + " " + safe(concept)).toLowerCase(Locale.ROOT);
        return findByKeyword(companyId, KIND_EXPENSE_BY_KEYWORD, searchText, amount);
    }

    /**
     * Busca en asientos previos del mismo proveedor cuál es la cuenta 6xx
     * más usada. Port de {@code buscarCuentaHistoricoProveedor} (línea 1361
     * de {@code contabilidadService.js} de CONTENDO).
     *
     * <p>Estrategia: une journal_entries con purchase_invoices por source_id;
     * agrupa por código de cuenta y devuelve la cuenta 6xx más frecuente
     * (excluyendo asientos VOIDED). Match por NIF si está disponible —
     * más estable que por nombre. Devuelve {@link Optional#empty} si el
     * proveedor no tiene historial.
     *
     * <p>Resultado: id + código de cuenta listos para usar en
     * {@code journal_entry_lines.account_id}. No incluye confianza porque no
     * es una regla aprendida formal — es solo "última cuenta usada".
     */
    public Optional<HistoricMatch> findHistoricExpenseAccountForSupplier(
            String supplierNif, String supplierName) {
        String companyId = tenantContext.getCurrentCompanyId();
        if ((supplierNif == null || supplierNif.isBlank())
                && (supplierName == null || supplierName.isBlank())) {
            return Optional.empty();
        }
        // Por NIF (más estable)
        if (supplierNif != null && !supplierNif.isBlank()) {
            List<HistoricMatch> rows = jdbcTemplate.query("""
                    SELECT l.account_id, a.code, COUNT(*) AS veces
                      FROM journal_entries e
                      JOIN journal_entry_lines l ON l.journal_entry_id = e.id
                      JOIN accounting_accounts a ON a.id = l.account_id
                      JOIN purchase_invoices p ON p.id = e.source_id
                     WHERE e.company_id = ?
                       AND e.source_type = 'PURCHASE_INVOICE'
                       AND e.status <> 'VOIDED'
                       AND a.code LIKE '6%'
                       AND l.debit > 0
                       AND UPPER(REPLACE(REPLACE(p.supplier_nif, ' ', ''), '-', '')) = ?
                     GROUP BY l.account_id, a.code
                     ORDER BY veces DESC
                     LIMIT 1
                    """,
                    (rs, n) -> new HistoricMatch(rs.getString("account_id"), rs.getString("code")),
                    companyId, supplierNif.toUpperCase(Locale.ROOT).replaceAll("[\\s\\-\\.]", ""));
            if (!rows.isEmpty()) return Optional.of(rows.get(0));
        }
        // Fallback por nombre normalizado
        if (supplierName != null && !supplierName.isBlank()) {
            List<HistoricMatch> rows = jdbcTemplate.query("""
                    SELECT l.account_id, a.code, COUNT(*) AS veces
                      FROM journal_entries e
                      JOIN journal_entry_lines l ON l.journal_entry_id = e.id
                      JOIN accounting_accounts a ON a.id = l.account_id
                      JOIN purchase_invoices p ON p.id = e.source_id
                     WHERE e.company_id = ?
                       AND e.source_type = 'PURCHASE_INVOICE'
                       AND e.status <> 'VOIDED'
                       AND a.code LIKE '6%'
                       AND l.debit > 0
                       AND UPPER(TRIM(p.supplier_name)) = ?
                     GROUP BY l.account_id, a.code
                     ORDER BY veces DESC
                     LIMIT 1
                    """,
                    (rs, n) -> new HistoricMatch(rs.getString("account_id"), rs.getString("code")),
                    companyId, supplierName.trim().toUpperCase(Locale.ROOT));
            if (!rows.isEmpty()) return Optional.of(rows.get(0));
        }
        return Optional.empty();
    }

    /** Espejo del histórico para ventas (cuenta 7xx más usada por cliente). */
    public Optional<HistoricMatch> findHistoricIncomeAccountForCustomer(
            String customerNif, String customerName) {
        String companyId = tenantContext.getCurrentCompanyId();
        if ((customerNif == null || customerNif.isBlank())
                && (customerName == null || customerName.isBlank())) {
            return Optional.empty();
        }
        if (customerName != null && !customerName.isBlank()) {
            List<HistoricMatch> rows = jdbcTemplate.query("""
                    SELECT l.account_id, a.code, COUNT(*) AS veces
                      FROM journal_entries e
                      JOIN journal_entry_lines l ON l.journal_entry_id = e.id
                      JOIN accounting_accounts a ON a.id = l.account_id
                      JOIN sales_invoices s ON s.id = e.source_id
                      LEFT JOIN customers c ON c.id = s.customer_id
                     WHERE e.company_id = ?
                       AND e.source_type = 'SALES_INVOICE'
                       AND e.status <> 'VOIDED'
                       AND a.code LIKE '7%'
                       AND l.credit > 0
                       AND UPPER(TRIM(c.legal_name)) = ?
                     GROUP BY l.account_id, a.code
                     ORDER BY veces DESC
                     LIMIT 1
                    """,
                    (rs, n) -> new HistoricMatch(rs.getString("account_id"), rs.getString("code")),
                    companyId, customerName.trim().toUpperCase(Locale.ROOT));
            if (!rows.isEmpty()) return Optional.of(rows.get(0));
        }
        return Optional.empty();
    }

    /** Resultado de búsqueda histórica: id de cuenta + código. */
    public record HistoricMatch(String accountId, String accountCode) {}

    /** Propone una cuenta de ingreso (grupo 7) para una factura emitida. */
    public Optional<AccountProposal> proposeIncomeAccount(
            String customerNif, String customerName, String concept, BigDecimal amount) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (customerNif != null && !customerNif.isBlank()) {
            Optional<AccountProposal> byNif = findByNif(companyId,
                    KIND_INCOME_BY_NIF, "match_customer_nif", customerNif.toUpperCase());
            if (byNif.isPresent()) return byNif;
        }
        String searchText = (safe(customerName) + " " + safe(concept)).toLowerCase(Locale.ROOT);
        return findByKeyword(companyId, KIND_INCOME_BY_KEYWORD, searchText, amount);
    }

    private Optional<AccountProposal> findByNif(String companyId, String kind,
                                                  String nifColumn, String nif) {
        String sql = """
                SELECT id, target_account_id, target_account_code, confidence
                  FROM accounting_learning_rules
                 WHERE company_id = ?
                   AND rule_kind = ?
                   AND active = TRUE
                   AND %s = ?
                   AND target_account_id IS NOT NULL
                 ORDER BY confidence DESC, times_applied DESC
                 LIMIT 1
                """.formatted(nifColumn);
        List<AccountProposal> hits = jdbcTemplate.query(sql, (rs, n) -> new AccountProposal(
                rs.getString("id"), rs.getString("target_account_id"),
                rs.getString("target_account_code"), rs.getBigDecimal("confidence")
        ), companyId, kind, nif);
        return hits.isEmpty() ? Optional.empty() : Optional.of(hits.get(0));
    }

    private Optional<AccountProposal> findByKeyword(String companyId, String kind,
                                                      String searchTextLower,
                                                      BigDecimal amount) {
        if (searchTextLower.isBlank()) return Optional.empty();
        List<KeywordCandidate> hits = jdbcTemplate.query("""
                SELECT id, target_account_id, target_account_code, confidence,
                       match_keyword
                  FROM accounting_learning_rules
                 WHERE company_id = ?
                   AND rule_kind = ?
                   AND active = TRUE
                   AND match_keyword IS NOT NULL
                   AND target_account_id IS NOT NULL
                   AND (match_amount_min IS NULL OR match_amount_min <= ?)
                   AND (match_amount_max IS NULL OR match_amount_max >= ?)
                 ORDER BY confidence DESC, times_applied DESC
                """, (rs, n) -> new KeywordCandidate(
                        rs.getString("id"), rs.getString("target_account_id"),
                        rs.getString("target_account_code"), rs.getBigDecimal("confidence"),
                        rs.getString("match_keyword")),
                companyId, kind, amount, amount);
        for (KeywordCandidate c : hits) {
            if (c.keyword != null && searchTextLower.contains(c.keyword.toLowerCase(Locale.ROOT))) {
                return Optional.of(new AccountProposal(c.id, c.accountId, c.accountCode, c.confidence));
            }
        }
        return Optional.empty();
    }

    // ====================================================================
    //  Aprendizaje — reforzar / corregir / crear
    // ====================================================================

    /**
     * El asesor validó el asiento sin tocar las líneas — refuerza las
     * reglas que se aplicaron al generarlo. {@code appliedRuleIds} es la
     * lista (puede tener nulos) de ids de regla que el caller almacenó
     * cuando generó cada línea.
     */
    @Transactional
    public void recordAcceptance(String journalEntryId, List<String> appliedRuleIds, String userId) {
        if (appliedRuleIds == null) return;
        for (String ruleId : appliedRuleIds) {
            if (ruleId == null || ruleId.isBlank()) continue;
            reinforceRule(ruleId);
            logEvent(journalEntryId, null, "RULE_REINFORCED", null, null, null, null,
                    ruleId, userId, null);
        }
        logEvent(journalEntryId, null, "ENTRY_ACCEPTED", null, null, null, null,
                null, userId, null);
    }

    /**
     * El asesor cambió la cuenta de una línea. Si había una regla original
     * que disparó la propuesta, se la debilita. Y se crea una nueva regla
     * con el criterio inferido (NIF proveedor/cliente o keyword).
     */
    @Transactional
    public String recordCorrection(CorrectionRequest req, String userId) {
        if (req.originalRuleId() != null) {
            weakenRule(req.originalRuleId());
            logEvent(req.journalEntryId(), req.lineId(), "RULE_WEAKENED",
                    req.fromAccountId(), req.toAccountId(), null, null,
                    req.originalRuleId(), userId, null);
        }
        logEvent(req.journalEntryId(), req.lineId(), "ACCOUNT_CORRECTED",
                req.fromAccountId(), req.toAccountId(), null, null,
                null, userId, req.notes());

        String newRuleId = createRuleFromCorrection(req, userId);
        if (newRuleId != null) {
            logEvent(req.journalEntryId(), req.lineId(), "RULE_CREATED",
                    null, null, null, null, newRuleId, userId, null);
        }
        return newRuleId;
    }

    private String createRuleFromCorrection(CorrectionRequest req, String userId) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (req.toAccountId() == null) return null;

        String kind;
        String supplierNif = null;
        String customerNif = null;
        String keyword = null;

        // Heurística: priorizar NIF si lo tenemos.
        if (req.supplierNif() != null && !req.supplierNif().isBlank()) {
            kind = isExpenseAccount(req.toAccountCode()) ? KIND_EXPENSE_BY_NIF : KIND_VAT_BY_NIF;
            supplierNif = req.supplierNif().toUpperCase();
        } else if (req.customerNif() != null && !req.customerNif().isBlank()) {
            kind = KIND_INCOME_BY_NIF;
            customerNif = req.customerNif().toUpperCase();
        } else if (req.keyword() != null && !req.keyword().isBlank()) {
            kind = isExpenseAccount(req.toAccountCode())
                    ? KIND_EXPENSE_BY_KEYWORD : KIND_INCOME_BY_KEYWORD;
            keyword = req.keyword().trim();
        } else {
            // Sin criterio claro: no creamos regla. La corrección queda en
            // el histórico pero no aprende.
            return null;
        }

        // Idempotencia: si ya existe una regla con el mismo criterio y
        // target, la reforzamos en vez de crear duplicado.
        String existing = findRuleByCriteria(companyId, kind, supplierNif, customerNif,
                keyword, req.toAccountId());
        if (existing != null) {
            reinforceRule(existing);
            return existing;
        }

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO accounting_learning_rules (
                    id, company_id, rule_kind,
                    match_supplier_nif, match_customer_nif, match_keyword,
                    target_account_id, target_account_code,
                    times_applied, times_overridden, confidence,
                    learned_from_entry_id, learned_from_line_id,
                    created_by_user_id, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?, TRUE)
                """,
                id, companyId, kind,
                supplierNif, customerNif, keyword,
                req.toAccountId(), req.toAccountCode(),
                INITIAL_CONFIDENCE,
                req.journalEntryId(), req.lineId(), userId);
        return id;
    }

    private String findRuleByCriteria(String companyId, String kind,
                                         String supplierNif, String customerNif,
                                         String keyword, String targetAccountId) {
        List<String> hits = jdbcTemplate.query("""
                SELECT id FROM accounting_learning_rules
                 WHERE company_id = ?
                   AND rule_kind = ?
                   AND target_account_id = ?
                   AND ((match_supplier_nif IS NULL AND ? IS NULL)
                        OR match_supplier_nif = ?)
                   AND ((match_customer_nif IS NULL AND ? IS NULL)
                        OR match_customer_nif = ?)
                   AND ((match_keyword IS NULL AND ? IS NULL)
                        OR match_keyword = ?)
                 LIMIT 1
                """,
                (rs, n) -> rs.getString("id"),
                companyId, kind, targetAccountId,
                supplierNif, supplierNif,
                customerNif, customerNif,
                keyword, keyword);
        return hits.isEmpty() ? null : hits.get(0);
    }

    private void reinforceRule(String ruleId) {
        jdbcTemplate.update("""
                UPDATE accounting_learning_rules
                   SET times_applied = times_applied + 1,
                       confidence = ROUND(times_applied + 1) * 100.0
                                  / GREATEST(1, times_applied + 1 + times_overridden),
                       last_applied_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, ruleId);
    }

    private void weakenRule(String ruleId) {
        jdbcTemplate.update("""
                UPDATE accounting_learning_rules
                   SET times_overridden = times_overridden + 1,
                       confidence = times_applied * 100.0
                                  / GREATEST(1, times_applied + times_overridden + 1),
                       active = CASE
                                 WHEN times_overridden + 1 > 3
                                   AND (times_applied * 100.0
                                        / GREATEST(1, times_applied + times_overridden + 1)) < ?
                                 THEN FALSE ELSE active
                                END
                 WHERE id = ?
                """, WEAK_CONFIDENCE, ruleId);
    }

    private boolean isExpenseAccount(String code) {
        return code != null && code.startsWith("6");
    }

    // ====================================================================
    //  CRUD reglas (UI manual)
    // ====================================================================

    public List<LearningRule> listRules(String kind, Boolean activeOnly) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, rule_kind,
                       match_supplier_nif, match_customer_nif, match_keyword,
                       match_amount_min, match_amount_max,
                       target_account_id, target_account_code, target_vat_percent,
                       times_applied, times_overridden, confidence,
                       learned_from_entry_id, learned_from_line_id,
                       created_by_user_id, last_applied_at, active,
                       notes, created_at, updated_at
                  FROM accounting_learning_rules
                 WHERE company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (kind != null && !kind.isBlank()) {
            sql.append(" AND rule_kind = ?");
            args.add(kind);
        }
        if (Boolean.TRUE.equals(activeOnly)) {
            sql.append(" AND active = TRUE");
        }
        sql.append(" ORDER BY confidence DESC, last_applied_at DESC, created_at DESC");
        return jdbcTemplate.query(sql.toString(), this::mapRule, args.toArray());
    }

    @Transactional
    public void setRuleActive(String ruleId, boolean active) {
        jdbcTemplate.update("""
                UPDATE accounting_learning_rules
                   SET active = ?
                 WHERE id = ? AND company_id = ?
                """, active, ruleId, tenantContext.getCurrentCompanyId());
    }

    @Transactional
    public void deleteRule(String ruleId) {
        // Borrado físico: las reglas no son legalmente vinculantes, solo
        // sugerencias. El histórico de aprendizaje se mantiene en
        // accounting_learning_events.
        jdbcTemplate.update("""
                DELETE FROM accounting_learning_rules
                 WHERE id = ? AND company_id = ?
                """, ruleId, tenantContext.getCurrentCompanyId());
    }

    // ====================================================================
    //  Histórico
    // ====================================================================

    public List<LearningEvent> listEventsForEntry(String journalEntryId) {
        return jdbcTemplate.query("""
                SELECT id, company_id, journal_entry_id, line_id, event_kind,
                       from_account_id, to_account_id, from_amount, to_amount,
                       related_rule_id, actor_user_id, occurred_at, notes
                  FROM accounting_learning_events
                 WHERE company_id = ? AND journal_entry_id = ?
                 ORDER BY occurred_at ASC
                """, this::mapEvent, tenantContext.getCurrentCompanyId(), journalEntryId);
    }

    private void logEvent(String entryId, String lineId, String kind,
                            String fromAcc, String toAcc,
                            BigDecimal fromAmount, BigDecimal toAmount,
                            String ruleId, String userId, String notes) {
        jdbcTemplate.update("""
                INSERT INTO accounting_learning_events (
                    id, company_id, journal_entry_id, line_id, event_kind,
                    from_account_id, to_account_id, from_amount, to_amount,
                    related_rule_id, actor_user_id, notes
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                tenantContext.getCurrentCompanyId(),
                entryId, lineId, kind,
                fromAcc, toAcc, fromAmount, toAmount,
                ruleId, userId, notes);
    }

    // ====================================================================
    //  Row mappers
    // ====================================================================

    private LearningRule mapRule(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp last = rs.getTimestamp("last_applied_at");
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        return new LearningRule(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("rule_kind"),
                rs.getString("match_supplier_nif"),
                rs.getString("match_customer_nif"),
                rs.getString("match_keyword"),
                rs.getBigDecimal("match_amount_min"),
                rs.getBigDecimal("match_amount_max"),
                rs.getString("target_account_id"),
                rs.getString("target_account_code"),
                rs.getBigDecimal("target_vat_percent"),
                rs.getInt("times_applied"),
                rs.getInt("times_overridden"),
                rs.getBigDecimal("confidence"),
                rs.getString("learned_from_entry_id"),
                rs.getString("learned_from_line_id"),
                rs.getString("created_by_user_id"),
                last == null ? null : last.toInstant(),
                rs.getBoolean("active"),
                rs.getString("notes"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    private LearningEvent mapEvent(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp oc = rs.getTimestamp("occurred_at");
        return new LearningEvent(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("journal_entry_id"), rs.getString("line_id"),
                rs.getString("event_kind"),
                rs.getString("from_account_id"), rs.getString("to_account_id"),
                rs.getBigDecimal("from_amount"), rs.getBigDecimal("to_amount"),
                rs.getString("related_rule_id"), rs.getString("actor_user_id"),
                oc == null ? null : oc.toInstant(),
                rs.getString("notes"));
    }

    private static String safe(String v) { return v == null ? "" : v; }

    // ====================================================================
    //  DTOs
    // ====================================================================

    /** Propuesta de cuenta para un caller. */
    public record AccountProposal(
            String ruleId, String accountId, String accountCode, BigDecimal confidence
    ) {
        /** ¿La confianza es suficiente para auto-aplicar sin pedir revisión? */
        public boolean isStrong() {
            return confidence != null
                    && confidence.compareTo(STRONG_CONFIDENCE) >= 0;
        }
    }

    public record CorrectionRequest(
            String journalEntryId,
            String lineId,
            String fromAccountId,
            String toAccountId,
            String toAccountCode,
            String supplierNif,
            String customerNif,
            String keyword,
            String originalRuleId,
            String notes
    ) {}

    public record LearningRule(
            String id, String companyId, String ruleKind,
            String matchSupplierNif, String matchCustomerNif, String matchKeyword,
            BigDecimal matchAmountMin, BigDecimal matchAmountMax,
            String targetAccountId, String targetAccountCode, BigDecimal targetVatPercent,
            int timesApplied, int timesOverridden, BigDecimal confidence,
            String learnedFromEntryId, String learnedFromLineId,
            String createdByUserId, Instant lastAppliedAt,
            boolean active, String notes,
            Instant createdAt, Instant updatedAt
    ) {}

    public record LearningEvent(
            String id, String companyId,
            String journalEntryId, String lineId, String eventKind,
            String fromAccountId, String toAccountId,
            BigDecimal fromAmount, BigDecimal toAmount,
            String relatedRuleId, String actorUserId,
            Instant occurredAt, String notes
    ) {}

    /** Helper estadístico (sin estado). */
    @SuppressWarnings("unused")
    private static BigDecimal computeConfidence(int applied, int overridden) {
        int total = applied + overridden;
        if (total == 0) return INITIAL_CONFIDENCE;
        return new BigDecimal(applied * 100)
                .divide(new BigDecimal(total), 2, RoundingMode.HALF_UP);
    }

    private record KeywordCandidate(
            String id, String accountId, String accountCode,
            BigDecimal confidence, String keyword
    ) {}
}
