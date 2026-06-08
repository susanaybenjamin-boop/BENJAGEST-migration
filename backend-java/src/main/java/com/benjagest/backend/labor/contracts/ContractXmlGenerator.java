package com.benjagest.backend.labor.contracts;

import com.benjagest.backend.settings.CompanyDataResponse;
import com.benjagest.backend.settings.CompanyDataService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * CTR-5 — Generador del XML para la aplicación oficial Contrat@ del SEPE.
 *
 * <p>El SEPE expone una aplicación web (Contrat@) que acepta envío
 * telemático de comunicaciones de contratos en formato XML. El esquema
 * oficial es complejo y cambia con cada reforma; esta v1 emite un XML
 * sencillo con los elementos esenciales que el asesor luego puede
 * importar manualmente o adaptar al envío telemático real.
 *
 * <p>Para v1 NO firmamos digitalmente el XML (el SEPE acepta envío
 * telemático con certificado, pero la integración SOAP completa requiere
 * un slice posterior tipo VF3-SOAP). El XML generado sirve como:
 * <ul>
 *   <li>Backup de los datos del contrato en formato estructurado.</li>
 *   <li>Punto de partida para integraciones posteriores con Contrat@.</li>
 *   <li>Documento alternativo de archivo para Inspección de Trabajo.</li>
 * </ul>
 *
 * <p><b>CTR-XADES (pendiente para v2)</b> — Firma XAdES-EPES del XML
 * Contrat@. Pasos previstos:
 * <ol>
 *   <li>Reusar el motor {@code com.benjagest.backend.billing.invoice.SignXadesService}
 *       que ya firma facturas VeriFactu (XAdES-EPES sobre el XML envuelto
 *       en {@code <ds:Signature>}).</li>
 *   <li>Cargar el certificado {@code .p12} de la asesoría desde
 *       {@code stored_certificates} (CERT-IMPORT) y abrirlo con la
 *       contraseña del OWNER (igual que ya hace SIF).</li>
 *   <li>Endpoint {@code GET /api/contracts/{id}/xml?signed=true} —
 *       devuelve el XML firmado. Si {@code signed=true} y no hay
 *       certificado válido cargado, 412 PRECONDITION_FAILED con
 *       mensaje que dirige al OWNER a Configuración → Certificado.</li>
 *   <li>Persistir el XML firmado en disco (mirror del PDF flow):
 *       columna {@code xml_path} en {@code employment_contracts}
 *       (ya creada en V75 para esto) y actualizar {@code sepe_sent_at}
 *       cuando el SEPE acepte el envío telemático.</li>
 *   <li>Para envío SOAP a Contrat@ — pendiente VF3-SOAP-style:
 *       wsdl SEPE + autenticación con certificado + parser respuesta.</li>
 * </ol>
 *
 * <p>Base legal:
 * <ul>
 *   <li>Orden TES/106/2017 — Sistema RED y comunicación de contratos</li>
 *   <li>RDLeg 8/2015 LGSS — obligación de comunicar al SEPE en 10 días</li>
 *   <li>Esquema XML Contrat@ del SEPE (versión vigente)</li>
 * </ul>
 */
@Service
public class ContractXmlGenerator {

    private static final DateTimeFormatter ISO_DATE = DateTimeFormatter.ISO_LOCAL_DATE;

    private final JdbcTemplate jdbc;
    private final TenantContext tenant;
    private final CompanyDataService companyData;

    public ContractXmlGenerator(JdbcTemplate jdbc,
                                TenantContext tenant,
                                CompanyDataService companyData) {
        this.jdbc = jdbc;
        this.tenant = tenant;
        this.companyData = companyData;
    }

    public byte[] generate(String contractId) {
        Map<String, Object> c = loadContract(contractId);
        CompanyDataResponse cd = companyData.getCurrent();

        StringBuilder xml = new StringBuilder();
        xml.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<ComunicacionContrato xmlns=\"http://www.sepe.es/contrata/v1\">\n");

        // Cabecera ─────────────────────────────────────────────────────────
        xml.append("  <Cabecera>\n");
        kv(xml, "    ", "Version", "1.0");
        kv(xml, "    ", "Fecha", LocalDate.now().format(ISO_DATE));
        kv(xml, "    ", "TipoComunicacion", "ALTA");
        xml.append("  </Cabecera>\n");

        // Empresa ──────────────────────────────────────────────────────────
        xml.append("  <Empresa>\n");
        kv(xml, "    ", "RazonSocial", esc(cd.legalName()));
        kv(xml, "    ", "NIF", esc(cd.taxIdentifier()));
        kv(xml, "    ", "Domicilio", esc(cd.addressLine()));
        kv(xml, "    ", "CodigoPostal", esc(cd.postalCode()));
        kv(xml, "    ", "Localidad", esc(cd.city()));
        kv(xml, "    ", "Provincia", esc(cd.province()));
        xml.append("  </Empresa>\n");

        // Trabajador ───────────────────────────────────────────────────────
        xml.append("  <Trabajador>\n");
        kv(xml, "    ", "NombreCompleto", esc((String) c.get("employee_name")));
        kv(xml, "    ", "NIF", esc((String) c.get("employee_nif")));
        kv(xml, "    ", "FechaNacimiento", date(c.get("employee_birth")));
        kv(xml, "    ", "Domicilio", esc((String) c.get("employee_address")));
        kv(xml, "    ", "NumeroSeguridadSocial", esc((String) c.get("employee_nuss")));
        xml.append("  </Trabajador>\n");

