package com.benjagest.backend.customer;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

@Repository
public class CustomerRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public CustomerRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public void insertCustomer(String id, CustomerCreateRequest request) {
        jdbcTemplate.update("""
                INSERT INTO customers (id, company_id, legal_name, trade_name, tax_identifier)
                VALUES (?, ?, ?, ?, ?)
                """,
                id,
                tenantContext.getCurrentCompanyId(),
                request.legalName().trim(),
                blankToNull(request.tradeName()),
                request.taxIdentifier().trim()
        );
    }

    public void insertPrimaryContact(String id, String customerId, CustomerCreateRequest request) {
        if (!StringUtils.hasText(request.contactName())
                && !StringUtils.hasText(request.email())
                && !StringUtils.hasText(request.phone())) {
            return;
        }

        jdbcTemplate.update("""
                INSERT INTO customer_contacts (id, customer_id, full_name, email, phone, primary_contact)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                id,
                customerId,
                StringUtils.hasText(request.contactName()) ? request.contactName().trim() : request.legalName().trim(),
                blankToNull(request.email()),
                blankToNull(request.phone())
        );
    }

    public CustomerResponse findById(String id) {
        return jdbcTemplate.queryForObject("""
                SELECT c.id,
                       c.legal_name,
                       c.trade_name,
                       c.tax_identifier,
                       pc.full_name AS primary_contact_name,
                       pc.email,
                       pc.phone,
                       c.created_at
                FROM customers c
                LEFT JOIN customer_contacts pc
                       ON pc.customer_id = c.id
                      AND pc.primary_contact = TRUE
                      AND pc.active = TRUE
                WHERE c.id = ?
                  AND c.company_id = ?
                """, this::mapCustomer, id, tenantContext.getCurrentCompanyId());
    }

    public List<CustomerResponse> findAllActive() {
        return jdbcTemplate.query("""
                SELECT c.id,
                       c.legal_name,
                       c.trade_name,
                       c.tax_identifier,
                       pc.full_name AS primary_contact_name,
                       pc.email,
                       pc.phone,
                       c.created_at
                FROM customers c
                LEFT JOIN customer_contacts pc
                       ON pc.customer_id = c.id
                      AND pc.primary_contact = TRUE
                      AND pc.active = TRUE
                WHERE c.active = TRUE
                  AND c.company_id = ?
                ORDER BY c.legal_name
                LIMIT 100
                """, this::mapCustomer, tenantContext.getCurrentCompanyId());
    }

    private CustomerResponse mapCustomer(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new CustomerResponse(
                rs.getString("id"),
                rs.getString("legal_name"),
                rs.getString("trade_name"),
                rs.getString("tax_identifier"),
                rs.getString("primary_contact_name"),
                rs.getString("email"),
                rs.getString("phone"),
                createdAt == null ? null : createdAt.toInstant()
        );
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
