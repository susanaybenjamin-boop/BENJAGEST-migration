package com.benjagest.backend.billing.pdf;

import com.benjagest.backend.billing.invoices.InvoiceLine;
import com.benjagest.backend.billing.invoices.SalesInvoice;
import com.benjagest.backend.billing.texts.InvoiceTexts;
import com.benjagest.backend.settings.CompanyDataResponse;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.Image;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.ColumnText;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfPageEventHelper;
import com.lowagie.text.pdf.PdfTemplate;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * Genera el PDF de una factura (F4b).
 *
 * Layout final (después de feedback 2026-06-03):
 *
 *   ┌────────────────────────────────────────────────────────────┐
 *   │                       FACTURA                              │  ← título centrado
 *   ├─────────────────────────┬──────────────────────────────────┤
 *   │ [Logo opcional]         │   Nº F-2026-0001                 │  ← nº+fecha encima
 *   │                         │   Fecha: 02/06/2026              │     del cliente
 *   │ Empresa SL              │                                  │
 *   │ NIF B12...              │   Cliente                        │
 *   │ Dirección               │   Construcciones Alba SL         │
 *   │ Contacto                │   NIF B12000001                  │
 *   │       [QR]              │   Calle Mayor 12, 28013 Madrid   │
 *   ├─────────────────────────┴──────────────────────────────────┤
 *   │ Tabla de líneas (multi-página automático)                  │
 *   │ ...                                                        │
 *   │                                                            │
 *   │                                                            │
 *   ├──── zona inferior fija (en CADA página) ─────────────────────┤
 *   │ Textos legales (rectificativa/IVA       ┌───────────────────┐ │
 *   │ exento/reducido/condiciones)            │ Base    100,00 €│ │
 *   │                                         │ IVA 21%  21,00 €│ │
 *   │                                         │ Ret.      0,00 €│ │
 *   │                                         │ TOTAL   121,00 €│ │
 *   │                                         └───────────────────┘ │
 *   │ IBAN: ESxx xxxx xxxx xxxx xxxx                                │
 *   │ Pie / agradecimientos                                         │
 *   │                                                                │
 *   │                    Página 1 / N                                │
 *   └────────────────────────────────────────────────────────────────┘
 *
 * El bloque inferior se dibuja en cada página vía PdfPageEvent.onEndPage
 * para que esté SIEMPRE pegado abajo, independientemente de dónde
 * termina la última línea de la tabla. Tres bandas:
 *   - banda alta (legales) — crece según el contenido
 *   - banda media-baja (IBAN + pie) — pegada al borde, papel timbrado
 *   - banda inferior (paginación) — siempre, incluso si solo hay 1 hoja
 *
 * El logo y el QR son por ahora placeholders: el QR llega con VF3
 * (cliente AEAT), el logo cuando se añada columna companies.logo_path.
 *
 * Pendiente: pintar los totales SOLO en la última página y dejar un
 * footer-light en las intermedias. Por ahora se pintan en todas, que es
 * el comportamiento habitual de muchas facturas (defensivo si el cliente
 * solo imprime una página).
 */
@Service
public class InvoicePdfGenerator {

