package com.benjagest.backend.labor.contracts;

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
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CTR-4 — Generador del PDF firmable del contrato.
 *
 * <p>Soporta dos modelos:
 * <ul>
 *   <li>{@code UNIFIED_2022}: modelo único post-Reforma Laboral 2022
 *       (RD-Ley 32/2021). Estructura común para todos los códigos SEPE.
 *       Es el modelo "oficial" que el SEPE acepta desde 30-mar-2022.</li>
 *   <li>{@code BY_CODE}: modelo unificado + sección extra "Modalidad
 *       específica" con el texto literal del código SEPE elegido.
 *       Útil para asesores que prefieren ver el clausulado explícito
 *       de cada modalidad (al estilo de los antiguos modelos por código).</li>
 * </ul>
 *
 * <p>El PDF incluye:
 * <ol>
 *   <li>Encabezado oficial: "CONTRATO DE TRABAJO" + código SEPE + modalidad</li>
 *   <li>Datos de la empresa: razón social, NIF, domicilio, CCC SS</li>
 *   <li>Datos del trabajador: nombre, NIF/NIE, fecha nac., domicilio, NUSS</li>
 *   <li>Centro de trabajo</li>
 *   <li>Cláusulas legales mínimas (modalidad, duración, jornada, salario,
 *       periodo de prueba, convenio, categoría, vacaciones)</li>
 *   <li>Anexos vinculados del catálogo (placeholders sustituidos)</li>
 *   <li>Cláusulas libres redactadas por el OWNER</li>
 *   <li>Espacio firmas: empresa + trabajador, con fecha y lugar</li>
 * </ol>
 *
 * <p>Base legal:
 * <ul>
 *   <li>Estatuto de los Trabajadores (RDLeg 2/2015) art. 8 (forma escrita)</li>
 *   <li>RD 1659/1998 (información sobre elementos esenciales)</li>
 *   <li>Ley 10/2021 (teletrabajo) — usado en anexos</li>
 *   <li>Reforma Laboral 2022 (RD-Ley 32/2021) — modalidades temporales</li>
 * </ul>
 */
@Service
public class ContractPdfGenerator {

    private static final DateTimeFormatter ES_DATE =
            DateTimeFormatter.ofPattern("d 'de' MMMM 'de' yyyy", new Locale("es", "ES"));

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CompanyDataService companyData;

