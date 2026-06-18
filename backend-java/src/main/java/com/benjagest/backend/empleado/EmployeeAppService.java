package com.benjagest.backend.empleado;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.auth.pin.dto.PairResponse;
import com.benjagest.backend.auth.pin.DeviceTokenService;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * MEMP-1 — Invitación + activación de la PWA del empleado.
 *
 * <p>Flujo (reusa el modelo PIN multi-puesto existente, sin tocarlo):
 * <ol>
 *   <li>El admin genera una invitación para un empleado con app_access
 *       (PIN ya asignado) → token one-time (caduca a las 72 h).</li>
 *   <li>El empleado abre la PWA con ese token y la "activa":
 *       {@code POST /api/public/empleado/activate} canjea el token y
 *       empareja SU móvil ({@link DeviceTokenService#pairEmployeeDevice})
 *       a la empresa del empleado → devuelve el device_secret (la PWA lo
 *       guarda en localStorage).</li>
 *   <li>A partir de ahí el empleado entra por PIN
 *       ({@code POST /api/auth/pin-login}) → JWT estándar con rol EMPLOYEE.</li>
 * </ol>
 * No es app nativa: la PWA es HTML/JS servido por Spring (slice MEMP-1b).
 */
@Service
public class EmployeeAppService {

    private static final int INVITATION_TTL_HOURS = 72;

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final DeviceTokenService deviceTokenService;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmployeeAppService(JdbcTemplate jdbc, TenantContext tenant,
                              DeviceTokenService deviceTokenService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.deviceTokenService = deviceTokenService;
    }

    /** Admin: genera una invitación one-time para un empleado con app_access. */
    @Transactional
    public InvitationResult generateInvitation(String employeeId) {
        String companyId = tenant.getCurrentCompanyId();
        List<EmpRow> rows = jdbc.query("""
                SELECT id, full_name, app_access, pin_hash IS NOT NULL AS has_pin
                  FROM employees
                 WHERE id = ? AND company_id = ? AND active = TRUE
                """,
                (rs, n) -> new EmpRow(rs.getString("id"), rs.getString("full_name"),
                        rs.getBoolean("app_access"), rs.getBoolean("has_pin")),
                employeeId, companyId);
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado");
        }
        EmpRow emp = rows.get(0);
        if (!emp.appAccess() || !emp.hasPin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El empleado debe tener acceso a la app y un PIN asignado antes de invitarlo.");
        }

        String token = newToken();
        String id = UUID.randomUUID().toString();
        Instant expires = Instant.now().plus(INVITATION_TTL_HOURS, ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO employee_app_invitations
                    (id, company_id, employee_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """, id, companyId, employeeId, sha256(token), java.sql.Timestamp.from(expires));

        return new InvitationResult(token, "/api/public/empleado/app?invite=" + token,
                INVITATION_TTL_HOURS);
    }

    /** Público: el móvil del empleado canjea el token y queda emparejado. */
    @Transactional
    public ActivateResult activate(String token, String deviceName) {
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Falta el token de invitación");
        }
        List<InvRow> rows = jdbc.query("""
                SELECT id, company_id, employee_id, used_at, expires_at
                  FROM employee_app_invitations
                 WHERE token_hash = ?
                """,
                (rs, n) -> new InvRow(rs.getString("id"), rs.getString("company_id"),
                        rs.getString("employee_id"), rs.getTimestamp("used_at"),
                        rs.getTimestamp("expires_at")),
                sha256(token));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Invitación no válida");
        }
        InvRow inv = rows.get(0);
        if (inv.usedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE, "Esta invitación ya se usó");
        }
        if (inv.expiresAt() != null && inv.expiresAt().toInstant().isBefore(Instant.now())) {
            throw new ResponseStatusException(HttpStatus.GONE, "La invitación ha caducado");
        }

        // Datos del empleado/empresa para emparejar y para el saludo en la PWA.
        List<Map<String, Object>> emp = jdbc.queryForList("""
                SELECT e.user_id, e.full_name, c.legal_name AS company_name
                  FROM employees e
                  JOIN companies c ON c.id = e.company_id
                 WHERE e.id = ? AND e.company_id = ?
                """, inv.employeeId(), inv.companyId());
        if (emp.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE, "Empleado no disponible");
        }
        String userId = (String) emp.get(0).get("user_id");
        String employeeName = (String) emp.get(0).get("full_name");
        String companyName = (String) emp.get(0).get("company_name");

        // Marcar usada (one-time) y emparejar el dispositivo a SU empresa.
        jdbc.update("UPDATE employee_app_invitations SET used_at = CURRENT_TIMESTAMP WHERE id = ?",
                inv.id());
        String name = "App empleado: " + (employeeName == null ? "—" : employeeName)
                + (deviceName == null || deviceName.isBlank() ? "" : " (" + deviceName + ")");
        PairResponse pair = deviceTokenService.pairEmployeeDevice(inv.companyId(), name, userId);

        return new ActivateResult(pair.deviceSecret(), employeeName, companyName);
    }

    private String newToken() {
        byte[] b = new byte[32];
        secureRandom.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private String sha256(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    private record EmpRow(String id, String fullName, boolean appAccess, boolean hasPin) {}
    private record InvRow(String id, String companyId, String employeeId,
                          java.sql.Timestamp usedAt, java.sql.Timestamp expiresAt) {}

    public record InvitationResult(String token, String path, int expiresInHours) {}
    public record ActivateResult(String deviceSecret, String employeeName, String companyName) {}
    public record ActivateRequest(String token, String deviceName) {}

    // ---- Controllers ------------------------------------------------------

    @RestController
    @RequestMapping("/api/labor/employees")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN"})
    public static class AdminController {
        private final EmployeeAppService service;
        public AdminController(EmployeeAppService service) { this.service = service; }

        @PostMapping("/{id}/app-invitation")
        public InvitationResult invite(@PathVariable("id") String employeeId) {
            return service.generateInvitation(employeeId);
        }
    }

    @RestController
    @RequestMapping("/api/public/empleado")
    public static class PublicController {
        private final EmployeeAppService service;
        public PublicController(EmployeeAppService service) { this.service = service; }

        @PostMapping("/activate")
        public ActivateResult activate(@RequestBody ActivateRequest req) {
            return service.activate(req.token(), req.deviceName());
        }
    }
}
