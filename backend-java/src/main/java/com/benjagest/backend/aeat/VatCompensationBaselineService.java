package com.benjagest.backend.aeat;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * IVA-COMP (2026-07-09) — saldo INICIAL de cuotas de IVA a compensar de
 * periodos anteriores (modelo 303, casilla 110). Una fila por empresa.
 *
 * <p>La empresa que migra a BENJAGEST a media vida teclea aquí una vez su
 * "IVA a compensar pendiente" de partida y desde qué trimestre empieza el
 * arrastre; de ahí en adelante {@link AeatExtraModelsService} lo calcula
 * solo. Mismo modelo que el baseline de migración de facturas.
 */
@Service
public class VatCompensationBaselineService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public VatCompensationBaselineService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public record Baseline(BigDecimal openingBalance, int asOfYear, int asOfQuarter, String notes) {}

    /** Saldo inicial de la empresa, o null si no se ha configurado. */
    public Baseline get() {
        List<Baseline> rows = jdbc.query("""
                SELECT opening_balance, as_of_year, as_of_quarter, notes
                  FROM vat_compensation_baseline WHERE company_id = ?
                """, (rs, n) -> new Baseline(
                        rs.getBigDecimal("opening_balance"),
                        rs.getInt("as_of_year"),
                        rs.getInt("as_of_quarter"),
                        rs.getString("notes")),
                tenant.getCurrentCompanyId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Transactional
    public Baseline upsert(Baseline req) {
        if (req == null || req.asOfQuarter() < 1 || req.asOfQuarter() > 4) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Trimestre de corte inválido (1-4).");
        }
        BigDecimal balance = req.openingBalance() == null ? BigDecimal.ZERO : req.openingBalance();
        if (balance.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El saldo a compensar no puede ser negativo (es el IVA negativo acumulado, en positivo).");
        }
        String companyId = tenant.getCurrentCompanyId();
        int updated = jdbc.update("""
                UPDATE vat_compensation_baseline
                   SET opening_balance = ?, as_of_year = ?, as_of_quarter = ?, notes = ?
                 WHERE company_id = ?
                """, balance, req.asOfYear(), req.asOfQuarter(), req.notes(), companyId);
        if (updated == 0) {
            jdbc.update("""
                    INSERT INTO vat_compensation_baseline
                        (id, company_id, opening_balance, as_of_year, as_of_quarter, notes)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), companyId, balance,
                    req.asOfYear(), req.asOfQuarter(), req.notes());
        }
        return get();
    }
}
