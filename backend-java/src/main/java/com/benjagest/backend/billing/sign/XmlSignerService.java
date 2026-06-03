package com.benjagest.backend.billing.sign;

import com.benjagest.backend.billing.verifactu.VerifactuConfig;
import com.benjagest.backend.billing.verifactu.VerifactuConfigRepository;
import com.benjagest.backend.certificates.Certificate;
import com.benjagest.backend.certificates.CertificateRepository;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.StringReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Collections;
import java.util.Optional;
import javax.xml.crypto.dsig.CanonicalizationMethod;
import javax.xml.crypto.dsig.DigestMethod;
import javax.xml.crypto.dsig.Reference;
import javax.xml.crypto.dsig.SignatureMethod;
import javax.xml.crypto.dsig.SignedInfo;
import javax.xml.crypto.dsig.Transform;
import javax.xml.crypto.dsig.XMLSignature;
import javax.xml.crypto.dsig.XMLSignatureFactory;
import javax.xml.crypto.dsig.dom.DOMSignContext;
import javax.xml.crypto.dsig.keyinfo.KeyInfo;
import javax.xml.crypto.dsig.keyinfo.KeyInfoFactory;
import javax.xml.crypto.dsig.keyinfo.X509Data;
import javax.xml.crypto.dsig.spec.C14NMethodParameterSpec;
import javax.xml.crypto.dsig.spec.TransformParameterSpec;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.w3c.dom.Document;
import org.xml.sax.InputSource;

/**
 * Firma XML-DSig del registro de la empresa (slice VF-SIGN MVP).
 *
 * Honestidad sobre el alcance:
 *
 * <ul>
 *   <li>Esta implementacion produce <b>XML-DSig enveloped signature</b>
 *       valido segun W3C — apto para almacenar localmente como prueba de
 *       integridad firmada por el certificado de la empresa.</li>
 *   <li>NO genera todavia <b>XAdES-EPES</b> estricto. Le faltan las
 *       estructuras {@code SignedSignatureProperties},
 *       {@code SigningCertificate} y {@code SignaturePolicyIdentifier}
 *       que la Orden HAC/1177/2024 anexo IV exige para el envio real.
 *       Cuando se cierre VF3-SOAP (envio AEAT) hay que ampliar este
 *       servicio para que produzca XAdES-EPES — entonces AEAT validara
 *       el formato. Hasta ese momento, BENJAGEST no envia, asi que el
 *       formato MVP cubre la firma local de cadena de cumplimiento.</li>
 *   <li>BouncyCastle se registra como proveedor de seguridad para
 *       cargar PKCS#12 con algoritmos modernos (AES-256, etc) que la
 *       JCE estandar puede no soportar en todos los .p12 emitidos por
 *       FNMT.</li>
 * </ul>
 *
 * Uso:
 *   sign(xmlContent) -> devuelve XML firmado con el certificado activo
 *   de VeriFactu de la empresa. Si no hay certificado configurado o
 *   no es accesible, devuelve Optional.empty (el caller decide si
 *   guarda sin firma o aborta).
 */
@Service
public class XmlSignerService {

    static {
        // Registro idempotente del provider BC. No usamos @PostConstruct
        // para que el provider este disponible incluso si la JVM lo
        // necesita antes de que Spring termine de inicializar.
        if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
    }

    private final VerifactuConfigRepository configRepository;
    private final CertificateRepository certificateRepository;

    public XmlSignerService(VerifactuConfigRepository configRepository,
                            CertificateRepository certificateRepository) {
        this.configRepository = configRepository;
        this.certificateRepository = certificateRepository;
    }

