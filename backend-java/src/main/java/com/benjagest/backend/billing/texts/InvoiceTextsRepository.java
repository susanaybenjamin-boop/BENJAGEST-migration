package com.benjagest.backend.billing.texts;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

/**
 * Lecturas y escrituras de los textos legales de factura que viven en
 * la tabla `companies` (introducidos en V15). Una fila por empresa.
 */
@Repository
public class InvoiceTextsRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public InvoiceTextsRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public Optional<InvoiceTexts> findCurrent() {
        List<InvoiceTexts> matches = jdbcTemplate.query("""
                SELECT invoice_footer_template AS pie,
                       invoice_text_exempt AS exempt,
                       invoice_text_reverse_charge AS reverse_charge,
                       invoice_text_reduced_vat AS reduced_vat,
                       invoice_text_rectifying AS rectifying,
                       invoice_text_legal_terms AS legal_terms,
                       invoice_show_iban AS show_iban
                  FROM companies
                 WHERE id = ?
                """, this::mapTexts, tenantContext.getCurrentCompanyId());
        return matches.stream().findFirst();
    }

    public int update(InvoiceTexts texts) {
        return jdbcTemplate.update("""
                UPDATE companies
                   SET invoice_footer_template = ?,
                       invoice_text_exempt = ?,
                       invoice_text_reverse_charge = ?,
                       invoice_text_reduced_vat = ?,
                       invoice_text_rectifying = ?,
                       invoice_text_legal_terms = ?,
                       invoice_show_iban = ?
                 WHERE id = ?
                """,
                blankToNull(texts.pie()),
                blankToNull(texts.exempt()),
                blankToNull(texts.reverseCharge()),
                blankToNull(texts.reducedVat()),
                blankToNull(texts.rectifying()),
                blankToNull(texts.legalTerms()),
                texts.showIban(),
                tenantContext.getCurrentCompanyId()
        );
    }

    private InvoiceTexts mapTexts(ResultSet rs, int rowNum) throws SQLException {
        return new InvoiceTexts(
                rs.getString("pie"),
                rs.getString("exempt"),
                rs.getString("reverse_charge"),
                rs.getString("reduced_vat"),
                rs.getString("rectifying"),
                rs.getString("legal_terms"),
                rs.getBoolean("show_iban")
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
