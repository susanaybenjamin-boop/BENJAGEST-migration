package com.benjagest.backend.billing.verifactu;

import com.benjagest.backend.certificates.Certificate;
import com.benjagest.backend.certificates.CertificateRepository;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

/**
 * Slice VF3-SOAP — cliente HTTPS para enviar registros VeriFactu a la
 * sede electronica de la AEAT.
 *
 * <h2>Estado real (sinceridad)</h2>
 *
 * Este cliente esta IMPLEMENTADO pero NO PROBADO contra AEAT.
 * Lo que cubre el codigo:
 *
 * <ul>
 *   <li>SSL mutual: carga el .p12 de la empresa como KeyManager. El
 *       TrustManager confia en cualquier servidor (TODO: limitar a los
 *       de AEAT cuando se prueben).</li>
 *   <li>Envoltura SOAP con el envelope que la Orden HAC/1177/2024
 *       anexo IV pide (namespaces sum: y sum1: de SuministroLR y
 *       SuministroInformacion).</li>
 *   <li>POST sincrono con timeout de 30s. Respuesta como String para
 *       parseo basico de codigos de respuesta.</li>
 * </ul>
 *
 * <h2>Lo que falta para que funcione contra AEAT real</h2>
 *
 * <ul>
 *   <li>El <b>payload</b> que enviamos en {@code <sum:RegistroFactura>}
 *       no se ajusta todavia al XSD oficial de AEAT — usamos nuestro
 *       formato interno {@code RegistroFacturacion xmlns="urn:benjagest...
 *       "}. AEAT validara el XSD y rechazara. Hay que ampliar el slice
 *       VF-SIGN-XADES para que el XML canonico sea ya el AEAT-XSD, y
 *       firmarlo en ese formato.</li>
 *   <li>El <b>certificado</b> debe ser FNMT representante de la persona
 *       juridica emisora, dado de alta como Sistema Informatico de
 *       Facturacion en la sede AEAT.</li>
 *   <li>La <b>respuesta</b> hay que mapearla a los codigos exactos que
 *       AEAT devuelve (Aceptado / AceptadoConErrores / Rechazado), no
 *       simplemente 200 OK.</li>
 * </ul>
 *
 * Por ahora: el codigo compila, el SignatureRetryScheduler lo puede
 * invocar. Cuando exista FNMT + ajuste XSD, devolvera respuestas
 * reales. Hasta entonces, todos los intentos quedan en ERROR — eso es
 * lo esperado y el retry_count se incrementa hasta el limite (5).
 */
@Service
public class AeatVerifactuClient {

    private static final Logger log = LoggerFactory.getLogger(AeatVerifactuClient.class);

    /** Endpoint TEST (entorno de pruebas AEAT). */
    private static final String ENDPOINT_TEST =
            "https://prewww1.aeat.es/wlpl/TIKE-CONT/ws/RegFactuSistemaFacturacion/RegFactuSistemaFacturacionService";
    /** Endpoint PROD (envio real). */
    private static final String ENDPOINT_PROD =
            "https://www1.agenciatributaria.gob.es/wlpl/TIKE-CONT/ws/RegFactuSistemaFacturacion/RegFactuSistemaFacturacionService";

    private final VerifactuConfigRepository configRepository;
    private final CertificateRepository certificateRepository;
    private final JdbcTemplate jdbcTemplate;

    public AeatVerifactuClient(VerifactuConfigRepository configRepository,
                                CertificateRepository certificateRepository,
                                JdbcTemplate jdbcTemplate) {
        this.configRepository = configRepository;
        this.certificateRepository = certificateRepository;
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Envia un registro firmado a AEAT. Devuelve la respuesta como
     * SendResult; mensaje vacio + ok=true significa Aceptado puro,
     * ok=false con mensaje significa Rechazo / error.
     *
     * Si la empresa no tiene certificado configurado o falla la
     * construccion del SSLContext, devuelve ok=false con el motivo.
     */
    public SendResult sendInvoice(String companyId, String mode,
                                   String emitterNif, String emitterLegalName,
                                   String signedRegistryXml) {
        String certId = lookupCertificateId(companyId);
        if (!StringUtils.hasText(certId)) {
            return new SendResult(false, "Empresa sin certificado configurado", null);
        }
        Certificate cert = certificateRepository.findById(certId).orElse(null);
        if (cert == null || !StringUtils.hasText(cert.certificateDataBase64())) {
            return new SendResult(false, "Certificado no encontrado o vacio", null);
        }

        try {
            SSLContext sslContext = buildSslContext(cert);
            HttpClient client = HttpClient.newBuilder()
                    .sslContext(sslContext)
                    .connectTimeout(Duration.ofSeconds(15))
                    .version(HttpClient.Version.HTTP_1_1)
                    .build();

            String envelope = buildSoapEnvelope(emitterNif, emitterLegalName, signedRegistryXml);
            String endpoint = "PROD".equals(mode) ? ENDPOINT_PROD : ENDPOINT_TEST;

            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "text/xml; charset=UTF-8")
                    .header("SOAPAction", "RegFactuSistemaFacturacion")
                    .POST(HttpRequest.BodyPublishers.ofString(envelope))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            String body = response.body() == null ? "" : response.body();

            if (status >= 200 && status < 300) {
                // TODO VF3-FINAL: parsear el SOAP response real de AEAT
                // y mapear los codigos a estados:
                //   - Aceptado -> ACKNOWLEDGED
                //   - AceptadoConErrores -> ACKNOWLEDGED + last_error
                //   - Rechazado -> ERROR + last_error
                // Por ahora un 2xx se considera OK; cualquier
                // estructura propia que devuelva AEAT se queda en
                // last_error para que el operador pueda ver el detalle.
                return new SendResult(true, extractAeatStatus(body), body);
            }
            return new SendResult(false, "HTTP " + status, body);
        } catch (Exception ex) {
            log.warn("VF3-SOAP: error enviando factura a AEAT (companyId={}, mode={}): {}",
                    companyId, mode, ex.getMessage());
            return new SendResult(false, ex.getClass().getSimpleName() + ": " + ex.getMessage(), null);
        }
    }

