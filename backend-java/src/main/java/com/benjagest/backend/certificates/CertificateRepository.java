package com.benjagest.backend.certificates;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

import org.jasypt.encryption.StringEncryptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import com.benjagest.backend.tenant.TenantContext;

/**
 * Acceso unico a `digital_certificates`. Cifra `encrypted_password` y
 * `certificate_data` al persistir, descifra al leer. El resto del
 * backend solo ve los datos en claro a traves del Service; nadie habla
 * con esta tabla saltandose esta clase.
 *
 * Aislamiento: companyId del TenantContext. Una empresa nunca ve
 * certificados de otra ni siquiera pasando ids en la URL.
 */
@Repository
public class CertificateRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final StringEncryptor encryptor;

    public CertificateRepository(JdbcTemplate jdbcTemplate,
                                 TenantContext tenantContext,
                                 StringEncryptor encryptor) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.encryptor = encryptor;
    }

    public void insert(Certificate cert) {
        jdbcTemplate.update("""
                INSERT INTO digital_certificates (
                    id, company_id, alias, certificate_type,
                    subject_name, subject_tax_identifier,
                    encrypted_password, storage_path, certificate_data,
                    valid_from, valid_to, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                cert.id(),
                tenantContext.getCurrentCompanyId(),
                cert.alias(),
                cert.certificateType(),
                cert.subjectName(),
                cert.subjectTaxIdentifier(),
                encryptIfPresent(cert.passwordPlaintext()),
                cert.storagePath(),
                encryptIfPresent(cert.certificateDataBase64()),
                cert.validFrom() == null ? null : Timestamp.from(cert.validFrom()),
                cert.validTo() == null ? null : Timestamp.from(cert.validTo()),
                cert.active()
        );
    }

    public int softDelete(String id) {
        return jdbcTemplate.update("""
                UPDATE digital_certificates
                   SET active = FALSE
                 WHERE id = ?
                   AND company_id = ?
                """,
                id,
                tenantContext.getCurrentCompanyId()
        );
    }

    public Optional<Certificate> findById(String id) {
        List<Certificate> matches = jdbcTemplate.query("""
                SELECT id, company_id, alias, certificate_type,
                       subject_name, subject_tax_identifier,
                       encrypted_password, storage_path, certificate_data,
                       valid_from, valid_to, active, created_at, updated_at
                  FROM digital_certificates
                 WHERE id = ?
                   AND company_id = ?
                """,
                this::mapDecrypted,
                id,
                tenantContext.getCurrentCompanyId()
        );
        return matches.stream().findFirst();
    }

    /**
     * Listado para la empresa actual. Devuelve los campos sensibles ya
     * descifrados; el Service decide cuales exponer al cliente.
     */
    public List<Certificate> findAllActive() {
        return jdbcTemplate.query("""
                SELECT id, company_id, alias, certificate_type,
                       subject_name, subject_tax_identifier,
                       encrypted_password, storage_path, certificate_data,
                       valid_from, valid_to, active, created_at, updated_at
                  FROM digital_certificates
                 WHERE company_id = ?
                   AND active = TRUE
                 ORDER BY alias
                """,
                this::mapDecrypted,
                tenantContext.getCurrentCompanyId()
        );
    }

    private Certificate mapDecrypted(ResultSet rs, int rowNum) throws SQLException {
        Timestamp validFrom = rs.getTimestamp("valid_from");
        Timestamp validTo = rs.getTimestamp("valid_to");
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new Certificate(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("alias"),
                rs.getString("certificate_type"),
                rs.getString("subject_name"),
                rs.getString("subject_tax_identifier"),
                decryptIfPresent(rs.getString("encrypted_password")),
                rs.getString("storage_path"),
                decryptIfPresent(rs.getString("certificate_data")),
                validFrom == null ? null : validFrom.toInstant(),
                validTo == null ? null : validTo.toInstant(),
                rs.getBoolean("active"),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }

    private String encryptIfPresent(String value) {
        return value == null ? null : encryptor.encrypt(value);
    }

    private String decryptIfPresent(String value) {
        return value == null ? null : encryptor.decrypt(value);
    }
}