    /**
     * Firma el XML pasado con el certificado activo de la empresa
     * actual. Devuelve Optional.empty si:
     *   - No hay configuracion VeriFactu, o
     *   - No hay certificateId asociado, o
     *   - El certificado existe pero no se puede cargar / no contiene
     *     clave privada / falla la operacion criptografica.
     *
     * En todos esos casos loggeamos a WARN y devolvemos vacio; el caller
     * persiste el registro sin firma (status PENDING) y otro slice
     * podra reintentar mas adelante (VF4).
     */
    public Optional<String> sign(String xmlContent) {
        if (!StringUtils.hasText(xmlContent)) return Optional.empty();
        VerifactuConfig config = configRepository.findCurrent().orElse(null);
        if (config == null || !StringUtils.hasText(config.certificateId())) {
            return Optional.empty();
        }
        Certificate cert = certificateRepository.findById(config.certificateId()).orElse(null);
        if (cert == null || !StringUtils.hasText(cert.certificateDataBase64())) {
            return Optional.empty();
        }
        try {
            byte[] keystoreBytes = Base64.getDecoder().decode(cert.certificateDataBase64());
            char[] password = cert.passwordPlaintext() == null
                    ? new char[0] : cert.passwordPlaintext().toCharArray();
            KeyStore ks = KeyStore.getInstance("PKCS12");
            try (ByteArrayInputStream in = new ByteArrayInputStream(keystoreBytes)) {
                ks.load(in, password);
            }
            String alias = pickAlias(ks);
            if (alias == null) return Optional.empty();
            PrivateKey privateKey = (PrivateKey) ks.getKey(alias, password);
            X509Certificate x509 = (X509Certificate) ks.getCertificate(alias);
            if (privateKey == null || x509 == null) return Optional.empty();

            Document doc = parseXml(xmlContent);
            String signed = signDocument(doc, privateKey, x509);
            return Optional.of(signed);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(XmlSignerService.class)
                    .warn("No se pudo firmar el XML (cert={}): {}",
                            config.certificateId(), ex.getMessage());
            return Optional.empty();
        }
    }

    private String pickAlias(KeyStore ks) throws Exception {
        // Primer alias con clave privada. Los .p12 de FNMT solo tienen
        // uno por archivo normalmente.
        java.util.Enumeration<String> aliases = ks.aliases();
        while (aliases.hasMoreElements()) {
            String a = aliases.nextElement();
            if (ks.isKeyEntry(a)) return a;
        }
        return null;
    }

    private Document parseXml(String xml) throws Exception {
        DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
        dbf.setNamespaceAware(true);
        // Seguridad: deshabilitar resolucion de entidades externas para
        // evitar XXE si el caller pasa XML con DOCTYPE manipulado.
        dbf.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        return dbf.newDocumentBuilder().parse(new InputSource(new StringReader(xml)));
    }

    private String signDocument(Document doc, PrivateKey privateKey, X509Certificate cert) throws Exception {
        XMLSignatureFactory fac = XMLSignatureFactory.getInstance("DOM");

        Reference ref = fac.newReference(
                "",
                fac.newDigestMethod(DigestMethod.SHA256, null),
                Collections.singletonList(
                        fac.newTransform(Transform.ENVELOPED, (TransformParameterSpec) null)),
                null, null);

        SignedInfo signedInfo = fac.newSignedInfo(
                fac.newCanonicalizationMethod(CanonicalizationMethod.INCLUSIVE,
                        (C14NMethodParameterSpec) null),
                fac.newSignatureMethod(SignatureMethod.RSA_SHA256, null),
                Collections.singletonList(ref));

        KeyInfoFactory kif = fac.getKeyInfoFactory();
        X509Data x509Data = kif.newX509Data(Collections.singletonList(cert));
        PublicKey publicKey = cert.getPublicKey();
        KeyInfo keyInfo = kif.newKeyInfo(java.util.List.of(x509Data, kif.newKeyValue(publicKey)));

        DOMSignContext signContext = new DOMSignContext(privateKey, doc.getDocumentElement());
        XMLSignature signature = fac.newXMLSignature(signedInfo, keyInfo);
        signature.sign(signContext);

        return serialize(doc);
    }

    private String serialize(Document doc) throws Exception {
        TransformerFactory tf = TransformerFactory.newInstance();
        tf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalDTD", "");
        tf.setAttribute("http://javax.xml.XMLConstants/property/accessExternalStylesheet", "");
        Transformer t = tf.newTransformer();
        t.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        t.setOutputProperty(OutputKeys.ENCODING, "UTF-8");
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        t.transform(new DOMSource(doc), new StreamResult(baos));
        return baos.toString(java.nio.charset.StandardCharsets.UTF_8);
    }
}
