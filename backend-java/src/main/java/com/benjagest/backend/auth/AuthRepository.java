package com.benjagest.backend.auth;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a user_accounts y company_memberships para el flujo de
 * autenticacion. No usa TenantContext: la auth es PREVIA a saber
 * en que empresa estoy.
 */
@Repository
public class AuthRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuthRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * PIN-OWNER — (userId, session_pin_hash) de los OWNER de una empresa que
     * tengan PIN de sesión. Permite que el admin entre por PIN en un equipo
     * emparejado con SU PIN de sesión (mismo PIN que el del bloqueo).
     */
    public List<String[]> ownerSessionPins(String companyId) {
        return jdbcTemplate.query("""
                SELECT ua.id, ua.session_pin_hash
                  FROM company_memberships m
                  JOIN user_accounts ua ON ua.id = m.user_id
                 WHERE m.company_id = ? AND m.active = TRUE AND m.role_name = 'OWNER'
                   AND ua.active = TRUE AND ua.session_pin_hash IS NOT NULL
                """,
                (rs, n) -> new String[]{rs.getString(1), rs.getString(2)},
                companyId);
    }

    /** REG-VERIFY — ¿la cuenta tiene el email verificado? (gate del login). */
    public boolean isEmailVerified(String userId) {
        Boolean v = jdbcTemplate.query(
                "SELECT email_verified FROM user_accounts WHERE id = ?",
                rs -> rs.next() && rs.getBoolean(1), userId);
        return Boolean.TRUE.equals(v);
    }

    /** ¿Existe alguna cuenta? Si no, el arranque muestra el REGISTRO (primer uso). */
    public boolean hasAnyAccount() {
        Integer n = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM user_accounts", Integer.class);
        return n != null && n > 0;
    }

    /**
     * ¿Hay alguna empresa de ASESORÍA (ADVISORY/INTERNAL)? El multi-puesto
     * (emparejar equipo + PIN) es exclusivo de asesoría; un empresario entra
     * siempre con email/contraseña. La UI usa esto para elegir la pantalla.
     */
    public boolean hasAdvisoryCompany() {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM companies WHERE company_type IN ('ADVISORY','INTERNAL')",
                Integer.class);
        return n != null && n > 0;
    }

    public Optional<UserRecord> findUserByEmail(String email) {
        List<UserRecord> matches = jdbcTemplate.query("""
                SELECT id, email, password_hash, display_name, global_role, active
                  FROM user_accounts
                 WHERE LOWER(email) = LOWER(?)
                   AND active = TRUE
                 LIMIT 1
                """,
                (rs, rowNum) -> mapUser(rs),
                email
        );
        return matches.stream().findFirst();
    }

    public Optional<UserRecord> findUserById(String id) {
        List<UserRecord> matches = jdbcTemplate.query("""
                SELECT id, email, password_hash, display_name, global_role, active
                  FROM user_accounts
                 WHERE id = ?
                   AND active = TRUE
                 LIMIT 1
                """,
                (rs, rowNum) -> mapUser(rs),
                id
        );
        return matches.stream().findFirst();
    }

    public List<MembershipRecord> findMembershipsForUser(String userId) {
        return jdbcTemplate.query("""
                SELECT m.company_id,
                       c.legal_name,
                       c.trade_name,
                       c.company_type,
                       m.role_name
                  FROM company_memberships m
                  JOIN companies c ON c.id = m.company_id
                 WHERE m.user_id = ?
                   AND m.active = TRUE
                   AND c.active = TRUE
                 ORDER BY c.legal_name
                """,
                (rs, rowNum) -> new MembershipRecord(
                        rs.getString("company_id"),
                        rs.getString("legal_name"),
                        rs.getString("trade_name"),
                        rs.getString("company_type"),
                        rs.getString("role_name")
                ),
                userId
        );
    }

    public Optional<MembershipRecord> findMembership(String userId, String companyId) {
        List<MembershipRecord> matches = jdbcTemplate.query("""
                SELECT m.company_id,
                       c.legal_name,
                       c.trade_name,
                       c.company_type,
                       m.role_name
                  FROM company_memberships m
                  JOIN companies c ON c.id = m.company_id
                 WHERE m.user_id = ?
                   AND m.company_id = ?
                   AND m.active = TRUE
                   AND c.active = TRUE
                 LIMIT 1
                """,
                (rs, rowNum) -> new MembershipRecord(
                        rs.getString("company_id"),
                        rs.getString("legal_name"),
                        rs.getString("trade_name"),
                        rs.getString("company_type"),
                        rs.getString("role_name")
                ),
                userId,
                companyId
        );
        return matches.stream().findFirst();
    }

    private UserRecord mapUser(ResultSet rs) throws SQLException {
        return new UserRecord(
                rs.getString("id"),
                rs.getString("email"),
                rs.getString("password_hash"),
                rs.getString("display_name"),
                rs.getString("global_role"),
                rs.getBoolean("active")
        );
    }

    public record UserRecord(
            String id,
            String email,
            String passwordHash,
            String displayName,
            String globalRole,
            boolean active
    ) {
    }

    public record MembershipRecord(
            String companyId,
            String companyLegalName,
            String companyTradeName,
            String companyType,
            String roleName
    ) {
    }
}
