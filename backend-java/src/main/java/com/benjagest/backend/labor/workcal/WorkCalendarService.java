package com.benjagest.backend.labor.workcal;

import com.benjagest.backend.tenant.TenantContext;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * L3-2 — Servicio del calendario laboral.
 *
 * <p>Lógica de negocio sobre {@link WorkCalendarRepository}: validaciones,
 * archivado del calendario activo previo cuando se crea uno nuevo, y
 * tope legal de 14 festivos anuales (Art. 37.2 Estatuto Trabajadores:
 * 9 nacionales + 4 autonómicos + 2 locales máximo legalmente exigibles).
 *
 * <p>Ámbito por empresa vía {@link TenantContext}. Cualquier endpoint que
 * llame aquí necesita {@code @RequiresRole({OWNER, ADMIN, ACCOUNTANT,
 * EMPLOYEE})} arriba — un empleado puede al menos LEER el calendario de
 * su empresa para saber qué días son festivos.
 */
@Service
public class WorkCalendarService {

    /**
     * Tope legal del Art. 37.2 ET: hasta 14 días al año entre nacionales,
     * autonómicos y locales. Si una empresa intenta cargar más, lo
     * bloqueamos con 400 — protege contra errores de seed BOE o
     * convenios colectivos mal configurados.
     */
    public static final int MAX_HOLIDAYS_PER_YEAR = 14;

    private final WorkCalendarRepository repository;
    private final TenantContext tenant;

    public WorkCalendarService(WorkCalendarRepository repository, TenantContext tenant) {
        this.repository = repository;
        this.tenant = tenant;
    }

    // ============================================================
    //  Calendarios
    // ============================================================

    public List<WorkCalendar> listForCurrentCompany() {
        return repository.listByCompany(tenant.getCurrentCompanyId());
    }

