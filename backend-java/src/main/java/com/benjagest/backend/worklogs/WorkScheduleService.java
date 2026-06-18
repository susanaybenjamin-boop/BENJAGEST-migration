package com.benjagest.backend.worklogs;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Comparator;
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
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * JOR-2 — Planificacion de jornada (plantillas de horario).
 *
 * <p>Modelo CONTENDO (plantillas_jornada_180 + plantilla_dias + bloques):
 * 1 plantilla = N bloques horarios por dia de la semana, asignable a M
 * empleados con vigencia. Port fiel del modelo; las excepciones por fecha
 * de CONTENDO quedan para un slice posterior.
 */
@Service
public class WorkScheduleService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;

    public WorkScheduleService(JdbcTemplate jdbc, TenantContext tenant) {
        this.jdbc = jdbc;
        this.tenant = tenant;
    }

    // ---- Plantillas -------------------------------------------------------

    public List<TemplateSummary> listTemplates() {
        String companyId = tenant.getCurrentCompanyId();
        return jdbc.query("""
                SELECT t.id, t.name, COALESCE(t.description, '') AS description,
                       t.active,
                       (SELECT COUNT(*) FROM work_schedule_blocks b WHERE b.template_id = t.id) AS blocks,
                       (SELECT COUNT(*) FROM work_schedule_assignments a
                          WHERE a.template_id = t.id
                            AND (a.effective_to IS NULL OR a.effective_to >= CURRENT_DATE)) AS assignments
                  FROM work_schedule_templates t
                 WHERE t.company_id = ?
                 ORDER BY t.active DESC, t.name
                """,
                (rs, n) -> new TemplateSummary(
                        rs.getString("id"), rs.getString("name"), rs.getString("description"),
                        rs.getBoolean("active"), rs.getInt("blocks"), rs.getInt("assignments")),
                companyId);
    }

    @Transactional
    public String createTemplate(TemplateUpsert req) {
        String companyId = tenant.getCurrentCompanyId();
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nombre obligatorio");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO work_schedule_templates (id, company_id, name, description, active)
                VALUES (?, ?, ?, ?, TRUE)
                """, id, companyId, req.name().trim(), req.description());
        return id;
    }

    @Transactional
    public void updateTemplate(String id, TemplateUpsert req) {
        assertTemplate(id);
        if (req.name() == null || req.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "nombre obligatorio");
        }
        jdbc.update("""
                UPDATE work_schedule_templates
                   SET name = ?, description = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """, req.name().trim(), req.description(),
                req.active() == null || req.active(), id, tenant.getCurrentCompanyId());
    }

    @Transactional
    public void deleteTemplate(String id) {
        assertTemplate(id);
        // ON DELETE CASCADE limpia bloques y asignaciones.
        jdbc.update("DELETE FROM work_schedule_templates WHERE id = ? AND company_id = ?",
                id, tenant.getCurrentCompanyId());
    }

    public TemplateDetail getTemplate(String id) {
        assertTemplate(id);
        TemplateSummary head = listTemplates().stream().filter(t -> t.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Plantilla no encontrada"));
        List<Block> blocks = jdbc.query("""
                SELECT id, weekday, block_type,
                       CAST(start_time AS CHAR) AS start_time,
                       CAST(end_time AS CHAR) AS end_time
                  FROM work_schedule_blocks
                 WHERE template_id = ? AND company_id = ?
                 ORDER BY weekday, start_time
                """,
                (rs, n) -> new Block(rs.getString("id"), rs.getByte("weekday"),
                        rs.getString("block_type"),
                        rs.getString("start_time").substring(0, 5),
                        rs.getString("end_time").substring(0, 5)),
                id, tenant.getCurrentCompanyId());
        return new TemplateDetail(head, blocks);
    }

    // ---- Bloques (reemplazo completo) -------------------------------------

    @Transactional
    public void replaceBlocks(String templateId, List<BlockInput> blocks) {
        assertTemplate(templateId);
        String companyId = tenant.getCurrentCompanyId();
        validateBlocks(blocks);
        jdbc.update("DELETE FROM work_schedule_blocks WHERE template_id = ? AND company_id = ?",
                templateId, companyId);
        int order = 0;
        for (BlockInput b : blocks) {
            jdbc.update("""
                    INSERT INTO work_schedule_blocks
                        (id, company_id, template_id, weekday, block_type, start_time, end_time, display_order)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), companyId, templateId,
                    b.weekday(), normalizeType(b.blockType()),
                    b.startTime() + ":00", b.endTime() + ":00", order++);
        }
    }

    private void validateBlocks(List<BlockInput> blocks) {
        if (blocks == null) return;
        // Por cada dia: hora_fin > hora_inicio y sin solapes (port validateBloques CONTENDO).
        for (int wd = 1; wd <= 7; wd++) {
            final int day = wd;
            List<BlockInput> ofDay = new ArrayList<>(blocks.stream().filter(b -> b.weekday() == day).toList());
            ofDay.sort(Comparator.comparing(b -> LocalTime.parse(b.startTime())));
            LocalTime prevEnd = null;
            for (BlockInput b : ofDay) {
                LocalTime s = LocalTime.parse(b.startTime());
                LocalTime e = LocalTime.parse(b.endTime());
                if (!e.isAfter(s)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Bloque (dia " + day + "): la hora fin debe ser posterior a la de inicio");
                }
                if (prevEnd != null && s.isBefore(prevEnd)) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "Bloque (dia " + day + "): solapa con el bloque anterior");
                }
                prevEnd = e;
            }
        }
    }

    private String normalizeType(String t) {
        return "BREAK".equalsIgnoreCase(t) ? "BREAK" : "WORK";
    }

    // ---- Asignaciones -----------------------------------------------------

    public List<Assignment> listAssignments(String templateId) {
        assertTemplate(templateId);
        return jdbc.query("""
                SELECT a.id, a.employee_id, emp.full_name,
                       CAST(a.effective_from AS CHAR) AS effective_from,
                       CAST(a.effective_to AS CHAR) AS effective_to
                  FROM work_schedule_assignments a
                  JOIN employees emp ON emp.id = a.employee_id
                 WHERE a.template_id = ? AND a.company_id = ?
                 ORDER BY a.effective_from DESC
                """,
                (rs, n) -> new Assignment(rs.getString("id"), rs.getString("employee_id"),
                        rs.getString("full_name"), rs.getString("effective_from"),
                        rs.getString("effective_to")),
                templateId, tenant.getCurrentCompanyId());
    }

    @Transactional
    public String assign(String templateId, AssignRequest req) {
        assertTemplate(templateId);
        if (req.employeeId() == null || req.employeeId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId obligatorio");
        }
        LocalDate from = req.effectiveFrom() != null ? req.effectiveFrom() : LocalDate.now();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO work_schedule_assignments
                    (id, company_id, template_id, employee_id, effective_from, effective_to)
                VALUES (?, ?, ?, ?, ?, ?)
                """, id, tenant.getCurrentCompanyId(), templateId,
                req.employeeId(), from, req.effectiveTo());
        return id;
    }

    @Transactional
    public void removeAssignment(String assignmentId) {
        int n = jdbc.update("DELETE FROM work_schedule_assignments WHERE id = ? AND company_id = ?",
                assignmentId, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asignacion no encontrada");
    }

    private void assertTemplate(String id) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM work_schedule_templates WHERE id = ? AND company_id = ?",
                Integer.class, id, tenant.getCurrentCompanyId());
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Plantilla no encontrada");
        }
    }

    // ---- Records ----------------------------------------------------------

    public record TemplateSummary(String id, String name, String description, boolean active,
                                  int blocks, int assignments) {}
    public record TemplateDetail(TemplateSummary template, List<Block> blocks) {}
    public record Block(String id, int weekday, String blockType, String startTime, String endTime) {}
    public record BlockInput(int weekday, String blockType, String startTime, String endTime) {}
    public record TemplateUpsert(String name, String description, Boolean active) {}
    public record Assignment(String id, String employeeId, String employeeName,
                             String effectiveFrom, String effectiveTo) {}
    public record AssignRequest(String employeeId, LocalDate effectiveFrom, LocalDate effectiveTo) {}

    // ---- Controller -------------------------------------------------------

    @RestController
    @RequestMapping("/api/labor/schedule-templates")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final WorkScheduleService service;
        public Controller(WorkScheduleService service) { this.service = service; }

        @GetMapping
        public List<TemplateSummary> list() { return service.listTemplates(); }

        @PostMapping
        public java.util.Map<String, String> create(@RequestBody TemplateUpsert req) {
            return java.util.Map.of("id", service.createTemplate(req));
        }

        @GetMapping("/{id}")
        public TemplateDetail detail(@PathVariable String id) { return service.getTemplate(id); }

        @PutMapping("/{id}")
        public void update(@PathVariable String id, @RequestBody TemplateUpsert req) {
            service.updateTemplate(id, req);
        }

        @DeleteMapping("/{id}")
        public void delete(@PathVariable String id) { service.deleteTemplate(id); }

        @PutMapping("/{id}/blocks")
        public void replaceBlocks(@PathVariable String id, @RequestBody List<BlockInput> blocks) {
            service.replaceBlocks(id, blocks);
        }

        @GetMapping("/{id}/assignments")
        public List<Assignment> assignments(@PathVariable String id) {
            return service.listAssignments(id);
        }

        @PostMapping("/{id}/assignments")
        public java.util.Map<String, String> assign(@PathVariable String id, @RequestBody AssignRequest req) {
            return java.util.Map.of("id", service.assign(id, req));
        }

        @DeleteMapping("/assignments/{assignmentId}")
        public void removeAssignment(@PathVariable String assignmentId) {
            service.removeAssignment(assignmentId);
        }
    }
}
