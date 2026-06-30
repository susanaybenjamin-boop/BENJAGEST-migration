package com.benjagest.backend.billing.tpb;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * AGR-1 — Gate de facturación por tercero.
 *
 * <p>Si la asesoría está operando "como" un cliente (la empresa activa de
 * la sesión NO coincide con su propia asesoría) y NO existe un acuerdo de
 * facturación por tercero {@code ACTIVE} que cubra el ámbito, bloquea
 * cualquier mutación de facturas (ventas o compras) y cobros/pagos del
 * cliente.
 *
 * <p>La contabilidad (asientos) NO pasa por este guard: llevar los libros
 * del cliente no es expedir facturas en su nombre, así que sigue
 * disponible aunque no haya acuerdo.
 *
 * <p>RD 1619/2012 art. 5: la facturación por tercero exige el
 * consentimiento previo del destinatario (el cliente), materializado en
 * BENJAGEST como {@link ThirdPartyBillingAgreement} firmado
 * ({@code status = 'ACTIVE'}).
 *
 * <p>La empresa propia ({@code advisoryId == clientId}) nunca se bloquea:
 * se factura a sí misma, no hay terceros de por medio.
 */
@Component
public class BillingAgreementGuard {

    public enum Scope { SALES, PURCHASES }

    /**
     * Mensaje único del bloqueo. La UI lo reconoce para pintar el aviso
     * accionable y deshabilitar los botones (AGR-2).
     */
    public static final String NO_AGREEMENT_MESSAGE =
            "No hay acuerdo de facturación por tercero firmado con este cliente. "
            + "Crea y firma el acuerdo antes de facturar o cobrar/pagar en su nombre.";

    private final TenantContext tenant;
    private final CurrentUserService currentUser;
    private final JdbcTemplate jdbc;

    public BillingAgreementGuard(TenantContext tenant,
                                 CurrentUserService currentUser,
                                 JdbcTemplate jdbc) {
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    /**
     * Lanza {@code 403} si la asesoría actúa-como un cliente y no hay
     * acuerdo {@code ACTIVE} que cubra {@code scope}. No hace nada para la
     * empresa propia ni fuera de un request con usuario (cron/hooks).
     */
    public void requireAgreementOrOwn(Scope scope) {
        String clientId = tenant.getCurrentCompanyId();
        String advisoryId;
        try {
            advisoryId = currentUser.require().activeCompanyId();
        } catch (RuntimeException ex) {
            return; // sin contexto de usuario (cron, scheduled, hooks) → no aplica
        }
        if (advisoryId == null || advisoryId.equals(clientId)) {
            return; // empresa propia → permitido
        }
        if (!hasActiveAgreement(advisoryId, clientId, scope)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, NO_AGREEMENT_MESSAGE);
        }
    }

    /**
     * Atajo para vencimientos (cobros/pagos), cuyo {@code invoice_kind} es
     * "SALES" o "PURCHASE": traduce al {@link Scope} correspondiente.
     */
    public void requireAgreementOrOwnForInvoiceKind(String invoiceKind) {
        requireAgreementOrOwn("PURCHASE".equals(invoiceKind) ? Scope.PURCHASES : Scope.SALES);
    }

    /**
     * ¿Hay acuerdo {@code ACTIVE} para el par (asesoría, cliente) que cubra
     * el ámbito? Consulta directa, sin lanzar — útil para la UI.
     */
    public boolean hasActiveAgreement(String advisoryId, String clientId, Scope scope) {
        String scopeCol = scope == Scope.PURCHASES ? "scope_purchases" : "scope_sales";
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM third_party_billing_agreements "
                + "WHERE advisory_company_id = ? AND client_company_id = ? "
                + "AND status = 'ACTIVE' AND " + scopeCol + " = TRUE",
                Integer.class, advisoryId, clientId);
        return n != null && n > 0;
    }
}
