-- =============================================================================
-- V97 — TPB-2: serie de facturación expedida por tercero
--
-- RD 1619/2012 art. 6.1.b) "[…] cuando el expedidor no sea el titular
-- de la operación, las facturas se expedirán en series específicas y
-- distintas para cada uno". Es OBLIGATORIO, no recomendado.
--
-- Modelo: la serie del cliente con expedited_by_company_id = NULL es la
-- serie propia (la usa cuando el propio cliente emite). Una serie con
-- expedited_by_company_id = <id asesoría> es la serie TPB de esa
-- asesoría operando en nombre del cliente. Cliente y asesoría pueden
-- coexistir con sus respectivas series sin solaparse.
--
-- Al activar un acuerdo TPB, el service garantiza la existencia de
-- una serie con (company_id=cliente, expedited_by_company_id=asesoría)
-- — la crea si no la encuentra.
-- =============================================================================

ALTER TABLE invoice_series
    ADD COLUMN expedited_by_company_id CHAR(36) NULL AFTER company_id,
    ADD CONSTRAINT fk_invoice_series_expedited_by
        FOREIGN KEY (expedited_by_company_id) REFERENCES companies(id)
        ON DELETE CASCADE,
    ADD INDEX idx_invoice_series_expedited (expedited_by_company_id);
