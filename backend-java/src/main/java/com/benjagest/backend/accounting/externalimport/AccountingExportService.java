package com.benjagest.backend.accounting.externalimport;

import com.benjagest.backend.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Exportación contable hacia formatos externos. Complementa a
 * {@link ExternalImportService} cerrando el ciclo bidireccional.
 *
 * <p>Formatos:
 * <ul>
 *   <li><b>CSV</b> genérico con cabecera nombrada.</li>
 *   <li><b>CONTASOL</b> TXT (mismo mapeo posicional que el importador).</li>
 *   <li><b>JSON_BENJAGEST</b> canónico — para backup completo o migrar
 *       entre instalaciones BenjaGest.</li>
 *   <li><b>A3 / SAGE</b> — esqueleto, se finaliza con muestras reales.</li>
 * </ul>
 *
 * <p>Targets:
 * <ul>
 *   <li>{@code ACCOUNTS} — plan contable de la empresa.</li>
 *   <li>{@code JOURNAL_ENTRIES} — diario contable.</li>
 *   <li>{@code INVOICES_SALES} / {@code INVOICES_PURCHASE} — facturas.</li>
 *   <li>{@code CUSTOMERS} / {@code SUPPLIERS}.</li>
 *   <li>{@code FIXED_ASSETS} / {@code LOANS}.</li>
 * </ul>
 */
