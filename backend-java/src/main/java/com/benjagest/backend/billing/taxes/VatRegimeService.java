package com.benjagest.backend.billing.taxes;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * VAT-REGIME — régimen de IVA de la empresa (versión base): GENERAL, PRORRATA
 * (con %) o CASH_CRITERION (criterio de caja). Se captura y se muestra; el
 * efecto fino en el cálculo se afina con un caso real.
 */
@Service
public class VatRegimeService {

    private static final Set<String> VALID = Set.of("GENERAL", "PRORRATA", "CASH_CRITERION");

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public VatRegimeService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public record Regime(String regime, BigDecimal prorrataPercent) {}

    public Regime get() {
        return jdbc.query(
                "SELECT vat_regime, prorrata_percent FROM companies WHERE id = ?",
                rs -> rs.next() ? new Regime(rs.getString(1), rs.getBigDecimal(2))
                        : new Regime("GENERAL", null),
                tenant.getCurrentCompanyId());
    }

    public Regime save(String regime, BigDecimal prorrataPercent) {
        String r = regime == null ? "GENERAL" : regime.trim().toUpperCase();
        if (!VALID.contains(r)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Régimen de IVA no válido.");
        }
        BigDecimal p = "PRORRATA".equals(r) ? prorrataPercent : null;
        if (p != null && (p.signum() < 0 || p.compareTo(BigDecimal.valueOf(100)) > 0)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "La prorrata debe estar entre 0 y 100.");
        }
        jdbc.update("UPDATE companies SET vat_regime = ?, prorrata_percent = ? WHERE id = ?",
                r, p, tenant.getCurrentCompanyId());
        return new Regime(r, p);
    }
}
