package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Una linea editable en la pantalla de crear/editar factura. Es
 * MUTABLE a proposito (todas las celdas se editan en vivo y los
 * totales se recalculan al perder el foco).
 *
 * No es un record: necesita setters para que la tabla JavaFX pueda
 * actualizar las celdas.
 */
public class InvoiceLineDraft {
    private String description;
    private BigDecimal quantity;
    private BigDecimal unitPrice;
    private BigDecimal vatPercent;
    private BigDecimal retentionPercent;
    /** FAC-IVA: id del tipo del catálogo elegido en el combo (opcional). */
    private String vatRateId;

    public InvoiceLineDraft() {
        this("", BigDecimal.ONE, BigDecimal.ZERO, new BigDecimal("21"), BigDecimal.ZERO);
    }

    public InvoiceLineDraft(String description, BigDecimal quantity, BigDecimal unitPrice,
                            BigDecimal vatPercent, BigDecimal retentionPercent) {
        this.description = description == null ? "" : description;
        this.quantity = quantity == null ? BigDecimal.ONE : quantity;
        this.unitPrice = unitPrice == null ? BigDecimal.ZERO : unitPrice;
        this.vatPercent = vatPercent == null ? BigDecimal.ZERO : vatPercent;
        this.retentionPercent = retentionPercent == null ? BigDecimal.ZERO : retentionPercent;
    }

    public String getDescription() { return description; }
    public void setDescription(String value) { this.description = value == null ? "" : value; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal value) { this.quantity = value == null ? BigDecimal.ZERO : value; }

    public BigDecimal getUnitPrice() { return unitPrice; }
    public void setUnitPrice(BigDecimal value) { this.unitPrice = value == null ? BigDecimal.ZERO : value; }

    public BigDecimal getVatPercent() { return vatPercent; }
    public void setVatPercent(BigDecimal value) { this.vatPercent = value == null ? BigDecimal.ZERO : value; }

    public String getVatRateId() { return vatRateId; }
    public void setVatRateId(String value) { this.vatRateId = value; }

    public BigDecimal getRetentionPercent() { return retentionPercent; }
    public void setRetentionPercent(BigDecimal value) { this.retentionPercent = value == null ? BigDecimal.ZERO : value; }
}
