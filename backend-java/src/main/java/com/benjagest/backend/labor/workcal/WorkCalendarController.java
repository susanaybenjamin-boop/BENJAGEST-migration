package com.benjagest.backend.labor.workcal;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.io.IOException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * L3-2 — REST API del calendario laboral por empresa.
 *
 * <p>Bajo {@code /api/labor/work-calendars}. Hereda el slug {@code "labor"}
 * porque la pantalla del calendario laboral vive dentro del módulo
 * Laboral (no es el calendario general "Agenda" — ese es slug {@code
 * "calendar"} con eventos/Google). El usuario empresario activa el
 * módulo Laboral cuando contrata empleados.
 *
 * <p>Roles: EMPLOYEE puede leer (necesita saber sus festivos). Solo
 * OWNER/ADMIN/ACCOUNTANT pueden crear/editar/borrar calendarios y
 * festivos. El servicio aplica esto vía {@code @RequiresRole} a nivel
 * de método cuando aplica.
 */
@RestController
@RequestMapping("/api/labor/work-calendars")
@RequiresModule("labor")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class WorkCalendarController {

    private final WorkCalendarService service;
    private final HolidayPdfExtractor pdfExtractor;

    public WorkCalendarController(WorkCalendarService service,
                                    HolidayPdfExtractor pdfExtractor) {
        this.service = service;
        this.pdfExtractor = pdfExtractor;
    }

    // ------------------------------------------------------------
    //  Calendarios
    // ------------------------------------------------------------

    /** Lista los calendarios (activos + archivados) de la empresa. */
    @GetMapping
    public List<WorkCalendar> list() {
        return service.listForCurrentCompany();
    }

    /** Calendario activo del año (para fast-lookup desde el UI). */
    @GetMapping("/active")
    public WorkCalendar getActive(@RequestParam("year") int year) {
        return service.getActiveForYear(year);
    }

    /** Detalle de un calendario por id. */
    @GetMapping("/{id}")
    public WorkCalendar getById(@PathVariable("id") String id) {
        return service.getById(id);
    }

    /** Crea un nuevo calendario (archiva el previo activo si lo había). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public WorkCalendar create(@RequestBody WorkCalendarService.CreateRequest req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public WorkCalendar update(@PathVariable("id") String id,
                                @RequestBody WorkCalendarService.UpdateRequest req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresRole({"OWNER", "ADMIN"})
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }

    // ------------------------------------------------------------
    //  Festivos
    // ------------------------------------------------------------

    @GetMapping("/{id}/holidays")
    public List<Holiday> listHolidays(@PathVariable("id") String id) {
        return service.listHolidays(id);
    }

    @PostMapping("/{id}/holidays")
    @ResponseStatus(HttpStatus.CREATED)
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public Holiday addHoliday(@PathVariable("id") String id,
                                @RequestBody WorkCalendarService.HolidayRequest req) {
        return service.addHoliday(id, req);
    }

    @DeleteMapping("/{id}/holidays/{holidayId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public void removeHoliday(@PathVariable("id") String id,
                                @PathVariable("holidayId") String holidayId) {
        service.removeHoliday(id, holidayId);
    }

    /**
     * Reemplaza el lote completo de festivos del calendario. Se usa
     * desde el modal CAL-IMPORT (L3-4) cuando el usuario valida la
     * lista detectada en un PDF/BOE/Nager y la vuelca de golpe.
     */
    @PostMapping("/{id}/holidays/replace")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public List<Holiday> replaceHolidays(@PathVariable("id") String id,
                                @RequestBody List<WorkCalendarService.HolidayRequest> reqs) {
        return service.replaceHolidays(id, reqs);
    }

    /**
     * Vuelca los festivos/ajustes/cierres del calendario laboral a la
     * Agenda general de la empresa (tabla {@code calendar_events}).
     * Idempotente — repetir la operación reemplaza, no duplica.
     *
     * @return JSON con el conteo de eventos volcados:
     *         {@code {"events": N}}
     */
    @PostMapping("/{id}/dump-to-agenda")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public DumpToAgendaResponse dumpToAgenda(@PathVariable("id") String id) {
        int n = service.dumpHolidaysToAgenda(id);
        return new DumpToAgendaResponse(n);
    }

    public record DumpToAgendaResponse(int events) {}

    /**
     * PORT-5 CAL-B — Inversa de dump-to-agenda. Quita de la Agenda
     * general los eventos volcados por este calendario laboral.
     * Idempotente y solo toca eventos con source_type='WORK_CALENDAR'.
     *
     * @return JSON con el conteo de eventos borrados: {@code {"events": N}}
     */
    @DeleteMapping("/{id}/dump-to-agenda")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public DumpToAgendaResponse removeFromAgenda(@PathVariable("id") String id) {
        int n = service.removeHolidaysFromAgenda(id);
        return new DumpToAgendaResponse(n);
    }

    /**
     * PORT-5 CAL-C — Carga los 10 festivos nacionales fijos del año del
     * calendario. Solo añade los que faltan (idempotente). Útil como
     * punto de partida cuando se crea un calendario nuevo, antes de
     * importar los autonómicos por PDF.
     *
     * @return JSON con el número de festivos insertados: {@code {"inserted": N}}
     */
    @PostMapping("/{id}/load-national-holidays")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public LoadNationalResponse loadNationalHolidays(@PathVariable("id") String id) {
        java.util.List<Holiday> inserted = service.loadNationalHolidays(id);
        return new LoadNationalResponse(inserted.size());
    }

    public record LoadNationalResponse(int inserted) {}

    /**
     * CAL-IMPORT-MODAL — Extrae festivos de un PDF que el usuario
     * descargó (BOE/BOJA/BOPV/DOGC, convenio colectivo, etc.).
     *
     * <p>NO persiste nada — devuelve solo el preview con los festivos
     * detectados + confidence. El UI los muestra en un modal
     * side-by-side editable; el usuario corrige y luego llama
     * {@code POST /{id}/holidays/replace} para volcar.
     *
     * <p>El archivo viene como multipart con clave {@code file}.
     * Tamaño máximo controlado por Spring (10MB por defecto, suficiente
     * para cualquier calendario laboral).
     */
    @PostMapping(value = "/extract-pdf", consumes = "multipart/form-data")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public HolidayPdfExtractor.ExtractionResult extractFromPdf(
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Archivo PDF obligatorio");
        }
        try {
            return pdfExtractor.extract(file.getBytes());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el PDF: " + ex.getMessage());
        }
    }

    /**
     * Versión DEBUG del extract-pdf. Devuelve también el texto crudo
     * que PDFBox sacó del PDF + las líneas que el parser ignoró por no
     * encajar con el patrón día-semana. Útil para diagnosticar
     * cuando un PDF no rinde y queremos ver qué está leyendo.
     */
    @PostMapping(value = "/extract-pdf/debug", consumes = "multipart/form-data")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public HolidayPdfExtractor.DebugResult extractFromPdfDebug(
            @RequestPart("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Archivo PDF obligatorio");
        }
        try {
            return pdfExtractor.extractWithDebug(file.getBytes());
        } catch (IOException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "No se pudo leer el PDF: " + ex.getMessage());
        }
    }
}
