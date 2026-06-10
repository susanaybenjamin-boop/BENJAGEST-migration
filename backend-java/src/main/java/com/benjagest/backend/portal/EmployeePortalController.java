package com.benjagest.backend.portal;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * PORT-1 — REST API del Portal del empleado.
 *
 * <p>Bajo {@code /api/portal}. Todos los endpoints son READ-ONLY. El
 * empleado consulta sus datos; las modificaciones siguen yendo por los
 * controllers existentes (LaborController para nóminas, etc.).
 *
 * <p>{@link RequiresModule} apunta a {@code "employee-portal"} — si la
 * empresa lo desactiva desde Configuración → Módulos, el sidebar deja
 * de mostrar el item y el endpoint devuelve 403.
 *
 * <p>Roles: cualquier rol autenticado (incluido EMPLOYEE). Los datos se
 * filtran por employee_id resuelto desde el user_id; un OWNER que no
 * sea empleado verá la sección de calendario pero las nóminas vacías
 * (no es su data).
 */
@RestController
@RequestMapping("/api/portal")
@RequiresModule("employee-portal")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class EmployeePortalController {

    private final EmployeePortalService service;

    public EmployeePortalController(EmployeePortalService service) {
        this.service = service;
    }

    @GetMapping("/calendar")
    public List<EmployeePortalService.PortalEvent> listCalendar(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to")   @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.listCalendar(from, to);
    }

    @GetMapping("/payslips")
    public List<EmployeePortalService.PortalPayslip> listPayslips() {
        return service.listPayslips();
    }

    @GetMapping("/notifications")
    public List<EmployeePortalService.PortalNotification> listNotifications() {
        return service.listNotifications();
    }

    @GetMapping("/jobs")
    public List<EmployeePortalService.PortalJob> listJobs() {
        return service.listJobs();
    }
}
