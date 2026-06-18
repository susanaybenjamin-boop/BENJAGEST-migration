package com.benjagest.backend.timeclock.kiosk;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import com.benjagest.backend.timeclock.TimeClockService;
import jakarta.servlet.http.HttpServletRequest;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * FM-2 — Backend del KIOSCO/PDA de fichaje. Porta el flujo de CONTENDO
 * (kioskController.js) a las convenciones BENJAGEST:
 *  - Admin (JWT, OWNER/ADMIN): alta/listar/editar/borrar dispositivos, generar
 *    token QR de activación, asignar empleados.
 *  - Público sin JWT: {@code activate} (canjea el QR por el KioskToken del
 *    dispositivo) y la sesión de kiosco (config/identify/estado/fichaje),
 *    validada por {@link KioskTokenInterceptor}.
 *  - El fichaje delega en {@link TimeClockService#punch} (reutiliza geo + cadena
 *    hash RD 8/2019; no se duplica nada).
 *
 * <p>Secretos: el token del dispositivo y el de activación son de ALTA entropía
 * → se guardan/comparan por SHA-256 (lookup indexado), no bcrypt. El PIN del
 * empleado sí es bcrypt (employees.pin_hash, ya existe).
 */
@Service
public class KioskService {

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final PasswordEncoder encoder;
    private final TimeClockService timeClock;
    private final SecureRandom rnd = new SecureRandom();

