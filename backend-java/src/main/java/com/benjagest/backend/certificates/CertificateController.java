package com.benjagest.backend.certificates;

import com.benjagest.backend.auth.RequiresRole;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class CertificateController {

    private final CertificateService service;
    private final BrowserCertSessionService browserSession;

    public CertificateController(CertificateService service,
                                 BrowserCertSessionService browserSession) {
        this.service = service;
        this.browserSession = browserSession;
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

    /**
     * GESTOR-NAVEGADOR (Fase 2): importa el .p12 activo del cliente
     * (X-Company-Id) al almacen de Windows para que Chromium lo ofrezca
     * al entrar en AEAT/DEHu/SS. Devuelve 200 con la huella o 204 si el
     * cliente no tiene certificado (el gestor abre igual).
     */
    @PostMapping("/browser/open")
    public ResponseEntity<BrowserCertSessionService.BrowserCertSession> openBrowserSession() {
        return browserSession.open()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * BROWSER-CERT-STORE-FIX (2026-07-16): entrega el .p12 descifrado del cliente
     * activo para que LO IMPORTE LA UI (que corre como el usuario), no el backend
     * (que como servicio corre como LocalSystem y lo metia en un almacen que el
     * navegador no ve). Ver {@link BrowserCertSessionService#material()}.
     *
     * <p><b>SOLO LOCALHOST.</b> El cuerpo lleva la clave privada descifrada y su
     * contrasena; el backend escucha en 0.0.0.0 (toda la LAN), asi que sin este
     * guard cualquiera en la red con un token podria llevarse el certificado del
     * cliente. La UI que consume esto corre en la MISMA maquina que el servicio
     * (modelo "todo es un puesto").
     */
    @PostMapping("/browser/material")
    public ResponseEntity<BrowserCertSessionService.BrowserCertMaterial> browserMaterial(
            jakarta.servlet.http.HttpServletRequest request) {
        if (!isLoopback(request)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "El material del certificado solo se entrega en local");
        }
        return browserSession.material()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    private static boolean isLoopback(jakarta.servlet.http.HttpServletRequest request) {
        try {
            return java.net.InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress();
        } catch (Exception ex) {
            return false; // ante la duda, NO entregar el material
        }
    }

    /** Quita la huella del almacen al cerrar el gestor. */
    @PostMapping("/browser/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeBrowserSession(@RequestBody BrowserCloseRequest request) {
        browserSession.close(request == null ? null : request.thumbprint());
    }

    /** Cuerpo de cierre: la huella devuelta por /browser/open. */
    public record BrowserCloseRequest(String thumbprint) {
    }
}
