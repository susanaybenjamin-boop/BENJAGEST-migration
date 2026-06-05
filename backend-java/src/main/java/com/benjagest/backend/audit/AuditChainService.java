package com.benjagest.backend.audit;

import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mantiene el hash encadenado de {@code audit_events} para cada empresa.
 *
 * <p>Cadena por empresa (decisión Benjamin 2026-06-05): cada insert lee
 * el último {@code sequence_number + event_hash} de la misma empresa con
 * un {@code FOR UPDATE} que solo bloquea ESA cadena (no toda la tabla),
 * calcula el siguiente hash y lo devuelve al repository para que lo
 * incluya en el INSERT. Mismo patrón que VerifactuHashService y
 * SifEventHashService.
 *
 * <p>Algoritmo: {@code SHA-256(prev_hash | seq | company | user | type |
 * entityType | entityId | result | details | createdAtIso)}.
 */
@Service
public class AuditChainService {

    private static final DateTimeFormatter ISO_SECONDS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss").withZone(ZoneOffset.UTC);

    private final JdbcTemplate jdbcTemplate;

    public AuditChainService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Calcula el bloque (seq, prevHash, hash) para un evento nuevo.
     * Lee la cadena con {@code FOR UPDATE} para serializar inserts
     * concurrentes en la misma empresa. Sin {@code @Transactional}
     * propio: hereda la transacción del caller — AuditEventRepository
     * .insert tiene {@code REQUIRES_NEW}, así FOR UPDATE bloquea hasta
     * que se hace commit del INSERT.
     */
    public Block computeNext(String companyId, String userId, String eventType,
                              String entityType, String entityId, String result,
                              String details, Instant createdAt) {
        Block last = companyId == null ? null : findLast(companyId);
        long seq = (last == null ? 0L : last.sequence) + 1L;
        String prev = last == null ? null : last.hash;
        String payload = String.join("|",
                nullSafe(prev), Long.toString(seq), nullSafe(companyId),
                nullSafe(userId), nullSafe(eventType), nullSafe(entityType),
                nullSafe(entityId), nullSafe(result), nullSafe(details),
                ISO_SECONDS.format(createdAt == null ? Instant.now() : createdAt));
        String hash = sha256(payload);
        return new Block(seq, prev, hash);
    }

    /**
     * Verifica la cadena entera de una empresa. Recorre por
     * sequence_number ascendente y recalcula. Devuelve OK o el id del
     * primer evento corrupto. Sirve al endpoint GET .../verify.
     */
    public VerificationResult verifyForCompany(String companyId) {
        if (companyId == null) {
            return new VerificationResult(true, null, 0L, "Sin empresa");
        }
        return jdbcTemplate.query("""
                SELECT id, sequence_number, prev_event_hash, event_hash,
                       IFNULL(user_id, '') AS user_id,
                       IFNULL(event_type, '') AS event_type,
                       IFNULL(entity_type, '') AS entity_type,
                       IFNULL(entity_id, '') AS entity_id,
                       IFNULL(result, '') AS result,
                       IFNULL(details, '') AS details,
                       created_at
                  FROM audit_events
                 WHERE company_id = ?
                   AND sequence_number IS NOT NULL
                 ORDER BY sequence_number ASC
                """, rs -> {
                    String prev = null;
                    long count = 0;
                    while (rs.next()) {
                        count++;
                        long seq = rs.getLong("sequence_number");
                        String storedPrev = rs.getString("prev_event_hash");
                        String storedHash = rs.getString("event_hash");
                        if (storedHash == null) {
                            return new VerificationResult(false, rs.getString("id"),
                                    count, "Evento sin hash");
                        }
                        if ((prev == null && storedPrev != null)
                                || (prev != null && !prev.equals(storedPrev))) {
                            return new VerificationResult(false, rs.getString("id"),
                                    count, "prev_event_hash no coincide");
                        }
                        String payload = String.join("|",
                                nullSafe(prev), Long.toString(seq), companyId,
                                rs.getString("user_id"), rs.getString("event_type"),
                                rs.getString("entity_type"), rs.getString("entity_id"),
                                rs.getString("result"), rs.getString("details"),
                                ISO_SECONDS.format(rs.getTimestamp("created_at").toInstant()));
                        String recalculated = sha256(payload);
                        if (!recalculated.equals(storedHash)) {
                            return new VerificationResult(false, rs.getString("id"),
                                    count, "event_hash recalculado no coincide");
                        }
                        prev = storedHash;
                    }
                    return new VerificationResult(true, null, count, "Cadena íntegra");
                }, companyId);
    }

    private Block findLast(String companyId) {
        return jdbcTemplate.query("""
                SELECT sequence_number, event_hash
                  FROM audit_events
                 WHERE company_id = ?
                   AND sequence_number IS NOT NULL
                 ORDER BY sequence_number DESC
                 LIMIT 1
                 FOR UPDATE
                """,
                rs -> rs.next()
                        ? new Block(rs.getLong("sequence_number"),
                                    null, rs.getString("event_hash"))
                        : null,
                companyId);
    }

    private static String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (Exception e) {
            return "";
        }
    }

    private static String nullSafe(String s) { return s == null ? "" : s; }

    public record Block(long sequence, String prevHash, String hash) {}

    public record VerificationResult(boolean valid, String brokenAtId, long count, String message) {}
}
