package com.benjagest.backend.settings;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.http.HttpStatus;

/**
 * CLIENT-CONFIG — datos del tab "Configuración" de la ficha del cliente:
 * cifras manuales (clientes sin contabilidad) + config interna de la asesoría
 * (periodicidad de modelos, contacto, notas internas). Opera sobre el tenant
 * actual (cuando la asesoría actúa por un cliente, el tenant es el cliente).
 */
@Service
public class ClientConfigService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public ClientConfigService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    // ---- Cifras manuales ----

    public List<ManualFinancial> listFinancials(Integer year) {
        String companyId = tenant.getCurrentCompanyId();
        if (year == null) {
            return jdbc.query("""
                    SELECT id, period_year, period_quarter, income, expenses, net_result, notes
                      FROM client_manual_financials
                     WHERE company_id = ?
                     ORDER BY period_year DESC, period_quarter
                    """, this::mapFinancial, companyId);
        }
        return jdbc.query("""
                SELECT id, period_year, period_quarter, income, expenses, net_result, notes
                  FROM client_manual_financials
                 WHERE company_id = ? AND period_year = ?
                 ORDER BY period_quarter
                """, this::mapFinancial, companyId, year);
    }

    public ManualFinancial upsertFinancial(ManualFinancial f) {
        String companyId = tenant.getCurrentCompanyId();
        int q = f.periodQuarter() == null ? 0 : Math.max(0, Math.min(4, f.periodQuarter()));
        BigDecimal income = f.income() == null ? BigDecimal.ZERO : f.income();
        BigDecimal expenses = f.expenses() == null ? BigDecimal.ZERO : f.expenses();
        jdbc.update("""
                INSERT INTO client_manual_financials
                    (id, company_id, period_year, period_quarter, income, expenses, net_result, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    income = VALUES(income), expenses = VALUES(expenses),
                    net_result = VALUES(net_result), notes = VALUES(notes)
                """,
                UUID.randomUUID().toString(), companyId, f.periodYear(), q,
                income, expenses, f.netResult(), blank(f.notes()));
        return listFinancials(f.periodYear()).stream()
                .filter(x -> x.periodQuarter() == q).findFirst().orElse(f);
    }

    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteFinancial(String id) {
        jdbc.update("DELETE FROM client_manual_financials WHERE id = ? AND company_id = ?",
                id, tenant.getCurrentCompanyId());
    }

    // ---- Config interna ----

    public AdvisoryConfig getConfig() {
        String companyId = tenant.getCurrentCompanyId();
        return jdbc.query("""
                SELECT fiscal_period, tax_regime, contact_channel, contact_value, internal_notes
                  FROM client_advisory_config WHERE company_id = ?
                """, rs -> rs.next()
                        ? new AdvisoryConfig(rs.getString("fiscal_period"), rs.getString("tax_regime"),
                                rs.getString("contact_channel"), rs.getString("contact_value"),
                                rs.getString("internal_notes"))
                        : new AdvisoryConfig(null, null, null, null, null),
                companyId);
    }

    public AdvisoryConfig saveConfig(AdvisoryConfig c) {
        String companyId = tenant.getCurrentCompanyId();
        jdbc.update("""
                INSERT INTO client_advisory_config
                    (company_id, fiscal_period, tax_regime, contact_channel, contact_value, internal_notes)
                VALUES (?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    fiscal_period = VALUES(fiscal_period), tax_regime = VALUES(tax_regime),
                    contact_channel = VALUES(contact_channel), contact_value = VALUES(contact_value),
                    internal_notes = VALUES(internal_notes)
                """,
                companyId, blank(c.fiscalPeriod()), blank(c.taxRegime()),
                blank(c.contactChannel()), blank(c.contactValue()), blank(c.internalNotes()));
        return getConfig();
    }

    private ManualFinancial mapFinancial(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new ManualFinancial(rs.getString("id"), rs.getInt("period_year"),
                rs.getInt("period_quarter"), rs.getBigDecimal("income"),
                rs.getBigDecimal("expenses"), rs.getBigDecimal("net_result"),
                rs.getString("notes"));
    }

    private String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public record ManualFinancial(String id, int periodYear, Integer periodQuarter,
            BigDecimal income, BigDecimal expenses, BigDecimal netResult, String notes) {}

    public record AdvisoryConfig(String fiscalPeriod, String taxRegime,
            String contactChannel, String contactValue, String internalNotes) {}

    @RestController
    @RequestMapping("/api/client-config")
    @RequiresModule("settings")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class ClientConfigController {
        private final ClientConfigService service;
        public ClientConfigController(ClientConfigService service) { this.service = service; }

        @GetMapping("/financials")
        public List<ManualFinancial> financials(@RequestParam(value = "year", required = false) Integer year) {
            return service.listFinancials(year);
        }

        @PutMapping("/financials")
        public ManualFinancial upsertFinancial(@RequestBody ManualFinancial f) {
            return service.upsertFinancial(f);
        }

        @DeleteMapping("/financials/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void deleteFinancial(@PathVariable("id") String id) { service.deleteFinancial(id); }

        @GetMapping("/advisory")
        public AdvisoryConfig getConfig() { return service.getConfig(); }

        @PutMapping("/advisory")
        public AdvisoryConfig saveConfig(@RequestBody AdvisoryConfig c) { return service.saveConfig(c); }
    }
}
