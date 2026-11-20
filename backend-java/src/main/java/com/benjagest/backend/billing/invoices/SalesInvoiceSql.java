package com.benjagest.backend.billing.invoices;

/**
 * DASH-E (2026-11-20) — definicion UNICA en SQL de "que es una factura de
 * venta emitida y viva".
 *
 * <p>Existe porque el criterio estaba copiado a mano en varios sitios y se
 * desincronizo: {@code ClientFinancialsService} lo tenia bien,
 * {@code WorkspaceRepository} (las tarjetas del inicio) sumaba TODOS los
 * estados, y el resumen de Facturacion contaba de una tercera forma. Resultado:
 * tres pantallas de la misma app dando tres cifras distintas de "facturado".
 *
 * <p>No tiene estado ni dependencias: son fragmentos de WHERE pensados para
 * concatenarse detras de una condicion existente, con la tabla
 * {@code sales_invoices} aliasada como <b>{@code si}</b>. Si cambia el criterio
 * de negocio, se cambia AQUI y lo heredan todos los consumidores.
 */
public final class SalesInvoiceSql {

    private SalesInvoiceSql() {
    }

    /**
     * Alias obligatorio de la tabla para que estos fragmentos compilen.
     */
    public static final String ALIAS = "si";

    /**
     * Factura EMITIDA y VIVA:
     * <ul>
     *   <li>{@code status = 'VALIDATED'} — un borrador no se ha emitido (no
     *       tiene numero de serie ni huella SIF), y las CANCELLED/VOIDED no
     *       cuentan como facturacion.</li>
     *   <li>sin las rectificativas que anulan una original ya VOIDED: esa
     *       pareja netea a cero, asi que dejar viva solo la negativa restaba
     *       de mas. Las rectificativas PARCIALES de una factura aun abierta
     *       (original no VOIDED) si siguen restando, que es lo correcto.</li>
     * </ul>
     * Mismo criterio que {@code ClientFinancialsService.pendingCollections}.
     */
    public static final String LIVE_ISSUED =
            " AND si.status = 'VALIDATED'"
            + " AND NOT (si.original_invoice_id IS NOT NULL AND EXISTS ("
            + " SELECT 1 FROM sales_invoices o"
            + " WHERE o.id = si.original_invoice_id AND o.status = 'VOIDED'))";

    /**
     * Las que ademas estan pendientes de cobro (total o parcialmente). Se
     * concatena DETRAS de {@link #LIVE_ISSUED}.
     */
    public static final String STILL_UNPAID =
            " AND si.payment_status IN ('PENDING','PARTIAL')";
}
