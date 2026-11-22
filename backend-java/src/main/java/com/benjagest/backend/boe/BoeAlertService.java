package com.benjagest.backend.boe;

import com.benjagest.backend.advisory.notifications.AdvisoryNotificationService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.boe.BoeMatcher.BoeItem;
import com.benjagest.backend.config.WindowsAwareTrust;
import com.benjagest.backend.boe.BoeMatcher.Match;
import com.benjagest.backend.tenant.TenantContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * BOE-RSS — alertas diarias del Boletín Oficial del Estado sobre temas
 * fiscales y laborales.
 *
 * <p>Cron diario 06:00. Llama a la API de datos abiertos del BOE
 * ({@code https://www.boe.es/datosabiertos/api/boe/sumario/{YYYYMMDD}}),
 * recorre los artículos del sumario y, <b>para cada asesoría</b>, se queda con
 * los que encajan con SUS preferencias ({@link BoeAlertPrefs}). Cada acierto
 * nuevo se guarda en {@code boe_alerts} (UK por {@code boe_id}, así que el
 * catálogo no se duplica aunque el job se relance) y se notifica solo a las
 * empresas a las que les encaja.
 *
 * <p><b>BOE-FILTRO (2026-11-20)</b> — Benjamin: <i>"el filtro es light, y se
 * llena de noticias que no interesan, tendríamos que poder escoger qué queremos
 * que nos llegue"</i>. Lo que cambia respecto a la versión anterior:
 * <ol>
 *   <li><b>El filtro deja de ser una constante del código</b> y pasa a ser
 *       configurable por empresa, con pantalla propia.</li>
 *   <li><b>Se filtra por SECCIÓN y DEPARTAMENTO</b>, no solo por palabra. El
 *       departamento ya se guardaba desde la V103 sin usarse y la sección ni se
 *       leía; son las dos palancas que más ruido quitan.</li>
 *   <li><b>Las palabras se comparan COMPLETAS</b> ({@link BoeMatcher}) en vez de
 *       con {@code contains()}.</li>
 *   <li><b>El JSON se parsea con Jackson</b>. El parser anterior recorría el
 *       cuerpo con cuatro {@code Matcher.find()} INDEPENDIENTES (id, título,
 *       url, departamento); en cuanto un bloque no traía alguno de los cuatro,
 *       los punteros se desincronizaban y los títulos se emparejaban con el
 *       enlace de otro artículo. Con el sumario real se comprobó que fallaba ya
 *       en el primero: el propio sumario del día tiene su {@code url_pdf} antes
 *       que cualquier artículo, así que el primer aviso se llevaba el PDF del
 *       boletín entero. El comentario que justificaba evitar Jackson
 *       ("para no añadir dependencia donde no la había") estaba obsoleto: el
 *       backend ya lo usa en media docena de servicios.</li>
 * </ol>
 *
 * <p>Defensivo: si el BOE no responde o cambia el formato, captura la excepción
 * y deja log. No bloquea otros schedulers ni el arranque.
 */
@Service
public class BoeAlertService {

    private static final Logger log = LoggerFactory.getLogger(BoeAlertService.class);
    private static final DateTimeFormatter API_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    /**
     * BOE-TLS — el cliente confia en el almacen de Java Y en el de Windows.
     * Desde el 18-oct-2026 www.boe.es presenta una cadena de la FNMT-RCM, cuya
     * raiz NO viene en el cacerts de Java: sin esto el barrido diario muere con
     * PKIX y, como la excepcion se captura, falla en silencio (en produccion
     * llevaba mas de un mes asi). Ver {@link WindowsAwareTrust}.
     */
    private static final HttpClient HTTP = buildHttpClient();

    private static HttpClient buildHttpClient() {
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10));
        javax.net.ssl.SSLContext ctx = WindowsAwareTrust.sslContextOrNull();
        if (ctx != null) b.sslContext(ctx);
        return b.build();
    }

    private final JdbcTemplate jdbc;
    private final AdvisoryNotificationService notifService;
    private final BoeAlertPrefsService prefsService;
    private final ObjectMapper mapper;

    public BoeAlertService(JdbcTemplate jdbc,
                           AdvisoryNotificationService notifService,
                           BoeAlertPrefsService prefsService,
                           ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.notifService = notifService;
        this.prefsService = prefsService;
        this.mapper = mapper;
    }

    /**
     * SUPERV — inyectado por setter (ver la nota de BackupService).
     */
    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.benjagest.backend.supervision.JobSupervisor supervisor;

    @Scheduled(cron = "0 0 6 * * *")
    public void runScheduled() {
        long t0 = System.currentTimeMillis();
        try {
            RunSummary r = runForDate(LocalDate.now());
            // SUPERV — OJO: runForDate NO lanza excepcion cuando el BOE falla,
            // devuelve el motivo en r.error(). Fue exactamente asi como estuvo
            // 34 dias caido sin que nadie lo supiera: para el que mirase desde
            // fuera, el metodo "terminaba bien". Si hay error, es ERROR.
            superviseRun(r, System.currentTimeMillis() - t0);
        } catch (Exception ex) {
            log.warn("BOE-RSS: fallo en scheduler diario", ex);
            supervise("ERROR", ex.getClass().getSimpleName()
                    + (ex.getMessage() == null ? "" : ": " + ex.getMessage()),
                    System.currentTimeMillis() - t0);
        }
    }

    /**
     * Tres resultados posibles, y distinguirlos importa:
     * <ul>
     *   <li><b>SKIPPED</b> — ese dia no hubo boletin. El BOE publica de lunes a
     *       sabado, asi que <b>todos los domingos</b> la API responde 404. No es
     *       un fallo y no puede pintarse en rojo: un aviso falso cada semana es
     *       la forma de que se dejen de leer los de verdad.</li>
     *   <li><b>ERROR</b> — se intento y fallo (red, certificado, formato).</li>
     *   <li><b>OK</b> — se leyo el boletin, con o sin avisos. Cero avisos NO es
     *       un fallo: es el filtro haciendo su trabajo.</li>
     * </ul>
     */
    private void superviseRun(RunSummary r, long ms) {
        if (r == null) {
            supervise("ERROR", "Sin resultado", ms);
        } else if (r.noBulletin()) {
            supervise("SKIPPED", "Sin boletin ese dia (el BOE no publica los domingos)", ms);
        } else if (r.error() != null && !r.error().isBlank()) {
            supervise("ERROR", r.error(), ms);
        } else {
            supervise("OK", r.totalArticles() + " articulos, " + r.hits() + " avisos", ms);
        }
    }

    private void supervise(String status, String message, long ms) {
        if (supervisor == null) return;
        supervisor.record(com.benjagest.backend.supervision.SupervisedJob.BOE_ALERTS,
                null, status, message, ms);
    }

    // ====================================================================
    //  Pasada diaria
    // ====================================================================

    public RunSummary runForDate(LocalDate date) {
        int hits = 0;
        int newInserts = 0;
        try {
            List<BoeItem> items = fetchItems(date);
            if (items.isEmpty()) {
                log.info("BOE-RSS: no hay boletín para {} (o venía vacío)", date);
                return RunSummary.noBulletin(date.toString());
            }

            // Un aviso puede interesarle a varias asesorías. Se guarda UNA fila
            // en el catálogo (UK por boe_id) y se notifica a cada una por
            // separado, con las palabras que SU filtro disparó — si no, dos
            // asesorías con filtros distintos verían el motivo de la otra.
            Map<String, Match> catalogo = new LinkedHashMap<>();
            for (String advisoryId : advisoryCompanyIds()) {
                BoeAlertPrefs prefs = prefsService.get(advisoryId);
                List<Match> matches = BoeMatcher.filter(items, prefs);
                for (Match m : matches) {
                    catalogo.putIfAbsent(m.item().id(), m);
                    notifyCompany(advisoryId, m, date);
                }
            }

            hits = catalogo.size();
            for (Match m : catalogo.values()) {
                if (insertIfNew(m, date)) newInserts++;
            }
            log.info("BOE-RSS: fecha {} → {} articulos, {} avisos ({} nuevos)",
                    date, items.size(), hits, newInserts);
            return RunSummary.ok(date.toString(), hits, newInserts, items.size());
        } catch (Exception ex) {
            log.warn("BOE-RSS: error para {}", date, ex);
            return RunSummary.failed(date.toString(), hits, newInserts, ex.getMessage());
        }
    }

    // ====================================================================
    //  Descarga + parseo del sumario
    // ====================================================================

    /**
     * Descarga el sumario del día y devuelve TODOS sus artículos, sin filtrar.
     * Filtrar es cosa de {@link BoeMatcher}, que se aplica una vez por empresa.
     */
    public List<BoeItem> fetchItems(LocalDate date) throws Exception {
        String url = "https://www.boe.es/datosabiertos/api/boe/sumario/" + date.format(API_DATE);
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(20))
                .header("Accept", "application/json")
                .GET().build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() != 200) {
            log.info("BOE-RSS: no hay boletín para {} (HTTP {})", date, res.statusCode());
            return List.of();
        }
        return parseItems(res.body());
    }

    /**
     * Recorre {@code data.sumario.diario[].seccion[].departamento[].epigrafe[].item[]}.
     *
     * <p>Ojo con la API del BOE (comprobado contra el sumario real del
     * 19-nov-2026, CLAUDE.md §10.ter): cuando un nivel tiene UN solo hijo
     * devuelve el objeto suelto en vez de un array. Por eso todo pasa por
     * {@link #asArray}; sin eso, los días flojos se perderían enteros en
     * silencio.
     */
    List<BoeItem> parseItems(String body) throws Exception {
        List<BoeItem> out = new ArrayList<>();
        JsonNode root = mapper.readTree(body);
        JsonNode sumario = root.path("data").path("sumario");
        for (JsonNode dia : asArray(sumario.path("diario"))) {
            for (JsonNode sec : asArray(dia.path("seccion"))) {
                String secCode = sec.path("codigo").asText("");
                String secName = sec.path("nombre").asText("");
                for (JsonNode dep : asArray(sec.path("departamento"))) {
                    String depName = dep.path("nombre").asText("");
                    for (JsonNode epi : asArray(dep.path("epigrafe"))) {
                        for (JsonNode it : asArray(epi.path("item"))) {
                            addItem(out, it, depName, secCode, secName);
                        }
                    }
                    // Algunos departamentos cuelgan los items directamente,
                    // sin epígrafe intermedio.
                    for (JsonNode it : asArray(dep.path("item"))) {
                        addItem(out, it, depName, secCode, secName);
                    }
                }
            }
        }
        return out;
    }

    private void addItem(List<BoeItem> out, JsonNode it, String depName,
                         String secCode, String secName) {
        String id = it.path("identificador").asText("");
        if (id.isBlank()) return;
        out.add(new BoeItem(
                id,
                it.path("titulo").asText(""),
                it.path("url_pdf").path("texto").asText(""),
                depName, secCode, secName));
    }

    /** Un nodo que puede venir como array o como objeto suelto. */
    private static List<JsonNode> asArray(JsonNode node) {
        List<JsonNode> out = new ArrayList<>();
        if (node == null || node.isMissingNode() || node.isNull()) return out;
        if (node.isArray()) {
            node.forEach(out::add);
        } else {
            out.add(node);
        }
        return out;
    }

    // ====================================================================
    //  Vista previa (BOE-FILTRO) — afinar sin ensuciar la bandeja
    // ====================================================================

    /**
     * Devuelve lo que la empresa RECIBIRÍA de un día concreto con unas
     * preferencias dadas, <b>sin guardar ni notificar nada</b>.
     *
     * <p>Es la pieza que hace que el filtro se pueda ajustar de verdad: se
     * prueba contra un día real del BOE (uno de los que llenaron la bandeja) y
     * se ve el efecto antes de guardar, en vez de cambiar palabras a ciegas y
     * esperar a mañana a las 06:00.
     */
    public PreviewResult preview(LocalDate date, BoeAlertPrefs prefs) {
        try {
            List<BoeItem> items = fetchItems(date);
            List<Match> matches = BoeMatcher.filter(items, prefs);
            List<PreviewItem> out = new ArrayList<>();
            for (Match m : matches) {
                out.add(new PreviewItem(
                        m.item().id(), m.item().title(), m.item().url(),
                        m.item().department(), m.item().sectionName(),
                        String.join(", ", m.keywords())));
            }
            return new PreviewResult(date.toString(), items.size(), out.size(), out, null);
        } catch (Exception ex) {
            log.warn("BOE-RSS: preview fallo para {}", date, ex);
            return new PreviewResult(date.toString(), 0, 0, List.of(), ex.getMessage());
        }
    }

    public record PreviewItem(String boeId, String title, String url,
                              String department, String section, String keywordsMatched) {}

    public record PreviewResult(String date, int totalArticles, int matched,
                                List<PreviewItem> items, String error) {}

    // ====================================================================
    //  Persistencia y notificación
    // ====================================================================

    private List<String> advisoryCompanyIds() {
        return jdbc.queryForList("""
                SELECT id FROM companies
                 WHERE company_type IN ('INTERNAL', 'ADVISORY')
                   AND parent_company_id IS NULL
                """, String.class);
    }

    private boolean insertIfNew(Match m, LocalDate date) {
        try {
            int n = jdbc.update("""
                    INSERT IGNORE INTO boe_alerts
                           (id, alert_date, boe_id, title, url, department,
                            keywords_matched, section_code, section_name)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(),
                    java.sql.Date.valueOf(date), m.item().id(),
                    truncate(m.item().title(), 500),
                    truncate(m.item().url(), 500),
                    truncate(m.item().department(), 200),
                    truncate(String.join(", ", m.keywords()), 300),
                    truncate(m.item().sectionCode(), 10),
                    truncate(m.item().sectionName(), 160));
            return n > 0;
        } catch (RuntimeException ex) {
            log.warn("BOE-RSS: no se pudo guardar {}", m.item().id(), ex);
            return false;
        }
    }

    private void notifyCompany(String companyId, Match m, LocalDate date) {
        try {
            notifService.emit(new AdvisoryNotificationService.EmitRequest(
                    companyId, null,
                    "BOE_FISCAL_LABOR",
                    AdvisoryNotificationService.SEVERITY_INFO,
                    "BOE — " + truncate(m.item().title(), 150),
                    "Palabras clave: " + String.join(", ", m.keywords())
                            + (m.item().department().isEmpty() ? ""
                                : "\nDepartamento: " + m.item().department())
                            + (m.item().url().isEmpty() ? ""
                                : "\nURL: " + m.item().url()),
                    "boe:" + m.item().id()));
        } catch (RuntimeException ex) {
            log.debug("BOE-RSS: no se pudo notificar a {}", companyId);
        }
    }

    /**
     * BOE-ACENTOS (2026-11-22) — descodifica los {@code \\uXXXX} que quedaron
     * guardados en los avisos VIEJOS.
     *
     * <p>Benjamin: <i>"sigue sin colocarse bien los acentos, comas o ñ"</i>. Y
     * tenia razon, pero no era el parser de ahora: el ANTERIOR desescapaba a
     * mano y solo conocia la comilla, el salto de linea y la barra — nunca
     * {@code \\uXXXX}. Los avisos guardados antes de la v0.1.76 llevan en la BD
     * literales como {@code Resoluci\\u00f3n} y asi se pintan para siempre. Se ve
     * clarisimo en su pantalla: las filas del 20-nov (parser nuevo, Jackson)
     * salen con sus tildes y las de octubre no.
     *
     * <p>Se arregla al LEER y no con una migracion que reescriba las filas: es
     * una tabla de solo lectura para mostrar, el riesgo de tocar datos
     * historicos no compensa, y asi tambien quedan bien los avisos que
     * importase alguien desde otra instalacion.
     *
     * <p>Solo toca secuencias {@code \\uXXXX} bien formadas; cualquier otra cosa
     * se deja tal cual.
     */
    static String decodeEscapes(String s) {
        if (s == null || s.indexOf("\\u") < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 5 < s.length() && s.charAt(i + 1) == 'u') {
                String hex = s.substring(i + 2, i + 6);
                try {
                    out.append((char) Integer.parseInt(hex, 16));
                    i += 5;
                    continue;
                } catch (NumberFormatException ignored) {
                    // No era un escape de verdad: se copia tal cual.
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max - 1);
    }

    /**
     * @param error      motivo del fallo, o null si fue bien
     * @param noBulletin el BOE no publico ese dia. <b>NO es un fallo</b>: el
     *                   BOE sale de lunes a sabado, asi que todos los domingos
     *                   la API responde 404. Antes esto viajaba dentro de
     *                   {@code error} y el supervisor lo marcaba como "Ha
     *                   fallado", o sea un falso aviso cada domingo — justo el
     *                   ruido que la supervision existe para evitar.
     */
    public record RunSummary(String date, int hits, int newInserts,
                             int totalArticles, String error, boolean noBulletin) {

        /** Dia sin boletin: ni exito ni fallo. */
        static RunSummary noBulletin(String date) {
            return new RunSummary(date, 0, 0, 0, null, true);
        }

        static RunSummary ok(String date, int hits, int newInserts, int totalArticles) {
            return new RunSummary(date, hits, newInserts, totalArticles, null, false);
        }

        static RunSummary failed(String date, int hits, int newInserts, String error) {
            return new RunSummary(date, hits, newInserts, 0, error, false);
        }
    }

    // ====================================================================
    //  Listado
    // ====================================================================

    public List<Alert> listRecent(int days) {
        return jdbc.query("""
                SELECT id, alert_date, boe_id, title, url, department,
                       keywords_matched, section_name, created_at
                  FROM boe_alerts
                 WHERE alert_date >= ?
                 ORDER BY alert_date DESC, created_at DESC
                """, (rs, n) -> new Alert(
                        rs.getString("id"),
                        rs.getDate("alert_date").toLocalDate().toString(),
                        rs.getString("boe_id"),
                        decodeEscapes(rs.getString("title")),
                        decodeEscapes(rs.getString("url")),
                        decodeEscapes(rs.getString("department")),
                        rs.getString("keywords_matched"),
                        rs.getString("section_name"),
                        rs.getTimestamp("created_at").toInstant().toString()),
                java.sql.Date.valueOf(LocalDate.now().minusDays(Math.max(1, days))));
    }

    public record Alert(String id, String alertDate, String boeId, String title,
                        String url, String department, String keywordsMatched,
                        String section, String createdAt) {}

    // ====================================================================
    //  REST
    // ====================================================================

    /** Cuerpo de guardado/preview de preferencias (listas ya desgranadas). */
    public record PrefsPayload(Boolean enabled, List<String> sections,
                               List<String> departments, List<String> keywords,
                               String date) {

        BoeAlertPrefs toPrefs() {
            return new BoeAlertPrefs(
                    enabled == null || enabled,
                    sections, departments, keywords);
        }
    }

    public record PrefsView(boolean enabled, List<String> sections,
                            List<String> departments, List<String> keywords,
                            List<BoeAlertPrefs.Section> sectionCatalog,
                            List<String> knownDepartments,
                            List<String> defaultKeywords) {}

    @RestController
    @RequestMapping("/api/boe-alerts")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class Controller {

        private final BoeAlertService service;
        private final BoeAlertPrefsService prefsService;
        private final TenantContext tenant;

        public Controller(BoeAlertService service, BoeAlertPrefsService prefsService,
                          TenantContext tenant) {
            this.service = service;
            this.prefsService = prefsService;
            this.tenant = tenant;
        }

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

        /** Preferencias de la empresa activa + lo que la pantalla necesita para ofrecerlas. */
        @GetMapping("/prefs")
        public PrefsView prefs() {
            BoeAlertPrefs p = prefsService.get(tenant.getCurrentCompanyId());
            return new PrefsView(p.enabled(), p.sections(), p.departments(), p.keywords(),
                    BoeAlertPrefs.sectionCatalog(), prefsService.knownDepartments(),
                    BoeAlertPrefs.DEFAULT_KEYWORDS);
        }

        @PutMapping("/prefs")
        public PrefsView savePrefs(@RequestBody PrefsPayload body) {
            BoeAlertPrefs saved = prefsService.save(
                    tenant.getCurrentCompanyId(), body.toPrefs());
            return new PrefsView(saved.enabled(), saved.sections(), saved.departments(),
                    saved.keywords(), BoeAlertPrefs.sectionCatalog(),
                    prefsService.knownDepartments(), BoeAlertPrefs.DEFAULT_KEYWORDS);
        }

        /**
         * Prueba en seco: qué habría llegado de un día concreto con estas
         * preferencias. No guarda ni notifica.
         */
        @PostMapping("/preview")
        public PreviewResult preview(@RequestBody PrefsPayload body) {
            LocalDate date = body.date() == null || body.date().isBlank()
                    ? LocalDate.now() : LocalDate.parse(body.date());
            return service.preview(date, body.toPrefs());
        }
    }
}
