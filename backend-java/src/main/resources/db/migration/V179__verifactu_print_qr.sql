-- VF-QR-TOGGLE: interruptor para NO imprimir el QR VERI*FACTU en los PDF
-- de factura mientras la empresa aun no este obligada (RD 1007/2023:
-- sociedades 1-ene-2027, autonomos 1-jul-2027). A partir de su fecha
-- limite el backend ignora el toggle y el QR se imprime siempre.
ALTER TABLE companies
    ADD COLUMN verifactu_print_qr TINYINT(1) NOT NULL DEFAULT 1;