@Service
public class AccountingExportService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE;
    private static final DateTimeFormatter CONTASOL_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final String NL = "\n";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;

    public AccountingExportService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                     ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
    }

    public ExportPayload export(ExportRequest req) {
        if (req.format() == null) throw bad("Falta formato");
        if (req.targetKind() == null) throw bad("Falta target");

        String content = switch (req.format().toUpperCase()) {
            case "CSV" -> exportCsv(req);
            case "CONTASOL" -> exportContasol(req);
            case "JSON_BENJAGEST" -> exportJson(req);
            case "A3", "SAGE", "XML_ESPI" -> throw bad(
                    "Formato " + req.format() + " no implementado todavía. "
                    + "Pide muestra real del cliente para finalizar generador.");
            default -> throw bad("Formato desconocido: " + req.format());
        };
        String filename = buildFilename(req);
        String mime = "JSON_BENJAGEST".equalsIgnoreCase(req.format())
                ? "application/json" : "text/plain";
        return new ExportPayload(content.getBytes(StandardCharsets.UTF_8), filename, mime);
    }

    // ====================================================================
    //  CSV
    // ====================================================================

    private String exportCsv(ExportRequest req) {
        return switch (req.targetKind().toUpperCase()) {
            case "ACCOUNTS" -> csvAccounts();
            case "JOURNAL_ENTRIES" -> csvJournal(req.from(), req.to(), req.includeDrafts());
            case "CUSTOMERS" -> csvParties(false);
            case "SUPPLIERS" -> csvParties(true);
            case "INVOICES_SALES" -> csvSalesInvoices(req.from(), req.to());
            case "INVOICES_PURCHASE" -> csvPurchaseInvoices(req.from(), req.to());
            case "FIXED_ASSETS" -> csvFixedAssets();
            case "LOANS" -> csvLoans();
            default -> throw bad("CSV no implementado para target " + req.targetKind());
        };
    }

    private String csvAccounts() {
        StringBuilder sb = new StringBuilder("code;name;account_type;active\n");
        jdbcTemplate.query("""
                SELECT code, name, account_type, active
                  FROM accounting_accounts
                 WHERE company_id = ?
                 ORDER BY code
                """, rs -> {
                    sb.append(esc(rs.getString("code"))).append(';')
                      .append(esc(rs.getString("name"))).append(';')
                      .append(esc(rs.getString("account_type"))).append(';')
                      .append(rs.getBoolean("active") ? "true" : "false")
                      .append(NL);
                }, tenantContext.getCurrentCompanyId());
        return sb.toString();
    }

    private String csvJournal(LocalDate from, LocalDate to, Boolean includeDrafts) {
        StringBuilder sb = new StringBuilder(
                "entry_number;date;concept;account_code;description;debit;credit;status\n");
        StringBuilder where = new StringBuilder(" WHERE je.company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (!Boolean.TRUE.equals(includeDrafts)) {
            where.append(" AND je.status = 'POSTED'");
        }
        if (from != null) { where.append(" AND je.entry_date >= ?"); args.add(java.sql.Date.valueOf(from)); }
        if (to != null)   { where.append(" AND je.entry_date <= ?"); args.add(java.sql.Date.valueOf(to)); }
        jdbcTemplate.query("""
                SELECT je.entry_number, je.entry_date, je.concept, je.status,
                       a.code AS account_code, l.description, l.debit, l.credit
                  FROM journal_entry_lines l
                  JOIN journal_entries je ON je.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                """ + where + """
                 ORDER BY je.entry_date, je.entry_number, l.id
                """, rs -> {
                    sb.append(rs.getInt("entry_number")).append(';')
                      .append(rs.getDate("entry_date").toLocalDate().format(ISO)).append(';')
                      .append(esc(rs.getString("concept"))).append(';')
                      .append(esc(rs.getString("account_code"))).append(';')
                      .append(esc(rs.getString("description"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("debit"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("credit"))).append(';')
                      .append(esc(rs.getString("status")))
                      .append(NL);
                }, args.toArray());
        return sb.toString();
    }

    private String csvParties(boolean suppliers) {
        String table = suppliers ? "suppliers" : "customers";
        String nameCol = suppliers ? "name" : "legal_name";
        StringBuilder sb = new StringBuilder("nif;name;email;phone;address;active\n");
        String sql = "SELECT nif, " + nameCol + " AS name, email, phone, address, active"
                + " FROM " + table
                + " WHERE company_id = ?"
                + " ORDER BY " + nameCol;
        try {
            jdbcTemplate.query(sql, rs -> {
                sb.append(esc(rs.getString("nif"))).append(';')
                  .append(esc(rs.getString("name"))).append(';')
                  .append(esc(rs.getString("email"))).append(';')
                  .append(esc(rs.getString("phone"))).append(';')
                  .append(esc(rs.getString("address"))).append(';')
                  .append(rs.getBoolean("active") ? "true" : "false")
                  .append(NL);
            }, tenantContext.getCurrentCompanyId());
        } catch (Exception ex) {
            // Si la tabla no tiene exactamente ese esquema, devolvemos vacío
            // con cabecera para que el asesor sepa el formato esperado.
            return sb.toString();
        }
        return sb.toString();
    }

    private String csvSalesInvoices(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder(
                "invoice_number;invoice_date;customer_legal_name;subtotal;vat_total;total_amount;status\n");
        StringBuilder where = new StringBuilder(" WHERE company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (from != null) { where.append(" AND invoice_date >= ?"); args.add(java.sql.Date.valueOf(from)); }
        if (to != null)   { where.append(" AND invoice_date <= ?"); args.add(java.sql.Date.valueOf(to)); }
        jdbcTemplate.query("""
                SELECT invoice_number, invoice_date, customer_legal_name,
                       subtotal, vat_total, total_amount, status
                  FROM sales_invoices
                """ + where + """
                 ORDER BY invoice_date, invoice_number
                """, rs -> {
                    sb.append(esc(rs.getString("invoice_number"))).append(';')
                      .append(rs.getDate("invoice_date").toLocalDate().format(ISO)).append(';')
                      .append(esc(rs.getString("customer_legal_name"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("subtotal"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("vat_total"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("total_amount"))).append(';')
                      .append(esc(rs.getString("status")))
                      .append(NL);
                }, args.toArray());
        return sb.toString();
    }

    private String csvPurchaseInvoices(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder(
                "invoice_number;invoice_date;supplier_nif;supplier_name;base_amount;vat_amount;total_amount;status\n");
        StringBuilder where = new StringBuilder(" WHERE company_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (from != null) { where.append(" AND invoice_date >= ?"); args.add(java.sql.Date.valueOf(from)); }
        if (to != null)   { where.append(" AND invoice_date <= ?"); args.add(java.sql.Date.valueOf(to)); }
        jdbcTemplate.query("""
                SELECT invoice_number, invoice_date, supplier_nif, supplier_name,
                       base_amount, vat_amount, total_amount, status
                  FROM purchase_invoices
                """ + where + """
                 ORDER BY invoice_date, invoice_number
                """, rs -> {
                    sb.append(esc(rs.getString("invoice_number"))).append(';')
                      .append(rs.getDate("invoice_date").toLocalDate().format(ISO)).append(';')
                      .append(esc(rs.getString("supplier_nif"))).append(';')
                      .append(esc(rs.getString("supplier_name"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("base_amount"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("vat_amount"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("total_amount"))).append(';')
                      .append(esc(rs.getString("status")))
                      .append(NL);
                }, args.toArray());
        return sb.toString();
    }

    private String csvFixedAssets() {
        StringBuilder sb = new StringBuilder(
                "code;name;category;acquisition_date;acquisition_cost;useful_life_years;method;active\n");
        jdbcTemplate.query("""
                SELECT code, name, category, acquisition_date, acquisition_cost,
                       useful_life_years, depreciation_method, active
                  FROM fixed_assets
                 WHERE company_id = ?
                 ORDER BY code
                """, rs -> {
                    sb.append(esc(rs.getString("code"))).append(';')
                      .append(esc(rs.getString("name"))).append(';')
                      .append(esc(rs.getString("category"))).append(';')
                      .append(rs.getDate("acquisition_date").toLocalDate().format(ISO)).append(';')
                      .append(formatAmount(rs.getBigDecimal("acquisition_cost"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("useful_life_years"))).append(';')
                      .append(esc(rs.getString("depreciation_method"))).append(';')
                      .append(rs.getBoolean("active") ? "true" : "false")
                      .append(NL);
                }, tenantContext.getCurrentCompanyId());
        return sb.toString();
    }

    private String csvLoans() {
        StringBuilder sb = new StringBuilder(
                "code;description;lender_name;lender_nif;principal_amount;interest_rate;term_months;start_date;status\n");
        jdbcTemplate.query("""
                SELECT code, description, lender_name, lender_nif,
                       principal_amount, interest_rate, term_months,
                       start_date, status
                  FROM loans
                 WHERE company_id = ?
                 ORDER BY start_date
                """, rs -> {
                    sb.append(esc(rs.getString("code"))).append(';')
                      .append(esc(rs.getString("description"))).append(';')
                      .append(esc(rs.getString("lender_name"))).append(';')
                      .append(esc(rs.getString("lender_nif"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("principal_amount"))).append(';')
                      .append(formatAmount(rs.getBigDecimal("interest_rate"))).append(';')
                      .append(rs.getInt("term_months")).append(';')
                      .append(rs.getDate("start_date").toLocalDate().format(ISO)).append(';')
                      .append(esc(rs.getString("status")))
                      .append(NL);
                }, tenantContext.getCurrentCompanyId());
        return sb.toString();
    }

    // ====================================================================
    //  Contasol
    // ====================================================================

    private String exportContasol(ExportRequest req) {
        return switch (req.targetKind().toUpperCase()) {
            case "ACCOUNTS" -> contasolAccounts();
            case "JOURNAL_ENTRIES" -> contasolJournal(req.from(), req.to());
            default -> throw bad("Contasol no soportado para target " + req.targetKind());
        };
    }

    private String contasolAccounts() {
        StringBuilder sb = new StringBuilder();
        jdbcTemplate.query("""
                SELECT code, name FROM accounting_accounts
                 WHERE company_id = ? ORDER BY code
                """, rs -> {
                    String code = pad(rs.getString("code"), 15, false);
                    String name = pad(rs.getString("name"), 50, false);
                    sb.append(code).append(name).append(NL);
                }, tenantContext.getCurrentCompanyId());
        return sb.toString();
    }

    private String contasolJournal(LocalDate from, LocalDate to) {
        StringBuilder sb = new StringBuilder();
        StringBuilder where = new StringBuilder(" WHERE je.company_id = ? AND je.status = 'POSTED'");
        List<Object> args = new ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (from != null) { where.append(" AND je.entry_date >= ?"); args.add(java.sql.Date.valueOf(from)); }
        if (to != null)   { where.append(" AND je.entry_date <= ?"); args.add(java.sql.Date.valueOf(to)); }
        jdbcTemplate.query("""
                SELECT je.entry_date, a.code AS account_code,
                       l.debit, l.credit, l.description
                  FROM journal_entry_lines l
                  JOIN journal_entries je ON je.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                """ + where + """
                 ORDER BY je.entry_date, je.entry_number, l.id
                """, rs -> {
                    String date = rs.getDate("entry_date").toLocalDate().format(CONTASOL_DATE);
                    String code = pad(rs.getString("account_code"), 10, false);
                    BigDecimal debit = rs.getBigDecimal("debit");
                    BigDecimal credit = rs.getBigDecimal("credit");
                    BigDecimal amount = debit.signum() > 0 ? debit : credit;
                    String side = debit.signum() > 0 ? "D" : "H";
                    String amountStr = pad(formatAmount(amount), 12, true);
                    String desc = (rs.getString("description") == null ? "" : rs.getString("description"));
                    if (desc.length() > 80) desc = desc.substring(0, 80);
                    sb.append(date).append(code).append(amountStr).append(side).append(desc).append(NL);
                }, args.toArray());
        return sb.toString();
    }

    // ====================================================================
    //  JSON BenjaGest (canónico, completo, lossless)
    // ====================================================================

    private String exportJson(ExportRequest req) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("format", "BENJAGEST/1.0");
        root.put("companyId", tenantContext.getCurrentCompanyId());
        root.put("targetKind", req.targetKind().toUpperCase());
        root.put("from", req.from() == null ? null : req.from().toString());
        root.put("to", req.to() == null ? null : req.to().toString());
        root.put("exportedAt", java.time.Instant.now().toString());

        switch (req.targetKind().toUpperCase()) {
            case "ACCOUNTS" -> root.put("accounts", jsonAccounts());
            case "JOURNAL_ENTRIES" -> root.put("entries", jsonJournal(req.from(), req.to(), req.includeDrafts()));
            case "INVOICES_SALES" -> root.put("salesInvoices", jsonSalesInvoices(req.from(), req.to()));
            case "INVOICES_PURCHASE" -> root.put("purchaseInvoices", jsonPurchaseInvoices(req.from(), req.to()));
            case "FIXED_ASSETS" -> root.put("fixedAssets", jsonFixedAssets());
            case "LOANS" -> root.put("loans", jsonLoans());
            case "CUSTOMERS" -> root.put("customers", jsonParties(false));
            case "SUPPLIERS" -> root.put("suppliers", jsonParties(true));
            default -> throw bad("JSON no soportado para target " + req.targetKind());
        }
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error serializando JSON: " + ex.getMessage());
        }
    }

    private List<Map<String, Object>> jsonAccounts() {
        return jdbcTemplate.query("""
                SELECT code, name, account_type, active FROM accounting_accounts
                 WHERE company_id = ? ORDER BY code
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("accountType", rs.getString("account_type"));
                    m.put("active", rs.getBoolean("active"));
                    return m;
                }, tenantContext.getCurrentCompanyId());
    }

    private List<Map<String, Object>> jsonJournal(LocalDate from, LocalDate to, Boolean includeDrafts) {
        List<Map<String, Object>> headers = jdbcTemplate.query("""
                SELECT je.id, je.entry_number, je.entry_date, je.concept,
                       je.source_type, je.source_id, je.status
                  FROM journal_entries je
                 WHERE je.company_id = ?
                   AND (? OR je.status = 'POSTED')
                   AND (? IS NULL OR je.entry_date >= ?)
                   AND (? IS NULL OR je.entry_date <= ?)
                 ORDER BY je.entry_date, je.entry_number
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("entryNumber", rs.getInt("entry_number"));
                    m.put("entryDate", rs.getDate("entry_date").toLocalDate().toString());
                    m.put("concept", rs.getString("concept"));
                    m.put("sourceType", rs.getString("source_type"));
                    m.put("sourceId", rs.getString("source_id"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, tenantContext.getCurrentCompanyId(),
                Boolean.TRUE.equals(includeDrafts),
                from == null ? null : java.sql.Date.valueOf(from),
                from == null ? null : java.sql.Date.valueOf(from),
                to == null ? null : java.sql.Date.valueOf(to),
                to == null ? null : java.sql.Date.valueOf(to));
        for (Map<String, Object> h : headers) {
            List<Map<String, Object>> lines = jdbcTemplate.query("""
                    SELECT a.code AS account_code, l.description, l.debit, l.credit
                      FROM journal_entry_lines l
                      JOIN accounting_accounts a ON a.id = l.account_id
                     WHERE l.journal_entry_id = ?
                     ORDER BY l.id
                    """, (rs, n) -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("accountCode", rs.getString("account_code"));
                        m.put("description", rs.getString("description"));
                        m.put("debit", rs.getBigDecimal("debit"));
                        m.put("credit", rs.getBigDecimal("credit"));
                        return m;
                    }, (String) h.get("id"));
            h.put("lines", lines);
        }
        return headers;
    }

    private List<Map<String, Object>> jsonSalesInvoices(LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT id, invoice_number, invoice_date, customer_legal_name,
                       subtotal, vat_total, total_amount, status
                  FROM sales_invoices
                 WHERE company_id = ?
                   AND (? IS NULL OR invoice_date >= ?)
                   AND (? IS NULL OR invoice_date <= ?)
                 ORDER BY invoice_date, invoice_number
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("invoiceNumber", rs.getString("invoice_number"));
                    m.put("invoiceDate", rs.getDate("invoice_date").toLocalDate().toString());
                    m.put("customerLegalName", rs.getString("customer_legal_name"));
                    m.put("subtotal", rs.getBigDecimal("subtotal"));
                    m.put("vatTotal", rs.getBigDecimal("vat_total"));
                    m.put("totalAmount", rs.getBigDecimal("total_amount"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, tenantContext.getCurrentCompanyId(),
                from == null ? null : java.sql.Date.valueOf(from),
                from == null ? null : java.sql.Date.valueOf(from),
                to == null ? null : java.sql.Date.valueOf(to),
                to == null ? null : java.sql.Date.valueOf(to));
    }

    private List<Map<String, Object>> jsonPurchaseInvoices(LocalDate from, LocalDate to) {
        return jdbcTemplate.query("""
                SELECT id, invoice_number, invoice_date, supplier_nif, supplier_name,
                       base_amount, vat_amount, total_amount, status
                  FROM purchase_invoices
                 WHERE company_id = ?
                   AND (? IS NULL OR invoice_date >= ?)
                   AND (? IS NULL OR invoice_date <= ?)
                 ORDER BY invoice_date, invoice_number
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("invoiceNumber", rs.getString("invoice_number"));
                    m.put("invoiceDate", rs.getDate("invoice_date").toLocalDate().toString());
                    m.put("supplierNif", rs.getString("supplier_nif"));
                    m.put("supplierName", rs.getString("supplier_name"));
                    m.put("baseAmount", rs.getBigDecimal("base_amount"));
                    m.put("vatAmount", rs.getBigDecimal("vat_amount"));
                    m.put("totalAmount", rs.getBigDecimal("total_amount"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, tenantContext.getCurrentCompanyId(),
                from == null ? null : java.sql.Date.valueOf(from),
                from == null ? null : java.sql.Date.valueOf(from),
                to == null ? null : java.sql.Date.valueOf(to),
                to == null ? null : java.sql.Date.valueOf(to));
    }

    private List<Map<String, Object>> jsonFixedAssets() {
        return jdbcTemplate.query("""
                SELECT id, code, name, category, acquisition_date, acquisition_cost,
                       useful_life_years, depreciation_method, residual_value, active
                  FROM fixed_assets
                 WHERE company_id = ? ORDER BY code
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("code", rs.getString("code"));
                    m.put("name", rs.getString("name"));
                    m.put("category", rs.getString("category"));
                    m.put("acquisitionDate", rs.getDate("acquisition_date").toLocalDate().toString());
                    m.put("acquisitionCost", rs.getBigDecimal("acquisition_cost"));
                    m.put("usefulLifeYears", rs.getBigDecimal("useful_life_years"));
                    m.put("depreciationMethod", rs.getString("depreciation_method"));
                    m.put("residualValue", rs.getBigDecimal("residual_value"));
                    m.put("active", rs.getBoolean("active"));
                    return m;
                }, tenantContext.getCurrentCompanyId());
    }

    private List<Map<String, Object>> jsonLoans() {
        return jdbcTemplate.query("""
                SELECT id, code, description, lender_name, lender_nif,
                       principal_amount, interest_rate, term_months,
                       start_date, first_installment_date, installment_amount,
                       method, status
                  FROM loans
                 WHERE company_id = ? ORDER BY start_date
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", rs.getString("id"));
                    m.put("code", rs.getString("code"));
                    m.put("description", rs.getString("description"));
                    m.put("lenderName", rs.getString("lender_name"));
                    m.put("lenderNif", rs.getString("lender_nif"));
                    m.put("principalAmount", rs.getBigDecimal("principal_amount"));
                    m.put("interestRate", rs.getBigDecimal("interest_rate"));
                    m.put("termMonths", rs.getInt("term_months"));
                    m.put("startDate", rs.getDate("start_date").toLocalDate().toString());
                    m.put("firstInstallmentDate", rs.getDate("first_installment_date").toLocalDate().toString());
                    m.put("installmentAmount", rs.getBigDecimal("installment_amount"));
                    m.put("method", rs.getString("method"));
                    m.put("status", rs.getString("status"));
                    return m;
                }, tenantContext.getCurrentCompanyId());
    }

    private List<Map<String, Object>> jsonParties(boolean suppliers) {
        String table = suppliers ? "suppliers" : "customers";
        String nameCol = suppliers ? "name" : "legal_name";
        String sql = "SELECT nif, " + nameCol + " AS name, email, phone, address, active"
                + " FROM " + table
                + " WHERE company_id = ?"
                + " ORDER BY " + nameCol;
        try {
            return jdbcTemplate.query(sql, (rs, n) -> {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("nif", rs.getString("nif"));
                m.put("name", rs.getString("name"));
                m.put("email", rs.getString("email"));
                m.put("phone", rs.getString("phone"));
                m.put("address", rs.getString("address"));
                m.put("active", rs.getBoolean("active"));
                return m;
            }, tenantContext.getCurrentCompanyId());
        } catch (Exception ex) {
            return List.of();
        }
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String esc(String s) {
        if (s == null) return "";
        // Si tiene ; o " o salto de línea → escapar entre comillas.
        if (s.indexOf(';') >= 0 || s.indexOf('"') >= 0 || s.indexOf('\n') >= 0) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    private String formatAmount(BigDecimal v) {
        if (v == null) return "0,00";
        return v.toPlainString().replace(".", ",");
    }

    private String pad(String s, int len, boolean rightAlign) {
        if (s == null) s = "";
        if (s.length() >= len) return s.substring(0, len);
        StringBuilder sb = new StringBuilder();
        if (rightAlign) {
            for (int i = 0; i < len - s.length(); i++) sb.append(' ');
            sb.append(s);
        } else {
            sb.append(s);
            for (int i = 0; i < len - s.length(); i++) sb.append(' ');
        }
        return sb.toString();
    }

    private String buildFilename(ExportRequest req) {
        String prefix = "benjagest-" + req.targetKind().toLowerCase().replace('_', '-');
        String ext = switch (req.format().toUpperCase()) {
            case "JSON_BENJAGEST" -> ".json";
            case "CONTASOL" -> ".txt";
            default -> ".csv";
        };
        String fromTo = (req.from() == null ? "" : "-" + req.from())
                + (req.to() == null ? "" : "-" + req.to());
        return prefix + fromTo + ext;
    }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record ExportRequest(
            String format, String targetKind,
            LocalDate from, LocalDate to,
            Boolean includeDrafts
    ) {}

    public record ExportPayload(byte[] content, String filename, String mimeType) {}
}
