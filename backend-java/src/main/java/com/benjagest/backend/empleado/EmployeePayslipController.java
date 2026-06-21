package com.benjagest.backend.empleado;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.labor.PayslipService;
import com.benjagest.backend.timeclock.TimeClockController;
import com.benjagest.backend.timeclock.TimeClockService;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MEMP-5 — Nóminas en el portal del empleado (PWA): recibir, descargar el PDF y
 * confirmar el recibí (acuse de recibo / "firma" simple).
 *
 * <p>Reutiliza {@link PayslipService} (cálculo y PDF ya existentes) y resuelve el
 * empleado del JWT ({@link TimeClockService#resolveCurrentEmployee}). Todas las
 * operaciones llevan guarda de propiedad: el empleado solo ve y acusa SUS nóminas.
 * No {@code @RequiresModule}: el empleado accede a sus nóminas con independencia
 * de los módulos del sidebar de la empresa (igual que fichaje/jornada/ausencias).
 */
@RestController
@RequestMapping("/api/empleado/nominas")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE", "USER"})
public class EmployeePayslipController {

    private final PayslipService payslips;
    private final TimeClockService timeClock;

    public EmployeePayslipController(PayslipService payslips, TimeClockService timeClock) {
        this.payslips = payslips;
        this.timeClock = timeClock;
    }

    @GetMapping
    public List<NominaView> mine() {
        TimeClockController.MyEmployeeInfo me = timeClock.resolveCurrentEmployee();
        return payslips.listForEmployeeApp(me.employeeId()).stream()
                .map(p -> new NominaView(
                        p.id(), p.periodYear(), p.periodMonth(), p.payslipType(),
                        p.grossAmount(), p.netAmount(), p.status(),
                        p.paidAt(), p.deliveredAt(), p.acknowledgedAt()))
                .toList();
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> pdf(@PathVariable("id") String id) {
        TimeClockController.MyEmployeeInfo me = timeClock.resolveCurrentEmployee();
        byte[] body = payslips.pdfForEmployee(id, me.employeeId());
        return ResponseEntity.ok()
                .header("Content-Type", "application/pdf")
                .header("Content-Disposition", "inline; filename=\"nomina.pdf\"")
                .body(body);
    }

    @PostMapping("/{id}/acknowledge")
    public void acknowledge(@PathVariable("id") String id,
                            @RequestBody(required = false) AckRequest req,
                            jakarta.servlet.http.HttpServletRequest http) {
        TimeClockController.MyEmployeeInfo me = timeClock.resolveCurrentEmployee();
        String pin = req == null ? null : req.pin();
        String device = req == null ? null : req.device();
        payslips.acknowledgeOwn(id, me.employeeId(), pin, device, clientIp(http));
    }

    /** IP del cliente tras el túnel Cloudflare (CF-Connecting-IP / X-Forwarded-For). */
    private String clientIp(jakarta.servlet.http.HttpServletRequest http) {
        String cf = http.getHeader("CF-Connecting-IP");
        if (cf != null && !cf.isBlank()) return cf.trim();
        String xff = http.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank()) return xff.split(",")[0].trim();
        return http.getRemoteAddr();
    }

    public record AckRequest(String pin, String device) {}

    public record NominaView(
            String id, int year, int month, String payslipType,
            BigDecimal gross, BigDecimal net, String status,
            LocalDate paidAt, LocalDate deliveredAt, LocalDate acknowledgedAt) {}
}
