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
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

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
    private final AuditChainService chainService;

    public AuditEventRepository(JdbcTemplate jdbcTemplate, AuditChainService chainService) {
        this.jdbcTemplate = jdbcTemplate;
        this.chainService = chainService;
    }

    /**
     * Inserta un evento de auditoría en su propia transacción
     * ({@code REQUIRES_NEW}). De esta forma:
     *
     * <ul>
     *   <li>El {@code FOR UPDATE} del cálculo de hash bloquea la
     *       cadena de la empresa hasta que el INSERT haga commit.</li>
     *   <li>Un fallo aquí (p. ej. duplicado de sequence_number)
     *       hace rollback solo del audit, no de la operación
     *       principal del usuario.</li>
     *   <li>Otra request escribiendo en la misma cadena espera al
     *       commit antes de leer el último hash.</li>
     * </ul>
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void insert(AuditEvent event) {
        // AUDIT-CHAIN: el bloque (seq, prev, hash) se calcula con FOR
        // UPDATE sobre la cadena de esta empresa. Si el bloque viene
        // null (companyId vacío o fallo del chain service) seguimos
        // insertando sin hash — eventos pre-auth (LOGIN_FAIL) no
        // tienen empresa y no pueden formar cadena.
        AuditChainService.Block block = null;
        if (event.companyId() != null && !event.companyId().isBlank()) {
            try {
                block = chainService.computeNext(event.companyId(), event.userId(),
                        event.eventType(), event.entityType(), event.entityId(),
                        event.result() != null ? event.result() : "OK",
                        event.details(), Instant.now());
            } catch (Exception ignored) {
                // No bloqueamos la operación principal por un fallo de cadena.
            }
        }
        jdbcTemplate.update("""
                INSERT INTO audit_events (
                    id, company_id, user_id, event_type, entity_type, entity_id,
                    result, ip_address, user_agent, details,
                    sequence_number, prev_event_hash, event_hash
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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
                event.details(),
                block == null ? null : block.sequence(),
                block == null ? null : block.prevHash(),
                block == null ? null : block.hash()
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
                       result, ip_address, user_agent, details, created_at,
                       sequence_number, prev_event_hash, event_hash
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
                       result, ip_address, user_agent, details, created_at,
                       sequence_number, prev_event_hash, event_hash
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
        long seq = rs.getLong("sequence_number");
        boolean seqNull = rs.wasNull();
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
                createdAt == null ? null : createdAt.toInstant(),
                seqNull ? null : seq,
                rs.getString("prev_event_hash"),
                rs.getString("event_hash")
        );
    }
}
