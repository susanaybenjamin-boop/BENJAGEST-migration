package com.benjagest.backend.labor;

import com.benjagest.backend.settings.CompanyDataResponse;
import com.benjagest.backend.settings.CompanyDataService;
import com.benjagest.backend.tenant.TenantContext;
import com.lowagie.text.Document;
import com.lowagie.text.DocumentException;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generador de recibo de salario (nómina) en PDF, con el layout clásico
 * de los programas profesionales de nómina (estilo Nomio / A3 / Sage),
 * conforme a la Orden ESS/2098/2014 (modelo oficial).
 *
 * <p>Estructura:
 * <ol>
 *   <li>Cabecera EMPRESA (NIF, domicilio, Nº SS) + TRABAJADOR (DNI,
 *       Nº afiliación, categoría, fecha alta, antigüedad, % IRPF, periodo).</li>
 *   <li>Tabla de conceptos: CLAVE | CONCEPTO | CANTIDAD | IMPORTE |
 *       DEVENGOS | DEDUCCIONES + totales.</li>
 *   <li>Bases de cotización (Remuneración + Prorrata + IT = Total).</li>
 *   <li>Líquido a percibir.</li>
 *   <li>Dos tablas lado a lado: aportación del TRABAJADOR y aportación de
 *       la EMPRESA a la Seguridad Social (art. 104.2 LGSS).</li>
 *   <li>Firmas trabajador / empresa.</li>
 * </ol>
 *
 * <p>El desglose de cotización (trabajador y empresa) se lee de las cuotas
 * TC ({@code social_security_contributions}) del periodo. Si no existen
 * (nóminas antiguas), se degrada a una línea única.
 */
@Service
public class PayslipPdfGenerator {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CompanyDataService companyDataService;

    public PayslipPdfGenerator(JdbcTemplate jdbcTemplate,
                                TenantContext tenantContext,
                                CompanyDataService companyDataService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.companyDataService = companyDataService;
    }

