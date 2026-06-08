package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CTR-3 — Plantillas reutilizables de contrato.
 *
 * <p>Una plantilla pre-rellena los 4 pasos del wizard (CTR-2) para que el
 * asesor no tenga que reescribir cada vez los mismos datos cuando da de
 * alta a varios empleados con el mismo perfil (ej. todos los camareros
 * del mismo restaurante o varios oficiales de 1ª en una constructora).
 *
 * <p>Ámbito por asesoría (company_id). Solo el OWNER/ADMIN puede crearlas.
 * Las plantillas NUNCA se borran en duro: soft-delete con active=FALSE
 * para mantener la integridad de las analíticas históricas si en algún
 * momento las usamos.
 */
@Service
public class ContractTemplateService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public ContractTemplateService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    public List<View> list() {
        return jdbc.query("""
                SELECT id, name, description,
                       sepe_contract_code, contract_type,
                       collective_agreement_id, professional_category_id, professional_group,
                       weekly_hours, gross_salary, annual_bonuses, vacation_days,
                       irpf_percent, probation_days, workplace_address,
                       clause_codes, pdf_model, is_built_in, active
                  FROM contract_templates
                 WHERE company_id = ? AND active = TRUE
                 ORDER BY name
                """, this::map, tenant.getCurrentCompanyId());
    }

    @Transactional
    public View create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO contract_templates (
                    id, company_id, name, description,
                    sepe_contract_code, contract_type,
                    collective_agreement_id, professional_category_id, professional_group,
                    weekly_hours, gross_salary, annual_bonuses, vacation_days,
                    irpf_percent, probation_days, workplace_address,
                    clause_codes, pdf_model, is_built_in, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, TRUE)
                """,
                id, tenant.getCurrentCompanyId(), req.name(), nz(req.description()),
                nz(req.sepeContractCode()), nz(req.contractType()),
                nz(req.collectiveAgreementId()), nz(req.professionalCategoryId()),
                nz(req.professionalGroup()),
                req.weeklyHours(), req.grossSalary(),
                req.annualBonuses(), req.vacationDays(),
                req.irpfPercent(), req.probationDays(),
                nz(req.workplaceAddress()),
                nz(req.clauseCodes()),  // JSON array stringified by UI
                nz(req.pdfModel())
        );
        return findById(id);
    }

    @Transactional
    public View update(String id, UpsertRequest req) {
        validate(req);
        int n = jdbc.update("""
                UPDATE contract_templates
                   SET name = ?, description = ?,
                       sepe_contract_code = ?, contract_type = ?,
                       collective_agreement_id = ?, professional_category_id = ?, professional_group = ?,
                       weekly_hours = ?, gross_salary = ?, annual_bonuses = ?, vacation_days = ?,
                       irpf_percent = ?, probation_days = ?, workplace_address = ?,
                       clause_codes = ?, pdf_model = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.name(), nz(req.description()),
                nz(req.sepeContractCode()), nz(req.contractType()),
                nz(req.collectiveAgreementId()), nz(req.professionalCategoryId()),
                nz(req.professionalGroup()),
                req.weeklyHours(), req.grossSalary(),
                req.annualBonuses(), req.vacationDays(),
                req.irpfPercent(), req.probationDays(),
                nz(req.workplaceAddress()),
                nz(req.clauseCodes()),
                nz(req.pdfModel()),
                id, tenant.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plantilla no encontrada");
        }
        return findById(id);
    }

    @Transactional
    public void delete(String id) {
        int n = jdbc.update("""
                UPDATE contract_templates SET active = FALSE
                 WHERE id = ? AND company_id = ?
                """, id, tenant.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plantilla no encontrada");
        }
    }

    private View findById(String id) {
        return jdbc.query("""
                SELECT id, name, description,
                       sepe_contract_code, contract_type,
                       collective_agreement_id, professional_category_id, professional_group,
                       weekly_hours, gross_salary, annual_bonuses, vacation_days,
                       irpf_percent, probation_days, workplace_address,
                       clause_codes, pdf_model, is_built_in, active
                  FROM contract_templates
                 WHERE id = ? AND company_id = ?
                """, this::map, id, tenant.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plantilla no encontrada"));
    }

    private View map(ResultSet rs, int rowNum) throws SQLException {
        return new View(
                rs.getString("id"),
                rs.getString("name"),
                rs.getString("description"),
                rs.getString("sepe_contract_code"),
                rs.getString("contract_type"),
                rs.getString("collective_agreement_id"),
                rs.getString("professional_category_id"),
                rs.getString("professional_group"),
                rs.getBigDecimal("weekly_hours"),
                rs.getBigDecimal("gross_salary"),
                (Integer) rs.getObject("annual_bonuses"),
                (Integer) rs.getObject("vacation_days"),
                rs.getBigDecimal("irpf_percent"),
                (Integer) rs.getObject("probation_days"),
                rs.getString("workplace_address"),
                rs.getString("clause_codes"),
                rs.getString("pdf_model"),
                rs.getBoolean("is_built_in"),
                rs.getBoolean("active")
        );
    }

    private void validate(UpsertRequest req) {
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre de la plantilla es obligatorio");
        }
    }

    private String nz(String v) { return v == null || v.isBlank() ? null : v.trim(); }

    public record View(
            String id, String name, String description,
            String sepeContractCode, String contractType,
            String collectiveAgreementId, String professionalCategoryId, String professionalGroup,
            BigDecimal weeklyHours, BigDecimal grossSalary,
            Integer annualBonuses, Integer vacationDays,
            BigDecimal irpfPercent, Integer probationDays,
            String workplaceAddress,
            String clauseCodes, String pdfModel,
            boolean isBuiltIn, boolean active
    ) {}

    public record UpsertRequest(
            String name, String description,
            String sepeContractCode, String contractType,
            String collectiveAgreementId, String professionalCategoryId, String professionalGroup,
            BigDecimal weeklyHours, BigDecimal grossSalary,
            Integer annualBonuses, Integer vacationDays,
            BigDecimal irpfPercent, Integer probationDays,
            String workplaceAddress,
            String clauseCodes, String pdfModel
    ) {}

    @RestController
    @RequestMapping("/api/contracts/templates")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN"})
    public static class Controller {
        private final ContractTemplateService service;

        public Controller(ContractTemplateService service) { this.service = service; }

        @GetMapping
        public List<View> list() { return service.list(); }

        @PostMapping
        public View create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public View update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
