package com.benjagest.backend.audit;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso unico a la tabla `audit_events`. Insert + query con filtros.
 *
 * - No filtra por tenant en el INSERT: quien llama (AuditService)
 *   decide el companyId. Esto es importante porque LOGIN_FAIL ocurre
 *   antes de saber a que empresa pertenece el usuario.
 * - En las consultas SI filtra por companyId: nunca devuelve eventos
 *   de otras empresas. Para auditoria global (LOGIN_FAIL pre-auth) hay
 *   una consulta separada que solo puede invocar ADMIN global (no
 *   implementada todavia).
 */
@Repository
public class AuditEventRepository {

    private final JdbcTemplate jdbcTemplate;

    public AuditEventRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AuditEvent event) {
        jdbcTemplate.update("""
                INSERT INTO audit_events (
                    id, company_id, user_id, event_type, entity_type, entity_id,
                    result, ip_address, user_agent, details
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                event.id() != null ? event.id() : UUID.randomUUID().toString(),
                event.companyId(),
                event.userId(),
                event.eventType(),
                event.entityType(),
                event.entityId(),
                event.result() != null ? event.result() : "OK",
                event.ipAddress(),
                event.userAgent(),
                event.details()
        );
    }

    /**
     * Listado paginado de eventos de la empresa. Filtros opcionales:
     *   - eventType: si != null, solo eventos de ese tipo
     *   - since: si != null, solo eventos creados a partir de esa fecha
     *   - limit: nunca mas de 500 filas (defensa contra dump masivo)
     */
    public List<AuditEvent> findForCompany(String companyId,
                                           String eventType,
                                           Instant since,
                                           int limit) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, user_id, event_type, entity_type, entity_id,
                       result, ip_address, user_agent, details, created_at
                  FROM audit_events
                 WHERE company_id = ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(companyId);

        if (eventType != null && !eventType.isBlank()) {
            sql.append("   AND event_type = ?\n");
            args.add(eventType.trim());
        }
        if (since != null) {
            sql.append("   AND created_at >= ?\n");
            args.add(Timestamp.from(since));
        }
        sql.append(" ORDER BY created_at DESC LIMIT ?");
        args.add(Math.min(Math.max(limit, 1), 500));

        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    /**
     * Listado completo (sin limit típico) de eventos en un rango.
     * Pensado para el export de auditoría a PDF/CSV: el documento es
     * un retrato del periodo, no hay paginación. Tope defensivo de
     * 50000 filas para que un rango absurdo no agote memoria — quien
     * necesite más lo hace en bloques.
     */
    public List<AuditEvent> findInRangeForCompany(String companyId,
                                                    Instant from, Instant to,
                                                    String eventTypePrefix) {
        StringBuilder sql = new StringBuilder("""
                SELECT id, company_id, user_id, event_type, entity_type, entity_id,
                       result, ip_address, user_agent, details, created_at
                  FROM audit_events
                 WHERE company_id = ?
                   AND created_at >= ?
                   AND created_at <= ?
                """);
        List<Object> args = new ArrayList<>();
        args.add(companyId);
        args.add(Timestamp.from(from));
        args.add(Timestamp.from(to));
        if (eventTypePrefix != null && !eventTypePrefix.isBlank()) {
            sql.append("   AND event_type LIKE ?\n");
            args.add(eventTypePrefix.trim() + "%");
        }
        sql.append(" ORDER BY created_at ASC LIMIT 50000");
        return jdbcTemplate.query(sql.toString(), this::mapEvent, args.toArray());
    }

    private AuditEvent mapEvent(ResultSet rs, int rowNum) throws SQLException {
        Timestamp createdAt = rs.getTimestamp("created_at");
        return new AuditEvent(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("user_id"),
                rs.getString("event_type"),
                rs.getString("entity_type"),
                rs.getString("entity_id"),
                rs.getString("result"),
                rs.getString("ip_address"),
                rs.getString("user_agent"),
                rs.getString("details"),
                createdAt == null ? null : createdAt.toInstant()
        );
    }
}
