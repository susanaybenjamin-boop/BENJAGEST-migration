package com.benjagest.backend.billing.series;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST de series de numeracion.
 *
 *   GET    /api/billing/series                  lista activas
 *   GET    /api/billing/series/{id}             una concreta
 *   POST   /api/billing/series                  crear
 *   PUT    /api/billing/series/{id}             editar (sin tocar correlativo)
 *   DELETE /api/billing/series/{id}             soft delete
 *   POST   /api/billing/series/{id}/claim       emitir siguiente numero
 *
 * Acceso: OWNER, ADMIN o ACCOUNTANT (contables del cliente o de la
 * asesoria pueden gestionar series, son ellos quienes hacen las
 * facturas).
 */
@RestController
@RequestMapping("/api/billing/series")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class SeriesController {

    private final SeriesService service;

    public SeriesController(SeriesService service) {
        this.service = service;
    }

    @GetMapping
    public List<Series> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public Series get(@PathVariable("id") String id) {
        return service.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Series create(@Valid @RequestBody SeriesUpsertRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public Series update(@PathVariable("id") String id,
                         @Valid @RequestBody SeriesUpsertRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }

    @PostMapping("/{id}/claim")
    public SeriesService.ClaimedNumber claim(@PathVariable("id") String id) {
        return service.claimNextNumber(id);
    }

    /**
     * Devuelve la serie que el server usaria al validar una factura
     * del kind indicado, incluida la logica TPB-2: si la sesion esta
     * actuando-como cliente con acuerdo activo, devuelve la serie TPB.
     * Sirve al editor para que el banner "PROXIMO Nº AL VALIDAR" muestre
     * el numero real, sin que la UI tenga que adivinar.
     */
    @GetMapping("/preview-next")
    public PreviewResponse previewNext(
            @RequestParam(value = "invoiceKind", defaultValue = "STANDARD")
                    String invoiceKind) {
        Series s = service.findActiveByKind(invoiceKind);
        return new PreviewResponse(
                s.id(), s.code(),
                s.expeditedByCompanyId(),
                s.formatTemplate(), s.nextNumber(),
                s.currentYear() == null ? 0 : s.currentYear());
    }

    public record PreviewResponse(
            String id, String code,
            String expeditedByCompanyId,
            String formatTemplate, int nextNumber, int currentYear) {}

    /**
     * Importacion del proximo correlativo desde otro programa.
     * Body: { "nextNumber": 43, "acknowledged": true }.
     * Si acknowledged != true, 400.
     */
    @PostMapping("/{id}/migrate")
    public Series migrate(@PathVariable("id") String id,
                          @RequestBody MigrateRequest request) {
        return service.migrate(id,
                request.nextNumber() == null ? 1 : request.nextNumber(),
                request.acknowledged() != null && request.acknowledged());
    }

    public record MigrateRequest(Integer nextNumber, Boolean acknowledged) {
    }
}