    public KioskService(JdbcTemplate jdbc, TenantContext tenant,
                        PasswordEncoder encoder, TimeClockService timeClock) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.encoder = encoder;
        this.timeClock = timeClock;
    }

    // ====================================================================
    //  Admin (tenant del JWT)
    // ====================================================================

    @Transactional
    public DeviceView register(DeviceUpsert req) {
        if (req == null || req.name() == null || req.name().isBlank()) {
            throw bad("Nombre del kiosco requerido.");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kiosk_devices (id, company_id, work_center_id, name,
                    device_token_hash, require_photo, photo_retention_days, active)
                VALUES (?, ?, ?, ?, NULL, ?, ?, TRUE)
                """,
                id, tenant.getCurrentCompanyId(), blank(req.workCenterId()), req.name().trim(),
                req.requirePhoto() != null && req.requirePhoto(),
                req.photoRetentionDays() == null ? 90 : req.photoRetentionDays());
        return get(id);
    }

    public List<DeviceView> list() {
        return jdbc.query("""
                SELECT id, company_id, work_center_id, name, require_photo, photo_retention_days,
                       active, (device_token_hash IS NOT NULL) AS activated, last_seen_at
                  FROM kiosk_devices
                 WHERE company_id = ?
                 ORDER BY active DESC, name
                """, this::mapDevice, tenant.getCurrentCompanyId());
    }

    public DeviceView get(String id) {
        return jdbc.query("""
                SELECT id, company_id, work_center_id, name, require_photo, photo_retention_days,
                       active, (device_token_hash IS NOT NULL) AS activated, last_seen_at
                  FROM kiosk_devices
                 WHERE id = ? AND company_id = ?
                """, this::mapDevice, id, tenant.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Kiosco no encontrado"));
    }

    @Transactional
    public DeviceView update(String id, DeviceUpsert req) {
        int n = jdbc.update("""
                UPDATE kiosk_devices
                   SET name = ?, work_center_id = ?, require_photo = ?,
                       photo_retention_days = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.name() == null ? null : req.name().trim(), blank(req.workCenterId()),
                req.requirePhoto() != null && req.requirePhoto(),
                req.photoRetentionDays() == null ? 90 : req.photoRetentionDays(),
                req.active() == null || req.active(),
                id, tenant.getCurrentCompanyId());
        if (n == 0) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kiosco no encontrado");
        return get(id);
    }

    @Transactional
    public void delete(String id) {
        jdbc.update("DELETE FROM kiosk_devices WHERE id = ? AND company_id = ?",
                id, tenant.getCurrentCompanyId());
    }

    /** Genera un token de activación (QR), válido 30 min. Devuelve el token EN
     *  CLARO (única vez) para pintar el QR. En BD solo el SHA-256. */
    @Transactional
    public ActivationView generateActivationToken(String deviceId) {
        get(deviceId); // valida pertenencia al tenant
        String token = randomToken();
        Instant expires = Instant.now().plusSeconds(30 * 60);
        jdbc.update("""
                INSERT INTO kiosk_activation_tokens (id, company_id, kiosk_device_id, token_hash, expires_at)
                VALUES (?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), tenant.getCurrentCompanyId(), deviceId,
                sha256(token), java.sql.Timestamp.from(expires));
        return new ActivationView(token, expires);
    }

    public List<EmployeeRef> listAssignedEmployees(String deviceId) {
        get(deviceId);
        return jdbc.query("""
                SELECT e.id, e.full_name
                  FROM kiosk_employee_assignments a
                  JOIN employees e ON e.id = a.employee_id
                 WHERE a.kiosk_device_id = ? AND a.company_id = ?
                 ORDER BY e.full_name
                """, (rs, n) -> new EmployeeRef(rs.getString("id"), rs.getString("full_name")),
                deviceId, tenant.getCurrentCompanyId());
    }

    @Transactional
    public void assignEmployees(String deviceId, List<String> employeeIds) {
        get(deviceId);
        if (employeeIds == null) return;
        for (String empId : employeeIds) {
            if (empId == null || empId.isBlank()) continue;
            jdbc.update("""
                    INSERT IGNORE INTO kiosk_employee_assignments (id, company_id, kiosk_device_id, employee_id)
                    VALUES (?, ?, ?, ?)
                    """, UUID.randomUUID().toString(), tenant.getCurrentCompanyId(), deviceId, empId);
        }
    }

    @Transactional
    public void removeEmployee(String deviceId, String employeeId) {
        jdbc.update("""
                DELETE FROM kiosk_employee_assignments
                 WHERE kiosk_device_id = ? AND employee_id = ? AND company_id = ?
                """, deviceId, employeeId, tenant.getCurrentCompanyId());
    }

    // ====================================================================
    //  Público — activación
    // ====================================================================

    /** Canjea el token de activación (QR) por el KioskToken persistente del
     *  dispositivo. El KioskToken se genera AQUÍ y solo se devuelve esta vez. */
    @Transactional
    public ActivateResult activate(String activationToken) {
        if (activationToken == null || activationToken.isBlank()) throw bad("Token de activación requerido.");
        List<String[]> rows = jdbc.query("""
                SELECT at.id, at.kiosk_device_id, at.company_id
                  FROM kiosk_activation_tokens at
                 WHERE at.token_hash = ? AND at.used_at IS NULL AND at.expires_at > NOW()
                 LIMIT 1
                """, (rs, n) -> new String[]{rs.getString("id"), rs.getString("kiosk_device_id"),
                        rs.getString("company_id")}, sha256(activationToken.trim()));
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Token de activación no válido o caducado. Genera uno nuevo desde la gestión de kioscos.");
        }
        String tokenId = rows.get(0)[0], deviceId = rows.get(0)[1], companyId = rows.get(0)[2];
        jdbc.update("UPDATE kiosk_activation_tokens SET used_at = NOW() WHERE id = ?", tokenId);
        String deviceToken = randomToken();
        jdbc.update("""
                UPDATE kiosk_devices SET device_token_hash = ?, last_seen_at = NOW(), active = TRUE
                 WHERE id = ? AND company_id = ?
                """, sha256(deviceToken), deviceId, companyId);
        String deviceName = jdbc.query("SELECT name FROM kiosk_devices WHERE id = ?",
                (rs, n) -> rs.getString("name"), deviceId).stream().findFirst().orElse("");
        String companyName = jdbc.query("SELECT legal_name FROM companies WHERE id = ?",
                (rs, n) -> rs.getString("legal_name"), companyId).stream().findFirst().orElse("");
        return new ActivateResult(deviceToken, deviceName, companyName);
    }

    // ====================================================================
    //  Sesión de kiosco (KioskToken validado por el interceptor)
    // ====================================================================

    /** Para el interceptor: resuelve dispositivo activo y activado por su token. */
    public DeviceRef resolveActiveByToken(String token) {
        return jdbc.query("""
                SELECT id, company_id FROM kiosk_devices
                 WHERE device_token_hash = ? AND active = TRUE
                 LIMIT 1
                """, (rs, n) -> new DeviceRef(rs.getString("id"), rs.getString("company_id")),
                sha256(token)).stream().findFirst().orElse(null);
    }

    public ConfigView config(String deviceId) {
        DeviceView d = get(deviceId);
        List<EmployeeRef> emps = jdbc.query("""
                SELECT e.id, e.full_name
                  FROM kiosk_employee_assignments a
                  JOIN employees e ON e.id = a.employee_id
                 WHERE a.kiosk_device_id = ? AND a.company_id = ? AND e.active = TRUE
                 ORDER BY e.full_name
                """, (rs, n) -> new EmployeeRef(rs.getString("id"), rs.getString("full_name")),
                deviceId, tenant.getCurrentCompanyId());
        jdbc.update("UPDATE kiosk_devices SET last_seen_at = NOW() WHERE id = ?", deviceId);
        return new ConfigView(d.name(), d.requirePhoto(), emps);
    }

    /** Identifica al empleado por PIN entre los asignados al kiosco. */
    public EmployeeRef identify(String deviceId, String pin) {
        if (pin == null || pin.isBlank()) throw bad("PIN requerido.");
        List<String[]> rows = jdbc.query("""
                SELECT e.id, e.full_name, e.pin_hash
                  FROM kiosk_employee_assignments a
                  JOIN employees e ON e.id = a.employee_id
                 WHERE a.kiosk_device_id = ? AND a.company_id = ?
                   AND e.active = TRUE AND e.pin_hash IS NOT NULL
                """, (rs, n) -> new String[]{rs.getString("id"), rs.getString("full_name"), rs.getString("pin_hash")},
                deviceId, tenant.getCurrentCompanyId());
        for (String[] r : rows) {
            if (encoder.matches(pin, r[2])) return new EmployeeRef(r[0], r[1]);
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "PIN no válido.");
    }

    public EstadoView estado(String deviceId, String employeeId) {
        assertAssigned(deviceId, employeeId);
        return jdbc.query("""
                SELECT event_type, event_time FROM time_clock_events
                 WHERE employee_id = ? AND company_id = ?
                 ORDER BY event_time DESC LIMIT 1
                """, (rs, n) -> new EstadoView(rs.getString("event_type"),
                        rs.getTimestamp("event_time") == null ? null : rs.getTimestamp("event_time").toInstant()),
                employeeId, tenant.getCurrentCompanyId())
                .stream().findFirst().orElse(new EstadoView(null, null));
    }

    @Transactional
    public FichajeResult fichaje(String deviceId, FichajeRequest req) {
        if (req == null || req.employeeId() == null) throw bad("Empleado requerido.");
        assertAssigned(deviceId, req.employeeId());
        String actor = jdbc.query("SELECT user_id FROM employees WHERE id = ? AND company_id = ?",
                (rs, n) -> rs.getString("user_id"), req.employeeId(), tenant.getCurrentCompanyId())
                .stream().findFirst().orElse(null);
        if (actor == null || actor.isBlank()) actor = req.employeeId();
        TimeClockService.PunchResult pr = timeClock.punch(
                req.employeeId(), req.eventType(), null, "KIOSK", req.lat(), req.lng(), actor);
        jdbc.update("UPDATE kiosk_devices SET last_seen_at = NOW() WHERE id = ?", deviceId);
        String geo = pr.geoWarning() == null ? null
                : "Fuera del radio del centro (" + pr.geoWarning().distanceMeters() + " m)";
        return new FichajeResult(pr.csv(), req.eventType(), geo);
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private void assertAssigned(String deviceId, String employeeId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM kiosk_employee_assignments
                 WHERE kiosk_device_id = ? AND employee_id = ? AND company_id = ?
                """, Integer.class, deviceId, employeeId, tenant.getCurrentCompanyId());
        if (n == null || n == 0) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El empleado no está asignado a este kiosco.");
        }
    }

    private DeviceView mapDevice(java.sql.ResultSet rs, int n) throws java.sql.SQLException {
        java.sql.Timestamp ls = rs.getTimestamp("last_seen_at");
        return new DeviceView(rs.getString("id"), rs.getString("work_center_id"),
                rs.getString("name"), rs.getBoolean("require_photo"),
                rs.getInt("photo_retention_days"), rs.getBoolean("active"),
                rs.getBoolean("activated"), ls == null ? null : ls.toInstant());
    }

    private String randomToken() {
        byte[] b = new byte[32];
        rnd.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String sha256(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(64);
            for (byte x : d) sb.append(String.format("%02x", x));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String blank(String v) { return v == null || v.isBlank() ? null : v.trim(); }
    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }

    // ====================================================================
    //  DTOs
    // ====================================================================

    public record DeviceRef(String id, String companyId) {}
    public record DeviceView(String id, String workCenterId, String name, boolean requirePhoto,
                             int photoRetentionDays, boolean active, boolean activated, Instant lastSeenAt) {}
    public record DeviceUpsert(String name, String workCenterId, Boolean requirePhoto,
                               Integer photoRetentionDays, Boolean active) {}
    public record ActivationView(String activationToken, Instant expiresAt) {}
    public record ActivateResult(String deviceToken, String deviceName, String companyName) {}
    public record EmployeeRef(String id, String fullName) {}
    public record ConfigView(String deviceName, boolean requirePhoto, List<EmployeeRef> employees) {}
    public record EstadoView(String lastEventType, Instant lastAt) {}
    public record IdentifyRequest(String pin) {}
    public record AssignRequest(List<String> employeeIds) {}
    public record FichajeRequest(String employeeId, String eventType, BigDecimal lat, BigDecimal lng) {}
    public record FichajeResult(String csv, String eventType, String geoWarning) {}
    public record EstadoRequest(String employeeId) {}

    // ====================================================================
    //  Controllers
    // ====================================================================

    /** Admin: gestión de kioscos (JWT, OWNER/ADMIN, módulo labor). */
    @RestController
    @RequestMapping("/api/kiosk")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN"})
    public static class KioskAdminController {
        private final KioskService service;
        public KioskAdminController(KioskService service) { this.service = service; }

        @GetMapping("/devices")
        public List<DeviceView> list() { return service.list(); }

        @PostMapping("/devices")
        public DeviceView register(@RequestBody DeviceUpsert req) { return service.register(req); }

        @PutMapping("/devices/{id}")
        public DeviceView update(@PathVariable("id") String id, @RequestBody DeviceUpsert req) {
            return service.update(id, req);
        }

        @DeleteMapping("/devices/{id}")
        public void delete(@PathVariable("id") String id) { service.delete(id); }

        @PostMapping("/devices/{id}/activation-token")
        public ActivationView activationToken(@PathVariable("id") String id) {
            return service.generateActivationToken(id);
        }

        @GetMapping("/devices/{id}/employees")
        public List<EmployeeRef> employees(@PathVariable("id") String id) {
            return service.listAssignedEmployees(id);
        }

        @PostMapping("/devices/{id}/employees")
        public void assign(@PathVariable("id") String id, @RequestBody AssignRequest req) {
            service.assignEmployees(id, req.employeeIds());
        }

        @DeleteMapping("/devices/{id}/employees/{empId}")
        public void removeEmployee(@PathVariable("id") String id, @PathVariable("empId") String empId) {
            service.removeEmployee(id, empId);
        }
    }

    /** Público: activación + sesión de kiosco (KioskToken vía interceptor). */
    @RestController
    @RequestMapping("/api/public/kiosk")
    public static class KioskPublicController {
        private final KioskService service;
        public KioskPublicController(KioskService service) { this.service = service; }

        @PostMapping("/activate")
        public ActivateResult activate(@RequestBody java.util.Map<String, String> body) {
            return service.activate(body == null ? null : body.get("activationToken"));
        }

        @GetMapping("/config")
        public ConfigView config(HttpServletRequest request) {
            return service.config(deviceId(request));
        }

        @PostMapping("/identify")
        public EmployeeRef identify(HttpServletRequest request, @RequestBody IdentifyRequest req) {
            return service.identify(deviceId(request), req.pin());
        }

        @PostMapping("/estado")
        public EstadoView estado(HttpServletRequest request, @RequestBody EstadoRequest req) {
            return service.estado(deviceId(request), req.employeeId());
        }

        @PostMapping("/fichaje")
        public FichajeResult fichaje(HttpServletRequest request, @RequestBody FichajeRequest req) {
            return service.fichaje(deviceId(request), req);
        }

        private String deviceId(HttpServletRequest request) {
            Object id = request.getAttribute(KioskTokenInterceptor.ATTR_DEVICE_ID);
            if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión de kiosco no válida.");
            return id.toString();
        }
    }
}
