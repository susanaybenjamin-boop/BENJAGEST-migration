package com.benjagest.backend.auth;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Denylist de refresh tokens. Solo dos operaciones:
 *   - revoke: anade un jti (con su user_id) a la denylist.
 *   - isRevoked: comprueba si un jti esta presente.
 *
 * Las inserciones duplicadas son IDEMPOTENTES: si el cliente llama
 * logout dos veces seguidas con el mismo refresh, la segunda no
 * revienta.
 */
@Repository
public class RevokedRefreshTokenRepository {

    private final JdbcTemplate jdbcTemplate;

    public RevokedRefreshTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void revoke(String jti, String userId) {
        try {
            jdbcTemplate.update("""
                    INSERT INTO revoked_refresh_tokens (jti, user_id)
                    VALUES (?, ?)
                    """,
                    jti,
                    userId
            );
        } catch (DuplicateKeyException ignored) {
            // Logout idempotente: el jti ya estaba revocado, OK.
        }
    }

    public boolean isRevoked(String jti) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM revoked_refresh_tokens WHERE jti = ?",
                Integer.class,
                jti
        );
        return count != null && count > 0;
    }
}
