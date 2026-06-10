package com.benjagest.backend.labor.workcenters;

import com.benjagest.backend.tenant.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Centros de trabajo (V89). Port fiel de CONTENDO
 * {@code centros_trabajo_180}.
 *
 * <p>Geolocalización opcional para validar fichajes:
 * <ul>
 *   <li>{@code none}   — sin validar.</li>
 *   <li>{@code info}   — registra fuera de zona como informativo.</li>
 *   <li>{@code soft}   — marca fichaje como sospechoso.</li>
 *   <li>{@code strict} — rechaza fichaje fuera del radio.</li>
 * </ul>
 */
@Service
public class WorkCenterService {

    private static final java.util.Set<String> ALLOWED_POLICIES =
            java.util.Set.of("none", "info", "soft", "strict");

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public WorkCenterService(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    private static final RowMapper<WorkCenter> MAPPER = (rs, i) -> new WorkCenter(
            rs.getString("id"),
            rs.getString("company_id"),
            rs.getString("name"),
            rs.getString("address"),
            rs.getString("city"),
            rs.getString("province"),
            rs.getString("postal_code"),
            rs.getBigDecimal("lat"),
            rs.getBigDecimal("lng"),
            rs.getInt("radio_m_raw"),
            rs.getString("geo_policy"),
            rs.getString("notes"),
            rs.getBoolean("active"));

    public List<WorkCenter> list() {
        return jdbcTemplate.query("""
                SELECT id, company_id, name,
                       COALESCE(address, '') AS address,
                       COALESCE(city, '') AS city,
                       COALESCE(province, '') AS province,
                       COALESCE(postal_code, '') AS postal_code,
                       lat, lng,
                       COALESCE(radio_m, 0) AS radio_m_raw,
                       geo_policy,
                       COALESCE(notes, '') AS notes,
                       active
                  FROM work_centers
                 WHERE company_id = ? AND active = TRUE
                 ORDER BY name
                """, MAPPER, tenantContext.getCurrentCompanyId());
    }

    @Transactional
    public WorkCenter create(UpsertRequest r) {
        validate(r);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO work_centers (id, company_id, name, address, city,
                                          province, postal_code, lat, lng,
                                          radio_m, geo_policy, notes, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE)
                """,
                id, tenantContext.getCurrentCompanyId(),
                r.name().trim(),
                blank(r.address()), blank(r.city()), blank(r.province()), blank(r.postalCode()),
                r.lat(), r.lng(), r.radioM(),
                policy(r.geoPolicy()), blank(r.notes()));
        return findById(id);
    }

    @Transactional
    public WorkCenter update(String id, UpsertRequest r) {
        validate(r);
        WorkCenter existing = findById(id);
        jdbcTemplate.update("""
                UPDATE work_centers
                   SET name = ?, address = ?, city = ?, province = ?,
                       postal_code = ?, lat = ?, lng = ?, radio_m = ?,
                       geo_policy = ?, notes = ?
                 WHERE id = ? AND company_id = ?
                """,
                r.name().trim(),
                blank(r.address()), blank(r.city()), blank(r.province()), blank(r.postalCode()),
                r.lat(), r.lng(), r.radioM(),
                policy(r.geoPolicy()), blank(r.notes()),
                existing.id(), tenantContext.getCurrentCompanyId());
        return findById(id);
    }

    @Transactional
    public void deactivate(String id) {
        WorkCenter existing = findById(id);
        jdbcTemplate.update(
                "UPDATE work_centers SET active = FALSE WHERE id = ?",
                existing.id());
    }

    private WorkCenter findById(String id) {
        List<WorkCenter> rows = jdbcTemplate.query("""
                SELECT id, company_id, name,
                       COALESCE(address, '') AS address,
                       COALESCE(city, '') AS city,
                       COALESCE(province, '') AS province,
                       COALESCE(postal_code, '') AS postal_code,
                       lat, lng,
                       COALESCE(radio_m, 0) AS radio_m_raw,
                       geo_policy,
                       COALESCE(notes, '') AS notes,
                       active
                  FROM work_centers
                 WHERE id = ? AND company_id = ?
                """, MAPPER, id, tenantContext.getCurrentCompanyId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Centro de trabajo no encontrado");
        }
        return rows.get(0);
    }

    private static void validate(UpsertRequest r) {
        if (r.name() == null || r.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "name obligatorio");
        }
        if (r.lat() != null) {
            double d = r.lat().doubleValue();
            if (d < -90 || d > 90) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "lat fuera de rango (-90, 90)");
            }
        }
        if (r.lng() != null) {
            double d = r.lng().doubleValue();
            if (d < -180 || d > 180) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "lng fuera de rango (-180, 180)");
            }
        }
        if (r.radioM() != null && r.radioM() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "radio_m no puede ser negativo");
        }
    }

    private static String policy(String p) {
        if (p == null || p.isBlank()) return "info";
        String low = p.toLowerCase();
        return ALLOWED_POLICIES.contains(low) ? low : "info";
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    // ============================================================
    //  DTOs
    // ============================================================

    public record WorkCenter(
            String id,
            String companyId,
            String name,
            String address,
            String city,
            String province,
            String postalCode,
            java.math.BigDecimal lat,
            java.math.BigDecimal lng,
            int radioM,
            String geoPolicy,
            String notes,
            boolean active
    ) {}

    public record UpsertRequest(
            String name,
            String address,
            String city,
            String province,
            String postalCode,
            java.math.BigDecimal lat,
            java.math.BigDecimal lng,
            Integer radioM,
            String geoPolicy,
            String notes
    ) {}
}