    private String lookupCertificateId(String companyId) {
        java.util.List<String> ids = jdbcTemplate.query(
                "SELECT verifactu_certificate_id FROM companies WHERE id = ?",
                (rs, rowNum) -> rs.getString(1),
                companyId);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private SSLContext buildSslContext(Certificate cert) throws Exception {
        byte[] keystoreBytes = Base64.getDecoder().decode(cert.certificateDataBase64());
        char[] password = cert.passwordPlaintext() == null
                ? new char[0] : cert.passwordPlaintext().toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (ByteArrayInputStream in = new ByteArrayInputStream(keystoreBytes)) {
            ks.load(in, password);
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, password);

        SSLContext ctx = SSLContext.getInstance("TLSv1.3");
        // TrustManager permisivo provisional. TODO VF3-FINAL: usar el
        // TrustManager por defecto (CAs del sistema) — AEAT sirve con
        // CA publica, no hace falta truststore custom.
        TrustManager[] trustAll = new TrustManager[] { new X509TrustManager() {
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}
        }};
        ctx.init(kmf.getKeyManagers(), trustAll, new java.security.SecureRandom());
        return ctx;
    }

    /**
     * SOAP envelope segun la estructura de AEAT VeriFactu. Los
     * namespaces son los publicados por hacienda en los XSD oficiales.
     * El contenido de RegistroFactura es por ahora nuestro XML interno
     * — ver TODO en cabecera de la clase.
     */
    private String buildSoapEnvelope(String nif, String legalName, String signedRegistryXml) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
        sb.append("<soapenv:Envelope xmlns:soapenv=\"http://schemas.xmlsoap.org/soap/envelope/\"");
        sb.append(" xmlns:sum=\"https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroLR.xsd\"");
        sb.append(" xmlns:sum1=\"https://www2.agenciatributaria.gob.es/static_files/common/internet/dep/aplicaciones/es/aeat/tike/cont/ws/SuministroInformacion.xsd\">");
        sb.append("<soapenv:Header/>");
        sb.append("<soapenv:Body>");
        sb.append("<sum:RegFactuSistemaFacturacion>");
        sb.append("<sum:Cabecera>");
        sb.append("<sum1:ObligadoEmision>");
        sb.append("<sum1:NombreRazon>").append(xml(legalName)).append("</sum1:NombreRazon>");
        sb.append("<sum1:NIF>").append(xml(nif)).append("</sum1:NIF>");
        sb.append("</sum1:ObligadoEmision>");
        sb.append("</sum:Cabecera>");
        sb.append("<sum:RegistroFactura>");
        // TODO VF3-FINAL: aqui debe ir el XML AEAT (XSD oficial) ya
        // firmado XAdES-EPES, no nuestro XML interno. Por ahora
        // empotramos el signedRegistryXml tal cual.
        sb.append(signedRegistryXml == null ? "" : signedRegistryXml);
        sb.append("</sum:RegistroFactura>");
        sb.append("</sum:RegFactuSistemaFacturacion>");
        sb.append("</soapenv:Body>");
        sb.append("</soapenv:Envelope>");
        return sb.toString();
    }

    private String extractAeatStatus(String body) {
        // Heuristica minima — el parseo real cuando se pueda probar.
        if (body == null) return "";
        if (body.contains("AceptadoConErrores")) return "AceptadoConErrores";
        if (body.contains("Rechazado")) return "Rechazado";
        if (body.contains("Aceptado")) return "Aceptado";
        return "";
    }

    private String xml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    /** Resultado del envio. ok=true si AEAT respondio 2xx; ok=false en cualquier otro caso. */
    public record SendResult(boolean ok, String message, String rawResponse) {}
}
