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
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
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
    private final KioskPhotoStorageService photoStorage;
    private final com.benjagest.backend.timeclock.TimeClockEventTypeService eventTypeService;
    private final SecureRandom rnd = new SecureRandom();

    public KioskService(JdbcTemplate jdbc, TenantContext tenant,
                        PasswordEncoder encoder, TimeClockService timeClock,
                        KioskPhotoStorageService photoStorage,
                        com.benjagest.backend.timeclock.TimeClockEventTypeService eventTypeService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.encoder = encoder;
        this.timeClock = timeClock;
        this.photoStorage = photoStorage;
        this.eventTypeService = eventTypeService;
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
        List<EventType> types = new ArrayList<>();
        for (var t : eventTypeService.list(false)) {
            types.add(new EventType(t.code(), t.labelEs(), t.labelEn(), t.icon(), t.isPause()));
        }
        return new ConfigView(d.name(), d.requirePhoto(), emps, types);
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
        // FM-4: foto-evidencia opcional. Si falla guardarla, el fichaje YA está
        // registrado (no se revierte por la foto); solo se omite la evidencia.
        if (req.photoBase64() != null && !req.photoBase64().isBlank() && pr.event() != null) {
            try {
                String path = photoStorage.savePhoto(tenant.getCurrentCompanyId(),
                        pr.event().id(), req.photoBase64());
                if (path != null) {
                    jdbc.update("""
                            INSERT INTO kiosk_punch_photos (id, company_id, event_id, kiosk_device_id, file_path)
                            VALUES (?, ?, ?, ?, ?)
                            """, UUID.randomUUID().toString(), tenant.getCurrentCompanyId(),
                            pr.event().id(), deviceId, path);
                }
            } catch (Exception ignore) {
                // la evidencia es secundaria; no romper el fichaje
            }
        }
        String geo = pr.geoWarning() == null ? null
                : "Fuera del radio del centro (" + pr.geoWarning().distanceMeters() + " m)";
        return new FichajeResult(pr.csv(), req.eventType(), geo);
    }

    /**
     * OFF-2 — Sincronización por lotes de fichajes capturados OFFLINE en el
     * kiosco. Cada fichaje trae {@code employeeId}, {@code clientUuid} (dedup) y
     * {@code eventTime} (sello real del momento). Idempotente: reenviar el lote no
     * duplica. NO es @Transactional: cada syncPunch va en su propia transacción,
     * así un fichaje que falle no tumba los demás del lote.
     */
    public SyncFichajeResult syncFichaje(String deviceId, SyncFichajeRequest req) {
        int accepted = 0, duplicates = 0, failed = 0;
        if (req != null && req.punches() != null) {
            for (SyncFichajePunch p : req.punches()) {
                try {
                    assertAssigned(deviceId, p.employeeId());
                    String actor = jdbc.query(
                            "SELECT user_id FROM employees WHERE id = ? AND company_id = ?",
                            (rs, n) -> rs.getString("user_id"), p.employeeId(),
                            tenant.getCurrentCompanyId()).stream().findFirst().orElse(null);
                    if (actor == null || actor.isBlank()) actor = p.employeeId();
                    java.time.Instant when = (p.eventTime() == null || p.eventTime().isBlank())
                            ? java.time.Instant.now() : java.time.Instant.parse(p.eventTime());
                    TimeClockService.PunchResult res = timeClock.syncPunch(
                            p.employeeId(), p.eventType(), null, "KIOSK_OFFLINE",
                            p.lat(), p.lng(), when, p.clientUuid(), actor);
                    if (res == null) duplicates++; else accepted++;
                } catch (Exception ex) {
                    failed++;
                }
            }
        }
        try { jdbc.update("UPDATE kiosk_devices SET last_seen_at = NOW() WHERE id = ?", deviceId); }
        catch (Exception ignored) {}
        return new SyncFichajeResult(accepted, duplicates, failed);
    }

    public record SyncFichajePunch(String clientUuid, String employeeId, String eventType,
                                   String eventTime, java.math.BigDecimal lat, java.math.BigDecimal lng) {}
    public record SyncFichajeRequest(java.util.List<SyncFichajePunch> punches) {}
    public record SyncFichajeResult(int accepted, int duplicates, int failed) {}

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
    public record ConfigView(String deviceName, boolean requirePhoto, List<EmployeeRef> employees,
                             List<EventType> eventTypes) {}
    public record EventType(String code, String labelEs, String labelEn, String icon, boolean isPause) {}
    public record EstadoView(String lastEventType, Instant lastAt) {}
    public record IdentifyRequest(String pin) {}
    public record AssignRequest(List<String> employeeIds) {}
    public record FichajeRequest(String employeeId, String eventType, BigDecimal lat, BigDecimal lng,
                                 String photoBase64) {}
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

        /** FM-3/4: página web del kiosco/móvil (responsive). Carga sin token; usa
         *  el KioskToken de localStorage para las llamadas de sesión. */
        @GetMapping(value = "/app", produces = MediaType.TEXT_HTML_VALUE)
        public ResponseEntity<String> app() {
            return ResponseEntity.ok().contentType(MediaType.valueOf("text/html; charset=UTF-8")).body(APP_HTML);
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

        /** OFF-2 — sincroniza un lote de fichajes capturados offline en el kiosco. */
        @PostMapping("/fichaje/sync")
        public SyncFichajeResult syncFichaje(HttpServletRequest request,
                                             @RequestBody SyncFichajeRequest req) {
            return service.syncFichaje(deviceId(request), req);
        }

        private String deviceId(HttpServletRequest request) {
            Object id = request.getAttribute(KioskTokenInterceptor.ATTR_DEVICE_ID);
            if (id == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sesión de kiosco no válida.");
            return id.toString();
        }

        // SPA del kiosco/móvil. Vanilla JS, sin dependencias. Guarda el KioskToken
        // en localStorage tras activar y lo manda en cada llamada de sesión.
        private static final String APP_HTML = """
                <!DOCTYPE html><html lang="es"><head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1, maximum-scale=1, user-scalable=no">
                <title>Fichaje BENJAGEST</title>
                <style>
                  :root{--blue:#1d4ed8;--dark:#0f1b2d;--ok:#0a8f4f;--bg:#eef3f8;}
                  *{box-sizing:border-box;font-family:'Segoe UI',Arial,sans-serif;}
                  body{margin:0;background:var(--bg);color:#172033;}
                  .wrap{max-width:680px;margin:0 auto;padding:18px;}
                  h1{font-size:20px;margin:6px 0 16px;}
                  .muted{color:#5b6b80;font-size:14px;}
                  .grid{display:grid;grid-template-columns:repeat(auto-fill,minmax(150px,1fr));gap:12px;}
                  button{cursor:pointer;border:none;border-radius:12px;font-weight:700;}
                  .emp{padding:22px 12px;background:#fff;border:1px solid #d8e2f0;font-size:16px;color:#172033;}
                  .emp:active{background:#e7eefb;}
                  .act{padding:24px 12px;font-size:18px;color:#fff;background:var(--blue);}
                  .act.pause{background:#6b7280;}
                  .primary{padding:14px 18px;background:var(--blue);color:#fff;font-size:16px;}
                  input{width:100%;padding:14px;font-size:22px;text-align:center;border:1px solid #c7d3e6;border-radius:10px;letter-spacing:6px;}
                  .pad{display:grid;grid-template-columns:repeat(3,1fr);gap:10px;margin-top:14px;}
                  .pad button{padding:20px;font-size:22px;background:#fff;border:1px solid #d8e2f0;}
                  .card{background:#fff;border:1px solid #d8e2f0;border-radius:14px;padding:18px;}
                  .big{font-size:22px;font-weight:800;margin:4px 0;}
                  .hide{display:none;}
                  .topbar{display:flex;justify-content:space-between;align-items:center;margin-bottom:10px;}
                  .link{background:none;color:var(--blue);font-size:14px;padding:6px;}
                  .done{text-align:center;padding:40px 16px;}
                  .done .check{font-size:64px;color:var(--ok);}
                  .err{color:#b91c1c;font-size:14px;min-height:18px;margin-top:8px;}
                  video{width:100%;border-radius:10px;background:#000;margin-top:10px;max-height:180px;object-fit:cover;}
                </style></head><body><div class="wrap">

                <div id="scr-activate" class="hide">
                  <h1>Activar este dispositivo</h1>
                  <p class="muted">Pega el codigo de activacion que te da el administrador en Laboral &rarr; Kioscos.</p>
                  <input id="actToken" placeholder="Codigo de activacion" autocomplete="off">
                  <div class="err" id="actErr"></div>
                  <button class="primary" style="margin-top:12px;width:100%" onclick="doActivate()">Activar</button>
                </div>

                <div id="scr-home" class="hide">
                  <div class="topbar"><h1 id="devName">Fichaje</h1></div>
                  <p class="muted">Toca tu nombre para fichar.</p>
                  <div class="grid" id="empGrid"></div>
                </div>

                <div id="scr-pin" class="hide">
                  <div class="topbar"><span class="big" id="pinEmp"></span><button class="link" onclick="go('home')">Cancelar</button></div>
                  <input id="pin" type="password" inputmode="numeric" readonly placeholder="PIN">
                  <div class="err" id="pinErr"></div>
                  <div class="pad" id="pinPad"></div>
                </div>

                <div id="scr-actions" class="hide">
                  <div class="topbar"><span class="big" id="actEmp"></span><button class="link" onclick="go('home')">Salir</button></div>
                  <p class="muted" id="estado"></p>
                  <video id="cam" class="hide" autoplay playsinline muted></video>
                  <div class="err" id="actErr2"></div>
                  <div class="grid" id="actGrid" style="margin-top:12px"></div>
                </div>

                <div id="scr-done" class="hide">
                  <div class="done"><div class="check">&#10004;</div>
                  <p class="big" id="doneMsg">Fichaje registrado</p>
                  <p class="muted" id="doneCsv"></p></div>
                </div>

                </div><script>
                var API='/api/public/kiosk';
                var token=localStorage.getItem('kioskToken');
                var cfg=null, curEmp=null, stream=null;
                function go(s){['activate','home','pin','actions','done'].forEach(function(x){
                  document.getElementById('scr-'+x).classList.toggle('hide', x!==s);});}
                function hdrs(){return {'Content-Type':'application/json','KioskToken':token||''};}
                function api(path,method,body){return fetch(API+path,{method:method||'GET',headers:hdrs(),
                  body:body?JSON.stringify(body):undefined}).then(function(r){
                  if(!r.ok){return r.text().then(function(t){throw new Error(t||('HTTP '+r.status));});}
                  var ct=r.headers.get('content-type')||''; return ct.indexOf('json')>=0?r.json():null;});}
                function doActivate(){
                  var t=document.getElementById('actToken').value.trim();
                  document.getElementById('actErr').textContent='';
                  fetch(API+'/activate',{method:'POST',headers:{'Content-Type':'application/json'},
                    body:JSON.stringify({activationToken:t})}).then(function(r){
                    if(!r.ok)throw new Error('Codigo no valido o caducado.');return r.json();}).then(function(d){
                    token=d.deviceToken; localStorage.setItem('kioskToken',token); init();
                  }).catch(function(e){document.getElementById('actErr').textContent=e.message;});}
                function init(){
                  if(!token){go('activate');return;}
                  api('/config').then(function(c){cfg=c;
                    document.getElementById('devName').textContent=c.deviceName||'Fichaje';
                    var g=document.getElementById('empGrid');g.innerHTML='';
                    (c.employees||[]).forEach(function(e){var b=document.createElement('button');
                      b.className='emp';b.textContent=e.fullName;b.onclick=function(){openPin(e);};g.appendChild(b);});
                    if(!c.employees||!c.employees.length){g.innerHTML='<p class="muted">No hay empleados asignados a este kiosco.</p>';}
                    kFlushQueue();
                    go('home');
                  }).catch(function(e){
                    if((e.message||'').indexOf('401')>=0||(e.message||'').indexOf('no v')>=0){
                      localStorage.removeItem('kioskToken');token=null;go('activate');
                    } else {alert(e.message);} });}
                function openPin(emp){curEmp=emp;document.getElementById('pinEmp').textContent=emp.fullName;
                  document.getElementById('pin').value='';document.getElementById('pinErr').textContent='';
                  var pad=document.getElementById('pinPad');pad.innerHTML='';
                  ['1','2','3','4','5','6','7','8','9','C','0','OK'].forEach(function(k){
                    var b=document.createElement('button');b.textContent=k;b.onclick=function(){pinKey(k);};pad.appendChild(b);});
                  go('pin');}
                function pinKey(k){var i=document.getElementById('pin');
                  if(k==='C'){i.value='';return;} if(k==='OK'){submitPin();return;} i.value+=k;}
                function submitPin(){var pin=document.getElementById('pin').value;
                  api('/identify','POST',{pin:pin}).then(function(emp){curEmp=emp;openActions(emp);})
                  .catch(function(){document.getElementById('pinErr').textContent='PIN no valido.';
                    document.getElementById('pin').value='';});}
                function openActions(emp){document.getElementById('actEmp').textContent=emp.fullName;
                  document.getElementById('actErr2').textContent='';
                  api('/estado','POST',{employeeId:emp.id}).then(function(s){
                    document.getElementById('estado').textContent=s&&s.lastEventType?('Ultimo: '+s.lastEventType):'Sin fichajes hoy.';});
                  var g=document.getElementById('actGrid');g.innerHTML='';
                  (cfg.eventTypes||[]).forEach(function(t){var b=document.createElement('button');
                    b.className='act'+(t.isPause?' pause':'');b.textContent=t.labelEs||t.code;
                    b.onclick=function(){doFichaje(t.code);};g.appendChild(b);});
                  startCam(); go('actions');}
                function startCam(){var v=document.getElementById('cam');
                  if(cfg&&cfg.requirePhoto&&navigator.mediaDevices){
                    navigator.mediaDevices.getUserMedia({video:{facingMode:'user'}}).then(function(s){
                      stream=s;v.srcObject=s;v.classList.remove('hide');}).catch(function(){});
                  } else {v.classList.add('hide');}}
                function stopCam(){if(stream){stream.getTracks().forEach(function(t){t.stop();});stream=null;}
                  document.getElementById('cam').classList.add('hide');}
                function snapshot(){if(!stream)return null;var v=document.getElementById('cam');
                  var c=document.createElement('canvas');c.width=v.videoWidth||320;c.height=v.videoHeight||240;
                  c.getContext('2d').drawImage(v,0,0,c.width,c.height);return c.toDataURL('image/jpeg',0.6);}
                // OFF-2: cola offline del kiosco (fichar sin red + sync al volver).
                function kGenUuid(){if(window.crypto&&crypto.randomUUID)return crypto.randomUUID();
                  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g,function(c){
                    var r=Math.random()*16|0,v=c==='x'?r:(r&0x3|0x8);return v.toString(16);});}
                function kGetQueue(){try{return JSON.parse(localStorage.getItem('kiosk_fichaje_queue')||'[]');}catch(e){return [];}}
                function kSetQueue(q){localStorage.setItem('kiosk_fichaje_queue',JSON.stringify(q));}
                function kQueue(p){var q=kGetQueue();q.push(p);kSetQueue(q);}
                function kFlushQueue(){var q=kGetQueue();if(!q.length||!navigator.onLine||!token)return;
                  api('/fichaje/sync','POST',{punches:q}).then(function(){
                    localStorage.removeItem('kiosk_fichaje_queue');}).catch(function(){});}
                window.addEventListener('online',kFlushQueue);
                function doFichaje(code){var photo=cfg&&cfg.requirePhoto?snapshot():null;
                  document.getElementById('actErr2').textContent='Registrando...';
                  function send(lat,lng){
                    var punch={clientUuid:kGenUuid(),employeeId:curEmp.id,eventType:code,
                      eventTime:new Date().toISOString(),lat:lat,lng:lng};
                    api('/fichaje','POST',{employeeId:curEmp.id,eventType:code,
                      lat:lat,lng:lng,photoBase64:photo}).then(function(r){stopCam();
                      document.getElementById('doneCsv').textContent=r&&r.csv?('CSV: '+r.csv):'';
                      go('done');kFlushQueue();setTimeout(function(){go('home');},4000);})
                      .catch(function(e){
                        var off=!navigator.onLine||(e.message||'').indexOf('Failed to fetch')>=0
                          ||(e.message||'').indexOf('NetworkError')>=0;
                        if(off){kQueue(punch);stopCam();
                          document.getElementById('doneCsv').textContent='Guardado SIN conexion. Se sincronizara solo.';
                          go('done');setTimeout(function(){go('home');},4000);}
                        else {document.getElementById('actErr2').textContent=e.message;}});}
                  if(navigator.geolocation){navigator.geolocation.getCurrentPosition(
                    function(p){send(p.coords.latitude,p.coords.longitude);},
                    function(){send(null,null);},{timeout:4000});} else {send(null,null);}}
                init();
                </script></body></html>
                """;
    }
}
