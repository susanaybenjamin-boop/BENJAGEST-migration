package com.benjagest.backend.advisory.collab;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * L4-6 — Repositorio de {@link AdvisoryCollaboration}. No usa
 * TenantContext directamente; filtra por columnas según el caller.
 */
@Repository
public class AdvisoryCollaborationRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdvisoryCollaborationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AdvisoryCollaboration> findById(String id) {
        List<AdvisoryCollaboration> rows = jdbcTemplate.query("""
                SELECT id, advisory_company_id, partner_advisory_id,
                       invited_email, status, invited_at, invited_by_user_id,
                       accepted_at, accepted_by_user_id,
                       revoked_at, revoked_by_user_id, notes,
                       created_at, updated_at
                  FROM advisory_collaborations
                 WHERE id = ?
                """, MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /** Invitaciones que la asesoría {@code advisoryId} envió (saliente). */
    public List<AdvisoryCollaboration> listOutgoing(String advisoryId) {
        return jdbcTemplate.query("""
                SELECT id, advisory_company_id, partner_advisory_id,
                       invited_email, status, invited_at, invited_by_user_id,
                       accepted_at, accepted_by_user_id,
                       revoked_at, revoked_by_user_id, notes,
                       created_at, updated_at
                  FROM advisory_collaborations
                 WHERE advisory_company_id = ?
                 ORDER BY invited_at DESC
                """, MAPPER, advisoryId);
    }

    /** Invitaciones que llegan a un email (entrantes que el destinatario ve). */
    public List<AdvisoryCollaboration> listIncoming(String email) {
        return jdbcTemplate.query("""
                SELECT id, advisory_company_id, partner_advisory_id,
                       invited_email, status, invited_at, invited_by_user_id,
                       accepted_at, accepted_by_user_id,
                       revoked_at, revoked_by_user_id, notes,
                       created_at, updated_at
                  FROM advisory_collaborations
                 WHERE LOWER(invited_email) = LOWER(?)
                   AND status = 'PENDING'
                 ORDER BY invited_at DESC
                """, MAPPER, email);
    }

    /** Colaboraciones aceptadas y vigentes para la asesoría anfitriona. */
    public List<AdvisoryCollaboration> listActivePartners(String advisoryId) {
        return jdbcTemplate.query("""
                SELECT id, advisory_company_id, partner_advisory_id,
                       invited_email, status, invited_at, invited_by_user_id,
                       accepted_at, accepted_by_user_id,
                       revoked_at, revoked_by_user_id, notes,
                       created_at, updated_at
                  FROM advisory_collaborations
                 WHERE advisory_company_id = ?
                   AND status = 'ACCEPTED'
                 ORDER BY accepted_at DESC
                """, MAPPER, advisoryId);
    }

    /**
     * Comprueba si ya existe una colaboración PENDING o ACCEPTED entre
     * la asesoría anfitriona y un email (para evitar duplicar invitaciones).
     */
    public boolean existsActiveByEmail(String advisoryId, String email) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM advisory_collaborations
                 WHERE advisory_company_id = ?
                   AND LOWER(invited_email) = LOWER(?)
                   AND status IN ('PENDING', 'ACCEPTED')
                """, Integer.class, advisoryId, email);
        return n != null && n > 0;
    }

    public void insert(AdvisoryCollaboration c) {
        jdbcTemplate.update("""
                INSERT INTO advisory_collaborations
                       (id, advisory_company_id, partner_advisory_id,
                        invited_email, status, invited_at, invited_by_user_id,
                        accepted_at, accepted_by_user_id,
                        revoked_at, revoked_by_user_id, notes,
                        created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, NOW(), ?, NULL, NULL, NULL, NULL, ?, NOW(), NOW())
                """,
                c.id(), c.advisoryCompanyId(), c.partnerAdvisoryId(),
                c.invitedEmail(), c.status(), c.invitedByUserId(), c.notes());
    }

    public void accept(String id, String partnerAdvisoryId, String acceptedByUserId) {
        jdbcTemplate.update("""
                UPDATE advisory_collaborations
                   SET status = 'ACCEPTED',
                       partner_advisory_id = ?,
                       accepted_at = NOW(),
                       accepted_by_user_id = ?,
                       updated_at = NOW()
                 WHERE id = ?
                """, partnerAdvisoryId, acceptedByUserId, id);
    }

    public void reject(String id, String byUserId) {
        jdbcTemplate.update("""
                UPDATE advisory_collaborations
                   SET status = 'REJECTED',
                       accepted_by_user_id = ?,
                       updated_at = NOW()
                 WHERE id = ? AND status = 'PENDING'
                """, byUserId, id);
    }

    public void revoke(String id, String byUserId) {
        jdbcTemplate.update("""
                UPDATE advisory_collaborations
                   SET status = 'REVOKED',
                       revoked_at = NOW(),
                       revoked_by_user_id = ?,
                       updated_at = NOW()
                 WHERE id = ?
                """, byUserId, id);
    }

    private static final RowMapper<AdvisoryCollaboration> MAPPER = (rs, i) -> new AdvisoryCollaboration(
            rs.getString("id"),
            rs.getString("advisory_company_id"),
            rs.getString("partner_advisory_id"),
            rs.getString("invited_email"),
            rs.getString("status"),
            toInstant(rs.getTimestamp("invited_at")),
            rs.getString("invited_by_user_id"),
            toInstant(rs.getTimestamp("accepted_at")),
            rs.getString("accepted_by_user_id"),
            toInstant(rs.getTimestamp("revoked_at")),
            rs.getString("revoked_by_user_id"),
            rs.getString("notes"),
            toInstant(rs.getTimestamp("created_at")),
            toInstant(rs.getTimestamp("updated_at"))
    );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
