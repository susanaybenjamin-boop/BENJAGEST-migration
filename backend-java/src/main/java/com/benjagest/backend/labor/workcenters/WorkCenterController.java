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
     * CENTROS-GEOCODE — geocodifica una dirección libre usando
     * Nominatim (OpenStreetMap). Devuelve {lat, lng, displayName} de
     * la primera coincidencia o 404 si no encuentra nada. Restringido
     * a España para reducir falsos positivos.
     *
     * <p>Nominatim Usage Policy obliga a enviar User-Agent identificable
     * y limita a 1 req/s. La UI solo dispara la llamada bajo botón
     * explícito del usuario, así que el rate natural está muy por
     * debajo del límite.
     */
    @GetMapping("/geocode")
    public Map<String, Object> geocode(@RequestParam("q") String query) {
        if (query == null || query.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "query obligatorio");
        }
        try {
            String encoded = URLEncoder.encode(query.trim(), StandardCharsets.UTF_8);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("https://nominatim.openstreetmap.org/search?format=json"
                            + "&limit=1&countrycodes=es&q=" + encoded))
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
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Sin resultados para esa dirección");
            }
            Matcher mLat = LAT_RE.matcher(body);
            Matcher mLon = LON_RE.matcher(body);
            Matcher mDisp = DISPLAY_RE.matcher(body);
            if (!mLat.find() || !mLon.find()) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Respuesta sin coordenadas");
            }
            BigDecimal lat = new BigDecimal(mLat.group(1));
            BigDecimal lng = new BigDecimal(mLon.group(1));
            String displayName = mDisp.find() ? mDisp.group(1) : "";
            return Map.of("lat", lat, "lng", lng, "displayName", displayName);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo contactar con Nominatim: " + ex.getMessage());
        }
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
