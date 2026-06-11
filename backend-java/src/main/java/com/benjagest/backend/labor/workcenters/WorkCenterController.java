package com.benjagest.backend.labor.workcenters;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.math.BigDecimal;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Port CONTENDO {@code centros_trabajo_180}. Bajo
 * {@code /api/labor/work-centers}.
 */
@RestController
@RequestMapping("/api/labor/work-centers")
@RequiresModule("labor")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class WorkCenterController {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5)).build();
    private static final Pattern LAT_RE = Pattern.compile("\"lat\"\\s*:\\s*\"([-0-9.]+)\"");
    private static final Pattern LON_RE = Pattern.compile("\"lon\"\\s*:\\s*\"([-0-9.]+)\"");
    private static final Pattern DISPLAY_RE = Pattern.compile("\"display_name\"\\s*:\\s*\"([^\"]+)\"");

    private final WorkCenterService service;

    public WorkCenterController(WorkCenterService service) {
        this.service = service;
    }

    @GetMapping
    public List<WorkCenterService.WorkCenter> list() {
        return service.list();
    }

    /**
     * CENTROS-GEOCODE — geocodifica una dirección estructurada o libre
     * usando Nominatim (OpenStreetMap). Restringido a España.
     *
     * <p>Estrategia de precisión (en orden, devolvemos el primero que
     * acierte):
     * <ol>
     *   <li>Si vienen los params estructurados (street/postalcode/city),
     *       usar el endpoint estructurado de Nominatim — mucho más
     *       preciso que la búsqueda libre porque indexa los campos
     *       por separado.</li>
     *   <li>Si no, búsqueda libre {@code q=...}.</li>
     *   <li>Si tampoco, devolvemos 404.</li>
     * </ol>
     *
     * <p>Pedimos {@code addressdetails=1} para que el {@code display_name}
     * sea humano completo. Nominatim Usage Policy: User-Agent
     * identificable + 1 req/s máx — el UI solo dispara bajo botón
     * explícito.
     */
    @GetMapping("/geocode")
    public Map<String, Object> geocode(
            @RequestParam(value = "q", required = false) String query,
            @RequestParam(value = "street", required = false) String street,
            @RequestParam(value = "postalcode", required = false) String postalcode,
            @RequestParam(value = "city", required = false) String city,
            @RequestParam(value = "state", required = false) String state) {
        boolean hasStructured = (street != null && !street.isBlank())
                || (postalcode != null && !postalcode.isBlank())
                || (city != null && !city.isBlank());
        boolean hasFree = query != null && !query.isBlank();
        if (!hasStructured && !hasFree) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Aporta street/city/postalcode o q");
        }
        try {
            Map<String, Object> result = null;
            if (hasStructured) {
                StringBuilder url = new StringBuilder(
                        "https://nominatim.openstreetmap.org/search?format=json"
                        + "&addressdetails=1&limit=1&countrycodes=es");
                if (street != null && !street.isBlank()) {
                    url.append("&street=").append(URLEncoder.encode(street.trim(), StandardCharsets.UTF_8));
                }
                if (postalcode != null && !postalcode.isBlank()) {
                    url.append("&postalcode=").append(URLEncoder.encode(postalcode.trim(), StandardCharsets.UTF_8));
                }
                if (city != null && !city.isBlank()) {
                    url.append("&city=").append(URLEncoder.encode(city.trim(), StandardCharsets.UTF_8));
                }
                if (state != null && !state.isBlank()) {
                    url.append("&state=").append(URLEncoder.encode(state.trim(), StandardCharsets.UTF_8));
                }
                result = callNominatim(url.toString());
            }
            if (result == null && hasFree) {
                String url = "https://nominatim.openstreetmap.org/search?format=json"
                        + "&addressdetails=1&limit=1&countrycodes=es&q="
                        + URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
                result = callNominatim(url);
            }
            if (result == null) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Sin resultados para esa dirección");
            }
            return result;
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo contactar con Nominatim: " + ex.getMessage());
        }
    }

    private Map<String, Object> callNominatim(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "BENJAGEST/0.1 (contact: susanaybenjamin@gmail.com)")
                .header("Accept-Language", "es")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> res = HTTP.send(req, HttpResponse.BodyHandlers.ofString());
        if (res.statusCode() >= 400) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Nominatim HTTP " + res.statusCode());
        }
        String body = res.body();
        if (body == null || body.isBlank() || body.trim().equals("[]")) {
            return null;
        }
        Matcher mLat = LAT_RE.matcher(body);
        Matcher mLon = LON_RE.matcher(body);
        Matcher mDisp = DISPLAY_RE.matcher(body);
        if (!mLat.find() || !mLon.find()) return null;
        BigDecimal lat = new BigDecimal(mLat.group(1));
        BigDecimal lng = new BigDecimal(mLon.group(1));
        String displayName = mDisp.find() ? mDisp.group(1) : "";
        return Map.of("lat", lat, "lng", lng, "displayName", displayName);
    }

    @PostMapping
    @RequiresRole({"OWNER", "ADMIN"})
    public WorkCenterService.WorkCenter create(@RequestBody WorkCenterService.UpsertRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN"})
    public WorkCenterService.WorkCenter update(
            @PathVariable("id") String id,
            @RequestBody WorkCenterService.UpsertRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN"})
    public void delete(@PathVariable("id") String id) {
        service.deactivate(id);
    }
}
