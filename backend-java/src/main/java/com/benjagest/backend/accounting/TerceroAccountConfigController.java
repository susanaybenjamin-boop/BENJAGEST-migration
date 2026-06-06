package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Pestaña Configuración → Contabilidad → "Plan de tercero".
 *
 * <p>Permite al asesor configurar cómo se generan las sub-cuentas de
 * tercero (proveedores 4000xxx y clientes 4300xxx) en la empresa activa:
 *
 * <ul>
 *   <li><b>length</b> (6–12): longitud total del código de tercero. Con
 *       length=7 se generan 4000001/4300001; con length=12 se generan
 *       400000000001/430000000001.</li>
 *   <li><b>mode</b>: {@code SEQUENTIAL} (1, 2, 3…) o {@code BY_NIF}
 *       (dígitos del NIF/CIF del tercero, padded a la longitud).</li>
 * </ul>
 *
 * <p>Cambios efectivos a partir del PRÓXIMO tercero creado — los
 * existentes <b>no</b> se renumeran (eso destrozaría el histórico
 * contable y los asientos pasados).
 */
@RestController
@RequestMapping("/api/accounting/tercero-config")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
public class TerceroAccountConfigController {

    public record TerceroConfigDto(int length, String mode) {}

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public TerceroAccountConfigController(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    @GetMapping
    public TerceroConfigDto get() {
        String companyId = tenantContext.getCurrentCompanyId();
        List<TerceroConfigDto> rows = jdbcTemplate.query("""
                SELECT tercero_account_length, tercero_account_mode
                  FROM companies WHERE id = ? LIMIT 1
                """,
                (rs, n) -> new TerceroConfigDto(
                        rs.getInt("tercero_account_length"),
                        rs.getString("tercero_account_mode")),
                companyId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada");
        }
        TerceroConfigDto r = rows.get(0);
        // Defensivo: si la empresa fue creada antes de V56 las columnas
        // podrían venir con 0/null — devolvemos defaults razonables.
        int len = r.length() >= 6 && r.length() <= 12 ? r.length() : 7;
        String mode = r.mode() == null ? "SEQUENTIAL" : r.mode().toUpperCase(Locale.ROOT);
        return new TerceroConfigDto(len, mode);
    }

    @PutMapping
    public TerceroConfigDto update(@RequestBody TerceroConfigDto body) {
        if (body == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta body");
        }
        int len = body.length();
        if (len < 6 || len > 12) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La longitud debe estar entre 6 y 12 dígitos.");
        }
        String mode = body.mode() == null ? "SEQUENTIAL" : body.mode().trim().toUpperCase(Locale.ROOT);
        if (!"SEQUENTIAL".equals(mode) && !"BY_NIF".equals(mode)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Modo no válido: usar SEQUENTIAL o BY_NIF.");
        }
        String companyId = tenantContext.getCurrentCompanyId();
        int n = jdbcTemplate.update("""
                UPDATE companies
                   SET tercero_account_length = ?,
                       tercero_account_mode   = ?
                 WHERE id = ?
                """, len, mode, companyId);
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empresa no encontrada");
        }
        return get();
    }
}
