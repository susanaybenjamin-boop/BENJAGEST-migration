package com.benjagest.backend.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * GOOGLE-UNIFICADO — Sincronización BIDIRECCIONAL entre la Agenda de BENJAGEST
 * (tabla {@code calendar_events}) y el Google Calendar de la empresa conectada.
 *
 * <p>{@code sync()} hace las dos direcciones:
 * <ul>
 *   <li><b>Push</b>: los eventos de usuario de la Agenda aún no enlazados
 *       (sin {@code external_event_id}) se crean en Google y se guarda su id.</li>
 *   <li><b>Pull</b>: los eventos próximos de Google que no están ya en la Agenda
 *       (por {@code external_event_id}) se crean como eventos de la Agenda.</li>
 * </ul>
 * Manual (a demanda) — robusto y sin bucles. El auto-sync en cada alta/edición
 * es un refinamiento posterior.
 */
@Service
public class GoogleCalendarService {

    private static final String CAL_BASE = "https://www.googleapis.com/calendar/v3/calendars/primary/events";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final GoogleOAuthService googleOAuth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GoogleCalendarService(JdbcTemplate jdbc, ObjectMapper objectMapper, GoogleOAuthService googleOAuth) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.googleOAuth = googleOAuth;
    }

    public record SyncResult(int pushed, int pulled) {}

    @Transactional
    public SyncResult sync(String companyId) {
        var conn = googleOAuth.apiConnection(companyId);
        if (!conn.calendar()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Google Calendar no está conectado.");
        }
        String accessToken = googleOAuth.freshAccessToken(companyId);
        int pushed = push(companyId, accessToken);
        int pulled = pull(companyId, accessToken);
        return new SyncResult(pushed, pulled);
    }

    /** Agenda → Google: sube los eventos de usuario aún no enlazados. */
    private int push(String companyId, String accessToken) {
        List<Event> events = jdbc.query("""
                SELECT id, event_date, title, description
                  FROM calendar_events
                 WHERE company_id = ? AND active = TRUE
                   AND source_type IS NULL
                   AND (external_event_id IS NULL OR external_event_id = '')
                   AND event_date >= CURRENT_DATE - INTERVAL 7 DAY
                """, (rs, n) -> new Event(rs.getString("id"),
                        rs.getDate("event_date").toLocalDate(),
                        rs.getString("title"), rs.getString("description")), companyId);
        int pushed = 0;
        for (Event e : events) {
            String start = e.date.toString();
            String end = e.date.plusDays(1).toString(); // all-day: fin exclusivo
            String body = "{\"summary\":" + js(e.title)
                    + ",\"description\":" + js(e.description == null ? "" : e.description)
                    + ",\"start\":{\"date\":\"" + start + "\"},\"end\":{\"date\":\"" + end + "\"}}";
            HttpResponse<String> r = post(CAL_BASE, accessToken, body);
            if (r.statusCode() >= 200 && r.statusCode() < 300) {
                try {
                    String gid = objectMapper.readTree(r.body()).path("id").asText(null);
                    if (gid != null) {
                        jdbc.update("UPDATE calendar_events SET external_event_id = ? WHERE id = ? AND company_id = ?",
                                gid, e.id, companyId);
                        pushed++;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return pushed;
    }

    /** Google → Agenda: trae los eventos próximos que no están ya enlazados. */
    private int pull(String companyId, String accessToken) {
        String timeMin = LocalDate.now().minusDays(7).atStartOfDay()
                .atZone(java.time.ZoneId.systemDefault()).toOffsetDateTime().toString();
        String url = CAL_BASE + "?singleEvents=true&orderBy=startTime&maxResults=250"
                + "&timeMin=" + enc(timeMin);
        HttpResponse<String> r = get(url, accessToken);
        if (r.statusCode() < 200 || r.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Google Calendar rechazó la lectura (HTTP " + r.statusCode() + ").");
        }
        int pulled = 0;
        try {
            JsonNode items = objectMapper.readTree(r.body()).path("items");
            for (JsonNode it : items) {
                String gid = it.path("id").asText(null);
                if (gid == null) continue;
                Integer exists = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM calendar_events WHERE company_id = ? AND external_event_id = ?",
                        Integer.class, companyId, gid);
                if (exists != null && exists > 0) continue;
                String dateStr = it.path("start").path("date").asText(
                        it.path("start").path("dateTime").asText(null));
                if (dateStr == null) continue;
                LocalDate date = LocalDate.parse(dateStr.substring(0, 10));
                jdbc.update("""
                        INSERT INTO calendar_events
                            (id, company_id, event_date, title, description, event_type, external_event_id, active)
                        VALUES (?, ?, ?, ?, ?, 'GOOGLE', ?, TRUE)
                        """,
                        UUID.randomUUID().toString(), companyId, java.sql.Date.valueOf(date),
                        it.path("summary").asText("(sin título)"),
                        it.path("description").asText(null), gid);
                pulled++;
            }
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Error leyendo Google Calendar: " + ex.getMessage());
        }
        return pulled;
    }

    private record Event(String id, LocalDate date, String title, String description) {}

    private HttpResponse<String> post(String url, String token, String body) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo contactar con Google Calendar: " + ex.getMessage());
        }
    }

    private HttpResponse<String> get(String url, String token) {
        try {
            return http.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo contactar con Google Calendar: " + ex.getMessage());
        }
    }

    private static String enc(String v) { return URLEncoder.encode(v, StandardCharsets.UTF_8); }

    /** Escapa un String como literal JSON entrecomillado. */
    private static String js(String s) {
        if (s == null) return "\"\"";
        StringBuilder sb = new StringBuilder("\"");
        for (char c : s.toCharArray()) {
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> { }
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.append("\"").toString();
    }
}
