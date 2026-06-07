package com.benjagest.backend.auth.pin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * L4-1 — Repositorio de {@link DeviceToken}.
 *
 * <p>No usa tenant context: la auth y el emparejado son PREVIOS a saber
 * en qué empresa estoy. El service los filtra por company_id según el
 * caso.
 */
@Repository
public class DeviceTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public DeviceTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<DeviceToken> findById(String id) {
        List<DeviceToken> rows = jdbcTemplate.query("""
                SELECT id, company_id, token_hash, token_prefix, name,
                       paired_at, paired_by_user_id, last_seen_at,
                       revoked_at, revoked_by_user_id
                  FROM device_tokens
                 WHERE id = ?
                """, MAPPER, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    /**
     * Tokens activos (no revocados) de una asesoría. Para el límite de
     * 5 y para la pantalla "Mis equipos".
     */
    public List<DeviceToken> listActiveByCompany(String companyId) {
        return jdbcTemplate.query("""
                SELECT id, company_id, token_hash, token_prefix, name,
                       paired_at, paired_by_user_id, last_seen_at,
                       revoked_at, revoked_by_user_id
                  FROM device_tokens
                 WHERE company_id = ?
                   AND revoked_at IS NULL
                 ORDER BY paired_at DESC
                """, MAPPER, companyId);
    }

    /**
     * Candidatos de token a verificar al recibir el secret en plano.
     * Filtramos por prefijo (los primeros 8 chars) para reducir las
     * candidaturas a verificar con bcrypt — sin necesidad de iterar
     * sobre TODOS los tokens del sistema.
     */
    public List<DeviceToken> findActiveByPrefix(String tokenPrefix) {
        return jdbcTemplate.query("""
                SELECT id, company_id, token_hash, token_prefix, name,
                       paired_at, paired_by_user_id, last_seen_at,
                       revoked_at, revoked_by_user_id
                  FROM device_tokens
                 WHERE token_prefix = ?
                   AND revoked_at IS NULL
                """, MAPPER, tokenPrefix);
    }

    public int countActiveByCompany(String companyId) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM device_tokens
                 WHERE company_id = ? AND revoked_at IS NULL
                """, Integer.class, companyId);
        return n == null ? 0 : n;
    }

    public void insert(DeviceToken t) {
        jdbcTemplate.update("""
                INSERT INTO device_tokens
                       (id, company_id, token_hash, token_prefix, name,
                        paired_at, paired_by_user_id, last_seen_at,
                        revoked_at, revoked_by_user_id)
                VALUES (?, ?, ?, ?, ?, NOW(), ?, NULL, NULL, NULL)
                """,
                t.id(), t.companyId(), t.tokenHash(), t.tokenPrefix(),
                t.name(), t.pairedByUserId());
    }

    public void touchLastSeen(String id) {
        jdbcTemplate.update("""
                UPDATE device_tokens SET last_seen_at = NOW() WHERE id = ?
                """, id);
    }

    public void revoke(String id, String revokedByUserId) {
        jdbcTemplate.update("""
                UPDATE device_tokens
                   SET revoked_at = NOW(),
                       revoked_by_user_id = ?
                 WHERE id = ? AND revoked_at IS NULL
                """, revokedByUserId, id);
    }

    private static final RowMapper<DeviceToken> MAPPER = (rs, i) -> new DeviceToken(
            rs.getString("id"),
            rs.getString("company_id"),
            rs.getString("token_hash"),
            rs.getString("token_prefix"),
            rs.getString("name"),
            toInstant(rs.getTimestamp("paired_at")),
            rs.getString("paired_by_user_id"),
            toInstant(rs.getTimestamp("last_seen_at")),
            toInstant(rs.getTimestamp("revoked_at")),
            rs.getString("revoked_by_user_id")
    );

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
