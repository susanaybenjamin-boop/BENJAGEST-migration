package com.benjagest.backend.settings;

import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a company_email_config (1 fila por empresa).
 *
 * No descifra password: guarda y devuelve el ciphertext tal cual.
 * Quien decide cifrar/descifrar es el Service, asi este Repository
 * no acopla la BD con Jasypt.
 */
@Repository
public class EmailConfigRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public EmailConfigRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public Optional<EmailConfigRow> findCurrent() {
        return findForCompany(tenantContext.getCurrentCompanyId());
    }

    /**
     * Busca la config SMTP de una empresa especifica, sin pasar por
     * tenantContext. Util para flujos donde la asesoria envia email
     * mientras esta "actuando como" cliente (TPB Magic Link): el SMTP
     * configurado es el de la asesoria, no el del cliente.
     */
    public Optional<EmailConfigRow> findForCompany(String companyId) {
        if (companyId == null || companyId.isBlank()) return Optional.empty();
        List<EmailConfigRow> matches = jdbcTemplate.query("""
                SELECT smtp_host, smtp_port, smtp_user, smtp_password_encrypted,
                       from_address, from_name, reply_to,
                       tls_enabled, auth_required
                  FROM company_email_config
                 WHERE company_id = ?
                """, this::mapRow, companyId);
        return matches.stream().findFirst();
    }

    /**
     * Upsert: si no existe la fila para la empresa, la crea. Si existe,
     * la actualiza. Solo cambia password si el caller pasa newCiphertext
     * != null; null significa "no toques la password guardada".
     */
    public void upsert(EmailConfigRow row, String newCiphertextOrNull) {
        String existingCiphertext = findCurrent().map(EmailConfigRow::passwordCiphertext).orElse(null);
        String passwordToWrite = newCiphertextOrNull != null ? newCiphertextOrNull : existingCiphertext;

        jdbcTemplate.update("""
                INSERT INTO company_email_config (
                    company_id, smtp_host, smtp_port, smtp_user, smtp_password_encrypted,
                    from_address, from_name, reply_to, tls_enabled, auth_required
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    smtp_host = VALUES(smtp_host),
                    smtp_port = VALUES(smtp_port),
                    smtp_user = VALUES(smtp_user),
                    smtp_password_encrypted = VALUES(smtp_password_encrypted),
                    from_address = VALUES(from_address),
                    from_name = VALUES(from_name),
                    reply_to = VALUES(reply_to),
                    tls_enabled = VALUES(tls_enabled),
                    auth_required = VALUES(auth_required)
                """,
                tenantContext.getCurrentCompanyId(),
                row.smtpHost(),
                row.smtpPort(),
                row.smtpUser(),
                passwordToWrite,
                row.fromAddress(),
                row.fromName(),
                row.replyTo(),
                row.tlsEnabled(),
                row.authRequired()
        );
    }

    private EmailConfigRow mapRow(ResultSet rs, int rowNum) throws SQLException {
        Integer port = rs.getInt("smtp_port");
        if (rs.wasNull()) {
            port = null;
        }
        return new EmailConfigRow(
                rs.getString("smtp_host"),
                port,
                rs.getString("smtp_user"),
                rs.getString("smtp_password_encrypted"),
                rs.getString("from_address"),
                rs.getString("from_name"),
                rs.getString("reply_to"),
                rs.getBoolean("tls_enabled"),
                rs.getBoolean("auth_required")
        );
    }
}
