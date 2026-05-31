package com.benjagest.backend.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import javax.crypto.SecretKey;
import org.springframework.stereotype.Service;

/**
 * Crea y valida JWTs HMAC-SHA256. Dos tipos de token:
 *
 *   - access  (8h por defecto): lleva todos los claims del usuario y
 *               la empresa activa. Es el que viaja en el header
 *               Authorization: Bearer ... de cada llamada al API.
 *   - refresh (30 dias): solo lleva el userId y un flag "type=refresh".
 *               Sirve para pedir un access nuevo cuando el anterior
 *               caduca, sin tener que re-loguearse con password.
 *
 * El secret debe tener al menos 32 bytes (256 bits) para HMAC-SHA256.
 * El que esta hardcoded en application.yml es para desarrollo; en
 * produccion se sobrescribe via env var.
 */
@Service
public class JwtService {

    public static final String CLAIM_EMAIL = "email";
    public static final String CLAIM_DISPLAY_NAME = "name";
    public static final String CLAIM_GLOBAL_ROLE = "globalRole";
    public static final String CLAIM_ACTIVE_COMPANY = "activeCompanyId";
    public static final String CLAIM_ROLE_IN_COMPANY = "roleInCompany";
    public static final String CLAIM_TYPE = "type";
    public static final String TYPE_ACCESS = "access";
    public static final String TYPE_REFRESH = "refresh";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        byte[] secretBytes = properties.secret().getBytes(StandardCharsets.UTF_8);
        if (secretBytes.length < 32) {
            throw new IllegalStateException(
                    "benjagest.jwt.secret debe tener al menos 32 caracteres para HMAC-SHA256"
            );
        }
        this.key = Keys.hmacShaKeyFor(secretBytes);
    }

    public String createAccessToken(AuthenticatedUser user) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.accessTtlMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(user.userId())
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_TYPE, TYPE_ACCESS)
                .claim(CLAIM_EMAIL, user.email())
                .claim(CLAIM_DISPLAY_NAME, user.displayName())
                .claim(CLAIM_GLOBAL_ROLE, user.globalRole())
                .claim(CLAIM_ACTIVE_COMPANY, user.activeCompanyId())
                .claim(CLAIM_ROLE_IN_COMPANY, user.roleInActiveCompany())
                .signWith(key)
                .compact();
    }

    public String createRefreshToken(String userId) {
        Instant now = Instant.now();
        Instant exp = now.plus(properties.refreshTtlMinutes(), ChronoUnit.MINUTES);
        return Jwts.builder()
                .subject(userId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(exp))
                .claim(CLAIM_TYPE, TYPE_REFRESH)
                .signWith(key)
                .compact();
    }

    public Claims parseAndValidate(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    public boolean isAccessToken(Claims claims) {
        return TYPE_ACCESS.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public boolean isRefreshToken(Claims claims) {
        return TYPE_REFRESH.equals(claims.get(CLAIM_TYPE, String.class));
    }

    public long accessTtlSeconds() {
        return properties.accessTtlMinutes() * 60L;
    }

    public long refreshTtlSeconds() {
        return properties.refreshTtlMinutes() * 60L;
    }
}