        // Contrato ─────────────────────────────────────────────────────────
        xml.append("  <Contrato>\n");
        kv(xml, "    ", "ClaveSepe", esc((String) c.get("sepe_contract_code")));
        kv(xml, "    ", "Modalidad", esc((String) c.get("contract_type")));
        kv(xml, "    ", "DescripcionModalidad", esc((String) c.get("sepe_description")));
        kv(xml, "    ", "FechaInicio", date(c.get("start_date")));
        if (c.get("end_date") != null) {
            kv(xml, "    ", "FechaFin", date(c.get("end_date")));
            kv(xml, "    ", "DuracionDeterminada", "S");
        } else {
            kv(xml, "    ", "DuracionDeterminada", "N");
        }
        kv(xml, "    ", "JornadaSemanal", num(c.get("weekly_hours")));
        kv(xml, "    ", "SalarioBrutoAnual", num(c.get("gross_salary")));
        kv(xml, "    ", "NumeroPagas", String.valueOf(c.getOrDefault("annual_bonuses", 14)));
        kv(xml, "    ", "DiasVacaciones", String.valueOf(c.getOrDefault("vacation_days", 30)));
        if (c.get("probation_days") != null) {
            kv(xml, "    ", "DiasPeriodoPrueba", c.get("probation_days").toString());
        }
        xml.append("  </Contrato>\n");

        // Convenio ─────────────────────────────────────────────────────────
        xml.append("  <Convenio>\n");
        kv(xml, "    ", "ConvenioColectivo", esc((String) c.get("collective_agreement")));
        kv(xml, "    ", "GrupoProfesional", esc((String) c.get("professional_group")));
        kv(xml, "    ", "CategoriaProfesional", esc((String) c.get("professional_category")));
        xml.append("  </Convenio>\n");

        // CentroTrabajo ────────────────────────────────────────────────────
        xml.append("  <CentroTrabajo>\n");
        kv(xml, "    ", "Domicilio", esc((String) c.get("workplace_address")));
        kv(xml, "    ", "CodigoPostal", esc((String) c.get("workplace_postal_code")));
        kv(xml, "    ", "Localidad", esc((String) c.get("workplace_city")));
        kv(xml, "    ", "Provincia", esc((String) c.get("workplace_province")));
        xml.append("  </CentroTrabajo>\n");

        // Anexos vinculados ────────────────────────────────────────────────
        var clauses = jdbc.queryForList("""
                SELECT ct.code, ct.title, ct.category
                  FROM contract_clause_links cl
                  JOIN contract_clause_templates ct ON ct.id = cl.clause_template_id
                 WHERE cl.contract_id = ?
                 ORDER BY cl.sort_order
                """, contractId);
        if (!clauses.isEmpty()) {
            xml.append("  <Anexos>\n");
            for (var row : clauses) {
                xml.append("    <Anexo>\n");
                kv(xml, "      ", "Codigo", esc((String) row.get("code")));
                kv(xml, "      ", "Titulo", esc((String) row.get("title")));
                kv(xml, "      ", "Categoria", esc((String) row.get("category")));
                xml.append("    </Anexo>\n");
            }
            xml.append("  </Anexos>\n");
        }

        xml.append("</ComunicacionContrato>\n");
        return xml.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    // ===== Helpers ==========================================================

    private static void kv(StringBuilder sb, String indent, String tag, String value) {
        if (value == null || value.isBlank()) return;
        sb.append(indent).append('<').append(tag).append('>')
                .append(value)
                .append("</").append(tag).append(">\n");
    }

    private static String esc(String s) {
        if (s == null) return null;
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    private static String date(Object o) {
        if (o == null) return null;
        if (o instanceof java.sql.Date d) return d.toLocalDate().format(ISO_DATE);
        if (o instanceof LocalDate d) return d.format(ISO_DATE);
        return o.toString();
    }

    private static String num(Object o) {
        if (o == null) return null;
        if (o instanceof BigDecimal d) return d.setScale(2, RoundingMode.HALF_UP).toPlainString();
        return o.toString();
    }

    private Map<String, Object> loadContract(String contractId) {
        var rows = jdbc.queryForList("""
                SELECT c.contract_type, c.sepe_contract_code,
                       c.collective_agreement, c.professional_category, c.professional_group,
                       c.start_date, c.end_date, c.weekly_hours, c.gross_salary,
                       c.annual_bonuses, c.vacation_days, c.probation_days,
                       c.workplace_address, c.workplace_city, c.workplace_province,
                       c.workplace_postal_code,
                       e.full_name AS employee_name, e.tax_identifier AS employee_nif,
                       e.address_line AS employee_address, e.birth_date AS employee_birth,
                       e.social_security_number AS employee_nuss,
                       s.description AS sepe_description
                  FROM employment_contracts c
             LEFT JOIN employees e ON e.id = c.employee_id
             LEFT JOIN sepe_contract_types s ON s.code = c.sepe_contract_code
                 WHERE c.id = ? AND c.company_id = ?
                """, contractId, tenant.getCurrentCompanyId());
        if (rows.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Contrato no encontrado");
        }
        return rows.get(0);
    }
}
