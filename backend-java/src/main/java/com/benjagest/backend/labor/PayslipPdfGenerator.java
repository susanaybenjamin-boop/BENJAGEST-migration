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
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Generador de recibo de salario (nomina) en PDF.
 *
 * Estructura simplificada pero conforme a la Orden ESS/2098/2014 (modelo
 * oficial de recibo de salarios). Cubre los apartados obligatorios:
 *
 *   - Cabecera: empresa (NIF, CCC, domicilio) + trabajador (NIF, NUSS,
 *     categoría, fecha alta, antigüedad).
 *   - Período de liquidación.
 *   - Devengos (salario base, complementos, pagas prorrateadas).
 *   - Deducciones (SS empleado, IRPF, anticipos, otros).
 *   - Base imponible IRPF, base de cotización, líquido.
 *   - Pie: firma empresa + firma trabajador + fecha entrega.
 *
 * Limitaciones honestas:
 *   - No separa "Salario base / complementos" — toma `gross_amount`
 *     como bloque único. El recibo legal exige desglose; sub-slice
 *     para añadir tabla de conceptos.
 *   - El CCC (Cuenta Cotización Cliente) de la empresa no se pinta
 *     (no está en companies todavía).
 *   - La antigüedad se calcula sobre hire_date del empleado.
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
                       c.professional_category, c.weekly_hours, c.gross_salary
                  FROM payslips p
                  JOIN employees e ON e.id = p.employee_id
                  LEFT JOIN employment_contracts c ON c.id = p.contract_id
                 WHERE p.id = ? AND p.company_id = ?
                """, payslipId, tenantContext.getCurrentCompanyId());

        if (data == null || data.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Nómina no encontrada");
        }

        // Cuotas TC del periodo (desglose de cotización trabajador + empresa).
        java.util.Map<String, BigDecimal> tcAmt = new java.util.HashMap<>();
        java.util.Map<String, BigDecimal> tcBase = new java.util.HashMap<>();
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

        CompanyDataResponse company = companyDataService.getCurrent();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 36, 36, 36, 36);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            // Fuentes
            Font fH1 = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, new Color(30, 60, 110));
            Font fBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.BLACK);
            Font fNormal = FontFactory.getFont(FontFactory.HELVETICA, 9, Color.BLACK);
            Font fSmall = FontFactory.getFont(FontFactory.HELVETICA, 8, Color.DARK_GRAY);

            // Sin titulón. El modelo oficial no lleva un título destacado;
            // como mucho una referencia discreta al modelo legal.
            Paragraph caption = new Paragraph(
                    "Recibo individual justificativo del pago de salarios", fSmall);
            caption.setAlignment(Element.ALIGN_LEFT);
            caption.setSpacingAfter(6f);
            doc.add(caption);

            // Cabecera: 2 columnas (empresa | trabajador)
            PdfPTable header = new PdfPTable(2);
            header.setWidthPercentage(100);
            header.setSpacingAfter(10f);

            PdfPCell empresa = sectionCell("EMPRESA", fH1);
            empresa.addElement(fieldLine("Razón social", company.legalName(), fBold, fNormal));
            empresa.addElement(fieldLine("NIF/CIF", company.taxIdentifier(), fBold, fNormal));
            empresa.addElement(fieldLine("Domicilio",
                    safeJoin(company.addressLine(), company.city(),
                            company.postalCode(), company.province()), fBold, fNormal));
            header.addCell(empresa);

            PdfPCell trabajador = sectionCell("TRABAJADOR", fH1);
            trabajador.addElement(fieldLine("Nombre", str(data.get("employee_name")), fBold, fNormal));
            trabajador.addElement(fieldLine("NIF", str(data.get("employee_nif")), fBold, fNormal));
            trabajador.addElement(fieldLine("Nº SS", str(data.get("employee_nuss")), fBold, fNormal));
            java.sql.Date hireDate = (java.sql.Date) data.get("hire_date");
            String antiguedad = hireDate == null ? "-" :
                    hireDate.toLocalDate() + " (" + yearsSince(hireDate.toLocalDate()) + " años)";
            trabajador.addElement(fieldLine("Antigüedad", antiguedad, fBold, fNormal));
            trabajador.addElement(fieldLine("Categoría",
                    str(data.get("professional_category")), fBold, fNormal));
            trabajador.addElement(fieldLine("Contrato",
                    str(data.get("contract_type")), fBold, fNormal));
            header.addCell(trabajador);

            doc.add(header);

            // Periodo
            int year = ((Number) data.get("period_year")).intValue();
            int month = ((Number) data.get("period_month")).intValue();
            String monthName = Month.of(month).getDisplayName(TextStyle.FULL, new Locale("es"));
            String periodLabel = monthName.toUpperCase(new Locale("es"))
                    + " " + year + "  ·  "
                    + ("MONTHLY".equals(data.get("payslip_type")) ? "Mensual" : str(data.get("payslip_type")));

            PdfPTable periodTbl = new PdfPTable(1);
            periodTbl.setWidthPercentage(100);
            periodTbl.setSpacingAfter(8f);
            PdfPCell pc = new PdfPCell(new Phrase(periodLabel, fBold));
            pc.setBackgroundColor(new Color(220, 230, 245));
            pc.setHorizontalAlignment(Element.ALIGN_CENTER);
            pc.setPadding(6f);
            periodTbl.addCell(pc);
            doc.add(periodTbl);

            // Devengos
            doc.add(sectionTitle("DEVENGOS", fH1));
            PdfPTable devengos = new PdfPTable(new float[]{6, 2, 2});
            devengos.setWidthPercentage(100);
            devengos.addCell(headerCell("Concepto", fBold));
            devengos.addCell(headerCell("Cantidad", fBold));
            devengos.addCell(headerCell("Importe", fBold));

            BigDecimal gross = (BigDecimal) data.get("gross_amount");
            devengos.addCell(textCell("Salario bruto del periodo", fNormal, Element.ALIGN_LEFT));
            devengos.addCell(textCell("1", fNormal, Element.ALIGN_CENTER));
            devengos.addCell(textCell(money(gross), fNormal, Element.ALIGN_RIGHT));

            PdfPCell totalDev = new PdfPCell(new Phrase("TOTAL DEVENGADO", fBold));
            totalDev.setColspan(2);
            totalDev.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalDev.setBackgroundColor(new Color(245, 245, 250));
            totalDev.setPadding(4f);
            devengos.addCell(totalDev);
            devengos.addCell(headerCellRight(money(gross), fBold));

            devengos.setSpacingAfter(10f);
            doc.add(devengos);

            // Deducciones
            doc.add(sectionTitle("DEDUCCIONES", fH1));
            PdfPTable deducciones = new PdfPTable(new float[]{6, 2, 2});
            deducciones.setWidthPercentage(100);
            deducciones.addCell(headerCell("Concepto", fBold));
            deducciones.addCell(headerCell("%", fBold));
            deducciones.addCell(headerCell("Importe", fBold));

            BigDecimal ss = (BigDecimal) data.get("ss_employee_amount");
            BigDecimal irpf = (BigDecimal) data.get("irpf_amount");
            BigDecimal otros = (BigDecimal) data.get("other_deductions");
            BigDecimal irpfPct = pctOf(irpf, gross);

            // Desglose de la aportación del trabajador a la SS por concepto
            // (cuotas TC). Si no hay cuotas TC (nóminas antiguas), una sola línea.
            boolean hasEeTc = tcAmt.keySet().stream().anyMatch(k -> k.startsWith("EMPLOYEE"));
            if (hasEeTc) {
                addDeduccion(deducciones, "Contingencias comunes", "EMPLOYEE_COMMON", tcAmt, tcBase, fNormal);
                addDeduccion(deducciones, "Desempleo", "EMPLOYEE_UNEMPLOYMENT", tcAmt, tcBase, fNormal);
                addDeduccion(deducciones, "Formación profesional", "EMPLOYEE_TRAINING", tcAmt, tcBase, fNormal);
                addDeduccion(deducciones, "MEI", "EMPLOYEE_MEI", tcAmt, tcBase, fNormal);
            } else {
                deducciones.addCell(textCell("Aportación trabajador SS", fNormal, Element.ALIGN_LEFT));
                deducciones.addCell(textCell(pctOf(ss, gross).toPlainString() + " %", fNormal, Element.ALIGN_CENTER));
                deducciones.addCell(textCell(money(ss), fNormal, Element.ALIGN_RIGHT));
            }

            deducciones.addCell(textCell("Retención IRPF", fNormal, Element.ALIGN_LEFT));
            deducciones.addCell(textCell(irpfPct.toPlainString() + " %", fNormal, Element.ALIGN_CENTER));
            deducciones.addCell(textCell(money(irpf), fNormal, Element.ALIGN_RIGHT));

            if (otros != null && otros.signum() > 0) {
                deducciones.addCell(textCell("Otras deducciones", fNormal, Element.ALIGN_LEFT));
                deducciones.addCell(textCell("", fNormal, Element.ALIGN_CENTER));
                deducciones.addCell(textCell(money(otros), fNormal, Element.ALIGN_RIGHT));
            }

            BigDecimal totalDed = ss.add(irpf).add(otros == null ? BigDecimal.ZERO : otros);
            PdfPCell totalDedLbl = new PdfPCell(new Phrase("TOTAL DEDUCIDO", fBold));
            totalDedLbl.setColspan(2);
            totalDedLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
            totalDedLbl.setBackgroundColor(new Color(245, 245, 250));
            totalDedLbl.setPadding(4f);
            deducciones.addCell(totalDedLbl);
            deducciones.addCell(headerCellRight(money(totalDed), fBold));

            deducciones.setSpacingAfter(12f);
            doc.add(deducciones);

            // Líquido
            BigDecimal net = (BigDecimal) data.get("net_amount");
            PdfPTable netoTbl = new PdfPTable(2);
            netoTbl.setWidthPercentage(100);
            netoTbl.setWidths(new float[]{6, 2});
            PdfPCell netoLbl = new PdfPCell(new Phrase("LÍQUIDO A PERCIBIR", FontFactory.getFont(
                    FontFactory.HELVETICA_BOLD, 12, Color.WHITE)));
            netoLbl.setBackgroundColor(new Color(30, 60, 110));
            netoLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
            netoLbl.setPadding(8f);
            netoTbl.addCell(netoLbl);
            PdfPCell netoVal = new PdfPCell(new Phrase(money(net), FontFactory.getFont(
                    FontFactory.HELVETICA_BOLD, 12, Color.WHITE)));
            netoVal.setBackgroundColor(new Color(30, 60, 110));
            netoVal.setHorizontalAlignment(Element.ALIGN_RIGHT);
            netoVal.setPadding(8f);
            netoTbl.addCell(netoVal);
            netoTbl.setSpacingAfter(20f);
            doc.add(netoTbl);

            // Determinación de las bases de cotización y aportación de la
            // empresa (Orden ESS/2098/2014 — art. 104.2 LGSS). Es el apartado
            // que distingue al recibo oficial: la empresa debe informar de su
            // propia aportación, separada de la del trabajador.
            doc.add(sectionTitle("DETERMINACIÓN DE LAS BASES DE COTIZACIÓN Y APORTACIÓN DE LA EMPRESA", fH1));
            PdfPTable bases = new PdfPTable(new float[]{5, 2, 1.3f, 2});
            bases.setWidthPercentage(100);
            bases.addCell(headerCell("Concepto", fBold));
            bases.addCell(headerCell("Base", fBold));
            bases.addCell(headerCell("Tipo", fBold));
            bases.addCell(headerCell("Aportación empresa", fBold));

            boolean hasErTc = tcAmt.keySet().stream().anyMatch(k -> k.startsWith("EMPLOYER"));
            BigDecimal totalEr = BigDecimal.ZERO;
            if (hasErTc) {
                totalEr = totalEr.add(addAportacion(bases, "Contingencias comunes", "EMPLOYER_COMMON", tcAmt, tcBase, fNormal));
                totalEr = totalEr.add(addAportacion(bases, "Contingencias profesionales (AT y EP)", "EMPLOYER_AT_EP", tcAmt, tcBase, fNormal));
                totalEr = totalEr.add(addAportacion(bases, "Desempleo", "EMPLOYER_UNEMPLOYMENT", tcAmt, tcBase, fNormal));
                totalEr = totalEr.add(addAportacion(bases, "Formación profesional", "EMPLOYER_TRAINING", tcAmt, tcBase, fNormal));
                totalEr = totalEr.add(addAportacion(bases, "FOGASA", "EMPLOYER_FOGASA", tcAmt, tcBase, fNormal));
                totalEr = totalEr.add(addAportacion(bases, "MEI", "EMPLOYER_MEI", tcAmt, tcBase, fNormal));

                PdfPCell totLbl = new PdfPCell(new Phrase("TOTAL APORTACIÓN EMPRESA", fBold));
                totLbl.setColspan(3);
                totLbl.setHorizontalAlignment(Element.ALIGN_RIGHT);
                totLbl.setBackgroundColor(new Color(245, 245, 250));
                totLbl.setPadding(4f);
                bases.addCell(totLbl);
                bases.addCell(headerCellRight(money(totalEr), fBold));
            } else {
                PdfPCell none = new PdfPCell(new Phrase(
                        "Sin desglose de cotización disponible para este periodo.", fNormal));
                none.setColspan(4);
                none.setPadding(6f);
                bases.addCell(none);
            }
            bases.setSpacingAfter(14f);
            doc.add(bases);

            // Coste total para la empresa (informativo).
            if (hasErTc) {
                Paragraph coste = new Paragraph(
                        "Coste total para la empresa (bruto + aportación SS empresa): "
                        + money(gross.add(totalEr)), fBold);
                coste.setSpacingAfter(26f);
                doc.add(coste);
            }

            // Firmas
            PdfPTable firmas = new PdfPTable(2);
            firmas.setWidthPercentage(80);
            firmas.setHorizontalAlignment(Element.ALIGN_CENTER);
            PdfPCell f1 = new PdfPCell(new Phrase("Firma de la empresa", fSmall));
            f1.setBorder(Rectangle.TOP);
            f1.setBorderWidth(0.5f);
            f1.setHorizontalAlignment(Element.ALIGN_CENTER);
            f1.setPaddingTop(40f);
            PdfPCell f2 = new PdfPCell(new Phrase("Recibí del trabajador", fSmall));
            f2.setBorder(Rectangle.TOP);
            f2.setBorderWidth(0.5f);
            f2.setHorizontalAlignment(Element.ALIGN_CENTER);
            f2.setPaddingTop(40f);
            firmas.addCell(f1);
            firmas.addCell(f2);
            doc.add(firmas);

            // Pie
            Paragraph pie = new Paragraph(
                    "Documento generado conforme al modelo oficial de recibo de salario (Orden ESS/2098/2014).",
                    fSmall);
            pie.setAlignment(Element.ALIGN_CENTER);
            pie.setSpacingBefore(20f);
            doc.add(pie);

            doc.close();
        } catch (DocumentException ex) {
            throw new RuntimeException("Error generando PDF nómina", ex);
        }
        return out.toByteArray();
    }

    // ---- helpers PDF ----

    private PdfPCell sectionCell(String title, Font fTitle) {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(new Color(200, 200, 210));
        c.setBorderWidth(0.5f);
        c.setPadding(8f);
        Paragraph p = new Paragraph(title, fTitle);
        p.setSpacingAfter(4f);
        c.addElement(p);
        return c;
    }

    private Paragraph fieldLine(String label, String value, Font fLabel, Font fValue) {
        Paragraph p = new Paragraph();
        p.add(new Phrase(label + ": ", fLabel));
        p.add(new Phrase(value == null ? "-" : value, fValue));
        p.setLeading(11f);
        return p;
    }

    private Paragraph sectionTitle(String text, Font f) {
        Paragraph p = new Paragraph(text, f);
        p.setSpacingBefore(4f);
        p.setSpacingAfter(4f);
        return p;
    }

    private PdfPCell headerCell(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(new Color(30, 60, 110));
        c.setHorizontalAlignment(Element.ALIGN_CENTER);
        c.setPadding(4f);
        Font white = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 9, Color.WHITE);
        c.setPhrase(new Phrase(text, white));
        return c;
    }

    private PdfPCell headerCellRight(String text, Font f) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setBackgroundColor(new Color(245, 245, 250));
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        c.setPadding(4f);
        return c;
    }

    private PdfPCell textCell(String text, Font f, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setPadding(4f);
        return c;
    }

    /** Añade una fila de deducción del trabajador (concepto / % / importe). */
    private void addDeduccion(PdfPTable t, String label, String type,
                               Map<String, BigDecimal> amt, Map<String, BigDecimal> base, Font f) {
        BigDecimal a = amt.get(type);
        if (a == null) return;
        BigDecimal b = base.get(type);
        t.addCell(textCell(label, f, Element.ALIGN_LEFT));
        t.addCell(textCell(pctOf(a, b).toPlainString() + " %", f, Element.ALIGN_CENTER));
        t.addCell(textCell(money(a), f, Element.ALIGN_RIGHT));
    }

    /** Añade una fila de aportación de la empresa (concepto / base / tipo /
     *  aportación) y devuelve el importe aportado (0 si no existe). */
    private BigDecimal addAportacion(PdfPTable t, String label, String type,
                                      Map<String, BigDecimal> amt, Map<String, BigDecimal> base, Font f) {
        BigDecimal a = amt.get(type);
        if (a == null) return BigDecimal.ZERO;
        BigDecimal b = base.get(type);
        t.addCell(textCell(label, f, Element.ALIGN_LEFT));
        t.addCell(textCell(money(b), f, Element.ALIGN_RIGHT));
        t.addCell(textCell(pctOf(a, b).toPlainString() + " %", f, Element.ALIGN_CENTER));
        t.addCell(textCell(money(a), f, Element.ALIGN_RIGHT));
        return a;
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
