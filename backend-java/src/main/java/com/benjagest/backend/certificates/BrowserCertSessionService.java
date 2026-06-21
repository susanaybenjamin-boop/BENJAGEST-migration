package com.benjagest.backend.certificates;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.credentials.CertificateUsageLogService;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Sesion de certificado para el GESTOR-NAVEGADOR (Fase 2).
 *
 * <p>Al abrir el gestor de un cliente, resuelve el .p12 activo de ESE
 * tenant (X-Company-Id = cliente), lo descifra y lo importa al almacen
 * de Windows del usuario para que el dialogo nativo de Chromium lo
 * ofrezca al entrar en AEAT/DEHu/SS. Registra el uso en
 * certificate_usage_log (LOPD). Al cerrar el gestor, quita la huella
 * del almacen.
 *
 * <p>Aislamiento por tenant heredado de {@link CertificateService} /
 * {@link CertificateRepository}: solo se ve el cert del cliente activo.
 */
@Service
public class BrowserCertSessionService {

    private static final Logger log = LoggerFactory.getLogger(BrowserCertSessionService.class);
    private static final String PURPOSE = "BROWSER_LOGIN";

    private final CertificateService certificateService;
    private final WindowsCertStoreService windowsCertStore;
    private final CertificateUsageLogService usageLog;
    private final CurrentUserService currentUserService;

    public BrowserCertSessionService(CertificateService certificateService,
                                     WindowsCertStoreService windowsCertStore,
                                     CertificateUsageLogService usageLog,
                                     CurrentUserService currentUserService) {
        this.certificateService = certificateService;
        this.windowsCertStore = windowsCertStore;
        this.usageLog = usageLog;
        this.currentUserService = currentUserService;
    }

    /**
     * Importa el .p12 activo del cliente al almacen de Windows.
     * Devuelve vacio si el cliente no tiene certificado con datos (el
     * gestor abre igual; el usuario seguira con su almacen del sistema).
     */
    public Optional<BrowserCertSession> open() {
        Certificate cert = pickActiveWithData();
        if (cert == null) {
            return Optional.empty();
        }
        AuthenticatedUser user = currentUserService.require();
        try {
            byte[] p12 = Base64.getDecoder().decode(cert.certificateDataBase64());
            String thumbprint = windowsCertStore.importToUserStore(p12, cert.passwordPlaintext());
            usageLog.recordUsage(cert.id(), user.userId(), PURPOSE, null, true, null, null);
            log.info("[browser-cert] Importado cert {} (huella {}) para sesion de navegador",
                    cert.alias(), thumbprint);
            return Optional.of(new BrowserCertSession(
                    cert.id(), thumbprint, cert.alias(), cert.subjectName()));
        } catch (RuntimeException ex) {
            usageLog.recordUsage(cert.id(), user.userId(), PURPOSE, null, false,
                    ex.getMessage(), null);
            throw ex;
        }
    }

    /** Quita la huella del almacen de Windows al cerrar el gestor (best-effort). */
    public void close(String thumbprint) {
        if (StringUtils.hasText(thumbprint)) {
            windowsCertStore.removeFromUserStore(thumbprint.trim());
        }
    }

    private Certificate pickActiveWithData() {
        List<Certificate> active = certificateService.listActiveDecrypted();
        return active.stream()
                .filter(c -> StringUtils.hasText(c.certificateDataBase64()))
                .findFirst()
                .orElse(null);
    }

    /** Datos no sensibles que el gestor/UI puede mostrar (sin .p12 ni contrasena). */
    public record BrowserCertSession(
            String certificateId,
            String thumbprint,
            String alias,
            String subjectName) {

        public BrowserCertSession {
            if (!StringUtils.hasText(thumbprint)) {
                throw new ResponseStatusException(
                        org.springframework.http.HttpStatus.BAD_GATEWAY,
                        "Sesion de certificado sin huella");
            }
        }
    }
}
