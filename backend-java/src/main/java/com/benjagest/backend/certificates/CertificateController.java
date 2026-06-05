package com.benjagest.backend.certificates;

import com.benjagest.backend.auth.RequiresRole;
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
 * Restringido a OWNER/ADMIN/ACCOUNTANT (la asesoría usa ACCOUNTANT
 * para gestionar el cert por su cliente). Sin @RequiresModule porque
 * la pantalla vive en Configuración y no debe depender de la
 * activación del módulo "documents".
 */
@RestController
@RequestMapping("/api/certificates")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
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

    @PostMapping("/inspect")
    public CertificateInspectResponse inspect(
            @Valid @RequestBody CertificateInspectRequest request) {
        return service.inspect(request);
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
