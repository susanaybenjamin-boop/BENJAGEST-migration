package com.benjagest.backend.customer;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * PORT-4 CLI — Editor extendido de clientes.
 *
 * <p>Endpoint independiente de {@link CustomerController} para no
 * romper los consumidores existentes del listado simple. Bajo
 * {@code /api/customers-extended}.
 *
 * <p>GET /{id}  → datos completos incluyendo dirección postal +
 *                  facturación + codigo interno + modo defecto.
 * <p>PUT /{id}  → upsert atómico de los mismos campos.
 */
@RestController
@RequestMapping("/api/customers-extended")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class CustomerExtendedController {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public CustomerExtendedController(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    @GetMapping("/{id}")
    public CustomerExtended get(@PathVariable("id") String id) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<CustomerExtended> rows = jdbcTemplate.query("""
                SELECT id, legal_name, trade_name, tax_identifier, customer_type,
                       COALESCE(fiscal_type, '') AS fiscal_type,
                       COALESCE(billing_email, '') AS billing_email,
                       COALESCE(billing_phone, '') AS billing_phone,
                       COALESCE(default_vat_percent, 0) AS default_vat_percent,
                       COALESCE(default_retention_percent, 0) AS default_retention_percent,
                       vat_exempt,
                       COALESCE(payment_method, '') AS payment_method,
                       COALESCE(iban, '') AS iban,
                       COALESCE(address, '') AS address,
                       COALESCE(city, '') AS city,
                       COALESCE(province, '') AS province,
                       COALESCE(postal_code, '') AS postal_code,
                       COALESCE(country, 'España') AS country,
                       COALESCE(internal_code, '') AS internal_code,
                       COALESCE(default_mode, '') AS default_mode,
                       COALESCE(phone, '') AS phone,
                       COALESCE(email, '') AS email,
                       COALESCE(website, '') AS website,
                       COALESCE(notes, '') AS notes
                  FROM customers
                 WHERE id = ? AND company_id = ?
                """,
                (rs, i) -> new CustomerExtended(
                        rs.getString("id"),
                        rs.getString("legal_name"),
                        rs.getString("trade_name"),
                        rs.getString("tax_identifier"),
                        rs.getString("customer_type"),
                        rs.getString("fiscal_type"),
                        rs.getString("billing_email"),
                        rs.getString("billing_phone"),
                        rs.getBigDecimal("default_vat_percent"),
                        rs.getBigDecimal("default_retention_percent"),
                        rs.getBoolean("vat_exempt"),
                        rs.getString("payment_method"),
                        rs.getString("iban"),
                        rs.getString("address"),
                        rs.getString("city"),
                        rs.getString("province"),
                        rs.getString("postal_code"),
                        rs.getString("country"),
                        rs.getString("internal_code"),
                        rs.getString("default_mode"),
                        rs.getString("phone"),
                        rs.getString("email"),
                        rs.getString("website"),
                        rs.getString("notes")),
                id, companyId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }
        return rows.get(0);
    }

    /**
     * TPB-CLIENT-SETUP F1 — Alta de cliente-receptor. Crea el customer
     * bajo el {@code company_id} del tenant actual (cuando la asesoria
     * actua-como un cliente sin vinculo, sera la shadow company de ese
     * titular, asi que el receptor queda asociado al cliente correcto).
     */
    @PostMapping
    public ResponseEntity<CustomerExtended> create(@RequestBody CustomerExtended req) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (req.legalName() == null || req.legalName().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre fiscal es obligatorio");
        }
        if (req.taxIdentifier() == null || req.taxIdentifier().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El NIF/CIF es obligatorio");
        }
        String id = java.util.UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO customers (
                    id, company_id, legal_name, trade_name, tax_identifier,
                    customer_type, fiscal_type, billing_email, billing_phone,
                    default_vat_percent, default_retention_percent, vat_exempt,
                    payment_method, iban, address, city, province, postal_code,
                    country, internal_code, default_mode, phone, email, website,
                    notes, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                          ?, ?, ?, ?, ?, ?, ?, TRUE)
                """,
                id, companyId,
                req.legalName().trim(),
                blank(req.tradeName()),
                req.taxIdentifier().trim(),
                req.customerType() == null || req.customerType().isBlank()
                        ? "COMPANY" : req.customerType(),
                blank(req.fiscalType()),
                blank(req.billingEmail()),
                blank(req.billingPhone()),
                req.defaultVatPercent(),
                req.defaultRetentionPercent(),
                req.vatExempt(),
                blank(req.paymentMethod()),
                blank(req.iban()),
                blank(req.address()),
                blank(req.city()),
                blank(req.province()),
                blank(req.postalCode()),
                blank(req.country()),
                blank(req.internalCode()),
                blank(req.defaultMode()),
                blank(req.phone()),
                blank(req.email()),
                blank(req.website()),
                blank(req.notes()));
        return ResponseEntity.status(HttpStatus.CREATED).body(get(id));
    }

    @PutMapping("/{id}")
    public CustomerExtended update(@PathVariable("id") String id,
                                    @RequestBody CustomerExtended req) {
        String companyId = tenantContext.getCurrentCompanyId();
        int n = jdbcTemplate.update("""
                UPDATE customers
                   SET legal_name = ?, trade_name = ?, tax_identifier = ?,
                       customer_type = COALESCE(?, customer_type),
                       fiscal_type = ?, billing_email = ?, billing_phone = ?,
                       default_vat_percent = ?, default_retention_percent = ?,
                       vat_exempt = ?, payment_method = ?, iban = ?,
                       address = ?, city = ?, province = ?, postal_code = ?,
                       country = ?, internal_code = ?, default_mode = ?,
                       phone = ?, email = ?, website = ?, notes = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.legalName().trim(),
                blank(req.tradeName()),
                req.taxIdentifier().trim(),
                req.customerType(),
                blank(req.fiscalType()),
                blank(req.billingEmail()),
                blank(req.billingPhone()),
                req.defaultVatPercent(),
                req.defaultRetentionPercent(),
                req.vatExempt(),
                blank(req.paymentMethod()),
                blank(req.iban()),
                blank(req.address()),
                blank(req.city()),
                blank(req.province()),
                blank(req.postalCode()),
                blank(req.country()),
                blank(req.internalCode()),
                blank(req.defaultMode()),
                blank(req.phone()),
                blank(req.email()),
                blank(req.website()),
                blank(req.notes()),
                id, companyId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Cliente no encontrado");
        }
        return get(id);
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    public record CustomerExtended(
            String id,
            String legalName,
            String tradeName,
            String taxIdentifier,
            String customerType,
            String fiscalType,
            String billingEmail,
            String billingPhone,
            java.math.BigDecimal defaultVatPercent,
            java.math.BigDecimal defaultRetentionPercent,
            boolean vatExempt,
            String paymentMethod,
            String iban,
            String address,
            String city,
            String province,
            String postalCode,
            String country,
            String internalCode,
            String defaultMode,
            String phone,
            String email,
            String website,
            String notes
    ) {}
}
