package com.benjagest.backend.certificates;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API de gestion de certificados digitales (.p12 / .pfx).
 *
 * - POST: subir un cert nuevo. Body cifrado en aplicacion antes de
 *   tocar BD; nunca queda el password en claro en columnas, logs ni
 *   transacciones serializadas.
 * - GET: lista la metadata SIN exponer password ni certificate_data.
 * - DELETE: soft delete. La fila se queda con active=FALSE para
 *   trazabilidad (un certificado caducado o revocado sigue siendo
 *   referencia historica de las facturas que firmo).
 *
 * Restringido a OWNER/ADMIN. El modulo activo requerido es "documents"
 * (es donde viven los certificados en el catalogo, V7 seed).
 */
@RestController
@RequestMapping("/api/certificates")
@RequiresModule("documents")
@RequiresRole({"OWNER", "ADMIN"})
public class CertificateController {

    private final CertificateService service;

    public CertificateController(CertificateService service) {
        this.service = service;
    }

    @GetMapping
    public List<CertificateSummary> list() {
        return service.list();
    }

    @GetMapping("/{id}")
    public CertificateSummary get(@PathVariable("id") String id) {
        return service.getSummary(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public CertificateSummary upload(@Valid @RequestBody CertificateUploadRequest request) {
        return service.upload(request);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        service.delete(id);
    }
}
