package com.benjagest.backend.accounting;

import com.benjagest.backend.tenant.TenantContext;
import java.time.LocalDate;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Guardián del cierre fiscal.
 *
 * <p>Cualquier operación que altere la contabilidad (borrar una
 * factura recibida o emitida, crear/borrar un asiento, modificar un
 * cobro/pago) tiene que preguntar primero "¿el periodo de esta fecha
 * está OPEN, LOCKED o CLOSED?". Si no está OPEN, la operación se
 * rechaza con 409 y la UI ofrece el flujo de rectificativa.
 *
 * <p>Estados (V2 ck_fiscal_years_status):
 * <ul>
 *   <li>{@code OPEN}: año vigente, todo permitido.</li>
 *   <li>{@code LOCKED}: el asesor lo cerró provisionalmente porque ya
 *     presentó modelos; nadie debe tocar ni rectificar sin desbloquear.
 *     Solo OWNER puede pasar a OPEN.</li>
 *   <li>{@code CLOSED}: cierre definitivo tras YEAR-CLOSE. Solo
 *     rectificativas con contraasiento nuevo en año siguiente.</li>
 * </ul>
 *
 * <p>Cuando el año fiscal para la fecha NO EXISTE en la tabla, el
 * comportamiento es permisivo (devuelve OPEN) — son casos legacy donde
 * no se sembró el fiscal_year. El seed se hará en YEAR-CLOSE.
 */
@Service
public class FiscalYearGuardService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public FiscalYearGuardService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    /**
     * Devuelve el estado del año fiscal que cubre la fecha indicada
     * para la empresa actual. Si no hay fila (legacy), devuelve OPEN.
     */
    public String statusForDate(LocalDate date) {
        return statusForDate(tenantContext.getCurrentCompanyId(), date);
    }

    public String statusForDate(String companyId, LocalDate date) {
        if (companyId == null || date == null) return "OPEN";
        Optional<String> status = jdbcTemplate.query("""
                SELECT status FROM fiscal_years
                 WHERE company_id = ?
                   AND start_date <= ?
                   AND end_date >= ?
                 LIMIT 1
                """,
                rs -> rs.next() ? Optional.of(rs.getString("status")) : Optional.<String>empty(),
                companyId, java.sql.Date.valueOf(date), java.sql.Date.valueOf(date));
        return status.orElse("OPEN");
    }

    /**
     * Lanza 409 Conflict si la fecha cae en un periodo LOCKED o CLOSED.
     * El mensaje es legible para que la UI lo muestre directamente al
     * usuario y le explique que tiene que rectificar en lugar de
     * borrar/modificar.
     */
    public void requireOpenForDate(LocalDate date, String operation) {
        String status = statusForDate(date);
        if ("LOCKED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El ejercicio fiscal del " + date + " está BLOQUEADO. "
                            + "No se puede " + operation + " sin desbloquearlo. "
                            + "Si el cambio es necesario, crea una rectificativa en el "
                            + "ejercicio actual.");
        }
        if ("CLOSED".equals(status)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El ejercicio fiscal del " + date + " está CERRADO. "
                            + "No se puede " + operation + " — el cierre es definitivo. "
                            + "Crea una rectificativa en el ejercicio actual.");
        }
    }
}
