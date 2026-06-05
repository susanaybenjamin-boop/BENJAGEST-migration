package com.benjagest.backend.advisory;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a {@code advisory_invitations}.
 *
 * Notable: los métodos que consulta el empresario (listPending,
 * findByToken para aceptar) NO filtran por TenantContext — el
 * empresario está en su propio tenant y la invitación apunta a su
 * NIF/email, no a su company_id. El service hace la validación
 * cruzada.
 */
@Repository
public class AdvisoryInvitationRepository {

    private final JdbcTemplate jdbcTemplate;

    public AdvisoryInvitationRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(AdvisoryInvitation inv) {
        jdbcTemplate.update("""
                INSERT INTO advisory_invitations (
                    id, advisory_company_id,
                    invited_email, invited_nif, invited_company_name,
                    invited_company_id, token, status, expires_at, notes,
                    created_by_user_id
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                inv.id(),
                inv.advisoryCompanyId(),
                inv.invitedEmail(),
                inv.invitedNif(),
                inv.invitedCompanyName(),
                inv.invitedCompanyId(),
                inv.token(),
                inv.status() == null ? AdvisoryInvitation.STATUS_PENDING : inv.status(),
                Timestamp.from(inv.expiresAt()),
                inv.notes(),
                inv.createdByUserId()
        );
    }

    public Optional<AdvisoryInvitation> findById(String id) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectBase() + " WHERE id = ?",
                    this::map, id));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    public Optional<AdvisoryInvitation> findByToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    selectBase() + " WHERE token = ?",
                    this::map, token));
        } catch (EmptyResultDataAccessException ex) {
            return Optional.empty();
        }
    }

    /** Listado de las invitaciones que la asesoría ha emitido. */
    public List<AdvisoryInvitation> listForAdvisory(String advisoryCompanyId) {
        return jdbcTemplate.query(
                selectBase()
                        + " WHERE advisory_company_id = ?"
                        + " ORDER BY created_at DESC",
                this::map, advisoryCompanyId);
    }

    /**
     * Invitaciones PENDING (y no expiradas) dirigidas a un empresario
     * concreto, identificado por su NIF de empresa y email de usuario.
     * Coincide cualquiera de las dos coordenadas.
     */
    public List<AdvisoryInvitation> listPending(String invitedNif, String invitedEmail) {
        return jdbcTemplate.query(
                selectBase()
                        + " WHERE status = 'PENDING'"
                        + "   AND expires_at > CURRENT_TIMESTAMP"
                        + "   AND ((invited_nif IS NOT NULL AND invited_nif = ?)"
                        + "     OR (invited_email IS NOT NULL AND invited_email = ?))"
                        + " ORDER BY created_at DESC",
                this::map,
                invitedNif == null ? "" : invitedNif.toUpperCase(),
                invitedEmail == null ? "" : invitedEmail.toLowerCase());
    }

    public int updateStatusAccepted(String id, String acceptedByUserId,
                                      String invitedCompanyId) {
        return jdbcTemplate.update("""
                UPDATE advisory_invitations
                   SET status = 'ACCEPTED',
                       accepted_at = CURRENT_TIMESTAMP,
                       accepted_by_user_id = ?,
                       invited_company_id = ?
                 WHERE id = ?
                """, acceptedByUserId, invitedCompanyId, id);
    }

    public int updateStatus(String id, String newStatus) {
        return jdbcTemplate.update("""
                UPDATE advisory_invitations
                   SET status = ?
                 WHERE id = ?
                """, newStatus, id);
    }

    /**
     * Marca una invitación como UNLINKED y sella el {@code unlinked_at}.
     * Se llama cuando el empresario rompe el vínculo con su asesoría.
     */
    public int updateStatusUnlinked(String id) {
        return jdbcTemplate.update("""
                UPDATE advisory_invitations
                   SET status = 'UNLINKED',
                       unlinked_at = CURRENT_TIMESTAMP
                 WHERE id = ?
                """, id);
    }

    /**
     * Devuelve la última invitación ACCEPTED para el par
     * (asesoría, empresa cliente). Pensado para localizar qué
     * invitación tiene que pasar a UNLINKED cuando se desvincula.
     */
    public Optional<AdvisoryInvitation> findLatestAcceptedByPair(
            String advisoryCompanyId, String invitedCompanyId) {
        return jdbcTemplate.query(
                selectBase()
                        + " WHERE advisory_company_id = ?"
                        + "   AND invited_company_id = ?"
                        + "   AND status = 'ACCEPTED'"
                        + " ORDER BY accepted_at DESC"
                        + " LIMIT 1",
                this::map, advisoryCompanyId, invitedCompanyId)
                .stream().findFirst();
    }

    private String selectBase() {
        return """
                SELECT id, advisory_company_id, invited_email, invited_nif,
                       invited_company_name, invited_company_id, token, status,
                       expires_at, notes, created_by_user_id, accepted_by_user_id,
                       accepted_at, created_at, updated_at
                  FROM advisory_invitations
                """;
    }

    private AdvisoryInvitation map(ResultSet rs, int n) throws SQLException {
        Timestamp expiresAt = rs.getTimestamp("expires_at");
        Timestamp acceptedAt = rs.getTimestamp("accepted_at");
        Timestamp createdAt = rs.getTimestamp("created_at");
        Timestamp updatedAt = rs.getTimestamp("updated_at");
        return new AdvisoryInvitation(
                rs.getString("id"),
                rs.getString("advisory_company_id"),
                rs.getString("invited_email"),
                rs.getString("invited_nif"),
                rs.getString("invited_company_name"),
                rs.getString("invited_company_id"),
                rs.getString("token"),
                rs.getString("status"),
                expiresAt == null ? null : expiresAt.toInstant(),
                rs.getString("notes"),
                rs.getString("created_by_user_id"),
                rs.getString("accepted_by_user_id"),
                acceptedAt == null ? null : acceptedAt.toInstant(),
                createdAt == null ? null : createdAt.toInstant(),
                updatedAt == null ? null : updatedAt.toInstant()
        );
    }
}
