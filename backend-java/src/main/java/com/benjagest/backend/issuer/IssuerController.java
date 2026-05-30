package com.benjagest.backend.issuer;

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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST para emisores. Es la unica clase que decide rutas y
 * codigos HTTP de exito. La validacion @Valid dispara el chequeo de las
 * anotaciones del DTO antes de ejecutar el metodo.
 *
 * Mapeo de rutas:
 *   GET    /api/issuers       -> lista emisores activos
 *   GET    /api/issuers/{id}  -> uno por id (404 si no existe)
 *   POST   /api/issuers       -> crea uno nuevo (201)
 *   PUT    /api/issuers/{id}  -> actualiza el existente
 *   DELETE /api/issuers/{id}  -> soft delete (active=false), 204
 */
@RestController
@RequestMapping("/api/issuers")
@RequiresModule("issuers")
public class IssuerController {

    private final IssuerService service;

    public IssuerController(IssuerService service) {
        this.service = service;
    }

    @GetMapping
    public List<IssuerResponse> list() {
        return service.list();
    }

    /**
     * Devuelve el emisor activo (is_default=TRUE) de la empresa actual.
     * 404 si la empresa no ha marcado ninguno.
     * Importante: este mapping debe declararse ANTES que /{id} para que
     * "default" no se interprete como un id (Spring resuelve por
     * especificidad, pero el orden explicito en el codigo es mas claro).
     */
    @GetMapping("/default")
    public IssuerResponse getDefault() {
        return service.getDefault();
    }

    @GetMapping("/{id}")
    public IssuerResponse get(@PathVariable("id") String id) {
        return service.findById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public IssuerResponse create(@Valid @RequestBody IssuerCreateRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    public IssuerResponse update(
            @PathVariable("id") String id,
            @Valid @RequestBody IssuerCreateRequest request
    ) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }

    /**
     * Marca el emisor con este id como activo de la empresa.
     * Pone todos los demas a is_default=FALSE dentro de la misma
     * transaccion.
     */
    @PutMapping("/{id}/default")
    public IssuerResponse markAsDefault(@PathVariable("id") String id) {
        return service.markAsDefault(id);
    }
}
