package com.benjagest.backend.aeat;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generadores de los modelos AEAT extra (347, 390, 190).
 *
 * <p>Cada método {@code generate347/390/190} calcula los datos sobre las
 * facturas/asientos del año y los persiste en {@code tax_filings} con
 * status DRAFT. El asesor luego revisa y cambia a READY → PRESENTED.
 *
 * <p>Los formatos AEAT oficiales (líneas posicionales para subir a
 * AEAT) los emite {@link AeatModelFormatter} como sub-slice — aquí solo
 * generamos el JSON estructurado con las casillas calculadas, que es lo
 * que la UI muestra y la prepresentación valida.
 *
 * <h2>Modelo 347 — Operaciones con terceros</h2>
 * Recolecta todas las facturas emitidas y recibidas del año. Agrupa por
 * NIF de cliente/proveedor. Si la suma anual con un NIF supera
 * <b>3.005,06€</b> (umbral legal histórico), se incluye desglosado por
 * trimestres.
 *
 * <h2>Modelo 390 — Resumen anual IVA</h2>
 * Agrega los 303 del año por casilla. Casillas principales: bases por
 * tipo (4/10/21%) y por tipo de operación (B2B, B2C, intracom),
 * IVA repercutido total, IVA soportado deducible, resultado.
 *
 * <h2>Modelo 190 — Resumen anual retenciones IRPF</h2>
 * Suma de retenciones practicadas en el año (cuenta 4751), agrupadas por
 * perceptor (empleados → clave A, profesionales → clave G).
 */
@Service
public class AeatExtraModelsService {

    /** Umbral del modelo 347 — operaciones que individualmente superan 3.005,06€ anuales. */
    public static final BigDecimal THRESHOLD_347 = new BigDecimal("3005.06");

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final ObjectMapper objectMapper;
    private final CurrentUserService currentUserService;