    public WorkCalendar getActiveForYear(int year) {
        return repository.findActiveForCompanyYear(tenant.getCurrentCompanyId(), year)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "No hay calendario laboral activo para " + year));
    }

    public WorkCalendar getById(String id) {
        WorkCalendar c = repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Calendario no encontrado"));
        ensureBelongsToTenant(c);
        return c;
    }

    /**
     * Crea un calendario nuevo para (empresa, año). Si ya había uno
     * activo en ese año, lo archiva (active=FALSE) y crea el nuevo
     * como activo. Devuelve el calendario nuevo.
     *
     * <p>El UNIQUE de BD garantiza que solo hay un activo por (empresa,
     * año), pero archivamos explícitamente antes para que el INSERT
     * no choque y para dejar traza en updated_at.
     */
    @Transactional
    public WorkCalendar create(CreateRequest req) {
        validateYear(req.year());
        validateCcaa(req.regionCcaa());
        String companyId = tenant.getCurrentCompanyId();
        repository.archiveActive(companyId, req.year());
        WorkCalendar c = new WorkCalendar(
                UUID.randomUUID().toString(),
                companyId,
                req.year(),
                blank(req.regionCcaa()),
                blank(req.regionMunicipality()),
                blank(req.name(), "Calendario " + req.year()),
                true,
                Instant.now(),
                Instant.now()
        );
        repository.insert(c);
        return c;
    }

    @Transactional
    public WorkCalendar update(String id, UpdateRequest req) {
        WorkCalendar current = getById(id);
        if (req.regionCcaa() != null) validateCcaa(req.regionCcaa());
        WorkCalendar updated = new WorkCalendar(
                current.id(),
                current.companyId(),
                current.year(),
                req.regionCcaa() != null ? blank(req.regionCcaa()) : current.regionCcaa(),
                req.regionMunicipality() != null
                        ? blank(req.regionMunicipality()) : current.regionMunicipality(),
                req.name() != null ? blank(req.name(), current.name()) : current.name(),
                req.active() != null ? req.active() : current.active(),
                current.createdAt(),
                Instant.now()
        );
        repository.update(updated);
        return updated;
    }

    @Transactional
    public void delete(String id) {
        WorkCalendar c = getById(id);
        // CASCADE elimina los holidays. No bloqueamos por presencia de
        // holidays — borrar el calendario es decisión consciente del
        // OWNER y queda registro en audit_events vía el controller.
        repository.delete(c.id());
    }

    // ============================================================
    //  Festivos
    // ============================================================

    public List<Holiday> listHolidays(String workCalendarId) {
        WorkCalendar c = getById(workCalendarId);  // valida tenant
        return repository.listByCalendar(c.id());
    }

    @Transactional
    public Holiday addHoliday(String workCalendarId, HolidayRequest req) {
        WorkCalendar c = getById(workCalendarId);
        validateHolidayRequest(req);
        List<Holiday> existing = repository.listByCalendar(c.id());
        // El tope legal SOLO cuenta festivos no laborables (Art. 37.2 ET).
        // Los AJUSTES de jornada y CIERRES propios de empresa no cuentan.
        long currentFestivos = existing.stream()
                .filter(h -> Holiday.TYPE_FESTIVO.equals(h.holidayType()))
                .count();
        if (Holiday.TYPE_FESTIVO.equals(req.typeOrDefault())
                && currentFestivos >= MAX_HOLIDAYS_PER_YEAR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tope legal de 14 festivos al año superado (Art. 37.2 Estatuto Trabajadores).");
        }
        // Defensa duplicado: el UK_HOLIDAYS_CALENDAR_DATE evita misma
        // fecha dos veces, pero damos un 409 legible en vez del SQL
        // exception nativa.
        boolean duplicate = existing.stream()
                .anyMatch(h -> h.holidayDate().equals(req.holidayDate()));
        if (duplicate) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una entrada en " + req.holidayDate() + " para este calendario.");
        }
        Holiday h = new Holiday(
                UUID.randomUUID().toString(),
                c.id(),
                req.holidayDate(),
                req.name().trim(),
                req.scope(),
                req.isPaid() == null ? true : req.isPaid(),
                blank(req.notes()),
                req.typeOrDefault(),
                Instant.now()
        );
        repository.insertHoliday(h);
        return h;
    }

    @Transactional
    public void removeHoliday(String workCalendarId, String holidayId) {
        WorkCalendar c = getById(workCalendarId);
        // Defensa: verificar que el holiday pertenece al calendario
        // y al tenant; si no, 404.
        boolean belongs = repository.listByCalendar(c.id()).stream()
                .anyMatch(h -> h.id().equals(holidayId));
        if (!belongs) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "El festivo no pertenece al calendario indicado.");
        }
        repository.deleteHoliday(holidayId);
    }

    /**
     * Reemplaza el lote completo de festivos del calendario por la lista
     * dada. Útil para el flujo de bootstrap desde BOE/Nager.Date o desde
     * el CAL-IMPORT-MODAL (UI L3-4) donde el usuario valida una lista
     * detectada y la vuelca de una vez.
     */
    @Transactional
    public List<Holiday> replaceHolidays(String workCalendarId, List<HolidayRequest> reqs) {
        WorkCalendar c = getById(workCalendarId);
        // Tope legal Art. 37.2 ET aplica SOLO a festivos no laborables.
        // Ajustes de jornada (convenio) y cierres propios de empresa no
        // cuentan — un calendario puede tener 14 festivos + 10 ajustes
        // sin problema.
        long festivos = reqs.stream()
                .filter(r -> Holiday.TYPE_FESTIVO.equals(r.typeOrDefault()))
                .count();
        if (festivos > MAX_HOLIDAYS_PER_YEAR) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Tope legal de 14 festivos al año superado (Art. 37.2 Estatuto Trabajadores). "
                            + "Festivos recibidos: " + festivos);
        }
        // Detección de fechas duplicadas en la entrada — el UK lo
        // bloquearía, pero el mensaje SQL es críptico; aquí va legible.
        long distinct = reqs.stream().map(HolidayRequest::holidayDate).distinct().count();
        if (distinct != reqs.size()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Hay fechas duplicadas en la lista.");
        }
        for (HolidayRequest r : reqs) {
            validateHolidayRequest(r);
        }
        List<Holiday> built = reqs.stream().map(r -> new Holiday(
                UUID.randomUUID().toString(),
                c.id(),
                r.holidayDate(),
                r.name().trim(),
                r.scope(),
                r.isPaid() == null ? true : r.isPaid(),
                blank(r.notes()),
                r.typeOrDefault(),
                Instant.now()
        )).toList();
        repository.replaceHolidays(c.id(), built);
        return built;
    }

    // ============================================================
    //  Validaciones y helpers
    // ============================================================

    private void ensureBelongsToTenant(WorkCalendar c) {
        String currentCompany = tenant.getCurrentCompanyId();
        if (!c.companyId().equals(currentCompany)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Calendario no encontrado en tu empresa.");
        }
    }

    private static void validateYear(int year) {
        // Acotamos a un rango razonable. Si alguien manda 1999 o 9999,
        // es casi seguro un error de entrada — preferimos 400 legible.
        if (year < 2020 || year > 2050) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Año fuera de rango razonable (2020-2050): " + year);
        }
    }

    private static void validateCcaa(String ccaa) {
        if (ccaa == null || ccaa.isBlank()) return; // null=solo nacionales
        if (!VALID_CCAA_CODES.contains(ccaa.toUpperCase())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Código de CCAA inválido (ISO 3166-2:ES): " + ccaa);
        }
    }

    private static void validateHolidayRequest(HolidayRequest r) {
        if (r.holidayDate() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "holidayDate obligatorio");
        }
        if (r.name() == null || r.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "name obligatorio");
        }
        if (r.scope() == null
                || !(r.scope().equals(WorkCalendar.SCOPE_NATIONAL)
                        || r.scope().equals(WorkCalendar.SCOPE_CCAA)
                        || r.scope().equals(WorkCalendar.SCOPE_LOCAL))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "scope debe ser NATIONAL | CCAA | LOCAL");
        }
        String t = r.holidayType();
        if (t != null && !t.isBlank()
                && !(t.equals(Holiday.TYPE_FESTIVO)
                        || t.equals(Holiday.TYPE_AJUSTE)
                        || t.equals(Holiday.TYPE_CIERRE))) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "holidayType debe ser FESTIVO | AJUSTE | CIERRE");
        }
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static String blank(String v, String fallback) {
        return v == null || v.isBlank() ? fallback : v.trim();
    }

    /**
     * 17 CCAA + 2 ciudades autónomas según ISO 3166-2:ES (sin prefijo
     * "ES-"). Estos códigos los espera la columna {@code region_ccaa}
     * de la tabla {@code work_calendars}.
     */
    private static final java.util.Set<String> VALID_CCAA_CODES = java.util.Set.of(
            "AN", "AR", "AS", "IB", "CN", "CB", "CL", "CM", "CT",
            "VC", "EX", "GA", "MD", "MC", "NC", "PV", "RI",
            "CE", "ML"
    );

    // ============================================================
    //  DTOs
    // ============================================================

    public record CreateRequest(
            Integer year,
            String regionCcaa,
            String regionMunicipality,
            String name
    ) {}

    public record UpdateRequest(
            String regionCcaa,
            String regionMunicipality,
            String name,
            Boolean active
    ) {}

    public record HolidayRequest(
            LocalDate holidayDate,
            String name,
            String scope,
            Boolean isPaid,
            String notes,
            /** FESTIVO | AJUSTE | CIERRE. null → FESTIVO por defecto. */
            String holidayType
    ) {
        /** Devuelve el tipo no-null (default FESTIVO). */
        public String typeOrDefault() {
            return holidayType == null || holidayType.isBlank()
                    ? Holiday.TYPE_FESTIVO : holidayType;
        }
    }

}
