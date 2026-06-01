package com.benjagest.backend.modules;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Acceso a module_catalog y company_modules. Es la unica clase que
 * habla con esas dos tablas; el resto pasa por el Service.
 */
@Repository
public class ModuleRepository {

    private final JdbcTemplate jdbcTemplate;

    public ModuleRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Catalogo entero (incluye categorias y sub-modulos, activos o no
     * para cualquier empresa). Para listar lo que puede ofrecerse.
     */
    public List<Module> findAll() {
        return jdbcTemplate.query("""
                SELECT m.slug, m.label, m.description,
                       parent.slug AS parent_slug,
                       requires.slug AS requires_slug,
                       m.icon, m.display_order,
                       m.advisory_only, m.active_in_catalog
                  FROM module_catalog m
                  LEFT JOIN module_catalog parent ON parent.id = m.parent_id
                  LEFT JOIN module_catalog requires ON requires.id = m.requires_module_id
                 ORDER BY m.display_order, m.label
                """, this::mapModule);
    }

    /**
     * Modulos activos para una empresa concreta (active=TRUE en
     * company_modules Y el modulo sigue vigente en el catalogo).
     */
    public List<Module> findActiveForCompany(String companyId) {
        return jdbcTemplate.query("""
                SELECT m.slug, m.label, m.description,
                       parent.slug AS parent_slug,
                       requires.slug AS requires_slug,
                       m.icon, m.display_order,
                       m.advisory_only, m.active_in_catalog
                  FROM company_modules cm
                  JOIN module_catalog m ON m.id = cm.module_id
                  LEFT JOIN module_catalog parent ON parent.id = m.parent_id
                  LEFT JOIN module_catalog requires ON requires.id = m.requires_module_id
                 WHERE cm.company_id = ?
                   AND cm.active = TRUE
                   AND m.active_in_catalog = TRUE
                 ORDER BY m.display_order, m.label
                """, this::mapModule, companyId);
    }

    /**
     * Comprobacion rapida: ?la empresa tiene este slug activo?
     * Devuelve TRUE solo si la fila existe Y active=TRUE Y el modulo
     * sigue vigente en el catalogo.
     */
    public boolean isModuleActive(String companyId, String slug) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                  FROM company_modules cm
                  JOIN module_catalog m ON m.id = cm.module_id
                 WHERE cm.company_id = ?
                   AND cm.active = TRUE
                   AND m.slug = ?
                   AND m.active_in_catalog = TRUE
                """,
                Integer.class,
                companyId,
                slug
        );
        return count != null && count > 0;
    }

    /**
     * Resuelve el UUID de un slug del catalogo. Devuelve null si el slug
     * no existe (no se distingue "no existe" de "esta inactivo" — quien
     * llama valida segun su caso).
     */
    public String findIdBySlug(String slug) {
        List<String> matches = jdbcTemplate.query(
                "SELECT id FROM module_catalog WHERE slug = ? LIMIT 1",
                (rs, rowNum) -> rs.getString("id"),
                slug
        );
        return matches.isEmpty() ? null : matches.get(0);
    }

    /**
     * Devuelve los slugs de los sub-modulos de una categoria. Si el slug
     * pasado no es categoria (tiene padre o no existe), devuelve lista
     * vacia. Lo usa CompanyModulesService para hacer la cascada cuando
     * el usuario marca/desmarca una categoria entera.
     */
    public List<String> findChildSlugsOfCategory(String categorySlug) {
        return jdbcTemplate.query("""
                SELECT child.slug
                  FROM module_catalog child
                  JOIN module_catalog parent ON parent.id = child.parent_id
                 WHERE parent.slug = ?
                """,
                (rs, rowNum) -> rs.getString("slug"),
                categorySlug
        );
    }

    /**
     * Indica si un slug del catalogo es una categoria raiz (parent_id NULL).
     */
    public boolean isRootCategory(String slug) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM module_catalog WHERE slug = ? AND parent_id IS NULL",
                Integer.class,
                slug
        );
        return count != null && count > 0;
    }

    /**
     * Activa/desactiva un modulo para una empresa. Upsert: si no existe
     * la fila company_modules, la crea con active=valor; si existe, solo
     * actualiza active y la fecha correspondiente.
     */
    public void setActive(String companyId, String moduleId, boolean active, String actorUserId) {
        jdbcTemplate.update("""
                INSERT INTO company_modules (id, company_id, module_id, active, activated_by, deactivated_by, deactivated_at)
                VALUES (UUID(), ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    active = VALUES(active),
                    activated_at = IF(VALUES(active) = TRUE, CURRENT_TIMESTAMP, activated_at),
                    activated_by = IF(VALUES(active) = TRUE, VALUES(activated_by), activated_by),
                    deactivated_at = IF(VALUES(active) = FALSE, CURRENT_TIMESTAMP, NULL),
                    deactivated_by = IF(VALUES(active) = FALSE, VALUES(deactivated_by), NULL)
                """,
                companyId,
                moduleId,
                active,
                active ? actorUserId : null,
                active ? null : actorUserId,
                active ? null : new java.sql.Timestamp(System.currentTimeMillis())
        );
    }

    private Module mapModule(ResultSet rs, int rowNum) throws SQLException {
        return new Module(
                rs.getString("slug"),
                rs.getString("label"),
                rs.getString("description"),
                rs.getString("parent_slug"),
                rs.getString("requires_slug"),
                rs.getString("icon"),
                rs.getInt("display_order"),
                rs.getBoolean("advisory_only"),
                rs.getBoolean("active_in_catalog")
        );
    }
}
