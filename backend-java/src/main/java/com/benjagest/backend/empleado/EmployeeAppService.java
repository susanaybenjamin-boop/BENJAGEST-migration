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
            const CACHE = 'benjagest-empleado-v4';
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
                .grid { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
                .tile { background: #1e293b; border-radius: 16px; padding: 22px 12px; text-align: center; }
                .tile .ic { font-size: 30px; }
                .tile .lbl { margin-top: 8px; font-size: 14px; }
                .tile .soon { display:block; font-size: 11px; color: #64748b; margin-top: 4px; }
                .msg { padding: 12px; border-radius: 12px; font-size: 14px; margin-top: 12px; }
                .msg.err { background: #7f1d1d; color: #fecaca; }
                .msg.ok { background: #14532d; color: #bbf7d0; }
                .hidden { display: none; }
                .center { text-align: center; }
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
                    <div class="tile"><div class="ic">&#128337;</div><div class="lbl">Fichar</div><span class="soon">proximamente</span></div>
                    <div class="tile"><div class="ic">&#127958;</div><div class="lbl">Vacaciones y bajas</div><span class="soon">proximamente</span></div>
                    <div class="tile"><div class="ic">&#128196;</div><div class="lbl">Nominas</div><span class="soon">proximamente</span></div>
                    <div class="tile"><div class="ic">&#128197;</div><div class="lbl">Mi jornada</div><span class="soon">proximamente</span></div>
                  </div>
                  <button class="secondary" id="logoutBtn">Cerrar sesion</button>
                </div>
              </div>

              <script>
                const API = '/api';
                const LS_SECRET = 'benjagest_emp_secret';
                const LS_NAME = 'benjagest_emp_name';
                const LS_COMPANY = 'benjagest_emp_company';
                const LS_TOKEN = 'benjagest_emp_token';

                function show(id) {
                  ['screen-invite','screen-pin','screen-home'].forEach(s => {
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
                }

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
                  } catch (e) { msg('pinMsg', e.message); }
                };

                document.getElementById('forgetBtn').onclick = () => {
                  localStorage.removeItem(LS_SECRET);
                  localStorage.removeItem(LS_TOKEN);
                  show('screen-invite');
                };
                document.getElementById('logoutBtn').onclick = () => {
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
