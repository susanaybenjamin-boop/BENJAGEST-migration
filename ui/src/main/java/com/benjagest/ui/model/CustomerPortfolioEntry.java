package com.benjagest.ui.model;

/**
 * Vista UI de la cartera unificada de clientes de la asesoría.
 *
 * Cada entrada es un customer (cartera de facturación) que puede o no
 * estar vinculado como managed client (la asesoría gestiona su BD).
 * También indica si tiene una invitación pendiente de aceptar para
 * mostrar el badge correcto en la UI.
 */
public record CustomerPortfolioEntry(
        String customerId,
        String legalName,
        String tradeName,
        String taxIdentifier,
        String customerType,
        String email,
        String phone,
        String city,
        String linkedCompanyId,
        boolean hasPendingInvitation
) {
    public boolean isLinked() {
        return linkedCompanyId != null && !linkedCompanyId.isBlank();
    }

    /** Convierte a ManagedClientEntry para reutilizar la pantalla del cliente. */
    public ManagedClientEntry asManagedClient() {
        return new ManagedClientEntry(
                linkedCompanyId, legalName, tradeName, taxIdentifier,
                customerType, email, phone, city, null);
    }
}
