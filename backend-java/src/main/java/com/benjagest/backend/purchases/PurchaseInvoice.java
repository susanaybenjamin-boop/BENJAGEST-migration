package com.benjagest.backend.purchases;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Representación en memoria de una fila de {@code purchase_invoices}.
 *
 * <p>Una factura de compra es el gasto contabilizado de un proveedor.
 * Se persiste tras la extracción PDF cuando el operador confirma los
 * datos (botón "Guardar gasto" del diálogo de extracción).
 *
 * <p>Multi-factura: si un mismo PDF Amazon contiene varias facturas,
 * se crea una fila por cada una con el mismo {@code documentSha256}
 * y distinto {@code invoiceIndexInPdf}.
 *
 * <p>El binario PDF NO se persiste — el usuario tiene el archivo en
 * disco. Solo conservamos el SHA-256 para detectar duplicados al
 * re-subir.
 */
public record PurchaseInvoice(
        String id,
        String companyId,
        String supplierNif,
        String supplierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        BigDecimal baseAmount,
        BigDecimal vatPercent,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        String documentSha256,
        int invoiceIndexInPdf,
        String status,
        String journalEntryId,
        String notes,
        String uploadedByUserId,
        String uploadedByCompanyId,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String STATUS_POSTED = "POSTED";
    public static final String STATUS_VOID = "VOID";
}