    public AeatExtraModelsService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                                    ObjectMapper objectMapper,
                                    CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.objectMapper = objectMapper;
        this.currentUserService = currentUserService;
    }

    // ====================================================================
    //  Modelo 347
    // ====================================================================

    @Transactional
    public Model347View generate347(int year, boolean persist) {
        String companyId = tenantContext.getCurrentCompanyId();
        Map<String, NifTotals> bySupplier = new LinkedHashMap<>();
        Map<String, NifTotals> byCustomer = new LinkedHashMap<>();

        // Adquisiciones (purchase_invoices).
        List<Object[]> purchaseRows = jdbcTemplate.query("""
                SELECT supplier_nif, supplier_name, invoice_date, total_amount
                  FROM purchase_invoices
                 WHERE company_id = ?
                   AND YEAR(invoice_date) = ?
                   AND supplier_nif IS NOT NULL
                """, (rs, n) -> new Object[]{
                        rs.getString("supplier_nif"),
                        rs.getString("supplier_name"),
                        rs.getDate("invoice_date").toLocalDate(),
                        rs.getBigDecimal("total_amount")},
                companyId, year);
        for (Object[] r : purchaseRows) {
            String nif = (String) r[0];
            if (nif == null || nif.isBlank()) continue;
            bySupplier.computeIfAbsent(nif.toUpperCase(),
                    k -> new NifTotals((String) r[1]))
                    .add((LocalDate) r[2], (BigDecimal) r[3]);
        }

        // Entregas (sales_invoices). No tiene customer_nif directo en V2,
        // pero sí customer_legal_name. Para el 347 vamos por customer_id
        // si hay customers; si no, solo por legal_name (el asesor lo
        // revisará — esto es el behavior 80%).
        List<Object[]> salesRows = jdbcTemplate.query("""
                SELECT c.legal_name AS customer_legal_name,
                       COALESCE(c.tax_identifier, '') AS customer_nif,
                       s.invoice_date, s.total AS total_amount
                  FROM sales_invoices s
                  JOIN customers c ON c.id = s.customer_id
                 WHERE s.company_id = ?
                   AND YEAR(s.invoice_date) = ?
                   AND s.status = 'VALIDATED'
                """, (rs, n) -> new Object[]{
                        rs.getString("customer_legal_name"),
                        rs.getString("customer_nif"),
                        rs.getDate("invoice_date").toLocalDate(),
                        rs.getBigDecimal("total_amount")},
                companyId, year);
        for (Object[] r : salesRows) {
            String name = (String) r[0];
            String nif = (String) r[1];
            if (name == null || name.isBlank()) continue;
            // Clave por NIF si existe; si no, por nombre (el asesor corrige el NIF en el editor).
            String key = (nif != null && !nif.isBlank())
                    ? nif.toUpperCase().trim() : name.toUpperCase().trim();
            byCustomer.computeIfAbsent(key, k -> new NifTotals(name))
                    .add((LocalDate) r[2], (BigDecimal) r[3]);
        }

        // Filtrar por umbral 3.005,06€.
        List<Model347Row> rows = new ArrayList<>();
        for (Map.Entry<String, NifTotals> e : bySupplier.entrySet()) {
            if (e.getValue().total.compareTo(THRESHOLD_347) > 0) {
                NifTotals t = e.getValue();
                rows.add(new Model347Row("A", e.getKey(), t.name,
                        t.q1, t.q2, t.q3, t.q4, t.total));
            }
        }
        for (Map.Entry<String, NifTotals> e : byCustomer.entrySet()) {
            if (e.getValue().total.compareTo(THRESHOLD_347) > 0) {
                NifTotals t = e.getValue();
                rows.add(new Model347Row("B", e.getKey(), t.name,
                        t.q1, t.q2, t.q3, t.q4, t.total));
            }
        }

        BigDecimal totalAdquisiciones = bySupplier.values().stream()
                .filter(t -> t.total.compareTo(THRESHOLD_347) > 0)
                .map(t -> t.total).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal totalEntregas = byCustomer.values().stream()
                .filter(t -> t.total.compareTo(THRESHOLD_347) > 0)
                .map(t -> t.total).reduce(BigDecimal.ZERO, BigDecimal::add);

        Model347View view = new Model347View(year, rows.size(),
                totalAdquisiciones, totalEntregas, rows);
        if (persist) persistFiling("347", year, null, view, view.totalEntregas().add(view.totalAdquisiciones()));
        return view;
    }

    // ====================================================================
    //  Modelo 390
    // ====================================================================

    @Transactional
    public Model390View generate390(int year, boolean persist) {
        String companyId = tenantContext.getCurrentCompanyId();

        // IVA REPERCUTIDO desde sales_invoices.
        VatBreakdown rep = jdbcTemplate.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN vat_total > 0 AND total > 0
                                    AND ABS((vat_total / NULLIF(subtotal, 0)) * 100 - 4) < 1
                                    THEN subtotal ELSE 0 END), 0) AS base04,
                  COALESCE(SUM(CASE WHEN vat_total > 0 AND total > 0
                                    AND ABS((vat_total / NULLIF(subtotal, 0)) * 100 - 10) < 1
                                    THEN subtotal ELSE 0 END), 0) AS base10,
                  COALESCE(SUM(CASE WHEN vat_total > 0 AND total > 0
                                    AND ABS((vat_total / NULLIF(subtotal, 0)) * 100 - 21) < 1
                                    THEN subtotal ELSE 0 END), 0) AS base21,
                  COALESCE(SUM(vat_total), 0) AS iva_total
                  FROM sales_invoices
                 WHERE company_id = ?
                   AND YEAR(invoice_date) = ?
                   AND status = 'VALIDATED'
                """, (rs, n) -> new VatBreakdown(
                        rs.getBigDecimal("base04"),
                        rs.getBigDecimal("base10"),
                        rs.getBigDecimal("base21"),
                        rs.getBigDecimal("iva_total")),
                companyId, year);

        // IVA SOPORTADO desde purchase_invoices.
        VatBreakdown sop = jdbcTemplate.queryForObject("""
                SELECT
                  COALESCE(SUM(CASE WHEN ABS(vat_percent - 4) < 1
                                    THEN base_amount ELSE 0 END), 0) AS base04,
                  COALESCE(SUM(CASE WHEN ABS(vat_percent - 10) < 1
                                    THEN base_amount ELSE 0 END), 0) AS base10,
                  COALESCE(SUM(CASE WHEN ABS(vat_percent - 21) < 1
                                    THEN base_amount ELSE 0 END), 0) AS base21,
                  COALESCE(SUM(vat_amount), 0) AS iva_total
                  FROM purchase_invoices
                 WHERE company_id = ?
                   AND YEAR(invoice_date) = ?
                """, (rs, n) -> new VatBreakdown(
                        rs.getBigDecimal("base04"),
                        rs.getBigDecimal("base10"),
                        rs.getBigDecimal("base21"),
                        rs.getBigDecimal("iva_total")),
                companyId, year);

        BigDecimal resultadoLiquidacion = rep.totalIva.subtract(sop.totalIva)
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> casillas = new LinkedHashMap<>();
        casillas.put("01_base04", rep.base04);
        casillas.put("02_base10", rep.base10);
        casillas.put("03_base21", rep.base21);
        casillas.put("06_iva_repercutido_total", rep.totalIva);
        casillas.put("22_base_soportada_04", sop.base04);
        casillas.put("24_base_soportada_10", sop.base10);
        casillas.put("28_base_soportada_21", sop.base21);
        casillas.put("33_iva_soportado_total", sop.totalIva);
        casillas.put("64_resultado_anual", resultadoLiquidacion);

        Model390View view = new Model390View(year, rep, sop, resultadoLiquidacion, casillas);
        if (persist) persistFiling("390", year, null, view, resultadoLiquidacion);
        return view;
    }

    // ====================================================================
    //  Modelo 190
    // ====================================================================

    @Transactional
    public Model190View generate190(int year, boolean persist) {
        String companyId = tenantContext.getCurrentCompanyId();

        // Resumen por perceptor: sumar todas las retenciones del año
        // desde journal_entry_lines con cuenta 4751 (HP acreedora por
        // retenciones practicadas). El "perceptor" lo identificamos por
        // el sourceId del journal_entry (cuando sea purchase/sales) y
        // resolvemos el nif.
        List<PerceptorTotals> perceptores = jdbcTemplate.query("""
                SELECT p.supplier_nif AS nif,
                       p.supplier_name AS name,
                       'G' AS subclave,
                       COALESCE(SUM(p.base_amount), 0) AS base,
                       0 AS retencion
                  FROM purchase_invoices p
                 WHERE p.company_id = ?
                   AND YEAR(p.invoice_date) = ?
                   AND p.supplier_nif IS NOT NULL
                 GROUP BY p.supplier_nif, p.supplier_name
                HAVING COUNT(*) > 0
                """, (rs, n) -> new PerceptorTotals(
                        rs.getString("nif"), rs.getString("name"),
                        rs.getString("subclave"),
                        rs.getBigDecimal("base"),
                        rs.getBigDecimal("retencion")),
                companyId, year);

        // Para refinar: las retenciones reales están en journal_entry_lines
        // contra cuenta 4751. Cruzamos esos importes con la fuente cuando
        // el source_type es PURCHASE_INVOICE.
        List<Map<String, Object>> raw = jdbcTemplate.query("""
                SELECT p.supplier_nif AS nif,
                       p.supplier_name AS name,
                       COALESCE(SUM(l.credit), 0) AS retencion
                  FROM purchase_invoices p
                  JOIN journal_entries je ON je.source_type = 'PURCHASE_INVOICE'
                                          AND je.source_id = p.id
                  JOIN journal_entry_lines l ON l.journal_entry_id = je.id
                  JOIN accounting_accounts a ON a.id = l.account_id
                                            AND a.code LIKE '4751%'
                 WHERE p.company_id = ?
                   AND YEAR(p.invoice_date) = ?
                   AND je.status = 'POSTED'
                 GROUP BY p.supplier_nif, p.supplier_name
                """, (rs, n) -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("nif", rs.getString("nif"));
                    m.put("name", rs.getString("name"));
                    m.put("retencion", rs.getBigDecimal("retencion"));
                    return m;
                }, companyId, year);

        // Merge: para cada perceptor con base, asignar la retención si la hay.
        Map<String, BigDecimal> retencionByNif = new LinkedHashMap<>();
        for (Map<String, Object> r : raw) {
            retencionByNif.put(((String) r.get("nif")).toUpperCase(),
                    (BigDecimal) r.get("retencion"));
        }
        List<Model190Row> rows = new ArrayList<>();
        BigDecimal totalRetenciones = BigDecimal.ZERO;
        BigDecimal totalBase = BigDecimal.ZERO;
        for (PerceptorTotals p : perceptores) {
            BigDecimal ret = retencionByNif.getOrDefault(
                    p.nif() == null ? "" : p.nif().toUpperCase(), BigDecimal.ZERO);
            if (ret == null) ret = BigDecimal.ZERO;
            // Solo incluir perceptores con retención > 0.
            if (ret.signum() == 0) continue;
            rows.add(new Model190Row(p.nif(), p.name(), p.subclave(), p.base(), ret));
            totalRetenciones = totalRetenciones.add(ret);
            totalBase = totalBase.add(p.base());
        }

        Model190View view = new Model190View(year, rows.size(),
                totalBase, totalRetenciones, rows);
        if (persist) persistFiling("190", year, null, view, totalRetenciones);
        return view;
    }

    // ====================================================================
    //  Persistencia en tax_filings
    // ====================================================================

    private void persistFiling(String modelCode, int year, Integer quarter,
                                 Object data, BigDecimal totalAmount) {
        try {
            String json = objectMapper.writeValueAsString(data);
            String userId = safeUserId();
            String companyId = tenantContext.getCurrentCompanyId();

            // Idempotencia: si ya existe DRAFT, actualizamos; si está
            // PRESENTED, error.
            List<String> existing = jdbcTemplate.query("""
                    SELECT id, status FROM tax_filings
                     WHERE company_id = ? AND tax_model_code = ?
                       AND period_year = ?
                       AND (period_quarter IS NULL AND ? IS NULL
                            OR period_quarter = ?)
                       AND period_month IS NULL
                     LIMIT 1
                    """, (rs, n) -> rs.getString("id") + "|" + rs.getString("status"),
                    companyId, modelCode, year, quarter, quarter);
            if (!existing.isEmpty()) {
                String[] parts = existing.get(0).split("\\|");
                String existingId = parts[0];
                String status = parts[1];
                if ("PRESENTED".equals(status) || "PAID".equals(status)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT,
                            "Ya hay una declaración " + modelCode + "/" + year
                                    + " presentada. No se puede regenerar.");
                }
                jdbcTemplate.update("""
                        UPDATE tax_filings SET data = ?, total_amount = ?,
                               updated_at = CURRENT_TIMESTAMP
                         WHERE id = ?
                        """, json, totalAmount, existingId);
                return;
            }
            jdbcTemplate.update("""
                    INSERT INTO tax_filings (
                        id, company_id, tax_model_code, period_year, period_quarter,
                        period_month, status, data, total_amount
                    ) VALUES (?, ?, ?, ?, ?, NULL, 'DRAFT', ?, ?)
                    """,
                    UUID.randomUUID().toString(), companyId, modelCode, year, quarter,
                    json, totalAmount);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error persistiendo modelo " + modelCode + ": " + ex.getMessage());
        }
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    // ====================================================================
    //  DTOs internos
    // ====================================================================

    private static class NifTotals {
        String name;
        BigDecimal q1 = BigDecimal.ZERO, q2 = BigDecimal.ZERO,
                   q3 = BigDecimal.ZERO, q4 = BigDecimal.ZERO,
                   total = BigDecimal.ZERO;
        NifTotals(String n) { this.name = n; }
        void add(LocalDate date, BigDecimal amt) {
            if (amt == null) return;
            int q = (date.getMonthValue() - 1) / 3 + 1;
            switch (q) {
                case 1 -> q1 = q1.add(amt);
                case 2 -> q2 = q2.add(amt);
                case 3 -> q3 = q3.add(amt);
                case 4 -> q4 = q4.add(amt);
            }
            total = total.add(amt);
        }
    }

    public record VatBreakdown(BigDecimal base04, BigDecimal base10,
                                  BigDecimal base21, BigDecimal totalIva) {}

    // ====================================================================
    //  DTOs públicos
    // ====================================================================

    public record Model347Row(
            String operationType, String nif, String name,
            BigDecimal q1, BigDecimal q2, BigDecimal q3, BigDecimal q4,
            BigDecimal yearTotal
    ) {}

    public record Model347View(
            int year, int rowsCount,
            BigDecimal totalAdquisiciones, BigDecimal totalEntregas,
            List<Model347Row> rows
    ) {}

    public record Model390View(
            int year,
            VatBreakdown repercutido, VatBreakdown soportado,
            BigDecimal resultadoLiquidacion,
            Map<String, Object> casillas
    ) {}

    private record PerceptorTotals(
            String nif, String name, String subclave,
            BigDecimal base, BigDecimal retencion
    ) {}

    public record Model190Row(
            String nif, String name, String subclave,
            BigDecimal base, BigDecimal retencion
    ) {}

    public record Model190View(
            int year, int perceptoresCount,
            BigDecimal totalBase, BigDecimal totalRetenciones,
            List<Model190Row> rows
    ) {}
}