    public byte[] generate(String payslipId) {
        Map<String, Object> data = jdbcTemplate.queryForMap("""
                SELECT p.id, p.employee_id, p.period_year, p.period_month, p.payslip_type,
                       p.gross_amount, p.ss_employee_amount, p.irpf_amount,
                       p.other_deductions, p.net_amount, p.status, p.paid_at,
                       e.full_name AS employee_name, e.tax_identifier AS employee_nif,
                       e.social_security_number AS employee_nuss,
                       e.hire_date, e.address_line, e.city, e.postal_code,
                       c.contract_type, c.collective_agreement,
                       c.professional_category, c.weekly_hours, c.gross_salary,
                       c.start_date AS contract_start_date
                  FROM payslips p
                  JOIN employees e ON e.id = p.employee_id
                  LEFT JOIN employment_contracts c ON c.id = p.contract_id
                 WHERE p.id = ? AND p.company_id = ?
                """, payslipId, tenantContext.getCurrentCompanyId());

        if (data == null || data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nómina no encontrada");
        }

        // Solo las nóminas MENSUALES generan cuotas TC. Las pagas extra (14
        // pagas) NO cotizan aparte: su cotización ya va prorrateada en las 12
        // mensuales. Si leyéramos las cuotas por (empleado, año, mes) sin más,
        // el recibo de la extra mostraría la cotización de la mensual de ese
        // mes (cruce). Por eso solo cargamos cuotas para la nómina mensual.
        boolean isMonthly = "MONTHLY".equals(str(data.get("payslip_type")));
        Map<String, BigDecimal> tcAmt = new HashMap<>();
        Map<String, BigDecimal> tcBase = new HashMap<>();
        if (isMonthly) {
            jdbcTemplate.query("""
                    SELECT contribution_type, base_amount, contribution_amount
                      FROM social_security_contributions
                     WHERE company_id = ? AND employee_id = ?
                       AND period_year = ? AND period_month = ?
                    """, rs -> {
                        tcAmt.put(rs.getString("contribution_type"), rs.getBigDecimal("contribution_amount"));
                        tcBase.put(rs.getString("contribution_type"), rs.getBigDecimal("base_amount"));
                    },
                    tenantContext.getCurrentCompanyId(), str(data.get("employee_id")),
                    ((Number) data.get("period_year")).intValue(),
                    ((Number) data.get("period_month")).intValue());
        }

        // Líneas de devengo (conceptos salariales de este recibo).
        java.util.List<String[]> devLines = jdbcTemplate.query("""
                SELECT concept_name, amount
                  FROM payslip_lines
                 WHERE payslip_id = ? AND company_id = ?
                 ORDER BY sort_order, concept_name
                """,
                (rs, n) -> new String[]{rs.getString("concept_name"),
                        money(rs.getBigDecimal("amount"))},
                payslipId, tenantContext.getCurrentCompanyId());

        CompanyDataResponse company = companyDataService.getCurrent();

        // ---- Magnitudes ----
        int year = ((Number) data.get("period_year")).intValue();
        int month = ((Number) data.get("period_month")).intValue();
        BigDecimal gross = nz((BigDecimal) data.get("gross_amount"));
        BigDecimal ssEmp = nz((BigDecimal) data.get("ss_employee_amount"));
        BigDecimal irpf = nz((BigDecimal) data.get("irpf_amount"));
        BigDecimal otros = nz((BigDecimal) data.get("other_deductions"));
        BigDecimal net = nz((BigDecimal) data.get("net_amount"));
        BigDecimal irpfPct = pctOf(irpf, gross);
        BigDecimal totalDed = ssEmp.add(irpf).add(otros);

        // Base de cotización (= anual/12, incluye prorrata pagas extras). En
        // pagas extra no hay cotización propia, así que base y prorrata son 0.
        BigDecimal cotBase;
        BigDecimal prorrata;
        if (isMonthly) {
            cotBase = tcBase.get("EMPLOYEE_COMMON");
            if (cotBase == null) cotBase = tcBase.getOrDefault("EMPLOYER_COMMON", gross);
            prorrata = cotBase.subtract(gross);
            if (prorrata.signum() < 0) prorrata = BigDecimal.ZERO;
        } else {
            cotBase = BigDecimal.ZERO;
            prorrata = BigDecimal.ZERO;
        }

        boolean hasEe = tcAmt.keySet().stream().anyMatch(k -> k.startsWith("EMPLOYEE"));
        boolean hasEr = tcAmt.keySet().stream().anyMatch(k -> k.startsWith("EMPLOYER"));

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // Margen inferior amplio (115) para reservar el espacio de las firmas
        // + pie legal, que se dibujan SIEMPRE al pie mediante un page event.
        Document doc = new Document(PageSize.A4, 30, 30, 30, 115);
        try {
            PdfWriter writer = PdfWriter.getInstance(doc, out);
            writer.setPageEvent(new FirmasFooter());
            doc.open();

            Font fLbl = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 6.5f, new Color(90, 90, 95));
            Font fVal = FontFactory.getFont(FontFactory.HELVETICA, 8.5f, Color.BLACK);
            Font fHead = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 7.5f, Color.BLACK);
            Font fNormal = FontFactory.getFont(FontFactory.HELVETICA, 8f, Color.BLACK);
            Font fBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8f, Color.BLACK);
            Font fSmall = FontFactory.getFont(FontFactory.HELVETICA, 7f, Color.DARK_GRAY);

            // ===== 1. Cabecera EMPRESA =====
            PdfPTable emp = new PdfPTable(new float[]{4, 2});
            emp.setWidthPercentage(100);
            emp.addCell(lv("EMPRESA", company.legalName(), fLbl, fVal));
            emp.addCell(lv("N.I.F.", company.taxIdentifier(), fLbl, fVal));
            emp.addCell(lv("DOMICILIO", safeJoin(company.addressLine(), company.city(),
                    company.postalCode(), company.province()), fLbl, fVal));
            emp.addCell(lv("Nº INSCRIPCIÓN S.S. (CCC)", "", fLbl, fVal));
            doc.add(emp);

            // ===== 2. Cabecera TRABAJADOR =====
            PdfPTable trab1 = new PdfPTable(new float[]{4, 2.5f, 3});
            trab1.setWidthPercentage(100);
            trab1.setSpacingBefore(2f);
            trab1.addCell(lv("TRABAJADOR/A", str(data.get("employee_name")), fLbl, fVal));
            trab1.addCell(lv("D.N.I.", str(data.get("employee_nif")), fLbl, fVal));
            trab1.addCell(lv("Nº AFILIACIÓN S.S.", str(data.get("employee_nuss")), fLbl, fVal));
            doc.add(trab1);

            // FECHA ALTA / ANTIGÜEDAD: fecha de contratación del empleado y, si
            // no está, fecha de inicio del contrato (alta en la empresa).
            java.sql.Date hire = (java.sql.Date) data.get("hire_date");
            java.sql.Date cStart = (java.sql.Date) data.get("contract_start_date");
            java.time.LocalDate altaDate = hire != null ? hire.toLocalDate()
                    : (cStart != null ? cStart.toLocalDate() : null);
            String antig = altaDate == null ? "-" : yearsSince(altaDate) + " años";
            String periodo = "1 al " + Month.of(month).length(java.time.Year.of(year).isLeap())
                    + " de " + Month.of(month).getDisplayName(TextStyle.FULL, new Locale("es"));
            // En pagas extra se identifica la gratificación en el período.
            String tipoNom = str(data.get("payslip_type"));
            if ("EXTRA_SUMMER".equals(tipoNom)) periodo += " · Paga extra de verano";
            else if ("EXTRA_CHRISTMAS".equals(tipoNom)) periodo += " · Paga extra de Navidad";
            PdfPTable trab2 = new PdfPTable(new float[]{3, 2.5f, 2, 1.5f, 3.5f, 1.2f});
            trab2.setWidthPercentage(100);
            trab2.addCell(lv("CATEGORÍA PROFESIONAL", str(data.get("professional_category")), fLbl, fVal));
            trab2.addCell(lv("CONTRATO", str(data.get("contract_type")), fLbl, fVal));
            trab2.addCell(lv("FECHA ALTA", altaDate == null ? "-" : altaDate.toString(), fLbl, fVal));
            trab2.addCell(lv("ANTIGÜEDAD", antig, fLbl, fVal));
            trab2.addCell(lv("PERIODO DE LIQUIDACIÓN", periodo + " " + year, fLbl, fVal));
            trab2.addCell(lv("DÍAS", "30", fLbl, fVal));
            doc.add(trab2);

            // ===== 3. Tabla de conceptos (devengos / deducciones) =====
            PdfPTable con = new PdfPTable(new float[]{1.2f, 4.5f, 1.6f, 1.8f, 1.9f, 1.9f});
            con.setWidthPercentage(100);
            con.setSpacingBefore(8f);
            con.addCell(hdr("CLAVE", fHead));
            con.addCell(hdr("CONCEPTO", fHead));
            con.addCell(hdr("CANTIDAD", fHead));
            con.addCell(hdr("IMPORTE", fHead));
            con.addCell(hdr("DEVENGOS", fHead));
            con.addCell(hdr("DEDUCCIONES", fHead));

            // Devengos: una línea por concepto salarial (salario base +
            // complementos). Fallback a línea única si no hay desglose.
            if (!devLines.isEmpty()) {
                int clave = 1;
                for (String[] l : devLines) {
                    con.addCell(cell(String.valueOf(clave++), fNormal, Element.ALIGN_LEFT));
                    con.addCell(cell(l[0], fNormal, Element.ALIGN_LEFT));
                    con.addCell(cell("", fNormal, Element.ALIGN_CENTER));
                    con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
                    con.addCell(cell(l[1], fNormal, Element.ALIGN_RIGHT));
                    con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
                }
            } else {
                con.addCell(cell("1", fNormal, Element.ALIGN_LEFT));
                con.addCell(cell("Salario bruto del periodo", fNormal, Element.ALIGN_LEFT));
                con.addCell(cell("30", fNormal, Element.ALIGN_CENTER));
                con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
                con.addCell(cell(money(gross), fNormal, Element.ALIGN_RIGHT));
                con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
            }

            // Deducciones del trabajador.
            if (hasEe) {
                conceptDed(con, "5100", "Contingencias comunes", "EMPLOYEE_COMMON", tcAmt, tcBase, fNormal);
                conceptDed(con, "5101", "Desempleo", "EMPLOYEE_UNEMPLOYMENT", tcAmt, tcBase, fNormal);
                conceptDed(con, "5102", "Formación profesional", "EMPLOYEE_TRAINING", tcAmt, tcBase, fNormal);
                conceptDed(con, "5103", "MEI", "EMPLOYEE_MEI", tcAmt, tcBase, fNormal);
            } else if (ssEmp.signum() > 0) {
                con.addCell(cell("5100", fNormal, Element.ALIGN_LEFT));
                con.addCell(cell("Aportación trabajador SS", fNormal, Element.ALIGN_LEFT));
                con.addCell(cell(pctStr(ssEmp, gross), fNormal, Element.ALIGN_CENTER));
                con.addCell(cell(money(gross), fNormal, Element.ALIGN_RIGHT));
                con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
                con.addCell(cell(money(ssEmp), fNormal, Element.ALIGN_RIGHT));
            }
            // (en pagas extra ssEmp = 0: no se muestra línea de cotización)
            con.addCell(cell("5120", fNormal, Element.ALIGN_LEFT));
            con.addCell(cell("Retención I.R.P.F.", fNormal, Element.ALIGN_LEFT));
            con.addCell(cell(irpfPct.toPlainString().replace(".", ",") + " %", fNormal, Element.ALIGN_CENTER));
            con.addCell(cell(money(gross), fNormal, Element.ALIGN_RIGHT));
            con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
            con.addCell(cell(money(irpf), fNormal, Element.ALIGN_RIGHT));
            if (otros.signum() > 0) {
                con.addCell(cell("", fNormal, Element.ALIGN_LEFT));
                con.addCell(cell("Otras deducciones", fNormal, Element.ALIGN_LEFT));
                con.addCell(cell("", fNormal, Element.ALIGN_CENTER));
                con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
                con.addCell(cell("", fNormal, Element.ALIGN_RIGHT));
                con.addCell(cell(money(otros), fNormal, Element.ALIGN_RIGHT));
            }

            // Totales.
            PdfPCell tot = new PdfPCell(new Phrase("TOTAL", fBold));
            tot.setColspan(4);
            tot.setHorizontalAlignment(Element.ALIGN_RIGHT);
            tot.setBackgroundColor(new Color(235, 235, 238));
            tot.setPadding(3f);
            con.addCell(tot);
            con.addCell(totCell(money(gross), fBold));
            con.addCell(totCell(money(totalDed), fBold));
            doc.add(con);

            // ===== 4. Bases de cotización =====
            PdfPTable bc = new PdfPTable(new float[]{4, 2, 2, 1.5f, 2});
            bc.setWidthPercentage(100);
            bc.setSpacingBefore(8f);
            bc.addCell(hdr("", fHead));
            bc.addCell(hdr("REMUNERACIÓN", fHead));
            bc.addCell(hdr("PRORRATA P. EXTRAS", fHead));
            bc.addCell(hdr("I.T.", fHead));
            bc.addCell(hdr("TOTAL BASE", fHead));

            bc.addCell(cell("Base cotización cont. comunes", fNormal, Element.ALIGN_LEFT));
            bc.addCell(cell(money(gross), fNormal, Element.ALIGN_RIGHT));
            bc.addCell(cell(money(prorrata), fNormal, Element.ALIGN_RIGHT));
            bc.addCell(cell(money(BigDecimal.ZERO), fNormal, Element.ALIGN_RIGHT));
            bc.addCell(cell(money(cotBase), fNormal, Element.ALIGN_RIGHT));

            bc.addCell(cell("Base cotización desempleo / FP / AT-EP", fNormal, Element.ALIGN_LEFT));
            bc.addCell(cell(money(gross), fNormal, Element.ALIGN_RIGHT));
            bc.addCell(cell(money(prorrata), fNormal, Element.ALIGN_RIGHT));
            bc.addCell(cell(money(BigDecimal.ZERO), fNormal, Element.ALIGN_RIGHT));
            bc.addCell(cell(money(cotBase), fNormal, Element.ALIGN_RIGHT));

            bc.addCell(cell("Base sujeta a retención I.R.P.F.", fNormal, Element.ALIGN_LEFT));
            PdfPCell irpfBase = new PdfPCell(new Phrase(money(gross), fNormal));
            irpfBase.setColspan(4);
            irpfBase.setHorizontalAlignment(Element.ALIGN_RIGHT);
            irpfBase.setPadding(3f);
            bc.addCell(irpfBase);
            doc.add(bc);

            // ===== 5. Líquido a percibir =====
            PdfPTable liq = new PdfPTable(new float[]{6, 2});
            liq.setWidthPercentage(100);
            liq.setSpacingBefore(8f);
            PdfPCell ll = new PdfPCell(new Phrase("LÍQUIDO TOTAL A PERCIBIR",
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE)));
            ll.setBackgroundColor(new Color(30, 60, 110));
            ll.setHorizontalAlignment(Element.ALIGN_RIGHT);
            ll.setPadding(7f);
            liq.addCell(ll);
            PdfPCell lvv = new PdfPCell(new Phrase(money(net),
                    FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.WHITE)));
            lvv.setBackgroundColor(new Color(30, 60, 110));
            lvv.setHorizontalAlignment(Element.ALIGN_RIGHT);
            lvv.setPadding(7f);
            liq.addCell(lvv);
            doc.add(liq);

            // ===== 6. Aportaciones a la Seguridad Social (trabajador | empresa) =====
            // Solo si hay cotización (nómina mensual). En pagas extra no hay.
            BigDecimal totalEe = sumPrefix(tcAmt, "EMPLOYEE");
            BigDecimal totalEr = sumPrefix(tcAmt, "EMPLOYER");
            if (hasEe || hasEr) {

            PdfPTable both = new PdfPTable(2);
            both.setWidthPercentage(100);
            both.setSpacingBefore(10f);
            both.getDefaultCell().setBorder(Rectangle.NO_BORDER);
            both.getDefaultCell().setPadding(0f);
            both.getDefaultCell().setPaddingRight(6f);

            PdfPCell leftWrap = new PdfPCell();
            leftWrap.setBorder(Rectangle.NO_BORDER);
            leftWrap.setPaddingRight(6f);
            leftWrap.addElement(aportTable("APORTACIÓN DEL TRABAJADOR A LA SEGURIDAD SOCIAL",
                    money(totalEe), "EMPLOYEE", tcAmt, tcBase, fHead, fNormal, fBold));
            both.addCell(leftWrap);

            PdfPCell rightWrap = new PdfPCell();
            rightWrap.setBorder(Rectangle.NO_BORDER);
            rightWrap.setPaddingLeft(6f);
            rightWrap.addElement(aportTable("APORTACIÓN DE LA EMPRESA A LA SEGURIDAD SOCIAL",
                    money(totalEr), "EMPLOYER", tcAmt, tcBase, fHead, fNormal, fBold));
            both.addCell(rightWrap);
            doc.add(both);

            if (hasEr) {
                Paragraph coste = new Paragraph(
                        "Coste total para la empresa (bruto + aportación SS empresa): "
                        + money(gross.add(totalEr)), fBold);
                coste.setSpacingBefore(8f);
                doc.add(coste);
            }
            } // fin if (hasEe || hasEr)

            // Las firmas y el pie legal se dibujan al pie de la página en
            // FirmasFooter.onEndPage (margen inferior reservado = 90).

            doc.close();
        } catch (DocumentException ex) {
            throw new RuntimeException("Error generando PDF nómina", ex);
        }
        return out.toByteArray();
    }

    /** Dibuja las firmas (trabajador / empresa) y el pie legal SIEMPRE al pie
     *  de la página, en el margen inferior reservado. */
    private static class FirmasFooter extends com.lowagie.text.pdf.PdfPageEventHelper {
        @Override
        public void onEndPage(com.lowagie.text.pdf.PdfWriter writer, Document document) {
            Font f = FontFactory.getFont(FontFactory.HELVETICA, 7, Color.DARK_GRAY);
            com.lowagie.text.pdf.PdfContentByte cb = writer.getDirectContent();
            float left = document.left();
            float right = document.right();
            float gap = 24f;
            float colW = (right - left - gap) / 2f;
            float xL = left;
            float xR = left + colW + gap;
            float yLine = document.bottom() - 30f;   // línea de firma
            cb.setLineWidth(0.5f);
            cb.setColorStroke(new Color(120, 120, 130));
            cb.moveTo(xL, yLine); cb.lineTo(xL + colW, yLine); cb.stroke();
            cb.moveTo(xR, yLine); cb.lineTo(xR + colW, yLine); cb.stroke();
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Firma del trabajador/a (Recibí)", f), xL, yLine - 12f, 0);
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Fecha: ____________________", f), xL, yLine - 24f, 0);
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Firma y sello de la empresa", f), xR, yLine - 12f, 0);
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_LEFT,
                    new Phrase("Municipio: ____________________", f), xR, yLine - 24f, 0);
            // Pie legal MUY abajo, separado de las firmas para que no se cruce.
            com.lowagie.text.pdf.ColumnText.showTextAligned(cb, Element.ALIGN_CENTER,
                    new Phrase("Documento generado conforme al modelo oficial de recibo de salarios (Orden ESS/2098/2014).", f),
                    (left + right) / 2f, 22f, 0);
        }
    }

    // ---- helpers ----

    /** Celda etiqueta-valor (label pequeño arriba, valor debajo). */
    private PdfPCell lv(String label, String value, Font fLabel, Font fValue) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(new Color(170, 170, 180));
        c.setBorderWidth(0.5f);
        c.setPadding(4f);
        Paragraph pl = new Paragraph(label, fLabel);
        pl.setLeading(8f);
        Paragraph pv = new Paragraph(value == null || value.isBlank() ? " " : value, fValue);
        pv.setLeading(10f);
        c.addElement(pl);
        c.addElement(pv);
        return c;
    }

    private PdfPCell hdr(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(new Color(225, 228, 235));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setBorderColor(new Color(170, 170, 180));
        c.setPadding(3f);
        return c;
    }

    private PdfPCell cell(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text == null ? "" : text, f));
        c.setHorizontalAlignment(align);
        c.setBorderColor(new Color(190, 190, 200));
        c.setPadding(3f);
        return c;
    }

    private PdfPCell totCell(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setBackgroundColor(new Color(235, 235, 238));
        c.setPadding(3f);
        return c;
    }

    /** Fila de deducción del trabajador en la tabla de conceptos. */
    private void conceptDed(PdfPTable t, String clave, String label, String type,
                             Map<String, BigDecimal> amt, Map<String, BigDecimal> base, Font f) {
        BigDecimal a = amt.get(type);
        if (a == null) return;
        BigDecimal b = base.get(type);
        t.addCell(cell(clave, f, Element.ALIGN_LEFT));
        t.addCell(cell(label, f, Element.ALIGN_LEFT));
        t.addCell(cell(pctStr(a, b), f, Element.ALIGN_CENTER));
        t.addCell(cell(money(b), f, Element.ALIGN_RIGHT));
        t.addCell(cell("", f, Element.ALIGN_RIGHT));
        t.addCell(cell(money(a), f, Element.ALIGN_RIGHT));
    }

    /** Sub-tabla de aportación a la SS (trabajador o empresa). */
    private PdfPTable aportTable(String title, String total, String prefix,
                                  Map<String, BigDecimal> amt, Map<String, BigDecimal> base,
                                  Font fHead, Font fNormal, Font fBold) {
        PdfPTable t = new PdfPTable(new float[]{4, 2, 1.4f, 2});
        t.setWidthPercentage(100);

        PdfPCell head = new PdfPCell(new Phrase(title + "   →   " + total, fBold));
        head.setColspan(4);
        head.setBackgroundColor(new Color(225, 228, 235));
        head.setBorderColor(new Color(170, 170, 180));
        head.setPadding(4f);
        t.addCell(head);

        t.addCell(hdr("CONCEPTO", fHead));
        t.addCell(hdr("BASE", fHead));
        t.addCell(hdr("%", fHead));
        t.addCell(hdr("TOTAL", fHead));

        if ("EMPLOYEE".equals(prefix)) {
            aportRow(t, "Contingencias comunes", "EMPLOYEE_COMMON", amt, base, fNormal);
            aportRow(t, "Desempleo", "EMPLOYEE_UNEMPLOYMENT", amt, base, fNormal);
            aportRow(t, "Formación profesional", "EMPLOYEE_TRAINING", amt, base, fNormal);
            aportRow(t, "MEI", "EMPLOYEE_MEI", amt, base, fNormal);
        } else {
            aportRow(t, "Contingencias comunes", "EMPLOYER_COMMON", amt, base, fNormal);
            aportRow(t, "Contingencias prof. (AT y EP)", "EMPLOYER_AT_EP", amt, base, fNormal);
            aportRow(t, "Desempleo", "EMPLOYER_UNEMPLOYMENT", amt, base, fNormal);
            aportRow(t, "Formación profesional", "EMPLOYER_TRAINING", amt, base, fNormal);
            aportRow(t, "FOGASA", "EMPLOYER_FOGASA", amt, base, fNormal);
            aportRow(t, "MEI", "EMPLOYER_MEI", amt, base, fNormal);
        }
        return t;
    }

    private void aportRow(PdfPTable t, String label, String type,
                           Map<String, BigDecimal> amt, Map<String, BigDecimal> base, Font f) {
        BigDecimal a = amt.get(type);
        if (a == null) return;
        BigDecimal b = base.get(type);
        t.addCell(cell(label, f, Element.ALIGN_LEFT));
        t.addCell(cell(money(b), f, Element.ALIGN_RIGHT));
        t.addCell(cell(pctStr(a, b), f, Element.ALIGN_CENTER));
        t.addCell(cell(money(a), f, Element.ALIGN_RIGHT));
    }

    private BigDecimal sumPrefix(Map<String, BigDecimal> amt, String prefix) {
        return amt.entrySet().stream()
                .filter(e -> e.getKey().startsWith(prefix))
                .map(Map.Entry::getValue)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static BigDecimal nz(BigDecimal v) {
        return v == null ? BigDecimal.ZERO : v;
    }

    private String money(BigDecimal amount) {
        if (amount == null) return "0,00 €";
        return amount.setScale(2, RoundingMode.HALF_UP)
                .toPlainString().replace(".", ",") + " €";
    }

    private BigDecimal pctOf(BigDecimal value, BigDecimal base) {
        if (base == null || base.signum() == 0) return BigDecimal.ZERO;
        return value.multiply(BigDecimal.valueOf(100))
                .divide(base, 2, RoundingMode.HALF_UP);
    }

    private String pctStr(BigDecimal value, BigDecimal base) {
        return pctOf(value, base).toPlainString().replace(".", ",") + " %";
    }

    private String str(Object o) {
        return o == null ? "" : o.toString();
    }

    private String safeJoin(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p == null || p.isBlank()) continue;
            if (sb.length() > 0) sb.append(", ");
            sb.append(p);
        }
        return sb.toString();
    }

    private int yearsSince(LocalDate date) {
        if (date == null) return 0;
        return LocalDate.now().getYear() - date.getYear();
    }
}