    public ContractPdfGenerator(JdbcTemplate jdbc,
                                TenantContext tenant,
                                CompanyDataService companyData) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.companyData = companyData;
    }

    public byte[] generate(String contractId, String modelOverride) {
        Contract c = loadContract(contractId);
        CompanyDataResponse cd = companyData.getCurrent();
        String model = modelOverride != null ? modelOverride :
                (c.pdfModel != null ? c.pdfModel : "UNIFIED_2022");

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4, 50, 50, 40, 40);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            renderHeader(doc, c, model);
            renderParties(doc, c, cd);
            renderEssentialClauses(doc, c, model);
            renderLinkedAnnexes(doc, c, cd);
            renderFreeClauses(doc, c);
            renderSignatures(doc, c, cd);

            doc.close();
        } catch (DocumentException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error generando PDF del contrato", e);
        }
        return out.toByteArray();
    }

    // ===== Render sections ==================================================

    private void renderHeader(Document doc, Contract c, String model) throws DocumentException {
        Font titleFont = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 16, Color.BLACK);
        Paragraph title = new Paragraph("CONTRATO DE TRABAJO", titleFont);
        title.setAlignment(Element.ALIGN_CENTER);
        title.setSpacingAfter(6);
        doc.add(title);

        Font subFont = FontFactory.getFont(FontFactory.HELVETICA, 11, Color.DARK_GRAY);
        String subtitle = (c.sepeContractCode == null ? "" : "Clave SEPE " + c.sepeContractCode + " · ")
                + (c.sepeDescription == null ? safeContractType(c.contractType) : c.sepeDescription);
        Paragraph sub = new Paragraph(subtitle, subFont);
        sub.setAlignment(Element.ALIGN_CENTER);
        sub.setSpacingAfter(2);
        doc.add(sub);

        Font modelFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.GRAY);
        Paragraph m = new Paragraph(
                "UNIFIED_2022".equals(model)
                        ? "Modelo unificado SEPE — Reforma Laboral 2022 (RD-Ley 32/2021)"
                        : "Modelo por código SEPE con clausulado específico",
                modelFont);
        m.setAlignment(Element.ALIGN_CENTER);
        m.setSpacingAfter(14);
        doc.add(m);
    }

    private void renderParties(Document doc, Contract c, CompanyDataResponse cd) throws DocumentException {
        Font lf = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
        Font bf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);

        // En el lugar/fecha de hoy ─────────────────────────────────────────
        String place = nz(cd.city(), nz(cd.addressLine(), "____________"));
        String date = LocalDate.now().format(ES_DATE);
        Paragraph intro = new Paragraph();
        intro.setFont(lf);
        intro.add(new Phrase("En "));
        intro.add(new Phrase(place, bf));
        intro.add(new Phrase(", a "));
        intro.add(new Phrase(date, bf));
        intro.add(new Phrase("."));
        intro.setSpacingAfter(12);
        doc.add(intro);

        // REUNIDOS ─────────────────────────────────────────────────────────
        section(doc, "REUNIDOS");

        Paragraph p1 = new Paragraph();
        p1.setFont(lf);
        p1.add(new Phrase("DE UNA PARTE, ", bf));
        p1.add(new Phrase("la empresa "));
        p1.add(new Phrase(nz(cd.legalName(), "________"), bf));
        p1.add(new Phrase(", con NIF "));
        p1.add(new Phrase(nz(cd.taxIdentifier(), "________"), bf));
        p1.add(new Phrase(", domicilio social en "));
        p1.add(new Phrase(nz(cd.addressLine(), "________"), bf));
        p1.add(new Phrase(", "));
        p1.add(new Phrase(nz(cd.postalCode(), ""), bf));
        p1.add(new Phrase(" "));
        p1.add(new Phrase(nz(cd.city(), ""), bf));
        p1.add(new Phrase("; en adelante, “la EMPRESA”."));
        p1.setSpacingAfter(8);
        doc.add(p1);

        Paragraph p2 = new Paragraph();
        p2.setFont(lf);
        p2.add(new Phrase("DE OTRA PARTE, ", bf));
        p2.add(new Phrase(nz(c.employeeName, "________"), bf));
        p2.add(new Phrase(", con "));
        p2.add(new Phrase(c.employeeIdType == null ? "DNI/NIE" : c.employeeIdType, lf));
        p2.add(new Phrase(" "));
        p2.add(new Phrase(nz(c.employeeNif, "________"), bf));
        p2.add(new Phrase(", domiciliado/a en "));
        p2.add(new Phrase(nz(c.employeeAddress, "________"), bf));
        if (c.employeeBirthDate != null) {
            p2.add(new Phrase(", nacido/a el "));
            p2.add(new Phrase(c.employeeBirthDate.format(ES_DATE), bf));
        }
        p2.add(new Phrase("; en adelante, “el/la TRABAJADOR/A”."));
        p2.setSpacingAfter(12);
        doc.add(p2);

        section(doc, "MANIFIESTAN");
        Paragraph m = new Paragraph(
                "Que ambas partes se reconocen mutuamente capacidad legal para suscribir el "
                        + "presente CONTRATO DE TRABAJO, conforme a lo dispuesto en el Estatuto "
                        + "de los Trabajadores (RDLeg 2/2015) y normativa concordante, y a tal "
                        + "efecto, libremente y de común acuerdo, ACUERDAN las siguientes\n\n",
                lf);
        m.setSpacingAfter(6);
        doc.add(m);
    }

    private void renderEssentialClauses(Document doc, Contract c, String model) throws DocumentException {
        Font lf = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
        Font bf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);

        section(doc, "CLÁUSULAS");

        clause(doc, "PRIMERA — Modalidad del contrato",
                "El/La trabajador/a prestará servicios bajo la modalidad "
                        + bold(c.sepeDescription != null ? c.sepeDescription : safeContractType(c.contractType))
                        + " (clave SEPE "
                        + bold(nz(c.sepeContractCode, "—"))
                        + "), conforme a la legislación vigente y, en especial, al Estatuto de los Trabajadores.");

        String duration;
        if (c.endDate == null) {
            duration = "El presente contrato tiene carácter INDEFINIDO, comenzando sus efectos el día "
                    + bold(c.startDate.format(ES_DATE)) + ".";
        } else {
            duration = "El presente contrato tiene carácter TEMPORAL, con efectos desde el día "
                    + bold(c.startDate.format(ES_DATE))
                    + " hasta el día " + bold(c.endDate.format(ES_DATE)) + ".";
        }
        clause(doc, "SEGUNDA — Duración", duration);

        String hours = c.weeklyHours == null ? "40,00" : c.weeklyHours.toPlainString().replace('.', ',');
        clause(doc, "TERCERA — Jornada",
                "La jornada de trabajo será de " + bold(hours)
                        + " horas semanales, distribuidas según las necesidades organizativas "
                        + "de la EMPRESA y respetando los descansos previstos en el ET arts. 34 y 37, "
                        + "así como el convenio colectivo aplicable.");

        String salary = c.grossSalary == null ? "—" :
                c.grossSalary.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',');
        Integer bonuses = c.annualBonuses == null ? 14 : c.annualBonuses;
        clause(doc, "CUARTA — Retribución",
                "El/La trabajador/a percibirá un salario bruto anual de "
                        + bold(salary + " €")
                        + ", distribuido en " + bold(bonuses + " pagas") + " conforme al convenio colectivo "
                        + "aplicable. La estructura salarial (salario base, complementos y, en su caso, "
                        + "pagas extraordinarias) se ajustará a las tablas del convenio.");

        Integer prob = c.probationDays;
        if (prob != null && prob > 0) {
            clause(doc, "QUINTA — Periodo de prueba",
                    "Se pacta un periodo de prueba de " + bold(prob + " días naturales") + " conforme al "
                            + "art. 14 del Estatuto de los Trabajadores y al convenio colectivo aplicable. "
                            + "Durante el periodo de prueba cualquiera de las partes podrá rescindir el "
                            + "contrato sin necesidad de preaviso ni indemnización.");
        }

        Integer vac = c.vacationDays == null ? 30 : c.vacationDays;
        clause(doc, "SEXTA — Vacaciones",
                "El/La trabajador/a tendrá derecho a " + bold(vac + " días naturales") + " de "
                        + "vacaciones anuales retribuidas, conforme al art. 38 del Estatuto de los "
                        + "Trabajadores y al convenio colectivo aplicable.");

        String agreement = c.collectiveAgreement == null ?
                "el convenio colectivo aplicable según la actividad de la EMPRESA" : c.collectiveAgreement;
        String category = c.professionalCategory == null ? "—" : c.professionalCategory;
        clause(doc, "SÉPTIMA — Convenio colectivo y categoría",
                "Las partes se someten a " + bold(agreement)
                        + ". El/La trabajador/a queda encuadrado/a en la categoría profesional de "
                        + bold(category) + ".");

        String workplace = c.workplaceAddress == null ? nz(c.companyAddress, "el centro de trabajo de la EMPRESA")
                : c.workplaceAddress;
        clause(doc, "OCTAVA — Centro de trabajo",
                "La prestación de servicios se realizará en " + bold(workplace) + ".");

        // BY_CODE: añadimos la modalidad SEPE literal como cláusula extra
        if ("BY_CODE".equals(model) && c.sepeDescription != null) {
            clause(doc, "NOVENA — Cláusula específica de la modalidad",
                    "El contrato se rige adicionalmente por las particularidades propias de la "
                            + "modalidad indicada en la cláusula primera: \""
                            + bold(c.sepeDescription)
                            + "\", conforme a su base legal: "
                            + (c.sepeLegalBasis == null ? "normativa SEPE vigente" : c.sepeLegalBasis)
                            + ".");
        }

        Font footFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.GRAY);
        Paragraph foot = new Paragraph(
                "Las cláusulas anteriores constituyen los elementos esenciales del contrato a efectos "
                        + "del art. 8.5 ET y del RD 1659/1998 (información al trabajador).",
                footFont);
        foot.setSpacingBefore(6);
        foot.setSpacingAfter(10);
        doc.add(foot);
    }

    private void renderLinkedAnnexes(Document doc, Contract c, CompanyDataResponse cd) throws DocumentException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT cl.sort_order, ct.title, ct.body, ct.legal_basis, ct.category
                  FROM contract_clause_links cl
                  JOIN contract_clause_templates ct ON ct.id = cl.clause_template_id
                 WHERE cl.contract_id = ?
                 ORDER BY cl.sort_order, ct.title
                """, c.id);
        if (rows.isEmpty()) return;

        section(doc, "ANEXOS");
        Map<String, String> ph = placeholders(c, cd);
        int idx = 1;
        for (Map<String, Object> row : rows) {
            String title = "ANEXO " + romanNum(idx++) + " — " + (String) row.get("title");
            String body = substitute((String) row.get("body"), ph);
            clause(doc, title, body);
            Object legal = row.get("legal_basis");
            if (legal != null) {
                Font sf = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 8, Color.GRAY);
                Paragraph p = new Paragraph("Base legal: " + legal, sf);
                p.setSpacingAfter(8);
                doc.add(p);
            }
        }
    }

    private void renderFreeClauses(Document doc, Contract c) throws DocumentException {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT title, body, sort_order FROM contract_free_clauses
                 WHERE contract_id = ? ORDER BY sort_order, title
                """, c.id);
        if (rows.isEmpty()) return;

        section(doc, "CLÁUSULAS ADICIONALES");
        for (Map<String, Object> row : rows) {
            clause(doc, (String) row.get("title"), (String) row.get("body"));
        }
    }

    private void renderSignatures(Document doc, Contract c, CompanyDataResponse cd) throws DocumentException {
        Font lf = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);
        Font bf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);

        Paragraph signed = new Paragraph(
                "Y en prueba de conformidad, ambas partes firman el presente contrato por "
                        + "duplicado y a un solo efecto, en el lugar y fecha indicados en el "
                        + "encabezamiento.\n\n",
                lf);
        signed.setSpacingBefore(20);
        signed.setSpacingAfter(30);
        doc.add(signed);

        PdfPTable signs = new PdfPTable(2);
        signs.setWidthPercentage(100);
        try { signs.setWidths(new int[]{1, 1}); } catch (DocumentException ignore) {}

        signs.addCell(signCell("LA EMPRESA", nz(cd.legalName(), ""), nz(cd.taxIdentifier(), ""), bf, lf));
        signs.addCell(signCell("EL/LA TRABAJADOR/A", nz(c.employeeName, ""), nz(c.employeeNif, ""), bf, lf));
        doc.add(signs);

        Font footFont = FontFactory.getFont(FontFactory.HELVETICA_OBLIQUE, 9, Color.GRAY);
        Paragraph footer = new Paragraph(
                "\nLa empresa entrega copia básica del contrato a la representación legal de los "
                        + "trabajadores conforme al art. 8.4 ET. El presente documento se comunicará "
                        + "al Servicio Público de Empleo Estatal (SEPE) en el plazo legal mediante "
                        + "la aplicación Contrat@.",
                footFont);
        footer.setSpacingBefore(20);
        doc.add(footer);
    }

    // ===== Helpers ==========================================================

    private void section(Document doc, String text) throws DocumentException {
        Font f = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 11, Color.BLACK);
        Paragraph p = new Paragraph(text, f);
        p.setSpacingBefore(8);
        p.setSpacingAfter(6);
        doc.add(p);
    }

    private void clause(Document doc, String title, String body) throws DocumentException {
        Font tf = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 10, Color.BLACK);
        Font lf = FontFactory.getFont(FontFactory.HELVETICA, 10, Color.BLACK);

        Paragraph t = new Paragraph(title, tf);
        t.setSpacingBefore(6);
        t.setSpacingAfter(2);
        doc.add(t);

        // Negrita inline mediante marcador __B[texto]__ que insertamos en bold().
        for (Paragraph p : parseInlineBold(body, lf, tf)) {
            doc.add(p);
        }
    }

    /** Marker simple para negrita inline: __B[texto en bold]__. */
    private static String bold(String text) { return "__B[" + text + "]__"; }

    /** Convierte un string con marcadores __B[..]__ en párrafos con tramos en bold. */
    private List<Paragraph> parseInlineBold(String s, Font normal, Font bold) {
        List<Paragraph> result = new java.util.ArrayList<>();
        for (String line : s.split("\n")) {
            Paragraph p = new Paragraph();
            p.setSpacingAfter(4);
            int i = 0;
            while (i < line.length()) {
                int start = line.indexOf("__B[", i);
                if (start < 0) {
                    p.add(new Phrase(line.substring(i), normal));
                    break;
                }
                if (start > i) p.add(new Phrase(line.substring(i, start), normal));
                int end = line.indexOf("]__", start);
                if (end < 0) {
                    p.add(new Phrase(line.substring(start), normal));
                    break;
                }
                p.add(new Phrase(line.substring(start + 4, end), bold));
                i = end + 3;
            }
            result.add(p);
        }
        return result;
    }

    private PdfPCell signCell(String role, String name, String nif, Font bold, Font normal) {
        PdfPCell cell = new PdfPCell();
        cell.setBorder(Rectangle.TOP);
        cell.setBorderColorTop(Color.BLACK);
        cell.setPaddingTop(8);
        cell.setPaddingBottom(20);

        Paragraph p1 = new Paragraph(role, bold);
        p1.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p1);

        Paragraph p2 = new Paragraph(name, normal);
        p2.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p2);

        if (nif != null && !nif.isBlank()) {
            Paragraph p3 = new Paragraph("NIF " + nif, normal);
            p3.setAlignment(Element.ALIGN_CENTER);
            cell.addElement(p3);
        }

        Paragraph p4 = new Paragraph("\n\nFdo.", normal);
        p4.setAlignment(Element.ALIGN_CENTER);
        cell.addElement(p4);
        return cell;
    }

    private Map<String, String> placeholders(Contract c, CompanyDataResponse cd) {
        Map<String, String> m = new HashMap<>();
        m.put("{empleado}", nz(c.employeeName, "________"));
        m.put("{empresa}", nz(cd.legalName(), "________"));
        m.put("{nif_empresa}", nz(cd.taxIdentifier(), "________"));
        m.put("{salario_bruto_anual}", c.grossSalary == null ? "—" :
                c.grossSalary.setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ','));
        m.put("{fecha_inicio}", c.startDate == null ? "—" : c.startDate.format(ES_DATE));
        m.put("{duracion_meses}", c.endDate == null ? "indefinida" :
                String.valueOf(java.time.temporal.ChronoUnit.MONTHS.between(c.startDate, c.endDate)));
        // Las demás (compensación no competencia, etc.) las dejamos sin
        // sustituir para que el OWNER las complete a mano antes de firmar.
        return m;
    }

    private String substitute(String body, Map<String, String> ph) {
        if (body == null) return "";
        String out = body;
        for (Map.Entry<String, String> e : ph.entrySet()) {
            out = out.replace(e.getKey(), e.getValue());
        }
        return out;
    }

    private String safeContractType(String t) {
        if (t == null) return "Indefinido";
        return switch (t) {
            case "INDEFINIDO" -> "Indefinido";
            case "TEMPORAL" -> "Temporal";
            case "FORMATIVO" -> "Formativo en alternancia";
            case "PRACTICAS" -> "Práctica profesional";
            default -> t;
        };
    }

    private String romanNum(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV";
            case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII";
            case 9 -> "IX"; case 10 -> "X";
            default -> String.valueOf(n);
        };
    }

    private static String nz(String v, String d) {
        return v == null || v.isBlank() ? d : v;
    }

    // ===== Loader ============================================================

    private Contract loadContract(String contractId) {
        return jdbc.query("""
                SELECT c.id, c.contract_type, c.sepe_contract_code,
                       c.collective_agreement, c.professional_category, c.professional_group,
                       c.start_date, c.end_date, c.weekly_hours, c.gross_salary,
                       c.annual_bonuses, c.vacation_days, c.probation_days,
                       c.workplace_address, c.workplace_city, c.workplace_province,
                       c.workplace_postal_code, c.pdf_model,
                       e.full_name AS employee_name, e.tax_identifier AS employee_nif,
                       e.address_line AS employee_address, e.birth_date AS employee_birth,
                       s.description AS sepe_description, s.legal_basis AS sepe_legal_basis
                  FROM employment_contracts c
             LEFT JOIN employees e ON e.id = c.employee_id
             LEFT JOIN sepe_contract_types s ON s.code = c.sepe_contract_code
                 WHERE c.id = ? AND c.company_id = ?
                """, this::mapContract, contractId, tenant.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado"));
    }

    private Contract mapContract(ResultSet rs, int n) throws SQLException {
        Contract c = new Contract();
        c.id = rs.getString("id");
        c.contractType = rs.getString("contract_type");
        c.sepeContractCode = rs.getString("sepe_contract_code");
        c.collectiveAgreement = rs.getString("collective_agreement");
        c.professionalCategory = rs.getString("professional_category");
        c.professionalGroup = rs.getString("professional_group");
        java.sql.Date s = rs.getDate("start_date");
        c.startDate = s == null ? null : s.toLocalDate();
        java.sql.Date e = rs.getDate("end_date");
        c.endDate = e == null ? null : e.toLocalDate();
        c.weeklyHours = rs.getBigDecimal("weekly_hours");
        c.grossSalary = rs.getBigDecimal("gross_salary");
        c.annualBonuses = (Integer) rs.getObject("annual_bonuses");
        c.vacationDays = (Integer) rs.getObject("vacation_days");
        c.probationDays = (Integer) rs.getObject("probation_days");
        c.workplaceAddress = rs.getString("workplace_address");
        c.pdfModel = rs.getString("pdf_model");
        c.employeeName = rs.getString("employee_name");
        c.employeeNif = rs.getString("employee_nif");
        c.employeeAddress = rs.getString("employee_address");
        java.sql.Date b = rs.getDate("employee_birth");
        c.employeeBirthDate = b == null ? null : b.toLocalDate();
        c.sepeDescription = rs.getString("sepe_description");
        c.sepeLegalBasis = rs.getString("sepe_legal_basis");
        c.employeeIdType = "DNI/NIE";
        return c;
    }

    private static class Contract {
        String id;
        String contractType;
        String sepeContractCode;
        String collectiveAgreement;
        String professionalCategory;
        String professionalGroup;
        LocalDate startDate;
        LocalDate endDate;
        BigDecimal weeklyHours;
        BigDecimal grossSalary;
        Integer annualBonuses;
        Integer vacationDays;
        Integer probationDays;
        String workplaceAddress;
        String pdfModel;
        String employeeName;
        String employeeNif;
        String employeeIdType;
        String employeeAddress;
        LocalDate employeeBirthDate;
        String sepeDescription;
        String sepeLegalBasis;
        String companyAddress;
    }
}
