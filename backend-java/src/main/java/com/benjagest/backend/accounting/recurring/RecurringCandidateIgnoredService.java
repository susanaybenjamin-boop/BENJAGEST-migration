package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Catálogo de candidatos de recurrencia silenciados.
 *
 * <p>Slice REC-IGNORE 2026-06-11. Cuando el usuario decide que un
 * patrón detectado no es realmente una recurrencia (multas que se
 * repiten, regalos a clientes, comisiones puntuales), pulsa
 * "Silenciar" y manda la combinación (kind, NIF, nombre normalizado,
 * total) a esta tabla. {@link RecurringCandidateService#findCandidates}
 * consulta {@link #isIgnored} y los excluye del listado.
 *
 * <p>{@code ignoreUntil} NULL = silencio indefinido. Si tiene fecha,
 * al pasarla el candidato vuelve a aparecer automáticamente sin
 * borrar la fila (queda como histórico de "esto se silenció antes").
 */
@Service
public class RecurringCandidateIgnoredService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CurrentUserService currentUserService;

    public RecurringCandidateIgnoredService(JdbcTemplate jdbc,
                                              TenantContext tenant,
                                              CurrentUserService currentUserService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.currentUserService = currentUserService;
    }

    public boolean isIgnored(String kind, String partyNif, String partyName, BigDecimal total) {
        String companyId = tenant.getCurrentCompanyId();
        if (companyId == null || kind == null || total == null) return false;
        String nif = partyNif == null ? "" : partyNif.trim();
        String nameNorm = normalize(partyName);
        BigDecimal amount = total.setScale(2, RoundingMode.HALF_UP);
        Integer hit = jdbc.queryForObject("""
                SELECT COUNT(*) FROM recurring_candidates_ignored
                 WHERE company_id = ?
                   AND kind = ?
                   AND party_nif = ?
                   AND party_name_norm = ?
                   AND total_amount = ?
                   AND (ignore_until IS NULL OR ignore_until >= CURRENT_DATE)
                """, Integer.class, companyId, kind, nif, nameNorm, amount);
        return hit != null && hit > 0;
    }

    public List<Ignored> listActive() {
        return jdbc.query("""
                SELECT id, company_id, kind, party_nif, party_name_norm,
                       total_amount, ignore_until, reason,
                       created_by_user_id, created_at
                  FROM recurring_candidates_ignored
                 WHERE company_id = ?
                   AND (ignore_until IS NULL OR ignore_until >= CURRENT_DATE)
                 ORDER BY created_at DESC
                """, MAPPER, tenant.getCurrentCompanyId());
    }

    @Transactional
    public Ignored ignore(IgnoreRequest req) {
        if (req.kind() == null || req.kind().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "kind obligatorio");
        }
        if (req.totalAmount() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "totalAmount obligatorio");
        }
        String companyId = tenant.getCurrentCompanyId();
        String nif = req.partyNif() == null ? "" : req.partyNif().trim();
        String nameNorm = normalize(req.partyName());
        if (nameNorm.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "partyName obligatorio");
        }
        BigDecimal amount = req.totalAmount().setScale(2, RoundingMode.HALF_UP);
        // UPSERT: si ya existe el candidato silenciado, refrescamos
        // ignore_until y reason en lugar de fallar por la UK.
        Optional<String> existingId = jdbc.query("""
                SELECT id FROM recurring_candidates_ignored
                 WHERE company_id = ? AND kind = ? AND party_nif = ?
                   AND party_name_norm = ? AND total_amount = ?
                """, (rs, n) -> rs.getString("id"),
                companyId, req.kind(), nif, nameNorm, amount).stream().findFirst();
        if (existingId.isPresent()) {
            jdbc.update("""
                    UPDATE recurring_candidates_ignored
                       SET ignore_until = ?, reason = ?
                     WHERE id = ?
                    """,
                    req.ignoreUntil() == null ? null : java.sql.Date.valueOf(req.ignoreUntil()),
                    nullIfBlank(req.reason()),
                    existingId.get());
            return getById(existingId.get());
        }
        String id = UUID.randomUUID().toString();
        String userId;
        try {
            userId = currentUserService.require().userId();
        } catch (Exception ex) {
            userId = null;
        }
        jdbc.update("""
                INSERT INTO recurring_candidates_ignored
                       (id, company_id, kind, party_nif, party_name_norm,
                        total_amount, ignore_until, reason, created_by_user_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, companyId, req.kind(), nif, nameNorm, amount,
                req.ignoreUntil() == null ? null : java.sql.Date.valueOf(req.ignoreUntil()),
                nullIfBlank(req.reason()),
                userId);
        return getById(id);
    }

    @Transactional
    public void unignore(String id) {
        Ignored row = getById(id);
        if (!row.companyId().equals(tenant.getCurrentCompanyId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No encontrado");
        }
        jdbc.update("DELETE FROM recurring_candidates_ignored WHERE id = ?", id);
    }

    private Ignored getById(String id) {
        return jdbc.query("""
                SELECT id, company_id, kind, party_nif, party_name_norm,
                       total_amount, ignore_until, reason,
                       created_by_user_id, created_at
                  FROM recurring_candidates_ignored
                 WHERE id = ?
                """, MAPPER, id).stream().findFirst().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Silenciado no encontrado"));
    }

    static String normalize(String s) {
        if (s == null) return "";
        String t = s.trim().toLowerCase(Locale.ROOT);
        // Colapsa whitespace múltiple para que "ENDESA  SA" == "ENDESA SA".
        return t.replaceAll("\\s+", " ");
    }

    private static String nullIfBlank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static final RowMapper<Ignored> MAPPER = (rs, i) -> {
        java.sql.Date until = rs.getDate("ignore_until");
        Timestamp created = rs.getTimestamp("created_at");
        return new Ignored(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("kind"),
                rs.getString("party_nif"),
                rs.getString("party_name_norm"),
                rs.getBigDecimal("total_amount"),
                until == null ? null : until.toLocalDate(),
                rs.getString("reason"),
                rs.getString("created_by_user_id"),
                created == null ? null : created.toInstant());
    };

    public record IgnoreRequest(
            String kind,
            String partyNif,
            String partyName,
            BigDecimal totalAmount,
            LocalDate ignoreUntil,
            String reason
    ) {}

    public record Ignored(
            String id,
            String companyId,
            String kind,
            String partyNif,
            String partyNameNorm,
            BigDecimal totalAmount,
            LocalDate ignoreUntil,
            String reason,
            String createdByUserId,
            java.time.Instant createdAt
    ) {}
}
