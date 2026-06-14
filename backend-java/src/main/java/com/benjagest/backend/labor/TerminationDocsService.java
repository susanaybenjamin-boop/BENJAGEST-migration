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
            doc.add(p("Las bases de cotización de los últimos 180 días, a efectos de la prestación "
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
                  + sev.antiquityYears().toPlainString() + " años de antigüedad)."
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
    }
}