    /** 2026-06-10 tarde — Inyectado para cargar datos extendidos del
     *  cliente (NIF + dirección + ciudad + ...) en el bloque del
     *  cliente del PDF. Sin esto solo pintábamos customerLegalName.
     *  Tras feedback de Benjamin: "la dirección del cliente, que para
     *  facturación tiene que estar". */
    private final org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    public InvoicePdfGenerator(org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final NumberFormat MONEY = NumberFormat.getCurrencyInstance(Locale.forLanguageTag("es-ES"));
    private static final DateTimeFormatter DATE_DM_Y = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private static final Color INK = new Color(15, 23, 42);
    private static final Color INK_LIGHT = new Color(71, 85, 105);
    private static final Color BG_HEADER = new Color(15, 27, 45);
    private static final Color BG_ALT = new Color(248, 251, 255);
    private static final Color BG_TOTAL = new Color(29, 78, 216);
    private static final Color DIVIDER = new Color(219, 227, 239);

    // Altura reservada para el footer fijo (totales + legal + IBAN/pie + paginado).
    private static final float FOOTER_HEIGHT = 250f;

    // Coordenadas verticales del footer en puntos PDF (y=0 es el borde
    // INFERIOR del papel). De abajo a arriba:
    //   y=18  → "Página X / N" centrado pegado al borde
    //   y=42–95 → IBAN + texto de pie (papel timbrado, siempre abajo)
    //   y=110 → arriba de aquí: textos legales (IVA reducido/exento,
    //          rectificativa, condiciones), pueden crecer hasta el
    //          divider superior del footer (≈ FOOTER_HEIGHT + 10).
    //   bloque totales en zona derecha (no cambia).
    private static final float PAGE_NUM_Y = 18f;
    private static final float IBAN_BOTTOM_Y = 42f;
    private static final float IBAN_TOP_Y = 100f;
    private static final float LEGAL_BOTTOM_Y = IBAN_TOP_Y + 8f;

    public byte[] generate(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts) {
        return generate(invoice, company, texts, null, null, null);
    }

    public byte[] generate(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts,
                           String verifactuHash) {
        return generate(invoice, company, texts, verifactuHash, null, null);
    }

    /**
     * Variante completa con QR oficial AEAT (VF3-QR) + huella VeriFactu
     * + etiqueta de cumplimiento.
     *
     * @param verifactuHash    SHA-256 hex; null = no se pinta huella.
     * @param qrPng            bytes del PNG del QR oficial AEAT (lo genera
     *                         {@link InvoiceQrService}); null = placeholder
     *                         visual como antes.
     * @param complianceLabel  texto bajo el QR ("VERI*FACTU" o
     *                         "Factura verificable en la sede electronica
     *                         de la AEAT"). null = no se pinta.
     */
    public byte[] generate(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts,
                           String verifactuHash, byte[] qrPng, String complianceLabel) {
        return generate(invoice, company, texts, verifactuHash, qrPng, complianceLabel, false);
    }

    /**
     * VF-QR-TOGGLE: variante con {@code qrSuppressed}. Cuando la empresa
     * apaga el QR (y aun no esta obligada), no se pinta NADA del bloque
     * VeriFactu — ni QR, ni etiqueta, ni placeholder, ni la linea de
     * huella — la zona queda vacia como en las proformas. La cadena hash
     * sigue registrandose en BD; solo se deja de imprimir.
     */
    public byte[] generate(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts,
                           String verifactuHash, byte[] qrPng, String complianceLabel,
                           boolean qrSuppressed) {
        try {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            // Top margin reducido (30) para subir el título cerca del
            // borde superior. bottomMargin sigue grande (FOOTER_HEIGHT)
            // para reservar la zona del footer fijo con totales+legales.
            Document document = new Document(PageSize.A4, 36, 36, 30, FOOTER_HEIGHT + 30);
            PdfWriter writer = PdfWriter.getInstance(document, bos);
            // FAC-IVA (2026-07-10): textos legales PROPIOS de los tipos de
            // IVA usados por las líneas (vat_rate_id). Con dos tipos al
            // mismo % (dos 0% distintos), el texto que se imprime es el del
            // tipo ELEGIDO, no el genérico de la empresa.
            writer.setPageEvent(new FooterEvent(invoice, company, texts,
                    loadVatLegalTexts(invoice)));
            document.open();

            // PROFORMA: documento comercial sin valor fiscal. No lleva QR
            // oficial AEAT, ni huella VeriFactu, ni etiqueta de
            // cumplimiento. Si la convertimos a NORMAL y validamos, el
            // PDF que se almacena entonces sí los lleva. Esto se aplica
            // tanto a borradores como a "validaciones" de proforma (si
            // tuviera serie PROFORMA emitida).
            boolean isProforma = "PROFORMA".equals(invoice.invoiceType());
            // VF-QR-TOGGLE: con el QR apagado la zona queda LIMPIA del todo
            // (ni QR, ni etiqueta, ni placeholder, ni linea de huella) —
            // pedido por Benjamin 2026-08-07. La huella sigue existiendo en
            // BD/registro; solo se deja de imprimir mientras dure el plazo.
            boolean hideQrZone = isProforma || qrSuppressed;
            String effHash = hideQrZone ? null : verifactuHash;
            byte[] effQr = hideQrZone ? null : qrPng;
            String effLabel = hideQrZone ? null : complianceLabel;

            addTitle(document, invoice);
            addTopBlock(document, invoice, company, effHash, effQr, effLabel, hideQrZone);
            addLinesTable(document, invoice);

            document.close();
            return bos.toByteArray();
        } catch (DocumentException e) {
            throw new RuntimeException("No se pudo generar el PDF de la factura", e);
        }
    }

    // ----- Cabecera -----

    private void addTitle(Document document, SalesInvoice invoice) throws DocumentException {
        boolean isRectifying = "RECTIFYING".equals(invoice.invoiceType());
        boolean isProforma = "PROFORMA".equals(invoice.invoiceType());
        String title = isRectifying ? "FACTURA RECTIFICATIVA"
                : isProforma ? "PROFORMA"
                : "FACTURA";

        // Tamaño moderado (15pt) y más spacingAfter para que el bloque
        // del logo encima del emisor no quede visualmente invadido por el
        // título — sobre todo en rectificativas, donde el texto es más
        // largo. Antes 22pt centrado pisaba la zona del logo.
        Font fTitle = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 15, INK);
        Paragraph p = new Paragraph(title, fTitle);
        p.setAlignment(Element.ALIGN_CENTER);
        p.setSpacingAfter(20f);
        document.add(p);
    }

