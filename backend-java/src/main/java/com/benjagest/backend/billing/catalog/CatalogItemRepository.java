package com.benjagest.backend.billing.catalog;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * CONC-1 — Acceso a {@code catalog_items} (conceptos guardados) y al
 * histórico de líneas ya facturadas, siempre filtrado por
 * {@link TenantContext}: el catálogo es de cada empresa.
 */
@Repository
public class CatalogItemRepository {

    /**
     * Tope de líneas históricas que se leen para agregar conceptos. Con el
     * ORDER BY descendente, las 3.000 más recientes son de sobra para un
     * selector (y evita traerse el histórico entero de una empresa vieja).
     */
    private static final int HISTORY_SCAN_LIMIT = 3000;

    private static final String SELECT_COLUMNS = """
            SELECT id, company_id, customer_id, item_type, name, description,
                   category, unit_price, default_vat_percent,
                   default_retention_percent, default_vat_rate_id,
                   billable, active, created_at, updated_at
              FROM catalog_items
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public CatalogItemRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    public List<CatalogItem> findActive() {
        return jdbcTemplate.query(SELECT_COLUMNS + """
                 WHERE company_id = ?
                   AND active = TRUE
                 ORDER BY name
                """, this::mapItem, tenantContext.getCurrentCompanyId());
    }

    public Optional<CatalogItem> findById(String id) {
        return jdbcTemplate.query(SELECT_COLUMNS + """
                 WHERE id = ? AND company_id = ?
                """, this::mapItem, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst();
    }

    /**
     * Busca un concepto ACTIVO con el mismo nombre (sin distinguir
     * mayúsculas ni espacios sobrantes). Sirve para que "guardar concepto"
     * sea idempotente en vez de llenar el catálogo de duplicados.
     */
    public Optional<CatalogItem> findActiveByName(String name) {
        if (name == null || name.isBlank()) {
            return Optional.empty();
        }
        return jdbcTemplate.query(SELECT_COLUMNS + """
                 WHERE company_id = ?
                   AND active = TRUE
                   AND LOWER(TRIM(name)) = LOWER(TRIM(?))
                 ORDER BY created_at
                """, this::mapItem, tenantContext.getCurrentCompanyId(), name)
                .stream().findFirst();
    }

    public void insert(String id, String name, String description, String category,
                       String itemType, BigDecimal unitPrice, BigDecimal vatPercent,
                       BigDecimal retentionPercent, String vatRateId) {
        jdbcTemplate.update("""
                INSERT INTO catalog_items (
                    id, company_id, item_type, name, description, category,
                    unit_price, default_vat_percent, default_retention_percent,
                    default_vat_rate_id, billable, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, TRUE, TRUE)
                """,
                id, tenantContext.getCurrentCompanyId(), itemType, name, description,
                category, unitPrice, vatPercent, retentionPercent, vatRateId);
    }

    public int update(String id, String name, String description, String category,
                      String itemType, BigDecimal unitPrice, BigDecimal vatPercent,
                      BigDecimal retentionPercent, String vatRateId) {
        return jdbcTemplate.update("""
                UPDATE catalog_items
                   SET name = ?, description = ?, category = ?, item_type = ?,
                       unit_price = ?, default_vat_percent = ?,
                       default_retention_percent = ?, default_vat_rate_id = ?
                 WHERE id = ? AND company_id = ?
                """,
                name, description, category, itemType, unitPrice, vatPercent,
                retentionPercent, vatRateId, id, tenantContext.getCurrentCompanyId());
    }

    /**
     * Baja LÓGICA. Nunca DELETE: las líneas de facturas ya emitidas
     * apuntan aquí por FK y una factura validada no se toca.
     */
    public int deactivate(String id) {
        return jdbcTemplate.update("""
                UPDATE catalog_items
                   SET active = FALSE
                 WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
    }

    /**
     * Líneas de facturas anteriores de la empresa, de la más reciente a la
     * más antigua. El agregado por concepto (contar usos, quedarse con el
     * precio del último uso) lo hace el Service en Java: así no dependemos
     * de funciones de ventana, que este proyecto no usa en ningún sitio y
     * la MariaDB embebida no está verificada para ellas.
     *
     * <p>Se excluyen las CANCELLED (borradores descartados): lo que el
     * usuario anuló no debería reaparecer como sugerencia.
     */
    public List<HistoryLine> findRecentLines() {
        return jdbcTemplate.query("""
                SELECT l.description, l.unit_price, l.vat_percent,
                       l.retention_percent, l.vat_rate_id, i.invoice_date
                  FROM sales_invoice_lines l
                  JOIN sales_invoices i ON i.id = l.invoice_id
                 WHERE i.company_id = ?
                   AND i.status <> 'CANCELLED'
                   AND l.description IS NOT NULL
                   AND TRIM(l.description) <> ''
                 ORDER BY i.invoice_date DESC, l.created_at DESC
                """ + " LIMIT " + HISTORY_SCAN_LIMIT,
                (rs, rowNum) -> {
                    Date d = rs.getDate("invoice_date");
                    return new HistoryLine(
                            rs.getString("description"),
                            rs.getBigDecimal("unit_price"),
                            rs.getBigDecimal("vat_percent"),
                            rs.getBigDecimal("retention_percent"),
                            rs.getString("vat_rate_id"),
                            d == null ? null : d.toLocalDate());
                },
                tenantContext.getCurrentCompanyId());
    }

    /** Una línea de factura ya emitida, tal cual, para agregar en el Service. */
    public record HistoryLine(
            String description,
            BigDecimal unitPrice,
            BigDecimal vatPercent,
            BigDecimal retentionPercent,
            String vatRateId,
            LocalDate invoiceDate
    ) {}

    private CatalogItem mapItem(ResultSet rs, int rowNum) throws SQLException {
        Timestamp c = rs.getTimestamp("created_at");
        Timestamp u = rs.getTimestamp("updated_at");
        return new CatalogItem(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("customer_id"),
                rs.getString("item_type"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("category"),
                rs.getBigDecimal("unit_price"),
                rs.getBigDecimal("default_vat_percent"),
                rs.getBigDecimal("default_retention_percent"),
                rs.getString("default_vat_rate_id"),
                rs.getBoolean("billable"),
                rs.getBoolean("active"),
                c == null ? null : c.toInstant(),
                u == null ? null : u.toInstant()
        );
    }
}
