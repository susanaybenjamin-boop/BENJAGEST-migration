package com.benjagest.backend.auth;

import com.benjagest.backend.auth.dto.LoginRequest;
import com.benjagest.backend.auth.dto.LoginResponse;
import com.benjagest.backend.auth.dto.RegisterRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Bloque REGISTRO — alta de cuenta (asesoría o empresa/autónomo). Crea, en una
 * transacción, la empresa + el usuario OWNER + su membership, y siembra lo mínimo
 * para operar (módulos esenciales, ejercicio fiscal OPEN, plan contable PGC desde
 * la plantilla permanente {@code pgc_template}, V147). Tras el alta hace
 * auto-login reutilizando {@link AuthService#login} (mismos tokens que un login).
 *
 * <p>Zona auth (CLAUDE.md §11.2): endpoint público {@code /api/auth/register},
 * el server FIJA global_role=USER, company_type=ADVISORY|CLIENT y role=OWNER —
 * nada de escalada desde el cliente. Verificación de email: con Google viene
 * verificado; con email+contraseña entra directo (SMTP propio = slice futuro).
 */
@Service
public class RegisterService {

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuthService authService;

    public RegisterService(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, AuthService authService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.authService = authService;
    }

    @Transactional
    public LoginResponse register(RegisterRequest r) {
        String email = norm(r.email());
        // ADVISORY = asesoría (ve cartera); cualquier otro valor = empresa/autónomo.
        boolean advisory = "ADVISORY".equalsIgnoreCase(trim(r.accountType()));
        String companyType = advisory ? "ADVISORY" : "CLIENT";

        if (email == null) throw bad("Email requerido");
        if (blank(r.legalName())) throw bad("La razón social es obligatoria");
        if (blank(r.taxIdentifier())) throw bad("El NIF/CIF es obligatorio");
        if (blank(r.addressLine()) || blank(r.city())) throw bad("El domicilio fiscal es obligatorio");
        if (r.password() == null || r.password().length() < 8) {
            throw bad("La contraseña debe tener al menos 8 caracteres");
        }
        // Email único (mensaje claro, no el 500 del UNIQUE).
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM user_accounts WHERE email = ?", Integer.class, email);
        if (exists != null && exists > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Ya existe una cuenta con ese email");
        }
        // NIF: validación básica de formato (no bloqueante de más; el detalle por
        // tipo se afina en Configuración). Debe tener letra/dígitos plausibles.
        String nif = trim(r.taxIdentifier()).toUpperCase();
        if (!nif.matches("[A-Z0-9]{8,10}")) {
            throw bad("El NIF/CIF no tiene un formato válido");
        }

        String companyId = UUID.randomUUID().toString();
        try {
            jdbc.update("""
                    INSERT INTO companies (
                        id, legal_name, trade_name, tax_identifier,
                        company_type, parent_company_id, active,
                        email, address_line, city, province, postal_code, country,
                        verifactu_mode, verifactu_modality
                    ) VALUES (?, ?, ?, ?, ?, NULL, TRUE,
                              ?, ?, ?, ?, ?, 'ES', 'TEST', 'NO_VERIFACTU')
                    """,
                    companyId, trim(r.legalName()), trim(r.legalName()), nif,
                    companyType, email, trim(r.addressLine()), trim(r.city()),
                    blank(r.province()) ? null : trim(r.province()),
                    blank(r.postalCode()) ? null : trim(r.postalCode()));
        } catch (org.springframework.dao.DataIntegrityViolationException ex) {
            // tax_identifier es UNIQUE: NIF ya registrado.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una empresa con ese NIF/CIF");
        }

        String userId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO user_accounts (
                    id, email, password_hash, display_name, global_role,
                    forced_password_change, active
                ) VALUES (?, ?, ?, ?, 'USER', FALSE, TRUE)
                """,
                userId, email, passwordEncoder.encode(r.password()), trim(r.displayName()));

        jdbc.update("""
                INSERT INTO company_memberships (id, company_id, user_id, role_name, active)
                VALUES (?, ?, ?, 'OWNER', TRUE)
                """,
                UUID.randomUUID().toString(), companyId, userId);

        // Sembrado mínimo para operar desde el primer minuto.
        activateEssentialModules(companyId, advisory);
        seedFiscalYearOpen(companyId);
        seedAccountsFromTemplate(companyId);

        // Auto-login: mismos tokens y respuesta que /login.
        return authService.login(new LoginRequest(email, r.password()));
    }

    // ---- Sembrado (mismo criterio que AdvisoryService, pero desde la plantilla) ----

    /**
     * Activa los módulos con los que arranca la cuenta. La ASESORÍA lleva los
     * fiscales/DEHú (Modelos AEAT = tax, buzón = notifications); el EMPRESARIO
     * NO (decisión Benjamin 2026-06-26: esos son de la asesoría) pero empieza con
     * el resto activo y desactiva lo que no use desde Configuración.
     */
    private void activateEssentialModules(String companyId, boolean advisory) {
        String slugs = advisory
                ? "'core','billing','purchases','accounting','tax','calendar',"
                  + "'notifications','labor','reports','settings'"
                : "'core','billing','purchases','accounting','calendar',"
                  + "'labor','reports','settings','time-clock'";
        jdbc.update("""
                INSERT IGNORE INTO company_modules (id, company_id, module_id, active)
                SELECT UUID(), ?, m.id, TRUE
                  FROM module_catalog m
                 WHERE m.slug IN (""" + slugs + """
                 ) AND m.active_in_catalog = TRUE
                """, companyId);
    }

    private void seedFiscalYearOpen(String companyId) {
        int year = java.time.LocalDate.now().getYear();
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number,
                                            start_date, end_date, status)
                SELECT UUID(), ?, ?, CONCAT(?, '-01-01'), CONCAT(?, '-12-31'), 'OPEN'
                  FROM dual
                 WHERE NOT EXISTS (
                       SELECT 1 FROM fiscal_years WHERE company_id = ? AND year_number = ?)
                """, companyId, year, year, year, companyId, year);
    }

    /** Plan contable estándar copiado de la plantilla permanente (V147). */
    private void seedAccountsFromTemplate(String companyId) {
        jdbc.update("""
                INSERT IGNORE INTO accounting_accounts
                    (id, company_id, code, name, account_type, active, is_standard)
                SELECT UUID(), ?, t.code, t.name, t.account_type, TRUE, TRUE
                  FROM pgc_template t
                """, companyId);
    }

    private static String norm(String s) {
        if (s == null) return null;
        String v = s.trim().toLowerCase();
        return v.isEmpty() ? null : v;
    }
    private static String trim(String s) { return s == null ? null : s.trim(); }
    private static boolean blank(String s) { return s == null || s.trim().isEmpty(); }
    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
