package com.benjagest.backend.timeclock;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de fichaje (RD 8/2019).
 *
 * Reglas:
 *   - Un fichaje original NO se modifica jamas. Para corregir, se inserta
 *     una correccion vinculada (art. 34.9).
 *   - Cada fichaje recibe automaticamente un CSV (art. 35.8) que se
 *     entrega al trabajador (o se queda en cola para entregar).
 *   - El employeeId debe ser el del usuario que ficha; si un manager
 *     ficha por otro, debe quedar reflejado en origin/auditoria.
 */
@Service
public class TimeClockService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] CSV_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final TimeClockRepository repository;
    private final CurrentUserService currentUserService;
    private final TimeClockEventTypeService eventTypeService;
    private final TenantContext tenantContext;
    private final com.benjagest.backend.realtime.RealtimeService realtime;

    public TimeClockService(TimeClockRepository repository,
                            CurrentUserService currentUserService,
                            TimeClockEventTypeService eventTypeService,
                            TenantContext tenantContext,
                            com.benjagest.backend.realtime.RealtimeService realtime) {
        this.repository = repository;
        this.currentUserService = currentUserService;
        this.eventTypeService = eventTypeService;
        this.tenantContext = tenantContext;
        this.realtime = realtime;
    }

    /**
     * Devuelve el {@code employees.id} del usuario logueado en la
     * empresa activa. Si no hay ficha, lanza 404 con un mensaje
     * legible que la UI muestra directamente al usuario para que sepa
     * que tiene que pedir el alta como empleado al administrador.
     */
    public TimeClockController.MyEmployeeInfo resolveCurrentEmployee() {
        AuthenticatedUser user = currentUserService.require();
        String companyId = tenantContext.getCurrentCompanyId();
        return repository.findEmployeeByUserAndCompany(user.userId(), companyId)
                .map(row -> new TimeClockController.MyEmployeeInfo(row.employeeId(), row.fullName()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Tu usuario no tiene ficha de empleado en esta empresa. "
                                + "Pide al administrador que te dé de alta en Personal > Empleados."));
    }

    /**
     * Registra un fichaje. Devuelve el evento creado + CSV emitido
     * (para mostrar al trabajador en el momento).
     */
    @Transactional
    public PunchResult punch(String employeeId, String eventType,
                             String customerId, String origin) {
        return punch(employeeId, eventType, customerId, origin, null, null);
    }

    /**
     * GEO-FICHAR (RD 8/2019): variante con coordenadas. Si el empleado
     * tiene work_center con lat/lng/radio_m, calculamos distancia
     * Haversine y aplicamos work_centers.geo_policy:
     *   - none: no verifica.
     *   - info: registra distancia pero no avisa al UI.
     *   - soft: warning amarillo si fuera del radio.
     *   - strict: rechaza fichaje fuera del radio (422).
     */
    @Transactional
    public PunchResult punch(String employeeId, String eventType,
                             String customerId, String origin,
                             java.math.BigDecimal lat, java.math.BigDecimal lng) {
        return punch(employeeId, eventType, customerId, origin, lat, lng,
                currentUserService.require().userId());
    }

    /**
     * FM-2 (kiosco): variante con actor EXPLÍCITO. El fichaje desde un kiosco
     * NO tiene JWT de usuario (se valida por KioskToken), así que el verificador
     * se pasa por parámetro (el user_id del propio empleado) en vez de leerlo de
     * {@code currentUserService.require()}, que lanzaría sin sesión.
     */
    @Transactional
    public PunchResult punch(String employeeId, String eventType,
                             String customerId, String origin,
                             java.math.BigDecimal lat, java.math.BigDecimal lng,
                             String actorUserId) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (eventType == null || !eventTypeService.isValidCode(eventType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventType no valido. Configura los tipos en Personal -> Config fichajes.");
        }

        // GEO-FICHAR: aplica policy del centro asignado.
        GeoCheckResult geo = checkGeo(employeeId, lat, lng);
        if (geo != null && geo.shouldBlock()) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Fichaje rechazado: fuera del radio del centro \""
                            + geo.centerName() + "\" (a "
                            + geo.distanceMeters() + " m, máximo " + geo.radioM() + " m). "
                            + "Aproxima tu posición al centro de trabajo o pide al administrador "
                            + "que cambie la política de geolocalización.");
        }

        String eventId = UUID.randomUUID().toString();
        TimeClockEvent event = new TimeClockEvent(
                eventId, null, employeeId, customerId,
                eventType, Instant.now(),
                origin == null ? "WEB" : origin,
                "VALID", null);
        repository.insertEvent(event, lat, lng,
                geo != null && geo.hasWarning() ? geo.distanceMeters() : null);

        String csv = generateCsv();
        repository.insertVerification(
                UUID.randomUUID().toString(), eventId, csv, actorUserId);

        HolidayWarning warning = detectHolidayWarning(employeeId);
        GeoWarning geoWarning = (geo != null && geo.shouldWarn())
                ? new GeoWarning(geo.centerName(), geo.distanceMeters(), geo.radioM())
                : null;

        // NOTIF-RT — push del fichaje a las pantallas de la empresa (escritorio
        // y PWA): que vean el nuevo evento sin recargar. Nunca rompe el fichaje.
        try {
            String companyId = tenantContext.getCurrentCompanyId();
            realtime.publishToCompany(companyId, "timeclock",
                    "{\"employeeId\":\"" + employeeId + "\",\"eventType\":\"" + eventType + "\"}");
        } catch (Exception ignored) {
            // sin tenant (p.ej. kiosco fuera de scope) o fallo del bus: se ignora.
        }

        return new PunchResult(event, csv, warning, geoWarning);
    }

    /**
     * OFF-1 — registra un fichaje SINCRONIZADO desde un dispositivo offline
     * (kiosco / PWA del empleado). Diferencias con {@link #punch}:
     * <ol>
     *   <li>usa el sello horario REAL del momento offline ({@code eventTime}), no
     *       {@code now()};</li>
     *   <li>idempotente por {@code clientUuid}: si ese fichaje ya se sincronizó,
     *       devuelve {@code null} y no duplica;</li>
     *   <li>NO bloquea por geo (el fichaje ya ocurrió; solo registra la distancia
     *       si viene).</li>
     * </ol>
     * El verificador del CSV es el usuario autenticado (empleado por PIN).
     */
    @Transactional
    public PunchResult syncPunch(String employeeId, String eventType, String customerId,
                                 String origin, java.math.BigDecimal lat, java.math.BigDecimal lng,
                                 Instant eventTime, String clientUuid) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (eventType == null || !eventTypeService.isValidCode(eventType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "eventType no valido.");
        }
        // Idempotencia: si este fichaje ya se sincronizó, no se duplica.
        if (clientUuid != null && !clientUuid.isBlank() && repository.existsClientUuid(clientUuid)) {
            return null;
        }
        GeoCheckResult geo = checkGeo(employeeId, lat, lng); // registra distancia, NO bloquea
        String eventId = UUID.randomUUID().toString();
        TimeClockEvent event = new TimeClockEvent(
                eventId, null, employeeId, customerId,
                eventType, eventTime == null ? Instant.now() : eventTime,
                origin == null ? "OFFLINE" : origin,
                "VALID", null);
        repository.insertEventWithUuid(event, lat, lng,
                geo != null && geo.hasWarning() ? geo.distanceMeters() : null, clientUuid);
        String csv = generateCsv();
        repository.insertVerification(UUID.randomUUID().toString(), eventId, csv,
                currentUserService.require().userId());
        try {
            realtime.publishToCompany(tenantContext.getCurrentCompanyId(), "timeclock",
                    "{\"employeeId\":\"" + employeeId + "\",\"eventType\":\"" + eventType + "\"}");
        } catch (Exception ignored) {
            // sin tenant o fallo del bus: se ignora (no rompe la sincronización).
        }
        return new PunchResult(event, csv, null, null);
    }

    /** GEO-FICHAR — pivote único de chequeo geo. */
    private GeoCheckResult checkGeo(String employeeId,
                                       java.math.BigDecimal lat,
                                       java.math.BigDecimal lng) {
        if (lat == null || lng == null) return null;
        var center = repository.findEmployeeWorkCenterGeo(employeeId).orElse(null);
        if (center == null || "none".equalsIgnoreCase(center.geoPolicy())) return null;
        if (center.lat() == null || center.lng() == null || center.radioM() == null) {
            return null;
        }
        int distance = haversineMeters(
                lat.doubleValue(), lng.doubleValue(),
                center.lat().doubleValue(), center.lng().doubleValue());
        boolean outside = distance > center.radioM();
        return new GeoCheckResult(center.centerName(), distance,
                center.radioM(), center.geoPolicy(), outside);
    }

    /** Distancia Haversine en metros. */
    private static int haversineMeters(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371000.0;
        double φ1 = Math.toRadians(lat1);
        double φ2 = Math.toRadians(lat2);
        double dφ = Math.toRadians(lat2 - lat1);
        double dλ = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dφ/2) * Math.sin(dφ/2)
                + Math.cos(φ1) * Math.cos(φ2) * Math.sin(dλ/2) * Math.sin(dλ/2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return (int) Math.round(R * c);
    }

    private record GeoCheckResult(String centerName, int distanceMeters,
                                    int radioM, String policy, boolean outside) {
        boolean shouldBlock() {
            return outside && "strict".equalsIgnoreCase(policy);
        }
        boolean shouldWarn() {
            return outside && "soft".equalsIgnoreCase(policy);
        }
        boolean hasWarning() {
            return outside; // siempre se persiste si está fuera (auditoría)
        }
    }

    /**
     * TC-CAL — Comprueba si la fecha actual coincide con un festivo
     * del calendario laboral asignado al empleado. Devuelve null si
     * no hay calendario asignado, no hay festivo, o falla la consulta.
     */
    private HolidayWarning detectHolidayWarning(String employeeId) {
        try {
            return repository.findTodaysHolidayForEmployee(employeeId).orElse(null);
        } catch (Exception ex) {
            return null;
        }
    }

    public List<TimeClockEvent> recent(String employeeId, int limit) {
        return repository.findRecentByEmployee(employeeId, limit);
    }

    /**
     * Solicita correccion de un fichaje. NO toca el original — crea
     * una fila PENDING en time_clock_corrections.
     */
    @Transactional
    public void requestCorrection(String originalEventId, String correctionType,
                                   String proposedEventType, Instant proposedEventTime,
                                   String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "motivo requerido");
        }
        AuthenticatedUser actor = currentUserService.require();
        repository.insertCorrection(
                UUID.randomUUID().toString(), originalEventId,
                correctionType, proposedEventType, proposedEventTime,
                reason, actor.userId());
    }

    /**
     * Verificacion publica por CSV — no requiere sesion. Devuelve el
     * evento "tal cual fue grabado" — sin aplicar correcciones, porque
     * el verificador externo quiere ver el original, no la version
     * editada.
     */
    public TimeClockEvent verifyByCsv(String csvCode) {
        return repository.findEventByCsv(csvCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "CSV no encontrado o revocado"));
    }

    /**
     * Genera un CSV de 16 caracteres en alfabeto Crockford-base32-like
     * (sin I/L/O/1/0 para evitar confusiones humanas). 16 chars × 32
     * opciones = 2^80 combinaciones, mas que suficiente para que no
     * choque ni se adivine.
     */
    private String generateCsv() {
        char[] buf = new char[16];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = CSV_ALPHABET[RNG.nextInt(CSV_ALPHABET.length)];
        }
        // Formato visual con guion en mitad para legibilidad humana.
        return new String(buf, 0, 4) + "-" + new String(buf, 4, 4)
                + "-" + new String(buf, 8, 4) + "-" + new String(buf, 12, 4);
    }

    public record PunchResult(TimeClockEvent event, String csv,
                              HolidayWarning holidayWarning,
                              GeoWarning geoWarning) {
        public PunchResult(TimeClockEvent event, String csv, HolidayWarning hw) {
            this(event, csv, hw, null);
        }
    }

    /**
     * GEO-FICHAR — warning amarillo cuando el empleado fichó fuera del
     * radio con geo_policy=soft. Strict bloquea (no llega aquí), info
     * registra silencioso (no warning), none no comprueba (no warning).
     */
    public record GeoWarning(String centerName, int distanceMeters, int radioM) {}

    /**
     * TC-CAL — Datos del festivo coincidente para mostrar warning
     * amarillo al empleado. {@code holidayType} = FESTIVO | AJUSTE |
     * CIERRE (codigos canonicos de holidays.holiday_type).
     */
    public record HolidayWarning(String holidayName, String holidayType,
                                  String scope) {}
}
