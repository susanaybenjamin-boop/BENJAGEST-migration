package com.benjagest.backend.certificates;

import java.io.ByteArrayInputStream;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Abre un .p12 / .pfx en memoria (sin tocar disco) y extrae los
 * metadatos del primer certificado X.509 que contenga.
 *
 * No persiste nada — la UI llama a este servicio antes de guardar
 * para que el usuario vea subject, NIF y validez auto-detectados.
 *
 * Reconocimiento de tipo (heuristico, suficiente para la UI):
 *
 *   - FNMT_PERSONA_FISICA: issuer contiene "FNMT" y subject NO contiene
 *     "REPRESENTANTE", "PERSONA JURIDICA" o similares.
 *   - FNMT_REPRESENTANTE: issuer FNMT y subject contiene
 *     "REPRESENTANTE" / "PERSONA JURIDICA".
 *   - CAMERFIRMA / IZENPE / ANCERT: por el campo issuer.
 *   - OTRO: cualquier otro.
 *
 * El NIF se intenta extraer en este orden:
 *
 *   1) Atributo serialNumber del DN (formato AEAT: "IDCES-W0184081H"
 *      o directamente "W0184081H").
 *   2) Patron NIF en el CN (raro pero algunos certificados lo meten).
 */
@Service
public class KeystoreInspector {

    private static final Pattern NIF_PATTERN = Pattern.compile(
            "([XYZxyz]?\\d{7,8}[A-HJ-NP-TV-Za-hj-np-tv-z]|" +
            "[A-HJ-NP-SUVWa-hj-np-suvw]\\d{7}[0-9A-Ja-j])");

    /**
     * Inspecciona el .p12. La password puede ser null o vacia para
     * keystores sin proteccion (raro pero existen).
     */
    public CertificateInspectResponse inspect(String base64Data, String password) {
        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(base64Data.replaceAll("\\s+", ""));
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El archivo no es base64 valido");
        }
        char[] pwd = password == null ? new char[0] : password.toCharArray();
        try {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(new ByteArrayInputStream(bytes), pwd);

            List<String> aliases = new ArrayList<>();
            X509Certificate firstX509 = null;
            Enumeration<String> en = ks.aliases();
            while (en.hasMoreElements()) {
                String alias = en.nextElement();
                aliases.add(alias);
                if (firstX509 == null) {
                    Certificate c = ks.getCertificate(alias);
                    if (c instanceof X509Certificate x) firstX509 = x;
                }
            }
            if (firstX509 == null) {
                throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                        "El keystore no contiene ningun certificado X.509");
            }
            String subjectDn = firstX509.getSubjectX500Principal().getName();
            String issuerDn = firstX509.getIssuerX500Principal().getName();
            String cn = extractRdn(subjectDn, "CN");
            String serial = extractRdn(subjectDn, "SERIALNUMBER");
            if (serial == null) serial = extractRdn(subjectDn, "2.5.4.5");
            String issuerCn = extractRdn(issuerDn, "CN");
            if (issuerCn == null) issuerCn = extractRdn(issuerDn, "O");

            String nif = guessNif(serial, cn, subjectDn);
            String type = guessType(issuerDn, subjectDn);

            return new CertificateInspectResponse(
                    cn,
                    nif,
                    issuerCn,
                    type,
                    firstX509.getNotBefore().toInstant(),
                    firstX509.getNotAfter().toInstant(),
                    aliases
            );
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (java.io.IOException ex) {
            // En .p12, "wrong password" se lanza como IOException con
            // mensaje muy variable segun JDK. Devolvemos 400 con texto
            // claro para que la UI lo presente al usuario.
            String msg = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
            if (msg.contains("password") || msg.contains("integrity")
                    || msg.contains("decrypt") || msg.contains("mac")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "Contrasena del certificado incorrecta");
            }
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se pudo abrir el .p12: " + ex.getMessage());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "No se pudo inspeccionar el certificado: " + ex.getMessage());
        }
    }

    /**
     * Saca el valor de un RDN (Relative Distinguished Name) del DN.
     * El DN de Java viene como "CN=foo, SERIALNUMBER=bar, O=baz".
     * No usamos LdapName porque su comportamiento ante comas y
     * codificaciones varia entre JDKs.
     */
    private String extractRdn(String dn, String key) {
        if (dn == null) return null;
        String upper = dn.toUpperCase();
        String needle = key.toUpperCase() + "=";
        int i = upper.indexOf(needle);
        if (i < 0) return null;
        int start = i + needle.length();
        // El valor termina en coma no escapada. Para simplificar,
        // cortamos en la primera coma no precedida por backslash.
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int j = start; j < dn.length(); j++) {
            char c = dn.charAt(j);
            if (escape) { sb.append(c); escape = false; continue; }
            if (c == '\\') { escape = true; continue; }
            if (c == ',') break;
            sb.append(c);
        }
        return sb.toString().trim();
    }

    private String guessNif(String serial, String cn, String subjectDn) {
        // FNMT y similares ponen serialNumber con prefijo: "IDCES-W0184081H".
        if (serial != null) {
            Matcher m = NIF_PATTERN.matcher(serial.toUpperCase());
            if (m.find()) return m.group(1).toUpperCase();
        }
        if (subjectDn != null) {
            Matcher m = NIF_PATTERN.matcher(subjectDn.toUpperCase());
            if (m.find()) return m.group(1).toUpperCase();
        }
        if (cn != null) {
            Matcher m = NIF_PATTERN.matcher(cn.toUpperCase());
            if (m.find()) return m.group(1).toUpperCase();
        }
        return null;
    }

    private String guessType(String issuerDn, String subjectDn) {
        String up = (issuerDn == null ? "" : issuerDn.toUpperCase())
                + " | " + (subjectDn == null ? "" : subjectDn.toUpperCase());
        if (up.contains("FNMT")) {
            if (up.contains("REPRESENTANTE")
                    || up.contains("PERSONA JURIDICA")
                    || up.contains("PERSONA JURÍDICA")) {
                return "FNMT_REPRESENTANTE";
            }
            return "FNMT_PERSONA_FISICA";
        }
        if (up.contains("CAMERFIRMA")) return "CAMERFIRMA";
        if (up.contains("IZENPE")) return "IZENPE";
        if (up.contains("ANCERT")) return "ANCERT";
        if (up.contains("SELLO")) return "SELLO_EMPRESA";
        return "OTRO";
    }
}