    private void addTopBlock(Document document, SalesInvoice invoice, CompanyDataResponse company,
                              String verifactuHash, byte[] qrPng, String complianceLabel,
                              boolean suppressQrZone) throws DocumentException {
        Font fEmpName = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, INK);
        Font fMeta = FontFactory.getFont(FontFactory.HELVETICA, 9, INK_LIGHT);
        Font fNumber = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, INK);
        Font fCustomerName = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, INK);

        // Tabla cabecera con 3 columnas y DOS filas, para que el nombre
        // del emisor (col izq, fila 2) y el nombre del cliente (col der,
        // fila 2) caigan a la misma altura visual. El "logo placeholder"
        // y el "Nº + Fecha" ocupan la fila 1, equilibrando alturas.
        //
        //   ┌──────────────┬───────┬──────────────────────┐
        //   │ [Logo]       │       │   Nº F-2026-0001     │  ← fila 1
        //   │              │       │   Fecha: 02/06/2026  │
        //   ├──────────────┼───────┼──────────────────────┤
        //   │ NombreEmisor │ [QR]  │   NombreCliente      │  ← fila 2
        //   │ NIF/dir/...  │       │                      │
        //   └──────────────┴───────┴──────────────────────┘
        PdfPTable top = new PdfPTable(3);
        top.setWidthPercentage(100);
        top.setWidths(new float[]{40f, 20f, 40f});
        top.setSpacingAfter(12f);

        // === FILA 1 ===

        // Col 1: hueco para el logo. PORT-4 LOGO (2026-06-10) — la columna
        // companies.logo_path ya existe (V87) y el endpoint
        // /api/settings/company/logo guarda/devuelve los bytes. Falta el
        // último paso: el generator necesita acceso a esos bytes. Como
        // cambiar la firma rompe 3 callers (InvoiceEmailService,
        // SalesInvoiceController, SalesInvoiceService), dejamos el slice
        // de "logo en PDF" para una iteración aparte (decisión 2026-06-10).
        PdfPCell logoCell = new PdfPCell(new Phrase(" ", fMeta));
        logoCell.setBorder(Rectangle.NO_BORDER);
        logoCell.setMinimumHeight(50f);
        top.addCell(logoCell);

        // Col 2: vacío en fila 1.
        PdfPCell emptyCenter = new PdfPCell(new Phrase(" ", fMeta));
        emptyCenter.setBorder(Rectangle.NO_BORDER);
        top.addCell(emptyCenter);

        // Col 3: Nº + Fecha + notes (si rectificativa), alineado derecha.
        PdfPCell numCell = new PdfPCell();
        numCell.setBorder(Rectangle.NO_BORDER);
        numCell.setPadding(2f);
        Paragraph numAndDate = new Paragraph();
        numAndDate.setAlignment(Element.ALIGN_RIGHT);
        numAndDate.add(new Phrase("Nº " + nz(invoice.invoiceNumber() == null ? "(borrador)" : invoice.invoiceNumber()) + "\n", fNumber));
        numAndDate.add(new Phrase("Fecha: " + safe(invoice.invoiceDate(), DATE_DM_Y) + "\n", fMeta));
        if ("RECTIFYING".equals(invoice.invoiceType()) && nonBlank(invoice.notes())) {
            numAndDate.add(new Phrase(invoice.notes() + "\n", fMeta));
        }
        numCell.addElement(numAndDate);
        top.addCell(numCell);

        // === FILA 2 ===

        // Col 1: emisor (nombre en primera línea para alinear con el del
        // cliente del lado derecho).
        PdfPCell empCell = new PdfPCell();
        empCell.setBorder(Rectangle.NO_BORDER);
        empCell.setPadding(2f);
        empCell.setVerticalAlignment(Element.ALIGN_TOP);
        Paragraph emp = new Paragraph();
        emp.setAlignment(Element.ALIGN_LEFT);
        emp.add(new Phrase(nz(company.legalName()) + "\n", fEmpName));
        if (nonBlank(company.taxIdentifier())) {
            emp.add(new Phrase("NIF: " + company.taxIdentifier() + "\n", fMeta));
        }
        String fullAddress = joinNonBlank(", ",
                company.addressLine(), company.postalCode(), company.city(), company.province());
        if (!fullAddress.isBlank()) {
            emp.add(new Phrase(fullAddress + "\n", fMeta));
        }
        String contact = joinNonBlank(" · ", company.phone(), company.email(), company.website());
        if (!contact.isBlank()) {
            emp.add(new Phrase(contact + "\n", fMeta));
        }
        if (nonBlank(company.registryInformation())) {
            emp.add(new Phrase(company.registryInformation() + "\n", fMeta));
        }
        empCell.addElement(emp);
        top.addCell(empCell);

        // Col 2: QR oficial AEAT (si esta disponible) + etiqueta de
        // cumplimiento + huella VeriFactu abreviada. Si no llega QR
        // real (factura aun en borrador, o problema generando), cae al
        // placeholder visual como antes.
        // PROFORMA o QR apagado (VF-QR-TOGGLE): ni QR ni placeholder.
        PdfPCell qrCell = new PdfPCell();
        qrCell.setBorder(Rectangle.NO_BORDER);
        qrCell.setPadding(2f);
        qrCell.setHorizontalAlignment(Element.ALIGN_CENTER);
        qrCell.setVerticalAlignment(Element.ALIGN_TOP);
        boolean qrRendered = false;
        if (qrPng != null && qrPng.length > 0) {
            try {
                Image qr = Image.getInstance(qrPng);
                // 70x70 pt en PDF ~= 24.7 mm impreso. Cae dentro del
                // rango 30-40mm que pide la Orden HAC/1177/2024 art. 20
                // cuando el documento se imprime a tamanyo real (los
                // navegadores y lectores no escalan PDFs por defecto).
                qr.scaleAbsolute(70f, 70f);
                qr.setAlignment(Element.ALIGN_CENTER);
                qrCell.addElement(qr);
                qrRendered = true;
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(InvoicePdfGenerator.class)
                        .warn("No se pudo embeber el QR en el PDF; uso placeholder", ex);
            }
        }
        if (!qrRendered && !suppressQrZone) {
            qrCell.addElement(qrPlaceholder());
        }
        if (complianceLabel != null && !complianceLabel.isBlank()) {
            // Texto obligatorio inmediatamente debajo del QR (Orden
            // HAC/1177/2024 art. 20). En VERIFACTU = "VERI*FACTU"; en
            // NO VERIFACTU = "Factura verificable en la sede
            // electronica de la AEAT".
            Font fLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6, INK);
            Paragraph p = new Paragraph(complianceLabel, fLabel);
            p.setAlignment(Element.ALIGN_CENTER);
            p.setSpacingBefore(2f);
            qrCell.addElement(p);
        }
        if (verifactuHash != null && !verifactuHash.isBlank()) {
            // Los 16 primeros caracteres del hash SHA-256 son suficientes
            // para que un humano compare visualmente dos facturas
            // correlativas y vea que la cadena no se ha roto. El hash
            // completo (64 chars) está en BD y se devuelve por /verify.
            Font fHash = FontFactory.getFont(FontFactory.HELVETICA, 6, INK_LIGHT);
            String shortHash = verifactuHash.length() > 16
                    ? verifactuHash.substring(0, 16) : verifactuHash;
            Paragraph hashP = new Paragraph("Huella: " + shortHash, fHash);
            hashP.setAlignment(Element.ALIGN_CENTER);
            hashP.setSpacingBefore(1f);
            qrCell.addElement(hashP);
        }
        top.addCell(qrCell);

        // Col 3: cliente. Tras feedback Benjamin 2026-06-10 tarde,
        // ya no se queda solo con el nombre — pinta NIF + dirección
        // completa + email + teléfono si están definidos en el cliente.
        // El cliente extendido se carga por customerId; si falla,
        // caemos al comportamiento previo (solo nombre).
        PdfPCell custCell = new PdfPCell();
        custCell.setBorder(Rectangle.NO_BORDER);
        custCell.setPadding(2f);
        custCell.setVerticalAlignment(Element.ALIGN_TOP);
        Paragraph custName = new Paragraph(nz(invoice.customerLegalName()), fCustomerName);
        custName.setAlignment(Element.ALIGN_RIGHT);
        custCell.addElement(custName);
        CustomerView cust = loadCustomerView(invoice.customerId());
        if (cust != null) {
            if (nonBlank(cust.taxIdentifier())) {
                Paragraph p = new Paragraph("NIF: " + cust.taxIdentifier(), fMeta);
                p.setAlignment(Element.ALIGN_RIGHT);
                custCell.addElement(p);
            }
            String custAddr = joinNonBlank(", ",
                    cust.address(), cust.postalCode(), cust.city(), cust.province());
            if (!custAddr.isBlank()) {
                Paragraph p = new Paragraph(custAddr, fMeta);
                p.setAlignment(Element.ALIGN_RIGHT);
                custCell.addElement(p);
            }
            if (nonBlank(cust.country()) && !"España".equalsIgnoreCase(cust.country().trim())) {
                Paragraph p = new Paragraph(cust.country(), fMeta);
                p.setAlignment(Element.ALIGN_RIGHT);
                custCell.addElement(p);
            }
            String custContact = joinNonBlank(" · ", cust.phone(), cust.email());
            if (!custContact.isBlank()) {
                Paragraph p = new Paragraph(custContact, fMeta);
                p.setAlignment(Element.ALIGN_RIGHT);
                custCell.addElement(p);
            }
        }
        top.addCell(custCell);

        document.add(top);
        document.add(divider());
    }

    private PdfPTable qrPlaceholder() {
        PdfPTable t = new PdfPTable(1);
        // Ancho moderado (90% del contenedor) para dejar respiración en
        // los laterales de la columna central.
        t.setWidthPercentage(90f);
        t.setHorizontalAlignment(Element.ALIGN_CENTER);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(75f);
        c.setBorder(Rectangle.BOX);
        c.setBorderColor(DIVIDER);
        c.setBackgroundColor(new Color(245, 248, 252));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        Font fSmall = FontFactory.getFont(FontFactory.HELVETICA, 7, INK_LIGHT);
        Paragraph p = new Paragraph("QR\nVeriFactu\n(VF3)", fSmall);
        p.setAlignment(Element.ALIGN_CENTER);
        c.addElement(p);
        t.addCell(c);
        return t;
    }

    // ----- Tabla líneas -----

    private void addLinesTable(Document document, SalesInvoice invoice) throws DocumentException {
        PdfPTable table = new PdfPTable(5);
        table.setWidthPercentage(100);
        table.setWidths(new float[]{50f, 10f, 14f, 10f, 16f});
        table.setSpacingAfter(8f);
        table.setHeaderRows(1);

        Font fHead = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.WHITE);
        addHeaderCell(table, "Descripción", fHead, Element.ALIGN_LEFT);
        addHeaderCell(table, "Cant.", fHead, Element.ALIGN_RIGHT);
        addHeaderCell(table, "Precio", fHead, Element.ALIGN_RIGHT);
        addHeaderCell(table, "IVA", fHead, Element.ALIGN_RIGHT);
        addHeaderCell(table, "Subtotal", fHead, Element.ALIGN_RIGHT);

        Font fBody = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
        int i = 0;
        for (InvoiceLine line : invoice.lines()) {
            Color bg = (i % 2 == 0) ? Color.WHITE : BG_ALT;
            addBodyCell(table, nz(line.description()), fBody, Element.ALIGN_LEFT, bg);
            addBodyCell(table, plainDecimal(line.quantity()), fBody, Element.ALIGN_RIGHT, bg);
            addBodyCell(table, money(line.unitPrice()), fBody, Element.ALIGN_RIGHT, bg);
            addBodyCell(table, plainDecimal(line.vatPercent()) + " %", fBody, Element.ALIGN_RIGHT, bg);
            addBodyCell(table, money(line.lineSubtotal()), fBody, Element.ALIGN_RIGHT, bg);
            i++;
        }
        document.add(table);
    }

    // ----- Footer fijo (totales + legales + pie) por página -----

    /**
     * FAC-IVA — mapa id → legal_text de los tipos de IVA que usan las líneas
     * de la factura (solo los que tienen texto). Vacío si ninguna línea
     * lleva vat_rate_id (histórico/imports).
     */
    private java.util.Map<String, String> loadVatLegalTexts(SalesInvoice invoice) {
        java.util.Set<String> ids = new java.util.LinkedHashSet<>();
        if (invoice.lines() != null) {
            for (var l : invoice.lines()) {
                if (l.vatRateId() != null && !l.vatRateId().isBlank()) ids.add(l.vatRateId());
            }
        }
        if (ids.isEmpty()) return java.util.Map.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        java.util.Map<String, String> out = new java.util.LinkedHashMap<>();
        try {
            jdbcTemplate.query(
                    "SELECT id, legal_text FROM vat_rates WHERE id IN (" + placeholders + ")",
                    rs -> {
                        String txt = rs.getString("legal_text");
                        if (txt != null && !txt.isBlank()) out.put(rs.getString("id"), txt.trim());
                    }, ids.toArray());
        } catch (Exception ignored) {
            // sin catálogo accesible el PDF sale con los textos genéricos
        }
        return out;
    }

    private class FooterEvent extends PdfPageEventHelper {
        private final SalesInvoice invoice;
        private final CompanyDataResponse company;
        private final InvoiceTexts texts;
        /** FAC-IVA: id de tipo → texto legal propio (solo tipos con texto). */
        private final java.util.Map<String, String> vatLegalTexts;
        // Template para escribir el total de páginas en la numeración.
        // Se rellena en onCloseDocument cuando ya sabemos cuántas hubo.
        private PdfTemplate totalPagesTpl;

        FooterEvent(SalesInvoice invoice, CompanyDataResponse company, InvoiceTexts texts,
                    java.util.Map<String, String> vatLegalTexts) {
            this.invoice = invoice;
            this.company = company;
            this.texts = texts;
            this.vatLegalTexts = vatLegalTexts == null ? java.util.Map.of() : vatLegalTexts;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            totalPagesTpl = writer.getDirectContent().createTemplate(40f, 12f);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();

            float pageWidth = document.getPageSize().getWidth();
            float pageMarginLeft = document.leftMargin();
            float pageMarginRight = document.rightMargin();
            float footerTopY = FOOTER_HEIGHT + 10f;

            // Divider gris encima del footer.
            cb.saveState();
            cb.setLineWidth(0.5f);
            cb.setColorStroke(DIVIDER);
            cb.moveTo(pageMarginLeft, footerTopY);
            cb.lineTo(pageWidth - pageMarginRight, footerTopY);
            cb.stroke();
            cb.restoreState();

            try {
                // ---- Bloque DERECHA: totales ----
                float totalsWidth = 220f;
                float totalsX = pageWidth - pageMarginRight - totalsWidth;
                float totalsTopY = footerTopY - 14f;
                drawTotals(cb, invoice, totalsX, totalsTopY, totalsWidth);

                // ---- Bloque IZQUIERDA: textos legales (zona alta) ----
                // Los textos legales (rectificativa, IVA reducido/exento,
                // condiciones) viven aquí y pueden crecer libremente.
                float leftWidth = pageWidth - pageMarginLeft - pageMarginRight - totalsWidth - 18f;
                float legalTopY = footerTopY - 14f;
                drawLegalTexts(cb, invoice, texts, pageMarginLeft, legalTopY, leftWidth, LEGAL_BOTTOM_Y);

                // ---- Bloque IZQUIERDA: IBAN + pie (zona baja, fija) ----
                // Pegados al borde inferior, como en un papel timbrado.
                drawIbanAndPie(cb, company, texts, pageMarginLeft, IBAN_TOP_Y, leftWidth, IBAN_BOTTOM_Y);
            } catch (DocumentException ignored) {
                // No reventamos por una página con footer incompleto.
            }

            // ---- Paginación centrada pegada al borde inferior ----
            drawPageNumber(cb, writer, pageWidth);
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            // Rellenamos el template con el total real. writer.getPageNumber()
            // tras cerrar el documento devuelve el siguiente número (= total+1
            // en algunas versiones), por eso restamos 1.
            int total = writer.getPageNumber() - 1;
            Font fNum = FontFactory.getFont(FontFactory.HELVETICA, 8, INK_LIGHT);
            ColumnText.showTextAligned(totalPagesTpl, Element.ALIGN_LEFT,
                    new Phrase(String.valueOf(total), fNum), 0f, 2f, 0f);
        }

        private void drawPageNumber(PdfContentByte cb, PdfWriter writer, float pageWidth) {
            Font fNum = FontFactory.getFont(FontFactory.HELVETICA, 8, INK_LIGHT);
            String prefix = "Página " + writer.getPageNumber() + " / ";
            float prefixWidth = fNum.getBaseFont().getWidthPoint(prefix, 8f);
            // Reservamos 24pt para el template del total y centramos el
            // conjunto (prefix + template) respecto al ancho de página.
            float blockWidth = prefixWidth + 24f;
            float startX = (pageWidth - blockWidth) / 2f;
            ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase(prefix, fNum), startX, PAGE_NUM_Y, 0f);
            cb.addTemplate(totalPagesTpl, startX + prefixWidth, PAGE_NUM_Y - 2f);
        }

        private void drawTotals(PdfContentByte cb, SalesInvoice invoice, float x, float topY, float width) throws DocumentException {
            Map<BigDecimal, BigDecimal> cuotaByVat = new LinkedHashMap<>();
            for (InvoiceLine line : invoice.lines()) {
                BigDecimal pct = line.vatPercent() == null ? BigDecimal.ZERO : line.vatPercent();
                cuotaByVat.merge(pct, line.lineVat(), BigDecimal::add);
            }

            PdfPTable totals = new PdfPTable(2);
            totals.setTotalWidth(width);
            totals.setWidths(new float[]{60f, 40f});
            totals.setLockedWidth(true);

            Font fRow = FontFactory.getFont(FontFactory.HELVETICA, 10, INK);
            Font fTotalLabel = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 12, Color.WHITE);
            Font fTotalValue = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, Color.WHITE);

            addTotalRow(totals, "Base imponible", money(invoice.subtotal()), fRow, fRow, null);
            for (Map.Entry<BigDecimal, BigDecimal> e : cuotaByVat.entrySet()) {
                addTotalRow(totals, "IVA " + plainDecimal(e.getKey()) + " %", money(e.getValue()), fRow, fRow, null);
            }
            if (invoice.retentionTotal() != null && invoice.retentionTotal().compareTo(BigDecimal.ZERO) != 0) {
                addTotalRow(totals, "Retención IRPF", "-" + money(invoice.retentionTotal()), fRow, fRow, null);
            }
            addTotalRow(totals, "TOTAL FACTURA", money(invoice.total()), fTotalLabel, fTotalValue, BG_TOTAL);

            totals.writeSelectedRows(0, -1, x, topY, cb);
        }

        /**
         * Banda alta del footer (debajo del divider, encima del bloque IBAN/pie).
         * Contiene los textos legales aplicables: rectificativa, IVA exento,
         * IVA reducido y condiciones generales. Si no hay nada que decir,
         * queda en blanco (lo legal es opcional).
         */
        private void drawLegalTexts(PdfContentByte cb, SalesInvoice invoice, InvoiceTexts texts,
                                     float x, float topY, float width, float bottomY) throws DocumentException {
            if (texts == null) return;
            Font fText = FontFactory.getFont(FontFactory.HELVETICA, 8, INK_LIGHT);

            ColumnText ct = new ColumnText(cb);
            ct.setSimpleColumn(x, bottomY, x + width, topY);

            if ("RECTIFYING".equals(invoice.invoiceType()) && nonBlank(texts.rectifying())) {
                ct.addElement(legalParagraph(texts.rectifying(), fText));
            }
            // FAC-IVA: primero, los textos legales PROPIOS de los tipos de
            // IVA elegidos en las líneas (deduplicados, en orden de uso).
            java.util.LinkedHashSet<String> typeTexts = new java.util.LinkedHashSet<>();
            for (var l : invoice.lines()) {
                if (l.vatRateId() != null) {
                    String txt = vatLegalTexts.get(l.vatRateId());
                    if (txt != null) typeTexts.add(txt);
                }
            }
            for (String txt : typeTexts) {
                ct.addElement(legalParagraph(txt, fText));
            }
            // El texto GENÉRICO de exención solo cubre las líneas al 0% que
            // NO llevan un tipo con texto propio (histórico o tipos sin
            // legal_text). Así no se imprime "el que quiera BENJAGEST" cuando
            // el usuario eligió un 0% concreto con su propio texto.
            boolean hasExempt = invoice.lines().stream()
                    .anyMatch(l -> l.vatPercent() != null
                            && l.vatPercent().compareTo(BigDecimal.ZERO) == 0
                            && (l.vatRateId() == null || vatLegalTexts.get(l.vatRateId()) == null));
            if (hasExempt && nonBlank(texts.exempt())) {
                ct.addElement(legalParagraph(texts.exempt(), fText));
            }
            boolean hasReduced = invoice.lines().stream()
                    .anyMatch(l -> l.vatPercent() != null
                            && (l.vatPercent().compareTo(new BigDecimal("4")) == 0
                                    || l.vatPercent().compareTo(new BigDecimal("10")) == 0));
            if (hasReduced && nonBlank(texts.reducedVat())) {
                ct.addElement(legalParagraph(texts.reducedVat(), fText));
            }
            // Los términos legales (vencimiento, mora, jurisdicción) solo
            // tienen sentido si la factura tiene fecha de vencimiento. Si se
            // emitió "sin vencimiento" (cobrada al contado), no se imprimen.
            if (nonBlank(texts.legalTerms()) && invoice.dueDate() != null) {
                ct.addElement(legalParagraph(texts.legalTerms(), fText));
            }
            ct.go();
        }

        /**
         * Banda baja del footer (papel timbrado): IBAN encima del pie, ambos
         * pegados al borde inferior. Se dibuja en cada página, igual que los
         * totales — así si una factura se imprime y solo llega la última
         * hoja, el cobrador sigue viendo el IBAN.
         *
         * Si no hay {@code InvoiceTexts} pero la empresa tiene
         * {@code invoiceFooter} configurado, lo pintamos como fallback para
         * no perder ese dato heredado.
         */
        private void drawIbanAndPie(PdfContentByte cb, CompanyDataResponse company, InvoiceTexts texts,
                                     float x, float topY, float width, float bottomY) throws DocumentException {
            Font fIban = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, INK);
            Font fFooter = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, INK_LIGHT);

            ColumnText ct = new ColumnText(cb);
            ct.setSimpleColumn(x, bottomY, x + width, topY);

            boolean addedSomething = false;
            if (texts != null) {
                if (texts.showIban() && nonBlank(company.iban())) {
                    Paragraph ibanP = new Paragraph("IBAN: " + company.iban(), fIban);
                    ct.addElement(ibanP);
                    addedSomething = true;
                }
                if (nonBlank(texts.pie())) {
                    Paragraph p = new Paragraph(texts.pie(), fFooter);
                    p.setSpacingBefore(4f);
                    ct.addElement(p);
                    addedSomething = true;
                }
            }
            // Tras V22 consolidacion, el pie de factura vive solo en
            // invoice_texts.pie. El fallback al campo legacy
            // company.invoice_footer queda fuera porque esa columna ya
            // no existe.
            ct.go();
        }
    }

    // ----- Helpers de cells -----

    private void addHeaderCell(PdfPTable table, String text, Font font, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(BG_HEADER);
        c.setHorizontalAlignment(align);
        c.setPadding(7f);
        c.setBorder(Rectangle.NO_BORDER);
        table.addCell(c);
    }

    private void addBodyCell(PdfPTable table, String text, Font font, int align, Color bg) {
        PdfPCell c = new PdfPCell(new Phrase(text, font));
        c.setBackgroundColor(bg);
        c.setHorizontalAlignment(align);
        c.setPadding(6f);
        c.setBorder(Rectangle.NO_BORDER);
        table.addCell(c);
    }

    private void addTotalRow(PdfPTable table, String label, String value, Font fLabel, Font fValue, Color bg) {
        PdfPCell cLabel = new PdfPCell(new Phrase(label, fLabel));
        cLabel.setBorder(Rectangle.NO_BORDER);
        cLabel.setPadding(6f);
        if (bg != null) cLabel.setBackgroundColor(bg);
        cLabel.setHorizontalAlignment(Element.ALIGN_LEFT);
        table.addCell(cLabel);

        PdfPCell cValue = new PdfPCell(new Phrase(value, fValue));
        cValue.setBorder(Rectangle.NO_BORDER);
        cValue.setPadding(6f);
        if (bg != null) cValue.setBackgroundColor(bg);
        cValue.setHorizontalAlignment(Element.ALIGN_RIGHT);
        table.addCell(cValue);
    }

    private Paragraph legalParagraph(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setSpacingBefore(2f);
        p.setSpacingAfter(1f);
        return p;
    }

    private PdfPTable divider() throws DocumentException {
        PdfPTable t = new PdfPTable(1);
        t.setWidthPercentage(100);
        PdfPCell c = new PdfPCell();
        c.setFixedHeight(1f);
        c.setBorder(Rectangle.NO_BORDER);
        c.setBackgroundColor(DIVIDER);
        t.addCell(c);
        t.setSpacingAfter(8f);
        return t;
    }

    // ----- Format helpers -----

    private String money(BigDecimal value) {
        if (value == null) return MONEY.format(0);
        return MONEY.format(value);
    }

    private String plainDecimal(BigDecimal value) {
        if (value == null) return "0";
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.scale() < 0
                ? stripped.setScale(0, RoundingMode.UNNECESSARY).toPlainString()
                : stripped.toPlainString();
    }

    private String safe(java.time.LocalDate d, DateTimeFormatter fmt) {
        return d == null ? "—" : d.format(fmt);
    }

    private String nz(String s) {
        return s == null ? "" : s;
    }

    private boolean nonBlank(String s) {
        return s != null && !s.isBlank();
    }

    private String joinNonBlank(String sep, String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (nonBlank(p)) {
                if (sb.length() > 0) sb.append(sep);
                sb.append(p);
            }
        }
        return sb.toString();
    }

    /** 2026-06-10 tarde — Datos extendidos del cliente para el bloque
     *  del cliente en el PDF de factura. Devuelve null si el cliente
     *  no existe (factura de demo, customerId roto, etc.) — en ese
     *  caso solo se pinta el nombre. */
    private CustomerView loadCustomerView(String customerId) {
        if (customerId == null || customerId.isBlank()) return null;
        try {
            return jdbcTemplate.query("""
                    SELECT legal_name, tax_identifier,
                           COALESCE(address, '') AS address,
                           COALESCE(city, '') AS city,
                           COALESCE(province, '') AS province,
                           COALESCE(postal_code, '') AS postal_code,
                           COALESCE(country, '') AS country,
                           COALESCE(phone, '') AS phone,
                           COALESCE(email, '') AS email
                      FROM customers WHERE id = ?
                    """, rs -> {
                        if (!rs.next()) return null;
                        return new CustomerView(
                                rs.getString("legal_name"),
                                rs.getString("tax_identifier"),
                                rs.getString("address"),
                                rs.getString("city"),
                                rs.getString("province"),
                                rs.getString("postal_code"),
                                rs.getString("country"),
                                rs.getString("phone"),
                                rs.getString("email"));
                    }, customerId);
        } catch (Exception ex) {
            // Defensivo: si V85 no se aplicó por algún motivo, no
            // queremos romper la generación entera del PDF.
            return null;
        }
    }

    private record CustomerView(
            String legalName, String taxIdentifier,
            String address, String city, String province, String postalCode,
            String country, String phone, String email
    ) {}
}
