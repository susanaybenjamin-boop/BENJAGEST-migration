package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Detecta facturas (emitidas o recibidas) que se repiten con el mismo
 * tercero + mismo importe en una ventana de tiempo, y propone
 * convertirlas en una tarea recurrente.
 *
 * <p>Criterio (Benjamin, 2026-06-07): {@code partyNif + total} idénticos.
 * Si el alquiler sube de 450 a 460 el sistema NO los agrupa — se trata
 * como un cambio real que requiere crear una recurrente nueva o editar
 * la existente. Sin tolerancia.
 *
 * <p>La frecuencia sugerida se infiere del gap medio entre fechas:
 * <ul>
 *   <li>≤14 días → WEEKLY</li>
 *   <li>≤45 días → MONTHLY</li>
 *   <li>≤105 días → QUARTERLY</li>
 *   <li>≤200 días → CUSTOM/6 meses</li>
 *   <li>{@literal >}200 → YEARLY</li>
 * </ul>
 *
 * <p>Excluye candidatos para los que ya existe una recurrente activa
 * apuntando al mismo tercero + mismo importe — de lo contrario el
 * banner repetiría la sugerencia cada vez que se genere una nueva.
 */
@Service
public class RecurringCandidateService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final RecurringCandidateIgnoredService ignoredService;

    public RecurringCandidateService(JdbcTemplate jdbcTemplate,
                                     TenantContext tenantContext,
                                     RecurringCandidateIgnoredService ignoredService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.ignoredService = ignoredService;
    }

    /**
     * Slice 3P — ¿Hay alguna recurrente activa del kind y tercero
     * indicados con un importe cercano? El UI lo usa para evitar
     * preguntar "¿hacer recurrente?" tras validar una factura que YA
     * está cubierta por una plantilla.
     *
     * <p>Compara substring sobre el payload_json (mismo enfoque que
     * filterOutAlreadyRecurring). NO se compara NIF cuando viene
     * vacío — en su lugar solo legalName + importe.
     */
    public boolean alreadyCovers(String kind, String partyName,
                                  java.math.BigDecimal amount) {
        return alreadyCovers(kind, null, partyName, amount);
    }

    /**
     * Igual que {@link #alreadyCovers(String, String, java.math.BigDecimal)} pero
     * además devuelve true si el usuario YA descartó esta sugerencia (la silenció
     * desde el prompt post-validación o el banner). Así "si ya dije que no" deja de
     * preguntar para ese tercero+importe (petición Benjamin 2026-06-25).
     */
    public boolean alreadyCovers(String kind, String partyNif, String partyName,
                                  java.math.BigDecimal amount) {
        if (ignoredService.isIgnored(kind, partyNif, partyName, amount)) return true;
        String companyId = tenantContext.getCurrentCompanyId();
        if (companyId == null || amount == null) return false;
        if (partyName == null || partyName.isBlank()) return false;
        List<String> payloads = jdbcTemplate.query("""
                SELECT payload_json
                  FROM recurring_tasks
                 WHERE company_id = ?
                   AND kind = ?
                   AND active = TRUE
                """, (rs, n) -> rs.getString("payload_json"), companyId, kind);
        String trimmedName = partyName.trim().toLowerCase();
        String amountKey = "SALES_INVOICE".equals(kind) ? "unitPrice" : "baseAmount";
        String amountStr = amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString();
        String amountStrPlain = amount.toPlainString();
        for (String pj : payloads) {
            if (pj == null) continue;
            String pjLower = pj.toLowerCase();
            // Comparar nombre tercero (puede estar en customerLegalName
            // para SALES o supplierName para PURCHASE).
            boolean nameMatch = pjLower.contains(trimmedName);
            boolean amountMatch = pj.contains(amountKey + "\":" + amountStr)
                    || pj.contains(amountKey + "\":" + amountStrPlain);
            if (nameMatch && amountMatch) return true;
        }
        return false;
    }

    /**
     * Lista los candidatos de recurrencia en la ventana indicada.
     *
     * @param kind        "SALES_INVOICE" o "PURCHASE".
     * @param windowDays  ventana de análisis hacia atrás. 180 por
     *                    defecto (suficiente para captar mensuales,
     *                    trimestrales y semestrales).
     */
    public List<Candidate> findCandidates(String kind, int windowDays) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (companyId == null) return List.of();
        LocalDate from = LocalDate.now().minusDays(Math.max(30, windowDays));

        List<Candidate> raw = switch (kind == null ? "" : kind.toUpperCase()) {
            case "SALES_INVOICE" -> findSalesCandidates(companyId, from);
            case "PURCHASE" -> findPurchaseCandidates(companyId, from);
            default -> List.of();
        };
        // REC-IGNORE: filtra los silenciados activos (ignore_until NULL
        // o futuro). Lo hacemos en memoria — la lista de candidatos es
        // siempre pequeña (decenas) y mantenemos el código del SQL
        // simple.
        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : raw) {
            if (!ignoredService.isIgnored(c.kind, c.partyNif, c.partyName, c.totalAmount)) {
                kept.add(c);
            }
        }
        return kept;
    }

    /**
     * Agrupa facturas emitidas no anuladas por (customer, total) y
     * devuelve grupos con ≥2 ocurrencias.
     */
    private List<Candidate> findSalesCandidates(String companyId, LocalDate from) {
        // Solo facturas vivas — anuladas/rectificadas no cuentan como
        // patrón recurrente.
        String sql = """
                SELECT si.customer_id,
                       c.tax_identifier AS party_nif,
                       c.legal_name     AS party_name,
                       si.total         AS total,
                       COUNT(*)         AS occurrences,
                       MIN(si.invoice_date) AS first_date,
                       MAX(si.invoice_date) AS last_date,
                       MAX(si.id)       AS sample_id
                  FROM sales_invoices si
                  JOIN customers c ON c.id = si.customer_id
                 WHERE si.company_id = ?
                   AND si.invoice_date >= ?
                   AND si.status IN ('VALIDATED', 'DRAFT')
                   AND si.total > 0
                 GROUP BY si.customer_id, c.tax_identifier, c.legal_name, si.total
                HAVING COUNT(*) >= 2
                 ORDER BY occurrences DESC, last_date DESC
                """;

        List<Candidate> raw = jdbcTemplate.query(sql, (rs, n) -> {
            Candidate c = new Candidate();
            c.kind = "SALES_INVOICE";
            c.partyId = rs.getString("customer_id");
            c.partyNif = rs.getString("party_nif");
            c.partyName = rs.getString("party_name");
            c.totalAmount = rs.getBigDecimal("total");
            c.occurrences = rs.getInt("occurrences");
            c.firstDate = rs.getDate("first_date").toLocalDate();
            c.lastDate = rs.getDate("last_date").toLocalDate();
            c.sampleInvoiceId = rs.getString("sample_id");
            c.suggestedFrequency = inferFrequency(c.firstDate, c.lastDate, c.occurrences);
            return c;
        }, companyId, java.sql.Date.valueOf(from));

        return filterOutAlreadyRecurring(raw, companyId, "SALES_INVOICE");
    }

    /**
     * Agrupa facturas recibidas por (supplier_nif, total_amount).
     * Nota: V45 eliminó la columna {@code supplier_id} de
     * {@code purchase_invoices} — ahora todo el tercero vive en
     * {@code supplier_nif} + {@code supplier_name}. También usamos
     * {@code total_amount} en lugar del antiguo {@code total}.
     */
    private List<Candidate> findPurchaseCandidates(String companyId, LocalDate from) {
        String sql = """
                SELECT COALESCE(pi.supplier_nif, '')  AS party_nif,
                       COALESCE(pi.supplier_name, '') AS party_name,
                       pi.total_amount               AS total,
                       COUNT(*)                       AS occurrences,
                       MIN(pi.invoice_date)           AS first_date,
                       MAX(pi.invoice_date)           AS last_date,
                       MAX(pi.id)                     AS sample_id
                  FROM purchase_invoices pi
                 WHERE pi.company_id = ?
                   AND pi.invoice_date >= ?
                   AND pi.total_amount > 0
                 GROUP BY COALESCE(pi.supplier_nif, ''),
                          COALESCE(pi.supplier_name, ''),
                          pi.total_amount
                HAVING COUNT(*) >= 2
                 ORDER BY occurrences DESC, last_date DESC
                """;

        List<Candidate> raw = jdbcTemplate.query(sql, (rs, n) -> {
            Candidate c = new Candidate();
            c.kind = "PURCHASE";
            c.partyId = null; // ya no hay supplier_id en purchase_invoices
            c.partyNif = rs.getString("party_nif");
            c.partyName = rs.getString("party_name");
            c.totalAmount = rs.getBigDecimal("total");
            c.occurrences = rs.getInt("occurrences");
            c.firstDate = rs.getDate("first_date").toLocalDate();
            c.lastDate = rs.getDate("last_date").toLocalDate();
            c.sampleInvoiceId = rs.getString("sample_id");
            c.suggestedFrequency = inferFrequency(c.firstDate, c.lastDate, c.occurrences);
            return c;
        }, companyId, java.sql.Date.valueOf(from));

        return filterOutAlreadyRecurring(raw, companyId, "PURCHASE");
    }

    /**
     * Quita los candidatos cuyo tercero + importe ya están cubiertos
     * por una tarea recurrente activa. Lo hacemos en memoria porque
     * el payload de la recurrente es JSON y filtrarlo en SQL nativo
     * (sin JSON_VALUE en compat con MariaDB 10.3) sería frágil.
     */
    private List<Candidate> filterOutAlreadyRecurring(List<Candidate> candidates,
                                                       String companyId,
                                                       String kind) {
        if (candidates.isEmpty()) return candidates;
        // Cargo todas las recurrentes activas del mismo kind una vez.
        List<RecurringSnapshot> existing = jdbcTemplate.query("""
                SELECT payload_json
                  FROM recurring_tasks
                 WHERE company_id = ?
                   AND kind = ?
                   AND active = TRUE
                """, (rs, n) -> {
            RecurringSnapshot r = new RecurringSnapshot();
            r.payloadJson = rs.getString("payload_json");
            return r;
        }, companyId, kind);

        List<Candidate> kept = new ArrayList<>();
        for (Candidate c : candidates) {
            boolean covered = existing.stream().anyMatch(e ->
                    payloadMatches(e.payloadJson, c, kind));
            if (!covered) kept.add(c);
        }
        return kept;
    }

    /**
     * Heurística superficial sobre el payload JSON: hay match si
     * el payload contiene el NIF del tercero candidato Y un importe
     * total cercano (mismo a 2 decimales).
     */
    private boolean payloadMatches(String payloadJson, Candidate c, String kind) {
        if (payloadJson == null) return false;
        String nifKey = "SALES_INVOICE".equals(kind) ? "customerNif" : "supplierNif";
        String totalKey = "SALES_INVOICE".equals(kind) ? "unitPrice" : "totalAmount";
        if (c.partyNif == null || c.partyNif.isBlank()) return false;
        boolean nifOk = payloadJson.contains("\"" + nifKey + "\":\"" + c.partyNif + "\"");
        if (!nifOk) return false;
        // El importe en el JSON puede llevar varios formatos (450.0,
        // 450.00, "450.00"). Solo comparamos por substring "valor."
        // para ser tolerante con representaciones equivalentes.
        String exact = c.totalAmount.setScale(2,
                java.math.RoundingMode.HALF_UP).toPlainString();
        return payloadJson.contains(totalKey + "\":" + exact)
                || payloadJson.contains(totalKey + "\":" + c.totalAmount.toPlainString());
    }

    private String inferFrequency(LocalDate first, LocalDate last, int occurrences) {
        if (first == null || last == null || occurrences < 2) return "MONTHLY";
        long totalDays = ChronoUnit.DAYS.between(first, last);
        long avgGap = totalDays / Math.max(1, occurrences - 1);
        if (avgGap <= 14) return "WEEKLY";
        if (avgGap <= 45) return "MONTHLY";
        if (avgGap <= 105) return "QUARTERLY";
        if (avgGap <= 200) return "CUSTOM"; // semestral aprox. (UI usa monthsBetween=6)
        return "YEARLY";
    }

    /** Salida del endpoint para que la UI muestre el banner y pre-rellene el editor. */
    public static class Candidate {
        public String kind;
        public String partyId;
        public String partyNif;
        public String partyName;
        public BigDecimal totalAmount;
        public int occurrences;
        public LocalDate firstDate;
        public LocalDate lastDate;
        public String sampleInvoiceId;
        public String suggestedFrequency;

        public String getKind() { return kind; }
        public String getPartyId() { return partyId; }
        public String getPartyNif() { return partyNif; }
        public String getPartyName() { return partyName; }
        public BigDecimal getTotalAmount() { return totalAmount; }
        public int getOccurrences() { return occurrences; }
        public LocalDate getFirstDate() { return firstDate; }
        public LocalDate getLastDate() { return lastDate; }
        public String getSampleInvoiceId() { return sampleInvoiceId; }
        public String getSuggestedFrequency() { return suggestedFrequency; }
    }

    private static class RecurringSnapshot {
        String payloadJson;
    }
}
