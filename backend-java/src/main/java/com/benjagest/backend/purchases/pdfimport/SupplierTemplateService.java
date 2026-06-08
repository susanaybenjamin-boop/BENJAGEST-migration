package com.benjagest.backend.purchases.pdfimport;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Servicio de plantillas de extracción por proveedor.
 *
 * Estrategia mínima viable:
 *   - rules es un JSON plano con {fieldKey: value}.
 *   - field_keys soportados:
 *       "supplierName"  → sobrescribe el nombre del proveedor.
 *       "vatPercent"    → porcentaje de IVA fijo (ej. "21" o "10").
 *   - Aplicación: si el extractor genérico detecta NIF emisor, se
 *     busca template para (company, supplier_nif). Si existe, los
 *     valores de rules sobrescriben los del extractor.
 *
 * Aprendizaje: el endpoint POST acepta directamente el JSON de rules
 * que la UI compone con las correcciones del operador. La lógica de
 * "qué guardar" la decide la UI (campos cambiados que el operador
 * marca como persistentes).
 *
 * Sub-slice futuro: estrategias "label_after" y "regex_capture" para
 * extraer dinámicamente (número de factura, fecha) — esos cambian
 * cada vez aunque el formato sea el mismo.
 */
@Service
public class SupplierTemplateService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;

    public SupplierTemplateService(JdbcTemplate jdbcTemplate,
                                     TenantContext tenantContext,
                                     CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
    }

    public List<TemplateView> list() {
        return jdbcTemplate.query("""
                SELECT id, supplier_nif, supplier_name, rules, uses_count,
                       last_used_at, created_at, updated_at
                  FROM supplier_extraction_templates
                 WHERE company_id = ?
                 ORDER BY uses_count DESC, supplier_name
                """, this::mapView, tenantContext.getCurrentCompanyId());
    }

    public TemplateView findByNif(String nif) {
        if (!StringUtils.hasText(nif)) return null;
        return jdbcTemplate.query("""
                SELECT id, supplier_nif, supplier_name, rules, uses_count,
                       last_used_at, created_at, updated_at
                  FROM supplier_extraction_templates
                 WHERE company_id = ? AND supplier_nif = ?
                """, this::mapView,
                tenantContext.getCurrentCompanyId(), nif.toUpperCase())
                .stream().findFirst().orElse(null);
    }

    @Transactional
    public TemplateView saveOrUpdate(SaveRequest req) {
        if (!StringUtils.hasText(req.supplierNif())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "supplierNif requerido");
        }
        String nif = req.supplierNif().toUpperCase();
        String userId;
        try { userId = currentUserService.require().userId(); }
        catch (Exception ex) { userId = null; }

        TemplateView existing = findByNif(nif);
        String rules = req.rulesJson() == null ? "{}" : req.rulesJson();
        if (existing != null) {
            jdbcTemplate.update("""
                    UPDATE supplier_extraction_templates
                       SET supplier_name = ?, rules = ?, last_used_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND company_id = ?
                    """, req.supplierName(), rules,
                    existing.id(), tenantContext.getCurrentCompanyId());
            return findByNif(nif);
        }
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO supplier_extraction_templates (
                    id, company_id, supplier_nif, supplier_name, rules,
                    uses_count, created_by_user_id
                ) VALUES (?, ?, ?, ?, ?, 0, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                nif, req.supplierName(), rules, userId);
        return findByNif(nif);
    }

    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                DELETE FROM supplier_extraction_templates
                 WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plantilla no encontrada");
    }

    /**
     * Aplica una plantilla a un resultado de extracción genérico,
     * sobrescribiendo los campos que la plantilla tiene definidos.
     * Devuelve un nuevo {@link InvoiceFieldsExtractor.ExtractionResult}.
     */
    public InvoiceFieldsExtractor.ExtractionResult apply(
            InvoiceFieldsExtractor.ExtractionResult base, TemplateView template) {
        if (template == null) return base;
        Map<String, String> rules = parseRules(template.rules());

        String supplierName = StringUtils.hasText(rules.get("supplierName"))
                ? rules.get("supplierName") : base.supplierName();
        BigDecimal vatPct = rules.containsKey("vatPercent")
                ? toBigDecimal(rules.get("vatPercent")) : base.vatPercent();
        // El extractor puede haber detectado un NIF "equivocado pero
        // consistente" (p. ej. el LU de Amazon en vez del W español). La
        // plantilla se buscó por ese NIF detectado; si rules trae
        // emitterNif, lo sobrescribimos para mostrar el correcto.
        String emitterNif = StringUtils.hasText(rules.get("emitterNif"))
                ? rules.get("emitterNif") : base.emitterNif();

        // Incrementar uso de forma asíncrona — fire and forget
        try {
            jdbcTemplate.update("""
                    UPDATE supplier_extraction_templates
                       SET uses_count = uses_count + 1, last_used_at = CURRENT_TIMESTAMP
                     WHERE id = ? AND company_id = ?
                    """, template.id(), tenantContext.getCurrentCompanyId());
        } catch (Exception ignored) { /* no bloquear si falla */ }

        return new InvoiceFieldsExtractor.ExtractionResult(
                base.allDetectedNifs(),
                emitterNif,
                supplierName,
                base.invoiceNumber(),
                base.invoiceDate(),
                base.baseAmount(),
                vatPct,
                base.vatAmount(),
                base.totalAmount(),
                base.documentSha256(),
                base.confidence(),
                base.rawTextHead(),
                base.receiverNif(),
                base.receiverName(),
                base.rectifying(),
                base.rectifiedInvoiceNumber()
        );
    }

    private Map<String, String> parseRules(String json) {
        Map<String, String> out = new HashMap<>();
        if (json == null || json.isBlank()) return out;
        Pattern p = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"((?:\\\\.|[^\"])*)\"");
        Matcher m = p.matcher(json);
        while (m.find()) {
            out.put(m.group(1), m.group(2).replace("\\\"", "\""));
        }
        return out;
    }

    private BigDecimal toBigDecimal(String s) {
        try { return new BigDecimal(s.replace(",", ".").trim()); }
        catch (Exception ex) { return null; }
    }

    private TemplateView mapView(ResultSet rs, int rowNum) throws SQLException {
        Timestamp lu = rs.getTimestamp("last_used_at");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new TemplateView(
                rs.getString("id"),
                rs.getString("supplier_nif"),
                rs.getString("supplier_name"),
                rs.getString("rules"),
                rs.getInt("uses_count"),
                lu == null ? null : lu.toInstant(),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    public record TemplateView(
            String id, String supplierNif, String supplierName, String rules,
            int usesCount, Instant lastUsedAt, Instant createdAt, Instant updatedAt
    ) {}

    public record SaveRequest(
            String supplierNif, String supplierName, String rulesJson
    ) {}

    @RestController
    @RequestMapping("/api/purchases/extraction-templates")
    @RequiresModule("purchases")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class TemplateController {
        private final SupplierTemplateService service;
        public TemplateController(SupplierTemplateService service) { this.service = service; }

        @GetMapping
        public List<TemplateView> list() { return service.list(); }

        @PostMapping
        public TemplateView save(@RequestBody SaveRequest req) { return service.saveOrUpdate(req); }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
