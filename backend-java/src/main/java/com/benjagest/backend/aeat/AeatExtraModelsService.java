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

    // Modelo 130 — parametros legales (art. 30 Reglamento IRPF + modelo 130).
    // Estimacion directa SIMPLIFICADA: 5% de gastos de dificil justificacion
    // sobre el rendimiento neto positivo, con tope de 2.000 EUR anuales.
    // El pago fraccionado es el 20% del rendimiento neto. Son constantes
    // legales estables; si cambian, es una edicion de una linea.
    private static final BigDecimal GASTOS_DIFICIL_JUSTIFICACION_PCT = new BigDecimal("0.05");
    private static final BigDecimal GASTOS_DIFICIL_JUSTIFICACION_TOPE = new BigDecimal("2000");
    private static final BigDecimal MODELO_130_TIPO = new BigDecimal("0.20");

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
                """, (rs, n) -> new Object[]{
                        rs.getString("supplier_nif"),
                        rs.getString("supplier_name"),
                        rs.getDate("invoice_date").toLocalDate(),
                        rs.getBigDecimal("total_amount")},
                companyId, year);
        for (Object[] r : purchaseRows) {
            String nif = (String) r[0];
            String name = (String) r[1];
            // Incluimos también proveedores SIN NIF (la asesoría lo rellena en el editor);
            // se agrupan por nombre. Solo se descartan filas sin NIF y sin nombre.
            if ((nif == null || nif.isBlank()) && (name == null || name.isBlank())) continue;
            boolean hasNif = nif != null && !nif.isBlank();
            String key = hasNif ? "NIF:" + nif.toUpperCase().trim()
                    : "NAME:" + (name == null ? "" : name.toUpperCase().trim());
            bySupplier.computeIfAbsent(key,
                    k -> new NifTotals(name, hasNif ? nif.toUpperCase().trim() : ""))
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
            boolean hasNif = nif != null && !nif.isBlank();
            String key = hasNif ? "NIF:" + nif.toUpperCase().trim() : "NAME:" + name.toUpperCase().trim();
            byCustomer.computeIfAbsent(key, k -> new NifTotals(name, hasNif ? nif.toUpperCase().trim() : ""))
                    .add((LocalDate) r[2], (BigDecimal) r[3]);
        }

        // Filtrar por umbral 3.005,06€.
        List<Model347Row> rows = new ArrayList<>();
        for (Map.Entry<String, NifTotals> e : bySupplier.entrySet()) {
            if (e.getValue().total.compareTo(THRESHOLD_347) > 0) {
                NifTotals t = e.getValue();
                rows.add(new Model347Row("A", t.nif, t.name,
                        t.q1, t.q2, t.q3, t.q4, t.total));
            }
        }
        for (Map.Entry<String, NifTotals> e : byCustomer.entrySet()) {
            if (e.getValue().total.compareTo(THRESHOLD_347) > 0) {
                NifTotals t = e.getValue();
                rows.add(new Model347Row("B", t.nif, t.name,
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

        // IVA repercutido y soportado del año desde los asientos POSTED
        // (misma fuente que el 303, sin filtro de trimestre).
        VatBreakdown rep = jdbcTemplate.queryForObject(SALES_VAT_BY_RATE_SQL,
                AeatExtraModelsService::mapRepercutido, companyId, year);
        VatBreakdown sop = jdbcTemplate.queryForObject(PURCHASE_VAT_BY_RATE_SQL,
                AeatExtraModelsService::mapSoportado, companyId, year);

        BigDecimal resultadoLiquidacion = computeResultadoIva(rep.totalIva, sop.totalIva);

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
    //  IVA desde la contabilidad (asientos POSTED), por tipo (vat_rate)
    // ====================================================================

    /** Bases de IVA REPERCUTIDO por tipo = haber de las líneas 7xx etiquetadas.
     *  Termina en YEAR(...)=? ; quien lo use añade el filtro de periodo. */
    private static final String SALES_VAT_BY_RATE_SQL = """
            SELECT
              COALESCE(SUM(CASE WHEN ABS(l.vat_rate - 4) < 1 THEN l.credit ELSE 0 END), 0) AS base04,
              COALESCE(SUM(CASE WHEN ABS(l.vat_rate - 10) < 1 THEN l.credit ELSE 0 END), 0) AS base10,
              COALESCE(SUM(CASE WHEN ABS(l.vat_rate - 21) < 1 THEN l.credit ELSE 0 END), 0) AS base21
              FROM journal_entry_lines l
              JOIN journal_entries e ON e.id = l.journal_entry_id
              JOIN accounting_accounts a ON a.id = l.account_id
             WHERE e.company_id = ? AND e.status = 'POSTED'
               AND l.vat_rate IS NOT NULL AND a.code LIKE '7%'
               AND YEAR(e.entry_date) = ?""";

    /** Bases de IVA SOPORTADO por tipo (debe 6xx) + cuota total (debe 472). */
    private static final String PURCHASE_VAT_BY_RATE_SQL = """
            SELECT
              COALESCE(SUM(CASE WHEN ABS(l.vat_rate - 4) < 1 AND a.code LIKE '6%' THEN l.debit ELSE 0 END), 0) AS base04,
              COALESCE(SUM(CASE WHEN ABS(l.vat_rate - 10) < 1 AND a.code LIKE '6%' THEN l.debit ELSE 0 END), 0) AS base10,
              COALESCE(SUM(CASE WHEN ABS(l.vat_rate - 21) < 1 AND a.code LIKE '6%' THEN l.debit ELSE 0 END), 0) AS base21,
              COALESCE(SUM(CASE WHEN a.code LIKE '472%' THEN l.debit ELSE 0 END), 0) AS iva_total
              FROM journal_entry_lines l
              JOIN journal_entries e ON e.id = l.journal_entry_id
              JOIN accounting_accounts a ON a.id = l.account_id
             WHERE e.company_id = ? AND e.status = 'POSTED'
               AND l.vat_rate IS NOT NULL
               AND YEAR(e.entry_date) = ?""";

    private static BigDecimal nz(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Repercutido: bases por tipo desde 7xx; la cuota se deriva (base × tipo). */
    private static VatBreakdown mapRepercutido(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return deriveRepercutido(nz(rs.getBigDecimal("base04")),
                nz(rs.getBigDecimal("base10")), nz(rs.getBigDecimal("base21")));
    }

    /**
     * PURO — cuota de IVA repercutido derivada de las bases por tipo
     * (4/10/21%). Separado del ResultSet para poder testear la aritmetica.
     */
    public static VatBreakdown deriveRepercutido(BigDecimal base04, BigDecimal base10, BigDecimal base21) {
        BigDecimal b4 = nz(base04), b10 = nz(base10), b21 = nz(base21);
        BigDecimal iva = b4.multiply(new BigDecimal("0.04"))
                .add(b10.multiply(new BigDecimal("0.10")))
                .add(b21.multiply(new BigDecimal("0.21")))
                .setScale(2, RoundingMode.HALF_UP);
        return new VatBreakdown(b4, b10, b21, iva);
    }

    /** PURO — resultado del 303/390 = IVA repercutido - IVA soportado. */
    public static BigDecimal computeResultadoIva(BigDecimal ivaRepercutido, BigDecimal ivaSoportado) {
        return nz(ivaRepercutido).subtract(nz(ivaSoportado)).setScale(2, RoundingMode.HALF_UP);
    }

    /**
     * PURO (IVA-COMP) — aplica las cuotas de IVA a compensar de periodos
     * anteriores (casilla 110) sobre el resultado del régimen general del
     * trimestre (casilla 46/64). Reproduce el modelo 303:
     *
     * <pre>
     *   resultado régimen >= 0 (a ingresar):
     *       aplicada (78)  = min(compensación previa, resultado régimen)
     *       resultado (71) = resultado régimen - aplicada
     *       remanente (87) = compensación previa - aplicada
     *   resultado régimen < 0 (a compensar):
     *       aplicada (78)  = 0
     *       resultado (71) = resultado régimen  (negativo, se declara a compensar)
     *       remanente (87) = compensación previa + |resultado régimen|
     * </pre>
     *
     * Validado con declaraciones REALES de Benjamin: 1T 2026 (régimen
     * 968,05 + previa 254,91 → resultado 713,14) y 1T 2025 (régimen
     * 114,62 + previa 1.207,25 → resultado 0,00, remanente 1.092,63).
     */
    public static Compensacion303 aplicarCompensacion(BigDecimal resultadoRegimen,
                                                       BigDecimal compensacionPrevia) {
        BigDecimal previa = nz(compensacionPrevia).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);
        BigDecimal reg = nz(resultadoRegimen).setScale(2, RoundingMode.HALF_UP);
        if (reg.signum() >= 0) {
            BigDecimal aplicada = previa.min(reg);
            return new Compensacion303(previa, aplicada, reg.subtract(aplicada), previa.subtract(aplicada));
        }
        return new Compensacion303(previa, BigDecimal.ZERO, reg, previa.add(reg.negate()));
    }

    public record Compensacion303(
            BigDecimal compensacionPrevia, // casilla 110
            BigDecimal aplicada,           // casilla 78
            BigDecimal resultado,          // casilla 71 (final)
            BigDecimal remanente           // casilla 87 (para el trimestre siguiente)
    ) {}

    /** Soportado: bases por tipo desde 6xx; cuota total real desde 472. */
    private static VatBreakdown mapSoportado(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        return new VatBreakdown(nz(rs.getBigDecimal("base04")), nz(rs.getBigDecimal("base10")),
                nz(rs.getBigDecimal("base21")), nz(rs.getBigDecimal("iva_total")));
    }

    // ====================================================================
    //  Modelo 303 (IVA trimestral) — prefill desde facturas del trimestre
    // ====================================================================

    @Transactional
    public Model303View generate303(int year, int quarter, boolean persist) {
        String companyId = tenantContext.getCurrentCompanyId();
        int mFrom = (quarter - 1) * 3 + 1;
        int mTo = quarter * 3;

        // IVA REPERCUTIDO del régimen general (casillas 01-09) desde los
        // asientos POSTED del trimestre, EXCLUYENDO las rectificativas (van
        // aparte en la casilla 14/15, modificación de bases y cuotas — como
        // en el 303 oficial, no se netean en el régimen general).
        VatBreakdown rep = jdbcTemplate.queryForObject(SALES_VAT_BY_RATE_SQL
                + " AND MONTH(e.entry_date) BETWEEN ? AND ?"
                + " AND NOT EXISTS (SELECT 1 FROM sales_invoices si"
                + "   WHERE si.id = e.source_id AND si.invoice_type = 'RECTIFYING')",
                AeatExtraModelsService::mapRepercutido,
                companyId, year, mFrom, mTo);

        // Modificación de bases y cuotas (casillas 14/15): rectificativas del
        // trimestre (base y cuota, con su signo). Es el caso de un abono.
        BigDecimal[] mod = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(subtotal),0), COALESCE(SUM(vat_total),0)
                  FROM sales_invoices
                 WHERE company_id = ? AND status = 'VALIDATED'
                   AND invoice_type = 'RECTIFYING'
                   AND YEAR(invoice_date) = ? AND MONTH(invoice_date) BETWEEN ? AND ?
                """, (rs, n) -> new BigDecimal[]{nz(rs.getBigDecimal(1)), nz(rs.getBigDecimal(2))},
                companyId, year, mFrom, mTo);
        BigDecimal modBase = mod[0], modCuota = mod[1];

        // IVA SOPORTADO (casillas 28/29) desde los asientos POSTED del trimestre.
        VatBreakdown sop = jdbcTemplate.queryForObject(PURCHASE_VAT_BY_RATE_SQL
                + " AND MONTH(e.entry_date) BETWEEN ? AND ?",
                AeatExtraModelsService::mapSoportado,
                companyId, year, mFrom, mTo);

        BigDecimal baseSoportada = sop.base04.add(sop.base10).add(sop.base21);
        // Total cuota devengada (casilla 27) = régimen general + modificación.
        // Las demás casillas del devengado (intracom 11, inversión 13, recargo…)
        // se editan en la UI y se suman ahí; aquí salen 0 desde la contabilidad.
        BigDecimal totalDevengado = rep.totalIva.add(modCuota).setScale(2, RoundingMode.HALF_UP);
        // Total a deducir (casilla 45). Igual: bienes de inversión, intracom,
        // importaciones… se añaden en la UI.
        BigDecimal totalDeducible = sop.totalIva.setScale(2, RoundingMode.HALF_UP);
        // Resultado del régimen general (casilla 46/64) = 27 - 45.
        BigDecimal resultadoRegimen = totalDevengado.subtract(totalDeducible);
        // IVA-COMP: cuotas a compensar de periodos anteriores (casilla 110).
        BigDecimal compensacionPrevia = resolveCompensacionPrevia(companyId, year, quarter);
        Compensacion303 comp = aplicarCompensacion(resultadoRegimen, compensacionPrevia);

        Map<String, Object> casillas = new LinkedHashMap<>();
        // Devengado — régimen general (bases por tipo) + modificación (14/15).
        casillas.put("base_4", rep.base04);
        casillas.put("base_10", rep.base10);
        casillas.put("base_21", rep.base21);
        casillas.put("14_mod_base", modBase);
        casillas.put("15_mod_cuota", modCuota);
        casillas.put("06_iva_repercutido_total", rep.totalIva);
        casillas.put("27_total_devengado", totalDevengado);
        // Deducible.
        casillas.put("base_soportado", baseSoportada);
        casillas.put("cuota_soportada", sop.totalIva);
        casillas.put("45_total_deducible", totalDeducible);
        // Resultado + compensación.
        casillas.put("46_resultado_regimen", resultadoRegimen);
        casillas.put("110_compensar_anteriores", comp.compensacionPrevia());
        casillas.put("78_compensacion_aplicada", comp.aplicada());
        casillas.put("71_resultado", comp.resultado());
        casillas.put("87_remanente_compensar", comp.remanente());

        Model303View view = new Model303View(year, quarter, rep, sop,
                baseSoportada, resultadoRegimen, comp.compensacionPrevia(),
                comp.aplicada(), comp.resultado(), comp.remanente(), casillas);
        if (persist) persistFiling("303", year, quarter, view, comp.resultado());
        return view;
    }

    /**
     * IVA-COMP — cuotas de IVA a compensar disponibles AL INICIO del
     * trimestre (year, quarter) = casilla 110.
     *
     * <p>Se arrastra desde la DECLARACIÓN ANTERIOR presentada, no
     * recalculando la contabilidad: la casilla 110 de un trimestre es el
     * remanente (casilla 87) del 303 anterior. Es lo correcto porque lo
     * que se compensa es lo que se DECLARÓ (el asesor puede haber tecleado
     * el 303 a mano si la contabilidad no está completa), y evita el bug
     * en el que un régimen recalculado a 0 nunca consumía el saldo.
     *
     * <p>Si no hay 303 anterior, se usa el saldo inicial
     * (vat_compensation_baseline) cuando su corte cubre el trimestre.
     */
    private BigDecimal resolveCompensacionPrevia(String companyId, int year, int quarter) {
        // 1) Remanente (casilla 87) del 303 inmediatamente anterior.
        List<String> prior = jdbcTemplate.query("""
                SELECT data FROM tax_filings
                 WHERE company_id = ? AND tax_model_code = '303'
                   AND period_quarter IS NOT NULL
                   AND (period_year < ? OR (period_year = ? AND period_quarter < ?))
                 ORDER BY period_year DESC, period_quarter DESC
                 LIMIT 1
                """, (rs, n) -> rs.getString("data"), companyId, year, year, quarter);
        if (!prior.isEmpty() && prior.get(0) != null) {
            BigDecimal rem = extractRemanente303(prior.get(0));
            if (rem != null) return rem;
        }
        // 2) Saldo inicial de partida, si su corte cubre este trimestre.
        List<BigDecimal[]> base = jdbcTemplate.query("""
                SELECT opening_balance, as_of_year, as_of_quarter
                  FROM vat_compensation_baseline WHERE company_id = ?
                """, (rs, n) -> new BigDecimal[]{
                        rs.getBigDecimal("opening_balance"),
                        BigDecimal.valueOf(rs.getInt("as_of_year")),
                        BigDecimal.valueOf(rs.getInt("as_of_quarter"))}, companyId);
        if (!base.isEmpty()) {
            int by = base.get(0)[1].intValue(), bq = base.get(0)[2].intValue();
            if (by < year || (by == year && bq <= quarter)) {
                return nz(base.get(0)[0]);
            }
        }
        return BigDecimal.ZERO;
    }

    /**
     * Remanente a compensar (casilla 87) que dejó una declaración 303
     * guardada. Prefiere el valor almacenado; si no está (declaración
     * tecleada por la UI, que guarda las cuotas), lo deriva del régimen
     * (repercutido − soportado) y la compensación previa que declaró.
     * Robusto a las dos formas del JSON (vista del backend y mapa de la UI).
     */
    static BigDecimal extractRemanente303(String json) {
        BigDecimal direct = jsonNum(json, "remanenteCompensar", "87_remanente_compensar");
        if (direct != null) return direct;
        BigDecimal previa = nz(jsonNum(json, "110_compensar_anteriores", "compensar_anteriores"));
        // Resultado del régimen (casilla 46): preferir el guardado, que ya
        // incluye modificación de bases/cuotas y demás casillas. Si no está
        // (declaraciones antiguas), derivarlo del repercutido − soportado.
        BigDecimal regimen = jsonNum(json, "46_resultado_regimen", "resultadoRegimen", "resultado_regimen");
        if (regimen == null) {
            BigDecimal repercutido = jsonNum(json, "27_total_devengado", "06_iva_repercutido_total");
            if (repercutido == null) {
                repercutido = nz(jsonNum(json, "cuota_21")).add(nz(jsonNum(json, "cuota_10")))
                        .add(nz(jsonNum(json, "cuota_4")));
            }
            BigDecimal soportado = nz(jsonNum(json, "cuota_soportada"));
            regimen = repercutido.subtract(soportado);
        }
        return aplicarCompensacion(regimen, previa).remanente();
    }

    /** Primer valor numérico de las claves dadas en un JSON (con o sin comillas). */
    private static BigDecimal jsonNum(String json, String... keys) {
        if (json == null) return null;
        for (String k : keys) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("\"" + java.util.regex.Pattern.quote(k) + "\"\\s*:\\s*\"?(-?[0-9]+(?:[.,][0-9]+)?)\"?")
                    .matcher(json);
            if (m.find()) {
                try { return new BigDecimal(m.group(1).replace(",", ".")); }
                catch (NumberFormatException ignored) { /* siguiente clave */ }
            }
        }
        return null;
    }

    // ====================================================================
    //  Modelo 130 (IRPF pago fraccionado, estimación directa) — acumulado
    //  del año hasta el trimestre. Pagos previos = suma de los 130
    //  anteriores del año (si no hay, 0 y el asesor lo edita a mano).
    // ====================================================================

    @Transactional
    public Model130View generate130(int year, int quarter, boolean persist) {
        String companyId = tenantContext.getCurrentCompanyId();
        int mTo = quarter * 3; // acumulado: de enero al fin del trimestre

        BigDecimal ingresos = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(subtotal), 0) FROM sales_invoices
                 WHERE company_id = ? AND YEAR(invoice_date) = ?
                   AND MONTH(invoice_date) <= ? AND status = 'VALIDATED'
                """, BigDecimal.class, companyId, year, mTo);
        BigDecimal gastos = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(base_amount), 0) FROM purchase_invoices
                 WHERE company_id = ? AND YEAR(invoice_date) = ?
                   AND MONTH(invoice_date) <= ?
                """, BigDecimal.class, companyId, year, mTo);
        // Retenciones IRPF que los clientes practicaron al autónomo en sus
        // facturas emitidas (acumulado del año).
        BigDecimal retenciones = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(retention_total), 0) FROM sales_invoices
                 WHERE company_id = ? AND YEAR(invoice_date) = ?
                   AND MONTH(invoice_date) <= ? AND status = 'VALIDATED'
                """, BigDecimal.class, companyId, year, mTo);
        // Pagos fraccionados previos = resultado de los 130 de trimestres
        // anteriores del mismo año ya guardados.
        BigDecimal pagosPrevios = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(total_amount), 0) FROM tax_filings
                 WHERE company_id = ? AND tax_model_code = '130'
                   AND period_year = ? AND period_quarter < ?
                """, BigDecimal.class, companyId, year, quarter);

        Model130Calc calc = compute130(ingresos, gastos, retenciones, pagosPrevios);

        Model130View view = new Model130View(year, quarter,
                ingresos, gastos,
                calc.gastosDificilJustificacion(), calc.rendimientoNeto(), calc.cuota(),
                retenciones, pagosPrevios, calc.pago());
        if (persist) persistFiling("130", year, quarter, view, calc.pago());
        return view;
    }

    /**
     * Calculo PURO del modelo 130 (IRPF pago fraccionado, estimacion
     * directa simplificada). Separado del acceso a BD para poder testear
     * con datos reales. Reproduce el modelo oficial:
     *
     * <pre>
     *   rendimiento previo   = ingresos - gastos
     *   gastos dificil just. = 5% del rendimiento previo POSITIVO, tope 2.000 EUR
     *   rendimiento neto     = rendimiento previo - gastos dificil just.
     *   cuota                = 20% del rendimiento neto POSITIVO
     *   pago                 = cuota - retenciones - pagos fraccionados previos
     *   resultado            = max(pago, 0)   (un trimestre negativo se
     *                          declara 0; lo negativo se arrastra por la
     *                          casilla 15 en trimestres siguientes)
     * </pre>
     *
     * Los importes de entrada son ACUMULADOS del año hasta el trimestre;
     * el tope de 2.000 EUR es anual y se aplica sobre el acumulado.
     */
    public static Model130Calc compute130(BigDecimal ingresos, BigDecimal gastos,
                                          BigDecimal retenciones, BigDecimal pagosPrevios) {
        BigDecimal ing = nz(ingresos);
        BigDecimal gas = nz(gastos);
        BigDecimal ret = nz(retenciones);
        BigDecimal prev = nz(pagosPrevios);

        BigDecimal rendimientoPrevio = ing.subtract(gas);
        BigDecimal gastosDificil = BigDecimal.ZERO;
        if (rendimientoPrevio.signum() > 0) {
            gastosDificil = rendimientoPrevio.multiply(GASTOS_DIFICIL_JUSTIFICACION_PCT)
                    .setScale(2, RoundingMode.HALF_UP);
            if (gastosDificil.compareTo(GASTOS_DIFICIL_JUSTIFICACION_TOPE) > 0) {
                gastosDificil = GASTOS_DIFICIL_JUSTIFICACION_TOPE;
            }
        }
        BigDecimal rendimientoNeto = rendimientoPrevio.subtract(gastosDificil);
        BigDecimal cuota = rendimientoNeto.signum() > 0
                ? rendimientoNeto.multiply(MODELO_130_TIPO).setScale(2, RoundingMode.HALF_UP)
                : BigDecimal.ZERO;
        BigDecimal pago = cuota.subtract(ret).subtract(prev).setScale(2, RoundingMode.HALF_UP);
        if (pago.signum() < 0) pago = BigDecimal.ZERO;
        return new Model130Calc(rendimientoPrevio, gastosDificil, rendimientoNeto, cuota, pago);
    }

    public record Model130Calc(
            BigDecimal rendimientoPrevio,
            BigDecimal gastosDificilJustificacion,
            BigDecimal rendimientoNeto,
            BigDecimal cuota,
            BigDecimal pago
    ) {}

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
        String nif;
        BigDecimal q1 = BigDecimal.ZERO, q2 = BigDecimal.ZERO,
                   q3 = BigDecimal.ZERO, q4 = BigDecimal.ZERO,
                   total = BigDecimal.ZERO;
        NifTotals(String n) { this(n, ""); }
        NifTotals(String n, String nif) { this.name = n; this.nif = nif == null ? "" : nif; }
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

    public record Model303View(
            int year, int quarter,
            VatBreakdown repercutido, VatBreakdown soportado,
            BigDecimal baseSoportada,
            // IVA-COMP: resultado del régimen (46) + compensación (110/78) +
            // resultado final (71) + remanente para el siguiente trimestre (87).
            BigDecimal resultadoRegimen,
            BigDecimal compensacionPrevia, BigDecimal compensacionAplicada,
            BigDecimal resultado, BigDecimal remanenteCompensar,
            Map<String, Object> casillas
    ) {}

    public record Model130View(
            int year, int quarter,
            BigDecimal ingresos, BigDecimal gastos,
            // MOD-130-FIX (2026-07-08): campos derivados del calculo — el 5%
            // de gastos de dificil justificacion, el rendimiento neto y la
            // cuota, para casar con las casillas del modelo 130 de la AEAT.
            BigDecimal gastosDificilJustificacion,
            BigDecimal rendimientoNeto, BigDecimal cuota,
            BigDecimal retenciones, BigDecimal pagosPrevios,
            BigDecimal pago
    ) {}
}
