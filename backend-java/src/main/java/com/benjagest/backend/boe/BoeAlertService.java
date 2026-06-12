package com.benjagest.backend.boe;

import com.benjagest.backend.advisory.notifications.AdvisoryNotificationService;
import com.benjagest.backend.auth.RequiresRole;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOE-RSS — alertas diarias del Boletín Oficial del Estado sobre
 * temas fiscales y laborales.
 *
 * <p>Cron diario 06:00. Llama a la API de datos abiertos del BOE
 * (https://www.boe.es/datosabiertos/api/boe/sumario/{YYYYMMDD}) y
 * filtra los artículos cuyo título contenga alguna keyword fiscal o
 * laboral. Por cada hit nuevo:
 * <ul>
 *   <li>Inserta fila en {@code boe_alerts} (UK por {@code boe_id}
 *       previene duplicados si el job se relanza).</li>
 *   <li>Notifica a cada asesoría activa via
 *       {@link AdvisoryNotificationService#emit}.</li>
 * </ul>
 *
 * <p>Defensivo: si BOE no responde o el formato cambia, captura la
 * excepción y deja log. No bloquea otros schedulers ni el arranque.
 */
@Service
public class BoeAlertService {

    private static final Logger log = LoggerFactory.getLogger(BoeAlertService.class);
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final List<String> KEYWORDS = List.of(
            "AEAT", "IVA", "IRPF", "Impuesto Sociedades", "RD ", "Real Decreto",
            "factura electrónica", "Verifactu", "SIF", "SEPE", "Seguridad Social",
            "salario", "salarial", "nómina", "fichaje", "registro horario",
            "contrato de trabajo", "Estatuto de los Trabajadores",
            "RD 1619/2012", "RD 1007/2023", "Orden HAC",
            "BOE-A-2026", "BOE-A-2027");

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private static final Pattern ID_RE = Pattern.compile("\"identificador\"\\s*:\\s*\"(BOE-A-[^\"]+)\"");
    private static final Pattern TITLE_RE = Pattern.compile("\"titulo\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern URL_RE = Pattern.compile("\"url_pdf\"\\s*:\\s*\\{[^}]*\"texto\"\\s*:\\s*\"([^\"]+)\"");
    private static final Pattern DEPT_RE = Pattern.compile("\"departamento\"\\s*:\\s*\\{[^}]*\"nombre\"\\s*:\\s*\"([^\"]+)\"");

    private final JdbcTemplate jdbc;
    private final AdvisoryNotificationService notifService;

    public BoeAlertService(JdbcTemplate jdbc, AdvisoryNotificationService notifService) {
        this.jdbc = jdbc;
        this.notifService = notifService;
    }

    @Scheduled(cron = "0 0 6 * * *")
    public void runScheduled() {
        try { runForDate(LocalDate.now()); }
        catch (Exception ex) {
            log.warn("BOE-RSS: fallo en scheduler diario", ex);
        }
    }

    public RunSummary runForDate(LocalDate date) {
        int hits = 0;
        int newInserts = 0;
        try {
            String url = "https://www.boe.es/datosabiertos/api/boe/sumario/"
                    + date.format(API_DATE);
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .header("Accept", "application/json")
                    .timeout(Duration.ofSeconds(30))
                    .GET().build();
            HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 400) {
                log.info("BOE-RSS: no hay boletín para {} (HTTP {})", date, res.statusCode());
                return new RunSummary(date.toString(), 0, 0, "Sin boletín");
            }
            String body = res.body();
            List<BoeHit> hitsList = parseAndFilter(body);
            hits = hitsList.size();
            for (BoeHit hit : hitsList) {
                if (insertIfNew(hit, date)) {
                    newInserts++;
                    notifyAdvisories(hit, date);
                }
            }
        } catch (Exception ex) {
            log.warn("BOE-RSS: error para {}", date, ex);
            return new RunSummary(date.toString(), hits, newInserts, ex.getMessage());
        }
        log.info("BOE-RSS: fecha {} → {} hits ({} nuevos)", date, hits, newInserts);
        return new RunSummary(date.toString(), hits, newInserts, null);
    }

    private List<BoeHit> parseAndFilter(String body) {
        List<BoeHit> out = new ArrayList<>();
        // Parser muy ligero: no usa Jackson para no añadir dependencia
        // donde no la había. Lee identificador + título + url + departamento
        // de bloques contiguos en el JSON.
        Matcher mId = ID_RE.matcher(body);
        Matcher mTitle = TITLE_RE.matcher(body);
        Matcher mUrl = URL_RE.matcher(body);
        Matcher mDept = DEPT_RE.matcher(body);
        while (mId.find()) {
            String id = mId.group(1);
            String title = mTitle.find() ? unescape(mTitle.group(1)) : "";
            String url = mUrl.find() ? unescape(mUrl.group(1)) : "";
            String dept = mDept.find() ? unescape(mDept.group(1)) : "";
            List<String> matched = matchedKeywords(title);
            if (matched.isEmpty()) continue;
            out.add(new BoeHit(id, title, url, dept, String.join(", ", matched)));
        }
        return out;
    }

    private List<String> matchedKeywords(String title) {
        if (title == null || title.isBlank()) return List.of();
        String low = title.toLowerCase();
        List<String> hits = new ArrayList<>();
        for (String k : KEYWORDS) {
            if (low.contains(k.toLowerCase())) hits.add(k);
        }
        return hits;
    }

    private boolean insertIfNew(BoeHit hit, LocalDate date) {
        try {
            int n = jdbc.update("""
                    INSERT IGNORE INTO boe_alerts
                           (id, alert_date, boe_id, title, url, department,
                            keywords_matched)
                    VALUES (?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    java.sql.Date.valueOf(date), hit.id(),
                    truncate(hit.title(), 500),
                    truncate(hit.url(), 500),
                    truncate(hit.department(), 200),
                    truncate(hit.keywordsMatched(), 300));
            return n > 0;
        } catch (RuntimeException ex) {
            log.warn("BOE-RSS: no se pudo guardar {}", hit.id(), ex);
            return false;
        }
    }

    private void notifyAdvisories(BoeHit hit, LocalDate date) {
        try {
            List<String> advisoryIds = jdbc.queryForList("""
                    SELECT id FROM companies
                     WHERE company_type IN ('INTERNAL', 'ADVISORY')
                       AND parent_company_id IS NULL
                    """, String.class);
            for (String advisoryId : advisoryIds) {
                try {
                    notifService.emit(new AdvisoryNotificationService.EmitRequest(
                            advisoryId, null,
                            "BOE_FISCAL_LABOR",
                            AdvisoryNotificationService.SEVERITY_INFO,
                            "BOE — " + truncate(hit.title(), 150),
                            "Palabras clave: " + hit.keywordsMatched()
                                    + (hit.department().isEmpty() ? ""
                                        : "\nDepartamento: " + hit.department())
                                    + (hit.url().isEmpty() ? ""
                                        : "\nURL: " + hit.url()),
                            "boe:" + hit.id()));
                } catch (RuntimeException ex) {
                    log.debug("BOE-RSS: no se pudo notificar a {}", advisoryId);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("BOE-RSS: error notificando", ex);
        }
    }

    private static String unescape(String s) {
        return s == null ? "" : s.replace("\\\"", "\"").replace("\\n", " ").replace("\\/", "/");
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    public record BoeHit(String id, String title, String url, String department,
                          String keywordsMatched) {}

    public record RunSummary(String date, int hits, int newInserts, String error) {}

    public List<Alert> listRecent(int days) {
        return jdbc.query("""
                SELECT id, alert_date, boe_id, title, url, department,
                       keywords_matched, created_at
                  FROM boe_alerts
                 WHERE alert_date >= ?
                 ORDER BY alert_date DESC, created_at DESC
                """, (rs, n) -> new Alert(
                        rs.getString("id"),
                        rs.getDate("alert_date").toLocalDate().toString(),
                        rs.getString("boe_id"),
                        rs.getString("title"),
                        rs.getString("url"),
                        rs.getString("department"),
                        rs.getString("keywords_matched"),
                        rs.getTimestamp("created_at").toInstant().toString()),
                java.sql.Date.valueOf(LocalDate.now().minusDays(Math.max(1, days))));
    }

    public record Alert(String id, String alertDate, String boeId, String title,
                          String url, String department, String keywordsMatched,
                          String createdAt) {}

    @RestController
    @RequestMapping("/api/boe-alerts")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {
        private final BoeAlertService service;

        public Controller(BoeAlertService service) { this.service = service; }

        @GetMapping
        public List<Alert> list(
                @RequestParam(value = "days", required = false, defaultValue = "30") int days) {
            return service.listRecent(days);
        }

        @PostMapping("/run-now")
        public RunSummary runNow(
                @RequestParam(value = "date", required = false) String dateStr) {
            LocalDate date = dateStr == null || dateStr.isBlank()
                    ? LocalDate.now() : LocalDate.parse(dateStr);
            return service.runForDate(date);
        }
    }
}
