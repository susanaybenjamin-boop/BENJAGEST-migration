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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Log de uso de certificados digitales (.p12). Por LOPD y por
 * trazabilidad interna: cada vez que se usa un certificado para
 * firmar/enviar algo, se registra aqui — quien, cuando, para que,
 * desde donde, exito/fallo.
 *
 * Hooks para escritura: en cualquier servicio que use un cert
 * (XmlSignerService, AeatVerifactuClient, futuros DEHu/SS), llamar
 * a recordUsage tras la operacion. Aqui solo dejamos la API de
 * lectura — la integracion de los hooks viene en sub-slices.
 */
@Service
public class CertificateUsageLogService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public CertificateUsageLogService(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    @Transactional
    public void recordUsage(String certificateId, String userId,
                             String purpose, String targetUrl,
                             boolean success, String errorMessage,
                             String ipAddress) {
        jdbcTemplate.update("""
                INSERT INTO certificate_usage_log (
                    id, company_id, certificate_id, user_id,
                    purpose, target_url, success, error_message, ip_address
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(),
                tenantContext.getCurrentCompanyId(),
                certificateId,
                userId,
                purpose,
                targetUrl,
                success,
                errorMessage,
                ipAddress
        );
    }

    public List<UsageEntry> list(String certificateId, int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, certificate_id, user_id, used_at,
                       purpose, target_url, success, error_message, ip_address
                  FROM certificate_usage_log
                 WHERE company_id = ?
                """);
        List<Object> args = new java.util.ArrayList<>();
        args.add(tenantContext.getCurrentCompanyId());
        if (certificateId != null && !certificateId.isBlank()) {
            sql.append(" AND certificate_id = ?");
            args.add(certificateId);
        }
        sql.append(" ORDER BY used_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));
        return jdbcTemplate.query(sql.toString(), this::mapEntry, args.toArray());
    }

    private UsageEntry mapEntry(ResultSet rs, int rowNum) throws SQLException {
        Timestamp t = rs.getTimestamp("used_at");
        return new UsageEntry(
                rs.getString("id"),
                rs.getString("certificate_id"),
                rs.getString("user_id"),
                t == null ? null : t.toInstant(),
                rs.getString("purpose"),
                rs.getString("target_url"),
                rs.getBoolean("success"),
                rs.getString("error_message"),
                rs.getString("ip_address")
        );
    }

    public record UsageEntry(
            String id, String certificateId, String userId, Instant usedAt,
            String purpose, String targetUrl, boolean success,
            String errorMessage, String ipAddress
    ) {}

    @RestController
    @RequestMapping("/api/credentials/cert-usage-log")
    @RequiresModule("documents")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class CertUsageLogController {
        private final CertificateUsageLogService service;

        public CertUsageLogController(CertificateUsageLogService service) {
            this.service = service;
        }

        @GetMapping
        public List<UsageEntry> list(
                @RequestParam(value = "certificateId", required = false) String certificateId,
                @RequestParam(value = "limit", defaultValue = "100") int limit) {
            return service.list(certificateId, limit);
        }
    }
}
