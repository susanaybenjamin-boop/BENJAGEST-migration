package com.benjagest.backend.billing.pdf;

import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.billing.verifactu.VerifactuConfig;
import com.benjagest.backend.settings.CompanyDataResponse;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.format.DateTimeFormatter;
import java.util.EnumMap;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Genera el codigo QR oficial AEAT para una factura emitida — slice
 * VF3-QR. La URL y los parametros estan definidos en la Orden
 * HAC/1177/2024 (anexo VI). El QR codifica esta cadena:
 *
 *   {endpoint}?nif={NIF}&numserie={ENCODED}&fecha={DD-MM-YYYY}&importe={X.XX}
 *
 * Endpoints:
 *   - TEST: https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR
 *   - PROD: https://www2.agenciatributaria.es/wlpl/TIKE-CONT/ValidarQR
 *
 * El endpoint se elige por el `mode` del config (TEST/PROD). En
 * modalidad NO_VERIFACTU se usa TEST (no hay envio real, pero el
 * cliente que escanee el QR debe poder llegar a una respuesta) — la
 * Orden permite TEST como sede de validacion siempre que el sistema
 * no este enviando.
 *
 * Especificaciones tecnicas (Orden HAC/1177/2024 art. 20):
 *   - Nivel de correccion M (15%).
 *   - Tamanyo entre 30x30 y 40x40 mm en papel.
 *   - Aqui generamos un PNG de 200x200 px para que al renderizar el
 *     PDF a 72 dpi (estandar OpenPDF) quepa en aprox. 70x70 pt
 *     (~24.7 mm). Si imprimen el PDF, queda dentro del rango legal.
 *
 * Devuelve null si no hay datos suficientes para generar el QR (factura
 * sin numero, sin fecha, sin NIF emisor) — el PdfGenerator interpreta
 * null como "no pintar QR" (sigue el placeholder o nada).
 */
@Service
public class InvoiceQrService {

    private static final String ENDPOINT_TEST = "https://prewww2.aeat.es/wlpl/TIKE-CONT/ValidarQR";
    private static final String ENDPOINT_PROD = "https://www2.agenciatributaria.es/wlpl/TIKE-CONT/ValidarQR";
    private static final DateTimeFormatter FECHA = DateTimeFormatter.ofPattern("dd-MM-yyyy");
    private static final int QR_PX = 200;

    /**
     * VF-QR-TOGGLE: true si la empresa ha APAGADO el QR en su config
     * ({@code printQr=false}) y ademas aun esta dentro del plazo legal
     * (antes de su fecha de obligacion VERI*FACTU, deducida del NIF).
     * Desde esa fecha el toggle caduca y esto devuelve siempre false.
     */
    public boolean qrSuppressed(VerifactuConfig config, CompanyDataResponse company) {
        if (config == null || config.printQr() == null || config.printQr()) return false;
        String taxId = company == null ? null : company.taxIdentifier();
        return com.benjagest.backend.billing.verifactu.VerifactuDates.qrOptOutStillAllowed(taxId);
    }

    public byte[] generatePng(SalesInvoice invoice, CompanyDataResponse company, VerifactuConfig config) {
        if (qrSuppressed(config, company)) return null;
        if (invoice == null || company == null) return null;
        if (invoice.invoiceNumber() == null || invoice.invoiceNumber().isBlank()) return null;
        if (invoice.invoiceDate() == null) return null;
        if (company.taxIdentifier() == null || company.taxIdentifier().isBlank()) return null;

        String endpoint = (config != null && "PROD".equals(config.mode())) ? ENDPOINT_PROD : ENDPOINT_TEST;
        String url = endpoint
                + "?nif=" + urlEncode(company.taxIdentifier().trim().toUpperCase())
                + "&numserie=" + urlEncode(invoice.invoiceNumber().trim())
                + "&fecha=" + invoice.invoiceDate().format(FECHA)
                + "&importe=" + formatImporte(invoice.total());

        try {
            QRCodeWriter writer = new QRCodeWriter();
            Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
            hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
            hints.put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);
            hints.put(EncodeHintType.MARGIN, 1);

            BitMatrix matrix = writer.encode(url, BarcodeFormat.QR_CODE, QR_PX, QR_PX, hints);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(matrix, "PNG", baos);
            return baos.toByteArray();
        } catch (Exception ex) {
            // Si fallamos generando el QR no rompemos el PDF entero —
            // mejor PDF sin QR que sin PDF. El operador vera el WARN.
            org.slf4j.LoggerFactory.getLogger(InvoiceQrService.class)
                    .warn("No se pudo generar QR para factura {}", invoice.invoiceNumber(), ex);
            return null;
        }
    }

    /**
     * Etiqueta de cumplimiento que va JUSTO debajo del QR.
     *
     * QR-LEY (2026-07-10, verificado contra el BOE consolidado): el art.
     * 6.5 del RD 1619/2012 (añadido por el RD 1007/2023) dice que la
     * leyenda «Factura verificable en la sede electrónica de la AEAT» o
     * «VERI*FACTU» se incorpora ÚNICAMENTE cuando el SIF realiza la
     * remisión de todos los registros a la AEAT. En modalidad NO
     * VERI*FACTU la factura lleva QR (arts. 20-21 Orden HAC/1177/2024)
     * pero SIN leyenda — imprimirla afirmaría una remisión que no existe.
     * (Antes devolvíamos la leyenda también en NO VERI*FACTU: incorrecto.)
     */
    public String complianceLabel(VerifactuConfig config) {
        if (config != null && "VERIFACTU".equals(config.modality())) {
            return "VERI*FACTU";
        }
        return null;
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private String formatImporte(BigDecimal total) {
        BigDecimal safe = total == null ? BigDecimal.ZERO : total;
        // Punto como separador decimal, 2 decimales. La Orden permite
        // hasta 12 enteros + 2 decimales. Punto en lugar de coma,
        // sin separador de miles. setScale(HALF_UP) garantiza el
        // formato exacto que AEAT espera.
        return safe.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }
}
