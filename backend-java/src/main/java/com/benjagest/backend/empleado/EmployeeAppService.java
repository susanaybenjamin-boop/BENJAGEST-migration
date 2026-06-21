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
    private final String publicBaseUrl;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmployeeAppService(JdbcTemplate jdbc, TenantContext tenant,
                              DeviceTokenService deviceTokenService,
                              @org.springframework.beans.factory.annotation.Value(
                                      "${benjagest.public-base-url:}") String publicBaseUrl) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.deviceTokenService = deviceTokenService;
        this.publicBaseUrl = publicBaseUrl;
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

        String path = "/api/public/empleado/app?invite=" + token;
        String url = (publicBaseUrl != null && !publicBaseUrl.isBlank())
                ? publicBaseUrl.replaceAll("/+$", "") + path
                : path;
        return new InvitationResult(token, url, INVITATION_TTL_HOURS);
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
        // Reutilizable hasta caducar (NO de un solo uso): en iOS la PWA instalada
        // usa un almacen distinto del navegador, asi que el empleado tiene que
        // activar DENTRO de la app instalada aunque ya lo hiciera en Safari.
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

    public record InvitationResult(String token, String url, int expiresInHours) {}
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

        @org.springframework.web.bind.annotation.GetMapping(
                value = "/app",
                produces = org.springframework.http.MediaType.TEXT_HTML_VALUE)
        public org.springframework.http.ResponseEntity<String> app() {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.valueOf("text/html; charset=UTF-8"))
                    .body(APP_HTML);
        }

        @org.springframework.web.bind.annotation.GetMapping(
                value = "/manifest.webmanifest",
                produces = "application/manifest+json")
        public org.springframework.http.ResponseEntity<String> manifest() {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.valueOf("application/manifest+json"))
                    .body(MANIFEST_JSON);
        }

        @org.springframework.web.bind.annotation.GetMapping(
                value = "/sw.js",
                produces = "application/javascript")
        public org.springframework.http.ResponseEntity<String> serviceWorker() {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.valueOf("application/javascript; charset=UTF-8"))
                    .body(SERVICE_WORKER_JS);
        }

        @org.springframework.web.bind.annotation.GetMapping(
                value = "/icon.svg",
                produces = "image/svg+xml")
        public org.springframework.http.ResponseEntity<String> icon() {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.valueOf("image/svg+xml"))
                    .body(ICON_SVG);
        }

        @org.springframework.web.bind.annotation.GetMapping(
                value = "/icon-180.png",
                produces = org.springframework.http.MediaType.IMAGE_PNG_VALUE)
        public org.springframework.http.ResponseEntity<byte[]> iconPng() {
            return org.springframework.http.ResponseEntity.ok()
                    .contentType(org.springframework.http.MediaType.IMAGE_PNG).body(pngIcon());
        }
    }

    /** Icono PNG 180x180 para apple-touch-icon (iOS no acepta SVG). Cacheado. */
    private static volatile byte[] cachedPng;
    static byte[] pngIcon() {
        if (cachedPng != null) return cachedPng;
        try {
            int s = 180;
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(s, s, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            java.awt.Graphics2D g = img.createGraphics();
            g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(java.awt.RenderingHints.KEY_TEXT_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setColor(java.awt.Color.decode("#0f172a"));
            g.fillRoundRect(0, 0, s, s, 40, 40);
            g.setColor(java.awt.Color.decode("#38bdf8"));
            g.setFont(new java.awt.Font("Arial", java.awt.Font.BOLD, 120));
            java.awt.FontMetrics fm = g.getFontMetrics();
            String b = "B";
            int x = (s - fm.stringWidth(b)) / 2;
            int y = (s - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString(b, x, y);
            g.dispose();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", bos);
            cachedPng = bos.toByteArray();
            return cachedPng;
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo generar el icono PNG", e);
        }
    }

    // ===================== PWA estática (MEMP-1b) =====================

    private static final String MANIFEST_JSON = """
            {
              "name": "BENJAGEST Empleado",
              "short_name": "BENJAGEST",
              "start_url": "/api/public/empleado/app",
              "scope": "/api/public/empleado/",
              "display": "standalone",
              "background_color": "#0f172a",
              "theme_color": "#0f172a",
              "lang": "es",
              "icons": [
                { "src": "/api/public/empleado/icon.svg", "sizes": "any", "type": "image/svg+xml", "purpose": "any maskable" }
              ]
            }
            """;

    private static final String SERVICE_WORKER_JS = """
            const CACHE = 'benjagest-empleado-v5';
            self.addEventListener('install', (e) => {
              self.skipWaiting();
              e.waitUntil(caches.open(CACHE).then((c) => c.addAll(['/api/public/empleado/app'])));
            });
            self.addEventListener('activate', (e) => {
              e.waitUntil(
                caches.keys().then((ks) => Promise.all(
                  ks.filter((k) => k !== CACHE).map((k) => caches.delete(k))
                )).then(() => self.clients.claim())
              );
            });
            self.addEventListener('fetch', (e) => {
              const url = e.request.url;
              // Solo cachear el cascaron; las llamadas a la API siempre van a red.
              if (e.request.method === 'GET' && url.indexOf('/api/public/empleado/app') !== -1) {
                e.respondWith(
                  fetch(e.request).then((r) => {
                    const copy = r.clone();
                    caches.open(CACHE).then((c) => c.put(e.request, copy));
                    return r;
                  }).catch(() => caches.match(e.request))
                );
              }
            });
            """;

    private static final String ICON_SVG = """
            <svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 192 192">
              <rect width="192" height="192" rx="36" fill="#0f172a"/>
              <text x="96" y="128" font-family="Arial, sans-serif" font-size="110" font-weight="700"
                    fill="#38bdf8" text-anchor="middle">B</text>
            </svg>
            """;

    private static final String APP_HTML = """
            <!DOCTYPE html>
            <html lang="es">
            <head>
              <meta charset="UTF-8"/>
              <meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover"/>
              <meta name="theme-color" content="#0f172a"/>
              <title>BENJAGEST Empleado</title>
              <link rel="manifest" href="/api/public/empleado/manifest.webmanifest"/>
              <link rel="icon" href="/api/public/empleado/icon.svg" type="image/svg+xml"/>
              <link rel="apple-touch-icon" href="/api/public/empleado/icon-180.png"/>
              <meta name="apple-mobile-web-app-capable" content="yes"/>
              <meta name="mobile-web-app-capable" content="yes"/>
              <meta name="apple-mobile-web-app-status-bar-style" content="black-translucent"/>
              <meta name="apple-mobile-web-app-title" content="BENJAGEST"/>
              <style>
                * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
                body { margin: 0; font-family: -apple-system, Segoe UI, Roboto, Arial, sans-serif;
                       background: #0f172a; color: #e2e8f0; min-height: 100vh; }
                .wrap { max-width: 440px; margin: 0 auto; padding: 24px 20px 40px; }
                h1 { font-size: 22px; margin: 8px 0 4px; }
                .sub { color: #94a3b8; font-size: 14px; margin: 0 0 24px; }
                .card { background: #1e293b; border-radius: 16px; padding: 20px; margin-bottom: 16px; }
                label { display: block; font-size: 13px; color: #94a3b8; margin-bottom: 6px; }
                input { width: 100%; padding: 14px; font-size: 18px; border-radius: 12px;
                        border: 1px solid #334155; background: #0f172a; color: #e2e8f0; text-align: center;
                        letter-spacing: 6px; }
                button { width: 100%; padding: 15px; font-size: 16px; font-weight: 600; border: none;
                         border-radius: 12px; background: #38bdf8; color: #04263a; margin-top: 14px; }
                button.secondary { background: transparent; color: #94a3b8; border: 1px solid #334155; }
                button:disabled { opacity: 0.35; }
                .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                .tile { background: #1e293b; border-radius: 16px; padding: 22px 12px; text-align: center; position: relative; }
                .tbadge { position: absolute; top: 8px; right: 8px; min-width: 20px; height: 20px;
                          background: #ef4444; color: #fff; border-radius: 10px; font-size: 12px;
                          font-weight: 700; line-height: 20px; padding: 0 6px; }
                .tile .ic { font-size: 30px; }
                .tile .lbl { margin-top: 8px; font-size: 14px; }
                .tile .soon { display:block; font-size: 11px; color: #64748b; margin-top: 4px; }
                .msg { padding: 12px; border-radius: 12px; font-size: 14px; margin-top: 12px; }
                .msg.err { background: #7f1d1d; color: #fecaca; }
                .msg.ok { background: #14532d; color: #bbf7d0; }
                .hidden { display: none; }
                .center { text-align: center; }
                .suggest { padding: 12px 14px; border-radius: 12px; font-size: 14px; margin: 0 0 14px;
                           background: #0c4a6e; color: #e0f2fe; border: 1px solid #0ea5e9; }
                .suggest.soft { background: #1e293b; color: #cbd5e1; border-color: #334155; }
                .suggest .big { display:block; font-size:16px; font-weight:600; margin-bottom:2px; }
                button.suggested { box-shadow: 0 0 0 3px #38bdf8, 0 0 16px #38bdf8; animation: pulse 1.6s infinite; }
                button.suggested.secondary { background: #38bdf8; color: #04263a; }
                @keyframes pulse { 0%,100% { box-shadow: 0 0 0 3px #38bdf8, 0 0 8px #38bdf8; }
                                   50% { box-shadow: 0 0 0 3px #7dd3fc, 0 0 20px #7dd3fc; } }
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="center">
                  <img src="/api/public/empleado/icon.svg" width="56" height="56" alt=""/>
                </div>

                <div id="screen-invite" class="hidden">
                  <h1>Activar la app</h1>
                  <p class="sub">Introduce el codigo de invitacion que te ha enviado tu empresa.</p>
                  <div id="installHint" class="card hidden">
                    <strong>Instala la app primero</strong>
                    <ol style="margin:8px 0 0; padding-left:18px; color:#cbd5e1; font-size:14px; line-height:1.5;">
                      <li>Pulsa <b>Copiar codigo</b>.</li>
                      <li>Toca <b>Compartir</b> (el cuadrado con la flecha) y <b>Anadir a pantalla de inicio</b>.</li>
                      <li>Abre la app desde su icono y pulsa <b>Activar</b>.</li>
                    </ol>
                  </div>
                  <div class="card">
                    <label>Codigo de invitacion</label>
                    <input id="inviteInput" inputmode="text" placeholder="pega aqui el codigo"/>
                    <button id="copyCodeBtn" class="hidden">Copiar codigo</button>
                    <button id="activateBtn">Activar</button>
                  </div>
                  <div id="inviteMsg"></div>
                </div>

                <div id="screen-pin" class="hidden">
                  <h1 id="pinHello">Hola</h1>
                  <p class="sub" id="pinCompany"></p>
                  <div class="card">
                    <label>Introduce tu PIN</label>
                    <input id="pinInput" type="password" inputmode="numeric" maxlength="8"
                           autocomplete="off" autocorrect="off" autocapitalize="off"
                           name="benjagest-pin" placeholder="****"/>
                    <button id="pinBtn">Entrar</button>
                  </div>
                  <div id="pinMsg"></div>
                  <button class="secondary" id="forgetBtn">Desvincular este dispositivo</button>
                </div>

                <div id="screen-home" class="hidden">
                  <h1 id="homeHello">Hola</h1>
                  <p class="sub" id="homeCompany"></p>
                  <div class="grid">
                    <div class="tile" style="cursor:pointer" onclick="gotoFichar()"><div class="ic">&#128337;</div><div class="lbl">Fichar</div></div>
                    <div class="tile" style="cursor:pointer" onclick="gotoAusencias()"><span class="tbadge hidden" id="badge-ausencias"></span><div class="ic">&#127958;</div><div class="lbl">Vacaciones y bajas</div></div>
                    <div class="tile" style="cursor:pointer" onclick="gotoNominas()"><span class="tbadge hidden" id="badge-nominas"></span><div class="ic">&#128196;</div><div class="lbl">Nominas</div></div>
                    <div class="tile" style="cursor:pointer" onclick="gotoJornada()"><div class="ic">&#128197;</div><div class="lbl">Mi jornada</div></div>
                  </div>
                  <button class="secondary" id="logoutBtn">Cerrar sesion</button>
                </div>

                <div id="screen-fichar" class="hidden">
                  <h1>Fichar</h1>
                  <p class="sub" id="ficharState">&nbsp;</p>
                  <div id="ficharSuggest" class="suggest hidden"></div>
                  <div class="grid">
                    <button id="btn-IN" onclick="doPunch('IN')">Entrada</button>
                    <button id="btn-OUT" onclick="doPunch('OUT')">Salida</button>
                    <button id="btn-BREAK_START" class="secondary" onclick="doPunch('BREAK_START')">Pausa</button>
                    <button id="btn-BREAK_END" class="secondary" onclick="doPunch('BREAK_END')">Volver de pausa</button>
                  </div>
                  <button class="secondary hidden" id="ficharOverride" onclick="ficharOverride()" style="margin-top:8px">Fichar otra cosa</button>
                  <div id="ficharMsg"></div>
                  <p class="sub" style="margin-top:18px">Ultimos fichajes</p>
                  <ul id="ficharRecent" style="list-style:none;padding:0;margin:0;color:#cbd5e1;font-size:14px"></ul>
                  <button class="secondary" onclick="gotoHome()">Volver</button>
                </div>

                <div id="screen-jornada" class="hidden">
                  <h1>Mi jornada</h1>
                  <p class="sub" id="jornadaDate">&nbsp;</p>
                  <div id="jornadaBody"></div>
                  <div id="jornadaMsg"></div>
                  <button class="secondary" onclick="gotoHome()">Volver</button>
                </div>

                <div id="screen-ausencias" class="hidden">
                  <h1>Vacaciones y bajas</h1>
                  <p class="sub">Solicita y consulta el estado de tus ausencias.</p>
                  <div class="card">
                    <label>Tipo</label>
                    <select id="ausTipo" onchange="refreshAdjLabel()" style="width:100%;padding:14px;font-size:16px;border-radius:12px;border:1px solid #334155;background:#0f172a;color:#e2e8f0">
                      <option value="VACATION">Vacaciones</option>
                      <option value="SICK_LEAVE">Baja medica (IT)</option>
                      <option value="PAID_LEAVE">Permiso retribuido</option>
                      <option value="OTHER">Otros</option>
                    </select>
                    <div style="display:flex;gap:10px;margin-top:12px">
                      <div style="flex:1"><label>Desde</label>
                        <input id="ausDesde" type="date" style="letter-spacing:normal;text-align:left"/></div>
                      <div style="flex:1"><label>Hasta</label>
                        <input id="ausHasta" type="date" style="letter-spacing:normal;text-align:left"/></div>
                    </div>
                    <label style="margin-top:12px">Motivo (opcional)</label>
                    <input id="ausMotivo" type="text" style="letter-spacing:normal;text-align:left" placeholder="motivo"/>
                    <label style="margin-top:12px" id="ausAdjLbl">Adjunto (foto o PDF)</label>
                    <input id="ausAdjunto" type="file" accept="image/*,application/pdf" multiple
                           style="letter-spacing:normal;text-align:left;padding:10px"/>
                    <button id="ausEnviar" onclick="submitAusencia()">Enviar solicitud</button>
                  </div>
                  <div id="ausMsg"></div>
                  <p class="sub" style="margin-top:18px">Mis solicitudes</p>
                  <div id="ausLista"></div>
                  <button class="secondary" onclick="gotoHome()">Volver</button>
                </div>

                <div id="screen-nominas" class="hidden">
                  <h1>Mis nominas</h1>
                  <p class="sub">Consulta, descarga y firma el recibi de tus nominas.</p>
                  <div id="nominasMsg"></div>
                  <div id="nominasLista"></div>
                  <button class="secondary" onclick="gotoHome()">Volver</button>
                </div>

                <div id="ackModal" class="hidden" style="position:fixed;inset:0;background:rgba(0,0,0,.6);display:flex;align-items:center;justify-content:center;padding:20px;z-index:50">
                  <div class="card" style="max-width:360px;width:100%;margin:0">
                    <h1 style="font-size:18px;margin-top:0">Firmar recibi</h1>
                    <p class="sub">Introduce tu PIN para firmar el recibi de la nomina (queda registrado con fecha y dispositivo).</p>
                    <input id="ackPin" type="password" inputmode="numeric" maxlength="8" autocomplete="off" placeholder="****"/>
                    <button onclick="confirmAck()">Firmar</button>
                    <button class="secondary" onclick="closeAck()">Cancelar</button>
                    <div id="ackMsg"></div>
                  </div>
                </div>
              </div>

              <script>
                const API = '/api';
                const LS_SECRET = 'benjagest_emp_secret';
                const LS_NAME = 'benjagest_emp_name';
                const LS_COMPANY = 'benjagest_emp_company';
                const LS_TOKEN = 'benjagest_emp_token';

                function show(id) {
                  ['screen-invite','screen-pin','screen-home','screen-fichar','screen-jornada','screen-ausencias','screen-nominas'].forEach(s => {
                    document.getElementById(s).classList.toggle('hidden', s !== id);
                  });
                }
                function msg(el, text, ok) {
                  const m = document.getElementById(el);
                  m.className = 'msg ' + (ok ? 'ok' : 'err');
                  m.textContent = text;
                }
                function qp(name) {
                  const m = new RegExp('[?&]' + name + '=([^&]+)').exec(location.search);
                  return m ? decodeURIComponent(m[1]) : null;
                }

                async function activate(token) {
                  const r = await fetch(API + '/public/empleado/activate', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ token: token, deviceName: navigator.userAgent.slice(0, 40) })
                  });
                  if (!r.ok) {
                    let e = 'No se pudo activar';
                    try { e = (await r.json()).message || e; } catch (x) {}
                    throw new Error(e);
                  }
                  return r.json();
                }

                async function pinLogin(secret, pin) {
                  const r = await fetch(API + '/auth/pin-login', {
                    method: 'POST', headers: {'Content-Type': 'application/json'},
                    body: JSON.stringify({ deviceSecret: secret, pin: pin })
                  });
                  if (!r.ok) {
                    let e = 'PIN incorrecto';
                    try { e = (await r.json()).message || e; } catch (x) {}
                    throw new Error(e);
                  }
                  return r.json();
                }

                function gotoPin() {
                  document.getElementById('pinHello').textContent = 'Hola, ' + (localStorage.getItem(LS_NAME) || '');
                  document.getElementById('pinCompany').textContent = localStorage.getItem(LS_COMPANY) || '';
                  show('screen-pin');
                }
                function gotoHome() {
                  document.getElementById('homeHello').textContent = 'Hola, ' + (localStorage.getItem(LS_NAME) || '');
                  document.getElementById('homeCompany').textContent = localStorage.getItem(LS_COMPANY) || '';
                  show('screen-home');
                  updateHomeBadges();
                }

                // ---- MEMP-2: fichaje ----
                function authHeaders() {
                  return { 'Content-Type': 'application/json',
                           'Authorization': 'Bearer ' + (localStorage.getItem(LS_TOKEN) || '') };
                }
                function typeLabel(t) {
                  return ({IN:'Entrada', OUT:'Salida', BREAK_START:'Pausa', BREAK_END:'Vuelta de pausa'})[t] || t;
                }
                function fmtTime(iso) {
                  try { return new Date(iso).toLocaleString('es-ES'); } catch (e) { return iso; }
                }
                function getGeo() {
                  return new Promise((res) => {
                    if (!navigator.geolocation) { res({}); return; }
                    navigator.geolocation.getCurrentPosition(
                      (p) => res({ lat: p.coords.latitude, lng: p.coords.longitude }),
                      () => res({}),
                      { timeout: 6000, enableHighAccuracy: true });
                  });
                }
                function gotoFichar() { show('screen-fichar'); document.getElementById('ficharMsg').textContent=''; loadEstado(); loadSugerencia(); }
                // FJ-4: resalta el boton que toca segun la jornada asignada (+/-15 min). No bloquea.
                function clearSuggestion() {
                  ['IN','OUT','BREAK_START','BREAK_END'].forEach(a => {
                    const b = document.getElementById('btn-' + a);
                    if (b) { b.classList.remove('suggested'); b.disabled = false; }
                  });
                  const s = document.getElementById('ficharSuggest');
                  s.classList.add('hidden'); s.classList.remove('soft');
                  document.getElementById('ficharOverride').classList.add('hidden');
                }
                // CONTENDO: solo el boton que toca. "Fichar otra cosa" reactiva todos.
                function ficharOverride() {
                  ['IN','OUT','BREAK_START','BREAK_END'].forEach(a => {
                    const b = document.getElementById('btn-' + a); if (b) b.disabled = false;
                  });
                  document.getElementById('ficharOverride').classList.add('hidden');
                }
                async function loadSugerencia() {
                  clearSuggestion();
                  try {
                    const r = await fetch(API + '/empleado/fichaje/sugerencia', { headers: authHeaders() });
                    if (!r.ok) return; // sin jornada o error: comportamiento normal, todos los botones
                    const d = await r.json();
                    const s = document.getElementById('ficharSuggest');
                    if (!d.hasSchedule) return;                 // hoy sin jornada / dia libre
                    if (!d.action) {                            // jornada ya completada
                      s.classList.remove('hidden'); s.classList.add('soft');
                      s.textContent = 'Has fichado toda tu jornada de hoy.';
                      return;
                    }
                    const label = typeLabel(d.action);
                    s.classList.remove('hidden');
                    if (d.inWindow) {
                      s.classList.remove('soft');
                      s.innerHTML = '<span class="big">Te toca: ' + label + '</span>'
                        + 'Hora prevista ' + d.scheduledTime + ' (margen ' + d.windowFrom + '-' + d.windowTo + ').';
                      const b = document.getElementById('btn-' + d.action);
                      if (b) b.classList.add('suggested');
                      // Desactiva los demas para no confundir (override los reactiva).
                      ['IN','OUT','BREAK_START','BREAK_END'].forEach(a => {
                        const x = document.getElementById('btn-' + a);
                        if (x) x.disabled = (a !== d.action);
                      });
                      document.getElementById('ficharOverride').classList.remove('hidden');
                    } else {
                      s.classList.add('soft');
                      s.innerHTML = '<span class="big">Proximo: ' + label + ' a las ' + d.scheduledTime + '</span>'
                        + 'Aun no es la hora (el boton se resaltara a partir de ' + d.windowFrom + ').';
                    }
                  } catch (e) { /* silencioso: no romper el fichaje por la sugerencia */ }
                }
                async function loadEstado() {
                  try {
                    const r = await fetch(API + '/empleado/fichaje/estado', { headers: authHeaders() });
                    if (r.status === 401 || r.status === 403) { localStorage.removeItem(LS_TOKEN); gotoPin(); return; }
                    if (!r.ok) {
                      let e = 'No se pudo cargar tu estado';
                      try { e = (await r.json()).message || e; } catch (x) {}
                      throw new Error(e);
                    }
                    const d = await r.json();
                    document.getElementById('ficharState').textContent =
                      d.clockedIn ? 'Estas DENTRO (jornada iniciada)' : 'Estas FUERA';
                    const ul = document.getElementById('ficharRecent');
                    ul.innerHTML = '';
                    (d.recent || []).forEach((e) => {
                      const li = document.createElement('li');
                      li.style.padding = '6px 0';
                      li.style.borderBottom = '1px solid #1e293b';
                      li.textContent = typeLabel(e.eventType) + '  -  ' + fmtTime(e.eventTime);
                      ul.appendChild(li);
                    });
                  } catch (e) { msg('ficharMsg', e.message); }
                }
                async function doPunch(type) {
                  msg('ficharMsg', 'Fichando...', true);
                  const geo = await getGeo();
                  try {
                    const r = await fetch(API + '/empleado/fichaje', {
                      method: 'POST', headers: authHeaders(),
                      body: JSON.stringify({ eventType: type,
                        lat: (geo.lat == null ? null : geo.lat),
                        lng: (geo.lng == null ? null : geo.lng) })
                    });
                    if (r.status === 401 || r.status === 403) { localStorage.removeItem(LS_TOKEN); gotoPin(); return; }
                    if (!r.ok) {
                      let e = 'No se pudo fichar';
                      try { e = (await r.json()).message || e; } catch (x) {}
                      throw new Error(e);
                    }
                    const d = await r.json();
                    msg('ficharMsg', typeLabel(d.eventType) + ' registrada'
                      + (d.warning ? '. ' + d.warning : ''), true);
                    loadEstado();
                    loadSugerencia();
                  } catch (e) { msg('ficharMsg', e.message); }
                }

                // ---- MEMP-3: mi jornada (horario + jornada real + festivo) ----
                function fmtMins(m) {
                  if (m == null) return '0h';
                  const h = Math.floor(m / 60), mm = m % 60;
                  return (h > 0 ? h + 'h ' : '') + (mm > 0 || h === 0 ? mm + 'm' : '').trim();
                }
                function gotoJornada() {
                  show('screen-jornada');
                  document.getElementById('jornadaMsg').textContent = '';
                  document.getElementById('jornadaBody').innerHTML = '';
                  loadJornada();
                }
                async function loadJornada() {
                  try {
                    const r = await fetch(API + '/empleado/jornada', { headers: authHeaders() });
                    if (r.status === 401 || r.status === 403) { localStorage.removeItem(LS_TOKEN); gotoPin(); return; }
                    if (!r.ok) {
                      let e = 'No se pudo cargar tu jornada';
                      try { e = (await r.json()).message || e; } catch (x) {}
                      throw new Error(e);
                    }
                    const d = await r.json();
                    document.getElementById('jornadaDate').textContent = 'Hoy ' + (d.date || '');
                    let html = '';
                    if (d.holiday) {
                      html += '<div class="suggest soft" style="border-color:#facc15;background:#422006;color:#fde68a">'
                        + '<span class="big">Hoy es festivo</span>' + (d.holiday.name || '') + '</div>';
                    }
                    // Horario asignado (JOR-2)
                    html += '<div class="card"><p class="sub" style="margin:0 0 10px">Tu horario de hoy</p>';
                    if (d.hasSchedule && d.blocks && d.blocks.length) {
                      html += '<ul style="list-style:none;padding:0;margin:0">';
                      d.blocks.forEach(b => {
                        const tag = b.type === 'BREAK' ? 'Pausa' : 'Trabajo';
                        const col = b.type === 'BREAK' ? '#94a3b8' : '#38bdf8';
                        html += '<li style="padding:6px 0;border-bottom:1px solid #0f172a">'
                          + '<span style="color:' + col + ';font-weight:600">' + b.start + ' - ' + b.end + '</span>'
                          + '<span style="color:#94a3b8;margin-left:10px;font-size:13px">' + tag + '</span></li>';
                      });
                      html += '</ul>';
                    } else {
                      html += '<p style="margin:0;color:#94a3b8">No tienes horario asignado para hoy (dia libre).</p>';
                    }
                    html += '</div>';
                    // Que toca ahora (FJ)
                    if (d.suggestion && d.suggestion.hasSchedule && d.suggestion.action) {
                      const lbl = typeLabel(d.suggestion.action);
                      html += '<div class="suggest' + (d.suggestion.inWindow ? '' : ' soft') + '">'
                        + '<span class="big">' + (d.suggestion.inWindow ? 'Te toca: ' + lbl : 'Proximo: ' + lbl + ' a las ' + d.suggestion.scheduledTime) + '</span>'
                        + (d.suggestion.inWindow ? 'Hora prevista ' + d.suggestion.scheduledTime : 'Se activara a partir de ' + d.suggestion.windowFrom) + '.</div>';
                    }
                    // Jornada real fichada (JOR-1)
                    html += '<div class="card"><p class="sub" style="margin:0 0 10px">Lo que llevas fichado hoy</p>';
                    if (d.worked) {
                      html += '<div style="font-size:15px">Trabajado: <b>' + fmtMins(d.worked.workedMinutes) + '</b>'
                        + ' &nbsp;·&nbsp; Pausa: ' + fmtMins(d.worked.pauseMinutes) + '</div>';
                      if (d.worked.firstIn) html += '<div style="color:#94a3b8;font-size:13px;margin-top:6px">Entrada ' + d.worked.firstIn
                        + (d.worked.lastOut ? ' · Ultima salida ' + d.worked.lastOut : '') + '</div>';
                    } else {
                      html += '<p style="margin:0;color:#94a3b8">Aun no has fichado hoy.</p>';
                    }
                    html += '</div>';
                    document.getElementById('jornadaBody').innerHTML = html;
                  } catch (e) { msg('jornadaMsg', e.message); }
                }

                // ---- MEMP-4: vacaciones y bajas ----
                function ausKindLabel(k) {
                  return ({VACATION:'Vacaciones', SICK_LEAVE:'Baja medica', PAID_LEAVE:'Permiso retribuido', OTHER:'Otros'})[k] || k;
                }
                function ausStatusInfo(s) {
                  return ({REQUESTED:['Pendiente','#0c4a6e','#7dd3fc'], APPROVED:['Aprobada','#14532d','#bbf7d0'],
                           REJECTED:['Rechazada','#7f1d1d','#fecaca'], CANCELLED:['Cancelada','#334155','#cbd5e1']})[s]
                         || [s,'#334155','#cbd5e1'];
                }
                function gotoAusencias() {
                  show('screen-ausencias');
                  localStorage.removeItem('aus_pending'); setBadge('badge-ausencias', '');
                  document.getElementById('ausMsg').textContent = '';
                  const hoy = new Date().toISOString().slice(0,10);
                  document.getElementById('ausDesde').value = hoy;
                  document.getElementById('ausHasta').value = hoy;
                  document.getElementById('ausMotivo').value = '';
                  document.getElementById('ausAdjunto').value = '';
                  document.getElementById('ausTipo').value = 'VACATION';
                  refreshAdjLabel();
                  loadAusencias();
                }
                function refreshAdjLabel() {
                  const baja = document.getElementById('ausTipo').value === 'SICK_LEAVE';
                  document.getElementById('ausAdjLbl').textContent = baja
                    ? 'Parte de baja (obligatorio)' : 'Adjunto (opcional: foto o PDF)';
                }
                async function loadAusencias() {
                  try {
                    const r = await fetch(API + '/empleado/ausencias', { headers: authHeaders() });
                    if (r.status === 401 || r.status === 403) { localStorage.removeItem(LS_TOKEN); gotoPin(); return; }
                    if (!r.ok) throw new Error('No se pudieron cargar tus solicitudes');
                    const list = await r.json();
                    const cont = document.getElementById('ausLista');
                    cont.innerHTML = '';
                    if (!list.length) { cont.innerHTML = '<p style="color:#94a3b8">Aun no tienes solicitudes.</p>'; return; }
                    list.forEach(s => {
                      const si = ausStatusInfo(s.status);
                      const nAdj = (s.attachments || []).length;
                      const div = document.createElement('div');
                      div.className = 'card';
                      div.style.padding = '14px';
                      div.innerHTML =
                        '<div style="display:flex;justify-content:space-between;align-items:center">'
                        + '<b>' + ausKindLabel(s.kind) + '</b>'
                        + '<span style="background:' + si[1] + ';color:' + si[2] + ';padding:3px 10px;border-radius:10px;font-size:12px">' + si[0] + '</span></div>'
                        + '<div style="color:#cbd5e1;font-size:14px;margin-top:6px">' + s.startDate + ' &rarr; ' + s.endDate
                        + (s.days ? ' (' + s.days + ' dias)' : '') + '</div>'
                        + (s.reason ? '<div style="color:#94a3b8;font-size:13px;margin-top:4px">' + s.reason + '</div>' : '')
                        + (nAdj ? '<div style="color:#94a3b8;font-size:13px;margin-top:4px">&#128206; ' + nAdj + ' adjunto(s)</div>' : '')
                        + (s.reviewNotes ? '<div style="color:#94a3b8;font-size:13px;margin-top:4px">Nota: ' + s.reviewNotes + '</div>' : '');
                      if (s.status === 'REQUESTED') {
                        const btn = document.createElement('button');
                        btn.className = 'secondary'; btn.textContent = 'Cancelar solicitud';
                        btn.onclick = () => cancelAusencia(s.id);
                        div.appendChild(btn);
                      }
                      cont.appendChild(div);
                    });
                  } catch (e) { msg('ausMsg', e.message); }
                }
                function readFileB64(file) {
                  return new Promise((res, rej) => {
                    const fr = new FileReader();
                    fr.onload = () => res({ filename: file.name, contentType: file.type, base64: fr.result });
                    fr.onerror = () => rej(new Error('No se pudo leer ' + file.name));
                    fr.readAsDataURL(file);
                  });
                }
                async function submitAusencia() {
                  const kind = document.getElementById('ausTipo').value;
                  const desde = document.getElementById('ausDesde').value;
                  const hasta = document.getElementById('ausHasta').value;
                  const motivo = document.getElementById('ausMotivo').value.trim();
                  const files = document.getElementById('ausAdjunto').files;
                  if (!desde || !hasta) { msg('ausMsg', 'Indica las fechas desde y hasta'); return; }
                  if (hasta < desde) { msg('ausMsg', 'La fecha hasta es anterior a la de inicio'); return; }
                  if (kind === 'SICK_LEAVE' && files.length === 0) { msg('ausMsg', 'La baja requiere adjuntar el parte de baja'); return; }
                  msg('ausMsg', 'Enviando...', true);
                  try {
                    const attachments = [];
                    for (let i = 0; i < files.length; i++) attachments.push(await readFileB64(files[i]));
                    const r = await fetch(API + '/empleado/ausencias', {
                      method: 'POST', headers: authHeaders(),
                      body: JSON.stringify({ kind: kind, startDate: desde, endDate: hasta,
                        reason: motivo || null, attachments: attachments })
                    });
                    if (r.status === 401 || r.status === 403) { localStorage.removeItem(LS_TOKEN); gotoPin(); return; }
                    if (!r.ok) {
                      let e = 'No se pudo enviar la solicitud';
                      try { e = (await r.json()).message || e; } catch (x) {}
                      throw new Error(e);
                    }
                    msg('ausMsg', 'Solicitud enviada', true);
                    document.getElementById('ausAdjunto').value = '';
                    document.getElementById('ausMotivo').value = '';
                    loadAusencias();
                  } catch (e) { msg('ausMsg', e.message); }
                }
                async function cancelAusencia(id) {
                  try {
                    const r = await fetch(API + '/empleado/ausencias/' + id + '/cancel', {
                      method: 'POST', headers: authHeaders() });
                    if (!r.ok) {
                      let e = 'No se pudo cancelar';
                      try { e = (await r.json()).message || e; } catch (x) {}
                      throw new Error(e);
                    }
                    loadAusencias();
                  } catch (e) { msg('ausMsg', e.message); }
                }

                // ---- MEMP-5: nominas ----
                function mesNombre(m) {
                  return ['','Enero','Febrero','Marzo','Abril','Mayo','Junio','Julio','Agosto',
                          'Septiembre','Octubre','Noviembre','Diciembre'][m] || ('Mes ' + m);
                }
                function gotoNominas() {
                  show('screen-nominas');
                  document.getElementById('nominasMsg').textContent = '';
                  document.getElementById('nominasLista').innerHTML = '';
                  loadNominas();
                }
                async function loadNominas() {
                  try {
                    const r = await fetch(API + '/empleado/nominas', { headers: authHeaders() });
                    if (r.status === 401 || r.status === 403) { localStorage.removeItem(LS_TOKEN); gotoPin(); return; }
                    if (!r.ok) throw new Error('No se pudieron cargar tus nominas');
                    const list = await r.json();
                    const cont = document.getElementById('nominasLista');
                    cont.innerHTML = '';
                    if (!list.length) { cont.innerHTML = '<p style="color:#94a3b8">Aun no tienes nominas disponibles.</p>'; return; }
                    list.forEach(p => {
                      const ack = !!p.acknowledgedAt;
                      const div = document.createElement('div');
                      div.className = 'card'; div.style.padding = '14px';
                      div.innerHTML =
                        '<div style="display:flex;justify-content:space-between;align-items:center">'
                        + '<b>' + mesNombre(p.month) + ' ' + p.year + '</b>'
                        + (ack ? '<span style="background:#14532d;color:#bbf7d0;padding:3px 10px;border-radius:10px;font-size:12px">Recibi confirmado</span>'
                               : '<span style="background:#0c4a6e;color:#7dd3fc;padding:3px 10px;border-radius:10px;font-size:12px">Pendiente de recibi</span>')
                        + '</div>'
                        + '<div style="color:#cbd5e1;font-size:14px;margin-top:6px">Liquido: <b>' + (p.net != null ? p.net : '-') + ' EUR</b>'
                        + '<span style="color:#94a3b8"> &nbsp;·&nbsp; Bruto ' + (p.gross != null ? p.gross : '-') + '</span></div>';
                      const row = document.createElement('div');
                      row.style.display = 'flex'; row.style.gap = '10px';
                      const pdfBtn = document.createElement('button');
                      pdfBtn.textContent = 'Ver PDF'; pdfBtn.onclick = () => openNominaPdf(p.id);
                      row.appendChild(pdfBtn);
                      if (!ack) {
                        const ackBtn = document.createElement('button');
                        ackBtn.className = 'secondary'; ackBtn.textContent = 'Firmar recibi';
                        ackBtn.onclick = () => ackNomina(p.id);
                        row.appendChild(ackBtn);
                      }
                      div.appendChild(row);
                      cont.appendChild(div);
                    });
                  } catch (e) { msg('nominasMsg', e.message); }
                }
                async function openNominaPdf(id) {
                  msg('nominasMsg', 'Abriendo PDF...', true);
                  try {
                    const r = await fetch(API + '/empleado/nominas/' + id + '/pdf', { headers: authHeaders() });
                    if (r.status === 401 || r.status === 403) { localStorage.removeItem(LS_TOKEN); gotoPin(); return; }
                    if (!r.ok) throw new Error('No se pudo abrir el PDF');
                    const blob = await r.blob();
                    const url = URL.createObjectURL(blob);
                    // Abrir en pestana nueva; si el navegador lo bloquea, descargar.
                    const w = window.open(url, '_blank');
                    if (!w) {
                      const a = document.createElement('a');
                      a.href = url; a.download = 'nomina.pdf'; document.body.appendChild(a); a.click(); a.remove();
                    }
                    setTimeout(() => URL.revokeObjectURL(url), 60000);
                    document.getElementById('nominasMsg').textContent = '';
                  } catch (e) { msg('nominasMsg', e.message); }
                }
                let ackPayslipId = null;
                function ackNomina(id) {
                  ackPayslipId = id;
                  document.getElementById('ackPin').value = '';
                  document.getElementById('ackMsg').textContent = '';
                  document.getElementById('ackModal').classList.remove('hidden');
                  setTimeout(() => { try { document.getElementById('ackPin').focus(); } catch(e){} }, 50);
                }
                function closeAck() {
                  document.getElementById('ackModal').classList.add('hidden');
                  ackPayslipId = null;
                }
                async function confirmAck() {
                  const pin = document.getElementById('ackPin').value.replace(/[^0-9]/g, '');
                  if (pin.length < 4) { msg('ackMsg', 'Introduce tu PIN (4-8 digitos)'); return; }
                  if (!ackPayslipId) { closeAck(); return; }
                  msg('ackMsg', 'Firmando...', true);
                  try {
                    const r = await fetch(API + '/empleado/nominas/' + ackPayslipId + '/acknowledge', {
                      method: 'POST', headers: authHeaders(),
                      body: JSON.stringify({ pin: pin, device: navigator.userAgent.slice(0, 200) })
                    });
                    if (!r.ok) {
                      let e = 'No se pudo firmar';
                      try { e = (await r.json()).message || e; } catch (x) {}
                      // 401 aqui = PIN incorrecto (no es sesion caducada): no cerramos sesion.
                      throw new Error(r.status === 401 ? (e || 'PIN incorrecto') : e);
                    }
                    closeAck();
                    msg('nominasMsg', 'Recibi firmado. Gracias.', true);
                    loadNominas(); updateHomeBadges();
                  } catch (e) { msg('ackMsg', e.message); }
                }

                // ---- NOTIF-RT: tiempo real (SSE) + fallback de sondeo + badges ----
                let evtSource = null;
                let sseAlive = false;     // se pone true si el SSE entrega algun evento
                let pollTimer = null;     // fallback cuando el SSE no fluye (tunel bufferiza)
                function currentScreen() {
                  for (const s of ['screen-fichar','screen-jornada','screen-ausencias','screen-nominas','screen-home']) {
                    if (!document.getElementById(s).classList.contains('hidden')) return s;
                  }
                  return null;
                }
                function setBadge(id, text) {
                  const el = document.getElementById(id);
                  if (!el) return;
                  if (text) { el.textContent = text; el.classList.remove('hidden'); }
                  else { el.classList.add('hidden'); }
                }
                async function updateHomeBadges() {
                  // Nominas sin recibi confirmado.
                  try {
                    const r = await fetch(API + '/empleado/nominas', { headers: authHeaders() });
                    if (r.ok) {
                      const list = await r.json();
                      const pend = list.filter(p => !p.acknowledgedAt).length;
                      setBadge('badge-nominas', pend > 0 ? String(pend) : '');
                    }
                  } catch (e) {}
                  // Solicitudes resueltas sin ver (marca local).
                  setBadge('badge-ausencias', localStorage.getItem('aus_pending') === '1' ? '!' : '');
                }
                function connectRealtime() {
                  const token = localStorage.getItem(LS_TOKEN);
                  if (!token) return;
                  sseAlive = false;
                  if (evtSource) { evtSource.close(); evtSource = null; }
                  try {
                    evtSource = new EventSource(API + '/realtime/stream?token=' + encodeURIComponent(token));
                  } catch (e) { return; }
                  evtSource.addEventListener('ready', () => { sseAlive = true; });
                  evtSource.addEventListener('timeclock', () => {
                    sseAlive = true;
                    if (currentScreen() === 'screen-fichar') { loadEstado(); loadSugerencia(); }
                    if (currentScreen() === 'screen-jornada') loadJornada();
                  });
                  evtSource.addEventListener('leave_reviewed', () => {
                    sseAlive = true;
                    if (currentScreen() === 'screen-ausencias') loadAusencias();
                    else { localStorage.setItem('aus_pending', '1'); updateHomeBadges(); }
                  });
                  evtSource.addEventListener('payslip', () => {
                    sseAlive = true;
                    if (currentScreen() === 'screen-nominas') loadNominas();
                    updateHomeBadges();
                  });
                  // onerror: EventSource reintenta solo; el sondeo cubre mientras tanto.
                }
                function disconnectRealtime() { if (evtSource) { evtSource.close(); evtSource = null; } }

                // Fallback de sondeo: si el SSE no entrega (Cloudflare bufferiza el
                // quick-tunnel), refrescamos la pantalla abierta + badges cada 18s.
                // Si el SSE funciona (sseAlive), el sondeo no hace nada.
                function startPolling() {
                  if (pollTimer) return;
                  pollTimer = setInterval(() => {
                    if (sseAlive || !localStorage.getItem(LS_TOKEN)) return;
                    const scr = currentScreen();
                    if (scr === 'screen-fichar') { loadEstado(); loadSugerencia(); }
                    else if (scr === 'screen-jornada') loadJornada();
                    else if (scr === 'screen-ausencias') loadAusencias();
                    else if (scr === 'screen-nominas') loadNominas();
                    updateHomeBadges();
                  }, 18000);
                }
                function stopPolling() { if (pollTimer) { clearInterval(pollTimer); pollTimer = null; } }

                document.getElementById('activateBtn').onclick = async () => {
                  const token = document.getElementById('inviteInput').value.trim();
                  if (!token) { msg('inviteMsg', 'Pega el codigo de invitacion'); return; }
                  try {
                    const res = await activate(token);
                    localStorage.setItem(LS_SECRET, res.deviceSecret);
                    localStorage.setItem(LS_NAME, res.employeeName || '');
                    localStorage.setItem(LS_COMPANY, res.companyName || '');
                    gotoPin();
                  } catch (e) { msg('inviteMsg', e.message); }
                };

                // Fuerza solo digitos (evita basura de autofill de iOS) y limita a 8.
                document.getElementById('pinInput').addEventListener('input', (e) => {
                  e.target.value = e.target.value.replace(/[^0-9]/g, '').slice(0, 8);
                });

                document.getElementById('copyCodeBtn').onclick = async () => {
                  const code = document.getElementById('inviteInput').value.trim();
                  if (!code) { msg('inviteMsg', 'No hay codigo para copiar'); return; }
                  try {
                    await navigator.clipboard.writeText(code);
                  } catch (err) {
                    const el = document.getElementById('inviteInput');
                    el.removeAttribute('readonly'); el.select();
                    try { document.execCommand('copy'); } catch (e2) {}
                    el.setAttribute('readonly', 'readonly');
                  }
                  msg('inviteMsg', 'Codigo copiado. Ahora instala la app y abrela desde el icono.', true);
                };

                document.getElementById('pinBtn').onclick = async () => {
                  const pin = document.getElementById('pinInput').value.replace(/[^0-9]/g, '');
                  const secret = localStorage.getItem(LS_SECRET);
                  if (!secret) { show('screen-invite'); return; }
                  if (pin.length < 4 || pin.length > 8) {
                    msg('pinMsg', 'El PIN son de 4 a 8 digitos.');
                    return;
                  }
                  try {
                    const res = await pinLogin(secret, pin);
                    localStorage.setItem(LS_TOKEN, res.accessToken);
                    if (res.displayName) localStorage.setItem(LS_NAME, res.displayName);
                    document.getElementById('pinInput').value = '';
                    gotoHome();
                    connectRealtime();
                    startPolling();
                  } catch (e) { msg('pinMsg', e.message); }
                };

                document.getElementById('forgetBtn').onclick = () => {
                  disconnectRealtime(); stopPolling();
                  localStorage.removeItem(LS_SECRET);
                  localStorage.removeItem(LS_TOKEN);
                  show('screen-invite');
                };
                document.getElementById('logoutBtn').onclick = () => {
                  disconnectRealtime(); stopPolling();
                  localStorage.removeItem(LS_TOKEN);
                  gotoPin();
                };

                function store(res) {
                  localStorage.setItem(LS_SECRET, res.deviceSecret);
                  localStorage.setItem(LS_NAME, res.employeeName || '');
                  localStorage.setItem(LS_COMPANY, res.companyName || '');
                }
                // iOS: la PWA instalada tiene almacenamiento propio (distinto de Safari),
                // por eso solo activamos automaticamente DENTRO de la app instalada.
                const standalone = window.matchMedia('(display-mode: standalone)').matches
                  || window.navigator.standalone === true;

                (async function init() {
                  if (localStorage.getItem(LS_SECRET)) { gotoPin(); return; }
                  const invite = qp('invite');
                  if (invite && standalone) {
                    try {
                      const res = await activate(invite);
                      store(res);
                      history.replaceState({}, '', '/api/public/empleado/app');
                      gotoPin();
                      return;
                    } catch (e) {
                      show('screen-invite');
                      document.getElementById('inviteInput').value = invite;
                      msg('inviteMsg', e.message);
                      return;
                    }
                  }
                  show('screen-invite');
                  if (invite) { document.getElementById('inviteInput').value = invite; }
                  if (!standalone) {
                    // En el navegador: instalar primero. No mostramos "Activar"
                    // (gastaria la activacion en Safari, que tiene otro almacen);
                    // en su lugar, boton para copiar el codigo.
                    document.getElementById('installHint').classList.remove('hidden');
                    document.getElementById('activateBtn').classList.add('hidden');
                    document.getElementById('copyCodeBtn').classList.remove('hidden');
                    document.getElementById('inviteInput').setAttribute('readonly', 'readonly');
                  }
                })();

                if ('serviceWorker' in navigator) {
                  navigator.serviceWorker.register('/api/public/empleado/sw.js').catch(() => {});
                }
              </script>
            </body>
            </html>
            """;
}
