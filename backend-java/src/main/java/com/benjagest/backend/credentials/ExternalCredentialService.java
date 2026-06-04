package com.benjagest.backend.credentials;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Credenciales externas cifradas (DEHu, SS RED, SILTRA, AEAT Cl@ve...).
 *
 * Las contrasenas se cifran con Jasypt antes de persistir y se
 * descifran solo al usar. Las RESPUESTAS del endpoint NUNCA incluyen
 * la password en claro — solo un boolean "passwordConfigured".
 */
@Service
public class ExternalCredentialService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final StringEncryptor encryptor;

    public ExternalCredentialService(JdbcTemplate jdbcTemplate,
                                      TenantContext tenantContext,
                                      StringEncryptor encryptor) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.encryptor = encryptor;
    }

    public List<ExternalCredentialView> list() {
        return jdbcTemplate.query("""
                SELECT id, system_code, label, username, encrypted_password,
                       auth_url, notes, active, last_used_at, created_at, updated_at
                  FROM external_credentials
                 WHERE company_id = ?
                 ORDER BY active DESC, system_code
                """,
                this::mapView,
                tenantContext.getCurrentCompanyId()
        );
    }

    @Transactional
    public ExternalCredentialView create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        String enc = StringUtils.hasText(req.password()) ? encryptor.encrypt(req.password()) : null;
        jdbcTemplate.update("""
                INSERT INTO external_credentials (
                    id, company_id, system_code, label, username,
                    encrypted_password, auth_url, notes, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.systemCode(), req.label(), blankToNull(req.username()),
                enc, blankToNull(req.authUrl()), blankToNull(req.notes()),
                req.active() == null || req.active()
        );
        return findById(id);
    }

    @Transactional
    public ExternalCredentialView update(String id, UpsertRequest req) {
        validate(req);
        ExternalCredentialView existing = findById(id);
        // Si llega password nueva, la ciframos. Si no, dejamos la que
        // ya habia (no enviar password en el PUT no debe borrarla).
        String enc = StringUtils.hasText(req.password())
                ? encryptor.encrypt(req.password())
                : (existing.passwordConfigured() ? "__UNCHANGED__" : null);
        int n;
        if ("__UNCHANGED__".equals(enc)) {
            n = jdbcTemplate.update("""
                    UPDATE external_credentials
                       SET label = ?, username = ?, auth_url = ?, notes = ?, active = ?
                     WHERE id = ? AND company_id = ?
                    """,
                    req.label(), blankToNull(req.username()),
                    blankToNull(req.authUrl()), blankToNull(req.notes()),
                    req.active() == null || req.active(),
                    id, tenantContext.getCurrentCompanyId());
        } else {
            n = jdbcTemplate.update("""
                    UPDATE external_credentials
                       SET label = ?, username = ?, encrypted_password = ?,
                           auth_url = ?, notes = ?, active = ?
                     WHERE id = ? AND company_id = ?
                    """,
                    req.label(), blankToNull(req.username()), enc,
                    blankToNull(req.authUrl()), blankToNull(req.notes()),
                    req.active() == null || req.active(),
                    id, tenantContext.getCurrentCompanyId());
        }
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Credencial no encontrada");
        }
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                DELETE FROM external_credentials
                 WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Credencial no encontrada");
        }
    }

    private ExternalCredentialView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, system_code, label, username, encrypted_password,
                       auth_url, notes, active, last_used_at, created_at, updated_at
                  FROM external_credentials
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Credencial no encontrada"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.systemCode())
                || !List.of("DEHU", "SS_RED", "SILTRA", "AEAT_CLAVE",
                        "NOTIFICA_GOB", "SEDE_AEAT", "BANCO_ESPANA", "OTHER")
                        .contains(req.systemCode())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "systemCode invalido");
        }
        if (!StringUtils.hasText(req.label())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "label requerido");
        }
    }

    private String blankToNull(String v) {
        return StringUtils.hasText(v) ? v.trim() : null;
    }

    private ExternalCredentialView mapView(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lu = rs.getTimestamp("last_used_at");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new ExternalCredentialView(
                rs.getString("id"),
                rs.getString("system_code"),
                rs.getString("label"),
                rs.getString("username"),
                StringUtils.hasText(rs.getString("encrypted_password")),
                rs.getString("auth_url"),
                rs.getString("notes"),
                rs.getBoolean("active"),
                lu == null ? null : lu.toInstant(),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    public record ExternalCredentialView(
            String id, String systemCode, String label, String username,
            boolean passwordConfigured, String authUrl, String notes,
            boolean active, Instant lastUsedAt,
            Instant createdAt, Instant updatedAt
    ) {}

    public record UpsertRequest(
            String systemCode, String label, String username, String password,
            String authUrl, String notes, Boolean active
    ) {}

    @RestController
    @RequestMapping("/api/credentials/external")
    @RequiresModule("documents")
    @RequiresRole({"OWNER", "ADMIN"})
    public static class ExternalCredentialController {
        private final ExternalCredentialService service;

        public ExternalCredentialController(ExternalCredentialService service) {
            this.service = service;
        }

        @GetMapping
        public List<ExternalCredentialView> list() { return service.list(); }

        @PostMapping
        public ExternalCredentialView create(@RequestBody UpsertRequest req) {
            return service.create(req);
        }

        @PutMapping("/{id}")
        public ExternalCredentialView update(@PathVariable("id") String id,
                                              @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
