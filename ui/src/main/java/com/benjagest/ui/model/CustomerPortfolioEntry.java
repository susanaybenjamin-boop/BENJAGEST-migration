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
        boolean hasPendingInvitation,
        boolean wasUnlinked,
        /**
         * TRUE = el cliente aceptó la invitación → tiene su PROPIA company
         * independiente (vinculación real). La UI muestra Facturación
         * con VeriFactu + Compras y Gastos por separado.
         *
         * FALSE = solo existe una shadow company gestionada por la
         * asesoría (MANAGED_CLIENT). La UI unifica en "Ventas y Gastos"
         * porque el asesor solo archiva, no emite.
         */
        boolean fullyLinked
) {
    /** TRUE si hay company asociada (sea shadow o vinculada real). */
    public boolean hasCompany() {
        return linkedCompanyId != null && !linkedCompanyId.isBlank();
    }

    /**
     * @deprecated semántica histórica engañosa — devuelve true incluso
     * para shadow companies. Usa {@link #fullyLinked()} para vínculo
     * real o {@link #hasCompany()} para "tiene company asociada".
     */
    @Deprecated
    public boolean isLinked() {
        return hasCompany();
    }

    /** Convierte a ManagedClientEntry para reutilizar la pantalla del cliente. */
    public ManagedClientEntry asManagedClient() {
        return new ManagedClientEntry(
                linkedCompanyId, legalName, tradeName, taxIdentifier,
                customerType, email, phone, city, null);
    }
}
