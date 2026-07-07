package com.benjagest.backend.audit;

import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * Fachada para escribir eventos de auditoria sin que cada caller tenga
 * que conocer la forma de la tabla. Los metodos record* son
 * idempotentes desde el punto de vista del flujo de negocio (un fallo
 * al insertar el evento NO debe romper la operacion principal — lo
 * tragamos como warning para no bloquear logins, guardados, etc.).
 *
 * IP y user agent se sacan del RequestContextHolder en cada llamada.
 * Si no hay request asociado (background task, test), quedan null.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    public void recordLoginOk(String userId, String activeCompanyId) {
        write(activeCompanyId, userId, "LOGIN_OK", null, null, "OK", null);
    }

    public void recordLoginFail(String email, String reason) {
        // Sin companyId / userId porque el login fallo antes de resolverlos.
        write(null, null, "LOGIN_FAIL", "user_account", null, "FAIL",
                "{\"email\":\"" + escape(email) + "\",\"reason\":\"" + escape(reason) + "\"}");
    }

    public void recordCompanySwitched(String userId, String fromCompanyId, String toCompanyId) {
        write(toCompanyId, userId, "COMPANY_SWITCHED", "company", toCompanyId, "OK",
                fromCompanyId == null ? null : "{\"fromCompanyId\":\"" + escape(fromCompanyId) + "\"}");
    }

    public void recordModuleToggled(String userId, String companyId, String moduleSlug, boolean active) {
        write(companyId, userId,
                active ? "MODULE_ENABLED" : "MODULE_DISABLED",
                "module_catalog", moduleSlug, "OK", null);
    }

    public void recordCompanyDataUpdated(String userId, String companyId) {
        write(companyId, userId, "COMPANY_DATA_UPDATED", "company", companyId, "OK", null);
    }

    /**
     * Subida de certificado digital. byAdvisory=true cuando lo sube
     * una asesoría operando en nombre del cliente (V38).
     */
    public void recordCertificateUploaded(String userId, String companyId,
                                            String certificateId, boolean byAdvisory,
                                            String uploaderCompanyId) {
        String details = byAdvisory
                ? "{\"byAdvisory\":true,\"uploaderCompanyId\":\""
                        + escape(uploaderCompanyId) + "\"}"
                : null;
        write(companyId, userId, "CERTIFICATE_UPLOADED",
                "digital_certificate", certificateId, "OK", details);
    }

    public void recordCertificateDeleted(String userId, String companyId, String certificateId) {
        write(companyId, userId, "CERTIFICATE_DELETED",
                "digital_certificate", certificateId, "OK", null);
    }

    /**
     * LOCK (2026-07-07): cerrar/reabrir un ejercicio fiscal cambia qué se
     * puede tocar en la contabilidad de todo un año — queda siempre en la
     * auditoría con quién y cuándo (reabrir además con el motivo).
     */
    public void recordFiscalYearClosed(String userId, String companyId, int year) {
        write(companyId, userId, "FISCAL_YEAR_CLOSED",
                "fiscal_year", String.valueOf(year), "OK", null);
    }

    public void recordFiscalYearReopened(String userId, String companyId, int year, String reason) {
        String details = reason == null || reason.isBlank() ? null
                : "{\"reason\":\"" + reason.replace("\"", "'") + "\"}";
        write(companyId, userId, "FISCAL_YEAR_REOPENED",
                "fiscal_year", String.valueOf(year), "OK", details);
    }

    public void recordPurchaseInvoicePosted(String userId, String companyId,
                                              String purchaseInvoiceId,
                                              java.math.BigDecimal totalAmount,
                                              boolean withJournal) {
        String details = "{\"totalAmount\":\""
                + (totalAmount == null ? "" : totalAmount.toPlainString())
                + "\",\"journalEntryCreated\":" + withJournal + "}";
        write(companyId, userId, "PURCHASE_INVOICE_POSTED",
                "purchase_invoice", purchaseInvoiceId, "OK", details);
    }

    public void recordPurchaseInvoiceVoided(String userId, String companyId,
                                              String purchaseInvoiceId) {
        write(companyId, userId, "PURCHASE_INVOICE_VOIDED",
                "purchase_invoice", purchaseInvoiceId, "OK", null);
    }

    /**
     * Borrado físico de factura recibida. Capturamos en el detail los
     * datos clave (proveedor, nº, total) para que la traza sirva como
     * libro de bajas aunque la fila ya no exista en purchase_invoices.
     */
    public void recordAdvisoryInvitationCreated(String userId, String advisoryCompanyId,
                                                  String invitationId, String invitedNif,
                                                  String invitedEmail) {
        String details = "{"
                + "\"invitedNif\":\"" + escape(invitedNif) + "\","
                + "\"invitedEmail\":\"" + escape(invitedEmail) + "\""
                + "}";
        write(advisoryCompanyId, userId, "ADVISORY_INVITATION_CREATED",
                "advisory_invitation", invitationId, "OK", details);
    }

    public void recordAdvisoryInvitationAccepted(String userId, String clientCompanyId,
                                                   String invitationId,
                                                   String advisoryCompanyId) {
        String details = "{\"advisoryCompanyId\":\"" + escape(advisoryCompanyId) + "\"}";
        write(clientCompanyId, userId, "ADVISORY_INVITATION_ACCEPTED",
                "advisory_invitation", invitationId, "OK", details);
    }

    public void recordAdvisoryInvitationRejected(String userId, String clientCompanyId,
                                                   String invitationId) {
        write(clientCompanyId, userId, "ADVISORY_INVITATION_REJECTED",
                "advisory_invitation", invitationId, "OK", null);
    }

    public void recordAdvisoryInvitationRevoked(String userId, String advisoryCompanyId,
                                                  String invitationId) {
        write(advisoryCompanyId, userId, "ADVISORY_INVITATION_REVOKED",
                "advisory_invitation", invitationId, "OK", null);
    }

    public void recordAdvisoryUnlinked(String userId, String clientCompanyId,
                                          String formerAdvisoryCompanyId) {
        String details = "{\"formerAdvisoryCompanyId\":\""
                + escape(formerAdvisoryCompanyId) + "\"}";
        write(clientCompanyId, userId, "ADVISORY_UNLINKED",
                "company", clientCompanyId, "OK", details);
    }

    /**
     * Export verificable de auditoría de fichajes (RD 8/2019) o de
     * auditoría general para Inspección/Hacienda. Guarda en el detalle
     * el rango, el SHA-256 del documento generado y el formato — así
     * un futuro reproche puede verificar que el fichero que enseña la
     * empresa es el que se generó (no se ha tocado).
     */
    public void recordExport(String userId, String companyId, String eventType,
                              String format, String fromIso, String toIso,
                              int count, String sha256Hex, String entityFilter) {
        StringBuilder json = new StringBuilder("{");
        json.append("\"format\":\"").append(escape(format)).append("\",");
        json.append("\"from\":\"").append(escape(fromIso)).append("\",");
        json.append("\"to\":\"").append(escape(toIso)).append("\",");
        json.append("\"count\":").append(count).append(',');
        json.append("\"sha256\":\"").append(escape(sha256Hex)).append("\"");
        if (entityFilter != null && !entityFilter.isBlank()) {
            json.append(",\"filter\":\"").append(escape(entityFilter)).append("\"");
        }
        json.append('}');
        write(companyId, userId, eventType, "export", null, "OK", json.toString());
    }

    public void recordPurchaseInvoiceDeleted(String userId, String companyId,
                                               String purchaseInvoiceId,
                                               java.math.BigDecimal totalAmount,
                                               String supplierName,
                                               String invoiceNumber) {
        String details = "{"
                + "\"totalAmount\":\""
                + (totalAmount == null ? "" : totalAmount.toPlainString()) + "\","
                + "\"supplierName\":\"" + escape(supplierName) + "\","
                + "\"invoiceNumber\":\"" + escape(invoiceNumber) + "\""
                + "}";
        write(companyId, userId, "PURCHASE_INVOICE_DELETED",
                "purchase_invoice", purchaseInvoiceId, "OK", details);
    }

    /**
     * Slice 5A — Alias publico de {@link #write} para slices que
     * graban audit_events sin necesitar un metodo dedicado por cada
     * tipo de evento (Equipo / Reparto de clientes). Misma semantica:
     * no lanza si la insercion falla.
     */
    public void recordGeneric(String companyId, String userId, String eventType,
                               String entityType, String entityId, String result,
                               String detailsJson) {
        write(companyId, userId, eventType, entityType, entityId, result, detailsJson);
    }

    /**
     * Punto unico de escritura. Si la insercion falla, NO lanza:
     * loguear es deseable pero nunca debe tumbar la operacion principal.
     *
     * <p>Slice 5A — visibilidad cambiada a package-private + dejada
     * un alias publico {@link #recordGeneric} para slices que necesitan
     * grabar audit_events sin tener que añadir un metodo dedicado aqui
     * por cada accion.
     */
    void write(String companyId, String userId, String eventType,
                       String entityType, String entityId, String result, String detailsJson) {
        try {
            // El IP/User-Agent se capturan AHORA (dentro del request), aunque el
            // INSERT se difiera al after-commit.
            HttpServletRequest request = currentRequest();
            String ip = request == null ? null : extractClientIp(request);
            String ua = request == null ? null : truncate(request.getHeader("User-Agent"), 500);

            Runnable insert = () -> {
                try {
                    repository.insert(new AuditEvent(
                            UUID.randomUUID().toString(),
                            companyId, userId, eventType, entityType, entityId,
                            result, ip, ua, detailsJson, null));
                } catch (Exception ex) {
                    System.err.println("[audit] no se pudo escribir evento "
                            + eventType + ": " + ex.getMessage());
                }
            };

            // AUDIT-LOCK-FIX (2026-06-27): si hay una transacción en curso (p. ej.
            // el UPDATE companies del guardado), DIFERIR el INSERT al after-commit.
            // Motivo: audit_events tiene FK a companies; el INSERT (REQUIRES_NEW,
            // transacción aparte) pedía un lock compartido sobre la fila de la
            // empresa que la transacción principal tenía bloqueada en exclusiva ->
            // se esperaban mutuamente -> "Lock wait timeout" aun con un solo clic,
            // y se perdía el evento (hueco en la cadena). Tras el commit, la fila
            // ya está liberada: el INSERT entra sin contención y SIEMPRE se graba.
            // Si la transacción hace rollback, no se audita -> correcto: no se
            // registra como hecho algo que no se llegó a guardar. Fuera de
            // transacción (login), se escribe en el acto.
            if (org.springframework.transaction.support.TransactionSynchronizationManager
                    .isSynchronizationActive()) {
                org.springframework.transaction.support.TransactionSynchronizationManager
                        .registerSynchronization(
                        new org.springframework.transaction.support.TransactionSynchronization() {
                            @Override public void afterCommit() { insert.run(); }
                        });
            } else {
                insert.run();
            }
        } catch (Exception ex) {
            // Tragamos: si auditoria falla no tiramos el login del usuario.
            // En produccion conviene wirearlo a un logger central; por
            // ahora System.err es suficiente para investigar en local.
            System.err.println("[audit] no se pudo escribir evento " + eventType + ": " + ex.getMessage());
        }
    }

    private HttpServletRequest currentRequest() {
        try {
            if (RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs) {
                return attrs.getRequest();
            }
        } catch (IllegalStateException ignored) {
            // sin scope de request (background)
        }
        return null;
    }

    private String extractClientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // X-Forwarded-For puede traer varias IPs separadas por coma.
            int comma = forwarded.indexOf(',');
            return truncate((comma < 0 ? forwarded : forwarded.substring(0, comma)).trim(), 60);
        }
        return truncate(request.getRemoteAddr(), 60);
    }

    private String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() > max ? value.substring(0, max) : value;
    }

    private String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
