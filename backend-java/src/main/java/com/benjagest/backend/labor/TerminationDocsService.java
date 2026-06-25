package com.benjagest.backend.labor;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.settings.CompanyDataResponse;
import com.benjagest.backend.settings.CompanyDataService;
import com.benjagest.backend.tenant.TenantContext;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.FontFactory;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * CV-DOC — Documentos de baja: carta de despido (por tipo) y certificado de
 * empresa. Se generan a partir de los datos del empleado, contrato y la
 * indemnización calculada por {@link TerminationService}.
 */
@Service
public class TerminationDocsService {

    private static final DateTimeFormatter DF = DateTimeFormatter.ofPattern("dd/MM/yyyy");

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CompanyDataService companyDataService;
    private final TerminationService terminationService;

    public TerminationDocsService(JdbcTemplate jdbc, TenantContext tenant,
                                   CompanyDataService companyDataService,
                                   TerminationService terminationService) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.companyDataService = companyDataService;
        this.terminationService = terminationService;
    }

    private Map<String, Object> loadData(String employeeId, LocalDate ceseDate) {
        try {
            return jdbc.queryForMap("""
                    SELECT e.full_name, e.tax_identifier, e.social_security_number,
                           c.start_date, c.gross_salary, c.professional_category, c.contract_type
                      FROM employees e
                      LEFT JOIN employment_contracts c
                        ON c.employee_id = e.id AND c.company_id = e.company_id
                       AND c.start_date <= ?
                       AND (c.end_date IS NULL OR c.end_date >= ?)
                     WHERE e.id = ? AND e.company_id = ?
                     ORDER BY c.start_date DESC LIMIT 1
                    """, ceseDate, ceseDate, employeeId, tenant.getCurrentCompanyId());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado/contrato no encontrado");
        }
    }

    public byte[] dismissalLetter(String employeeId, LocalDate ceseDate, String type) {
        Map<String, Object> d = loadData(employeeId, ceseDate);
        CompanyDataResponse co = companyDataService.getCurrent();
        BigDecimal gross = (BigDecimal) d.get("gross_salary");
        LocalDate start = d.get("start_date") == null ? null : ((java.sql.Date) d.get("start_date")).toLocalDate();
        TerminationService.Severance sev = terminationService.computeSeverance(type, gross, start, ceseDate);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 50, 50);
        Font fH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(30, 60, 110));
        Font fB = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);
        Font fBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
        try {
            com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(p(co.legalName() + "  ·  " + nz(co.taxIdentifier()), fBold, Element.ALIGN_LEFT, 2));
            doc.add(p(safeJoin(co.addressLine(), co.city(), co.postalCode(), co.province()), fB, Element.ALIGN_LEFT, 10));
            doc.add(p(place(co) + ", a " + ceseDate.format(DF), fB, Element.ALIGN_RIGHT, 16));

            doc.add(p(letterTitle(type), fH, Element.ALIGN_CENTER, 14));
            doc.add(p("A la atención de D./Dña. " + str(d.get("full_name"))
                    + " (NIF " + nz(str(d.get("tax_identifier"))) + ")", fBold, Element.ALIGN_LEFT, 12));

            for (String par : letterBody(type, ceseDate, sev)) {
                doc.add(p(par, fB, Element.ALIGN_JUSTIFIED, 10));
            }

            doc.add(p(" ", fB, Element.ALIGN_LEFT, 30));
            doc.add(p("Firma y sello de la empresa" + "                              "
                    + "Recibí (firma del trabajador/a)", fB, Element.ALIGN_LEFT, 0));
            doc.close();
        } catch (Exception ex) {
            throw new RuntimeException("Error generando carta de despido", ex);
        }
        return out.toByteArray();
    }

    public byte[] companyCertificate(String employeeId, LocalDate ceseDate, String type) {
        Map<String, Object> d = loadData(employeeId, ceseDate);
        CompanyDataResponse co = companyDataService.getCurrent();
        LocalDate start = d.get("start_date") == null ? null : ((java.sql.Date) d.get("start_date")).toLocalDate();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 50, 50);
        Font fH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13, new Color(30, 60, 110));
        Font fB = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.BLACK);
        Font fBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
        try {
            com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(p("CERTIFICADO DE EMPRESA", fH, Element.ALIGN_CENTER, 14));
            doc.add(p("La empresa " + co.legalName() + ", con N.I.F. " + nz(co.taxIdentifier())
                    + " y domicilio en " + safeJoin(co.addressLine(), co.city(), co.postalCode(), co.province())
                    + ",", fB, Element.ALIGN_JUSTIFIED, 10));
            doc.add(p("CERTIFICA que el trabajador/a D./Dña. " + str(d.get("full_name"))
                    + ", con NIF " + nz(str(d.get("tax_identifier")))
                    + " y nº de afiliación a la Seguridad Social " + nz(str(d.get("social_security_number")))
                    + ", ha prestado servicios en esta empresa con la categoría profesional de "
                    + nz(str(d.get("professional_category"))) + ".", fB, Element.ALIGN_JUSTIFIED, 10));
            doc.add(p("Fecha de alta: " + (start == null ? "-" : start.format(DF)), fBold, Element.ALIGN_LEFT, 2));
            doc.add(p("Fecha de baja: " + ceseDate.format(DF), fBold, Element.ALIGN_LEFT, 2));
            doc.add(p("Causa de la baja: " + reasonLabel(type), fBold, Element.ALIGN_LEFT, 12));
            long diasAlta = start == null ? 0
                    : java.time.temporal.ChronoUnit.DAYS.between(start, ceseDate) + 1;
            String periodoBases = (start != null && diasAlta < 180)
                    ? "del periodo de alta (desde " + start.format(DF) + " hasta " + ceseDate.format(DF)
                      + ", " + diasAlta + " días)"
                    : "de los últimos 180 días";
            doc.add(p("Las bases de cotización " + periodoBases + ", a efectos de la prestación "
                    + "por desempleo, se comunican a la Tesorería General de la Seguridad Social a "
                    + "través del Sistema RED / certific@2.", fB, Element.ALIGN_JUSTIFIED, 24));
            doc.add(p("Y para que conste a los efectos oportunos, se expide el presente certificado en "
                    + place(co) + ", a " + ceseDate.format(DF) + ".", fB, Element.ALIGN_JUSTIFIED, 30));
            doc.add(p("Firma y sello de la empresa", fB, Element.ALIGN_LEFT, 0));
            doc.close();
        } catch (Exception ex) {
            throw new RuntimeException("Error generando certificado de empresa", ex);
        }
        return out.toByteArray();
    }

    /**
     * REG-FINIQUITO — Recibo / carta de finiquito (el documento legal que el
     * trabajador firma al causar baja). Renderiza el finiquito que YA calcula
     * {@link TerminationService#preview} (liquidación de vacaciones + P.P. pagas
     * extra + salario pendiente, vía PayslipService) + la indemnización. NO
     * recalcula nada. Formato fiel a CONTENDO (DEVENGOS / DESCUENTOS / LÍQUIDO +
     * declaración de saldo y finiquito + firmas).
     */
    public byte[] settlementReceipt(String employeeId, LocalDate ceseDate, String type,
                                    String extrasAccrual, BigDecimal otherDeductions) {
        Map<String, Object> d = loadData(employeeId, ceseDate);
        CompanyDataResponse co = companyDataService.getCurrent();
        LocalDate start = d.get("start_date") == null ? null
                : ((java.sql.Date) d.get("start_date")).toLocalDate();

        // La indemnización se calcula con gross+fechas del contrato (loadData es
        // por rango de fecha → funciona también con el contrato ya TERMINATED).
        TerminationService.Severance sev = terminationService.computeSeverance(
                type, (BigDecimal) d.get("gross_salary"), start, ceseDate);
        BigDecimal sevGross = sev.gross() == null ? BigDecimal.ZERO : sev.gross();

        java.util.List<String[]> devengos = new java.util.ArrayList<>();
        BigDecimal settGross, ss, irpf, otras;

        // 1) Post-baja: el contrato está TERMINATED y NO se puede recalcular
        //    (PayslipService exige contrato ACTIVE/SUSPENDED). Cargamos el
        //    finiquito que execute() YA guardó (payslips type=SETTLEMENT).
        Map<String, Object> saved = loadSavedSettlement(employeeId, ceseDate);
        if (saved != null) {
            settGross = nzAmt((BigDecimal) saved.get("gross_amount"));
            ss   = nzAmt((BigDecimal) saved.get("ss_employee_amount"));
            irpf = nzAmt((BigDecimal) saved.get("irpf_amount"));
            otras = nzAmt((BigDecimal) saved.get("other_deductions"));
            for (Map<String, Object> ln : loadSettlementLines(str(saved.get("id")))) {
                BigDecimal amt = (BigDecimal) ln.get("amount");
                if (amt != null && amt.signum() != 0
                        && !"DEDUCTION".equalsIgnoreCase(str(ln.get("kind")))) {
                    devengos.add(new String[]{str(ln.get("concept_name")), money(amt)});
                }
            }
        } else {
            // 2) Pre-baja (contrato activo): recálculo en vivo.
            PayslipService.PreviewResult s = terminationService.preview(
                    new TerminationService.TerminationRequest(employeeId, ceseDate, type,
                            extrasAccrual, otherDeductions, null)).settlement();
            settGross = nzAmt(s.gross()); ss = nzAmt(s.ssEmployee());
            irpf = nzAmt(s.irpf()); otras = nzAmt(s.otherDeductions());
            if (s.devengos() != null) {
                for (PayslipService.LineView lv : s.devengos()) {
                    if (lv.amount() != null && lv.amount().signum() != 0) {
                        devengos.add(new String[]{lv.concept(), money(lv.amount())});
                    }
                }
            }
        }
        if (sevGross.signum() > 0) devengos.add(new String[]{"INDEM. FIN CONTRATO", money(sevGross)});
        BigDecimal totalDevengado = settGross.add(sevGross);

        // DESCUENTOS.
        java.util.List<String[]> descuentos = new java.util.ArrayList<>();
        if (ss.signum() > 0)    descuentos.add(new String[]{"Aportación Seguridad Social", money(ss)});
        if (irpf.signum() > 0)  descuentos.add(new String[]{"Retención IRPF", money(irpf)});
        if (otras.signum() > 0) descuentos.add(new String[]{"Otras deducciones", money(otras)});
        BigDecimal totalDeducir = ss.add(irpf).add(otras);
        BigDecimal liquido = totalDevengado.subtract(totalDeducir);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 50, 50);
        Font fH = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14, new Color(30, 60, 110));
        Font fB = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
        Font fBold = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
        Font fSec = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, new Color(30, 60, 110));
        try {
            com.lowagie.text.pdf.PdfWriter.getInstance(doc, out);
            doc.open();
            doc.add(p("FINIQUITO", fH, Element.ALIGN_CENTER, 14));
            // Empresa.
            doc.add(p("Empresa: " + co.legalName() + "   ·   C.I.F./N.I.F.: " + nz(co.taxIdentifier()),
                    fBold, Element.ALIGN_LEFT, 1));
            doc.add(p("Domicilio: " + safeJoin(co.addressLine(), co.city(), co.postalCode(), co.province()),
                    fB, Element.ALIGN_LEFT, 12));
            // Párrafo de cabecera.
            String alta = start == null ? "-" : start.format(DF);
            doc.add(p("D./Dña. " + str(d.get("full_name")) + " con N.I.F. " + nz(str(d.get("tax_identifier")))
                    + ", dado/a de alta en esta empresa con fecha " + alta
                    + ", causa baja en la misma con fecha " + ceseDate.format(DF)
                    + ", en la que queda rescindido su contrato por el motivo de "
                    + reasonLabel(type).toLowerCase()
                    + ", y declara percibir en este momento la cantidad de " + money(liquido)
                    + " € por todos los conceptos que se detallan a continuación:",
                    fB, Element.ALIGN_JUSTIFIED, 12));

            // Tabla DEVENGOS.
            doc.add(p("DEVENGOS", fSec, Element.ALIGN_LEFT, 2));
            doc.add(amountTable(devengos, "TOTAL DEVENGADO", money(totalDevengado), fB, fBold));
            // Tabla DESCUENTOS.
            doc.add(p("DESCUENTOS", fSec, Element.ALIGN_LEFT, 2));
            if (descuentos.isEmpty()) descuentos.add(new String[]{"—", money(BigDecimal.ZERO)});
            doc.add(amountTable(descuentos, "TOTAL A DEDUCIR", money(totalDeducir), fB, fBold));

            doc.add(p("IMPORTE LÍQUIDO (DEVENGOS - DESCUENTOS): " + money(liquido) + " €",
                    fBold, Element.ALIGN_LEFT, 14));

            // Declaración legal (fiel a CONTENDO).
            doc.add(p("Con el percibo de dicha cantidad declara hallarse completamente saldado y "
                    + "finiquitado por todos y cuantos devengos salariales le pudieran corresponder por "
                    + "razón de trabajo por cuenta de la mencionada empresa, no teniendo más que pedir ni "
                    + "reclamar por concepto salarial alguno, hasta el día de la fecha en que causó baja de "
                    + "la misma, quedando totalmente rescindidas sus relaciones laborales que lo unían con "
                    + "la empresa.", fB, Element.ALIGN_JUSTIFIED, 8));
            doc.add(p("Se pone en conocimiento el derecho que le asiste a solicitar la presencia de un "
                    + "representante legal de los trabajadores en el acto de la firma del recibo de "
                    + "finiquito.", fB, Element.ALIGN_JUSTIFIED, 16));
            doc.add(p("Lo que se firma en " + place(co) + ", a " + ceseDate.format(DF) + ".",
                    fB, Element.ALIGN_LEFT, 36));
            doc.add(p("Firma y sello Empresa            Firma del trabajador/a            "
                    + "Representante Trabajadores", fB, Element.ALIGN_LEFT, 0));
            doc.close();
        } catch (Exception ex) {
            throw new RuntimeException("Error generando el recibo de finiquito", ex);
        }
        return out.toByteArray();
    }

    /** Tabla de 2 columnas (concepto | importe) con fila de total. */
    private com.lowagie.text.pdf.PdfPTable amountTable(java.util.List<String[]> rows,
                                                       String totalLabel, String totalAmount,
                                                       Font fB, Font fBold) {
        com.lowagie.text.pdf.PdfPTable t = new com.lowagie.text.pdf.PdfPTable(2);
        t.setWidthPercentage(100);
        try { t.setWidths(new float[]{75, 25}); } catch (Exception ignored) { }
        t.setSpacingAfter(10);
        for (String[] r : rows) {
            t.addCell(cell(r[0], fB, Element.ALIGN_LEFT));
            t.addCell(cell(r[1] + " €", fB, Element.ALIGN_RIGHT));
        }
        t.addCell(cell(totalLabel, fBold, Element.ALIGN_LEFT));
        t.addCell(cell(totalAmount + " €", fBold, Element.ALIGN_RIGHT));
        return t;
    }

    private com.lowagie.text.pdf.PdfPCell cell(String text, Font f, int align) {
        com.lowagie.text.pdf.PdfPCell c = new com.lowagie.text.pdf.PdfPCell(new com.lowagie.text.Phrase(text, f));
        c.setHorizontalAlignment(align);
        c.setBorder(com.lowagie.text.Rectangle.BOTTOM);
        c.setBorderColor(new Color(220, 220, 220));
        c.setPadding(4);
        return c;
    }

    private static BigDecimal nzAmt(BigDecimal v) { return v == null ? BigDecimal.ZERO : v; }

    /** Finiquito YA guardado (payslips type=SETTLEMENT) del periodo del cese, o null. */
    private Map<String, Object> loadSavedSettlement(String employeeId, LocalDate cese) {
        java.util.List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT id, gross_amount, ss_employee_amount, irpf_amount, other_deductions, net_amount
                  FROM payslips
                 WHERE company_id = ? AND employee_id = ?
                   AND period_year = ? AND period_month = ?
                   AND payslip_type = 'SETTLEMENT'
                 ORDER BY created_at DESC LIMIT 1
                """, tenant.getCurrentCompanyId(), employeeId, cese.getYear(), cese.getMonthValue());
        return rows.isEmpty() ? null : rows.get(0);
    }

    private java.util.List<Map<String, Object>> loadSettlementLines(String payslipId) {
        return jdbc.queryForList("""
                SELECT concept_name, kind, amount FROM payslip_lines
                 WHERE payslip_id = ? AND company_id = ?
                 ORDER BY sort_order, created_at
                """, payslipId, tenant.getCurrentCompanyId());
    }

    private String letterTitle(String type) {
        return switch (type == null ? "" : type) {
            case "DISMISSAL_DISCIPLINARY" -> "CARTA DE DESPIDO DISCIPLINARIO";
            case "DISMISSAL_OBJECTIVE" -> "CARTA DE DESPIDO POR CAUSAS OBJETIVAS";
            case "DISMISSAL_UNFAIR" -> "COMUNICACIÓN DE EXTINCIÓN (DESPIDO)";
            case "END_OF_CONTRACT" -> "COMUNICACIÓN DE FIN DE CONTRATO";
            case "VOLUNTARY" -> "ACUSE DE BAJA VOLUNTARIA";
            case "RETIREMENT" -> "COMUNICACIÓN DE BAJA POR JUBILACIÓN";
            default -> "COMUNICACIÓN DE EXTINCIÓN DE LA RELACIÓN LABORAL";
        };
    }

    private java.util.List<String> letterBody(String type, LocalDate cese, TerminationService.Severance sev) {
        String efectos = "con efectos del día " + cese.format(DF);
        String indem = sev.gross() != null && sev.gross().signum() > 0
                ? "Le corresponde una indemnización de " + money(sev.gross()) + " € ("
                  + sev.days().stripTrailingZeros().toPlainString() + " días de salario por "
                  + antiquityText(sev) + " de antigüedad)."
                : "";
        java.util.List<String> b = new java.util.ArrayList<>();
        switch (type == null ? "" : type) {
            case "DISMISSAL_DISCIPLINARY" -> {
                b.add("Por medio de la presente le comunicamos la extinción de su contrato de trabajo "
                        + efectos + ", por despido disciplinario al amparo del artículo 54 del Estatuto "
                        + "de los Trabajadores, por los hechos que se le han imputado.");
                b.add("Este despido no conlleva derecho a indemnización. Tiene a su disposición la "
                        + "liquidación de las cantidades adeudadas (finiquito).");
            }
            case "DISMISSAL_OBJECTIVE" -> {
                b.add("Por medio de la presente le comunicamos la extinción de su contrato de trabajo "
                        + efectos + ", por causas objetivas al amparo del artículo 52 del Estatuto de "
                        + "los Trabajadores.");
                b.add(indem + " Junto con esta comunicación se pone a su disposición dicha indemnización "
                        + "y la liquidación (finiquito).");
            }
            case "DISMISSAL_UNFAIR" -> {
                b.add("Por medio de la presente le comunicamos la extinción de su contrato de trabajo "
                        + efectos + ".");
                b.add(indem + " Se pone a su disposición la indemnización y la liquidación (finiquito).");
            }
            case "END_OF_CONTRACT" -> {
                b.add("Le comunicamos que su contrato de trabajo de carácter temporal finaliza "
                        + efectos + ", por llegada de su término.");
                b.add(indem + " Se pone a su disposición la liquidación correspondiente (finiquito).");
            }
            case "VOLUNTARY" -> {
                b.add("Acusamos recibo de su comunicación de baja voluntaria en la empresa "
                        + efectos + ".");
                b.add("Se pone a su disposición la liquidación de las cantidades adeudadas (finiquito).");
            }
            case "RETIREMENT" -> {
                b.add("Le comunicamos la extinción de su contrato de trabajo por jubilación "
                        + efectos + ".");
                b.add("Se pone a su disposición la liquidación correspondiente (finiquito).");
            }
            default -> b.add("Por medio de la presente le comunicamos la extinción de su contrato de "
                    + "trabajo " + efectos + ".");
        }
        return b;
    }

    /** Antigüedad legible: "X años, Y meses y Z días" (omite las partes a 0). */
    static String antiquityText(TerminationService.Severance sev) {
        java.util.List<String> parts = new java.util.ArrayList<>();
        if (sev.antiqYears() > 0) parts.add(sev.antiqYears() + (sev.antiqYears() == 1 ? " año" : " años"));
        if (sev.antiqMonths() > 0) parts.add(sev.antiqMonths() + (sev.antiqMonths() == 1 ? " mes" : " meses"));
        if (sev.antiqDays() > 0) parts.add(sev.antiqDays() + (sev.antiqDays() == 1 ? " día" : " días"));
        if (parts.isEmpty()) return "0 días";
        if (parts.size() == 1) return parts.get(0);
        return String.join(", ", parts.subList(0, parts.size() - 1)) + " y " + parts.get(parts.size() - 1);
    }

    private String reasonLabel(String type) {
        return switch (type == null ? "" : type) {
            case "DISMISSAL_DISCIPLINARY" -> "Despido disciplinario (art. 54 ET)";
            case "DISMISSAL_OBJECTIVE" -> "Despido por causas objetivas (art. 52 ET)";
            case "DISMISSAL_UNFAIR" -> "Despido";
            case "END_OF_CONTRACT" -> "Fin de contrato temporal";
            case "VOLUNTARY" -> "Baja voluntaria";
            case "RETIREMENT" -> "Jubilación";
            default -> "Extinción de la relación laboral";
        };
    }

    private Paragraph p(String text, Font f, int align, float spacingAfter) {
        Paragraph par = new Paragraph(text, f);
        par.setAlignment(align);
        par.setSpacingAfter(spacingAfter);
        return par;
    }

    private String place(CompanyDataResponse co) {
        return co.city() != null && !co.city().isBlank() ? co.city() : "—";
    }

    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String nz(String s) { return s == null || s.isBlank() ? "—" : s; }
    private static String money(BigDecimal v) {
        if (v == null) return "0,00";
        return v.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace(".", ",");
    }
    private static String safeJoin(String... parts) {
        StringBuilder sb = new StringBuilder();
        for (String x : parts) if (x != null && !x.isBlank()) { if (sb.length() > 0) sb.append(", "); sb.append(x); }
        return sb.length() == 0 ? "—" : sb.toString();
    }

    @RestController
    @RequestMapping("/api/labor/terminations/docs")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT"})
    public static class Controller {
        private final TerminationDocsService service;

        public Controller(TerminationDocsService service) { this.service = service; }

        @GetMapping("/dismissal-letter")
        public ResponseEntity<byte[]> dismissalLetter(@RequestParam("employeeId") String employeeId,
                                                       @RequestParam("date") String date,
                                                       @RequestParam("type") String type) {
            byte[] pdf = service.dismissalLetter(employeeId, LocalDate.parse(date), type);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", "inline; filename=\"carta-despido.pdf\"").body(pdf);
        }

        @GetMapping("/company-certificate")
        public ResponseEntity<byte[]> companyCertificate(@RequestParam("employeeId") String employeeId,
                                                          @RequestParam("date") String date,
                                                          @RequestParam("type") String type) {
            byte[] pdf = service.companyCertificate(employeeId, LocalDate.parse(date), type);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", "inline; filename=\"certificado-empresa.pdf\"").body(pdf);
        }

        /** Recibo / carta de finiquito (documento legal que firma el trabajador). */
        @GetMapping("/settlement-receipt")
        public ResponseEntity<byte[]> settlementReceipt(
                @RequestParam("employeeId") String employeeId,
                @RequestParam("date") String date,
                @RequestParam("type") String type,
                @RequestParam(value = "extrasAccrual", required = false) String extrasAccrual,
                @RequestParam(value = "otherDeductions", required = false) java.math.BigDecimal otherDeductions) {
            byte[] pdf = service.settlementReceipt(employeeId, LocalDate.parse(date), type,
                    extrasAccrual, otherDeductions);
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
                    .header("Content-Disposition", "inline; filename=\"recibo-finiquito.pdf\"").body(pdf);
        }
    }
}
