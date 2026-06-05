package com.benjagest.backend.certificates;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import javax.security.auth.x500.X500Principal;
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

    /** Prefijos que cuelgan delante del NIF en el serialNumber del subject:
     *   - FNMT persona fisica: "IDCES-12345678Z"
     *   - FNMT representante:  "VATES-B12345678"  (o "CIFES-")
     *   - Camerfirma a veces:  "NIFES-12345678Z"
     */
    private static final Pattern NIF_PREFIX = Pattern.compile(
            "(?i)^(IDCE?S?|VATE?S?|NIFE?S?|CIFE?S?)[-:]?\\s*");

    /** OID del atributo serialNumber del subject (X.520). */
    private static final String OID_SERIAL_NUMBER = "2.5.4.5";
    /** OID de CN. */
    private static final String OID_CN = "2.5.4.3";
    /** OID de O (Organization). */
    private static final String OID_ORGANIZATION = "2.5.4.10";

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
            // RFC2253 da formato estandar con escapes correctos para LdapName.
            String subjectDn = firstX509.getSubjectX500Principal().getName(X500Principal.RFC2253);
            String issuerDn = firstX509.getIssuerX500Principal().getName(X500Principal.RFC2253);

            String cn = findRdn(subjectDn, OID_CN, "CN");
            String serial = findRdn(subjectDn, OID_SERIAL_NUMBER, "SERIALNUMBER");
            String issuerO = findRdn(issuerDn, OID_ORGANIZATION, "O");
            String issuerCn = findRdn(issuerDn, OID_CN, "CN");
            // Para mostrar emisor: O suele ser mas legible que CN (FNMT
            // pone "FNMT-RCM" en O y un CN tecnico larguisimo).
            String issuerLabel = issuerO != null ? issuerO : issuerCn;

            String nif = guessNif(serial, cn, subjectDn);
            String type = guessType(issuerDn, subjectDn);

            return new CertificateInspectResponse(
                    cn,
                    nif,
                    issuerLabel,
                    type,
                    firstX509.getNotBefore().toInstant(),
                    firstX509.getNotAfter().toInstant(),
                    aliases,
                    subjectDn,
                    issuerDn,
                    firstX509.getSerialNumber().toString(16)
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
     * Busca el valor de un RDN en el DN. Usa {@link LdapName} que
     * parsea RFC2253 correctamente: maneja comas escapadas, espacios,
     * codificacion hex y valores multi-byte.
     *
     * Por seguridad acepta dos identificadores: el OID y el nombre
     * canonico. Algunos JDK rinden "OID.2.5.4.5=#1308..." y otros
     * "SERIALNUMBER=IDCES-12345678Z" para el mismo atributo.
     */
    private String findRdn(String dn, String oid, String canonicalName) {
        if (dn == null) return null;
        try {
            LdapName ldap = new LdapName(dn);
            for (Rdn r : ldap.getRdns()) {
                String type = r.getType();
                if (type == null) continue;
                if (type.equalsIgnoreCase(canonicalName)
                        || type.equalsIgnoreCase(oid)
                        || type.equalsIgnoreCase("OID." + oid)) {
                    return rdnValueToString(r.getValue());
                }
            }
        } catch (InvalidNameException ignored) {
            // DN malformado — devolvemos null y caller decide.
        }
        return null;
    }

    /**
     * Los valores hex-encoded de RFC2253 ("#1308504552534f4e41") los
     * LdapName devuelve como byte[]. Hay que decodificar a String.
     */
    private String rdnValueToString(Object value) {
        if (value == null) return null;
        if (value instanceof String s) return s;
        if (value instanceof byte[] bytes) {
            // El primer byte es el ASN.1 tag (0x0C=UTF8String,
            // 0x13=PrintableString, 0x14=T61String). Los siguientes
            // 1-2 bytes son la longitud. Para nuestro caso (NIF/CN
            // cortos) tomamos heuristico: si los primeros bytes son
            // tipo+length, los saltamos.
            if (bytes.length >= 2 && (bytes[0] == 0x0C || bytes[0] == 0x13
                    || bytes[0] == 0x14 || bytes[0] == 0x16)) {
                int len = bytes[1] & 0xFF;
                if (bytes.length >= 2 + len) {
                    return new String(bytes, 2, len, StandardCharsets.UTF_8);
                }
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return value.toString();
    }

    private String guessNif(String serial, String cn, String subjectDn) {
        // 1) serialNumber del subject (OID 2.5.4.5) — la fuente
        //    autoritativa en certificados FNMT, Camerfirma, etc.
        //    Eliminamos prefijos conocidos (IDCES-, VATES-, NIFES-,
        //    CIFES-) antes de intentar matchear el NIF.
        if (serial != null && !serial.isBlank()) {
            String cleaned = NIF_PREFIX.matcher(serial).replaceFirst("").trim();
            Matcher m = NIF_PATTERN.matcher(cleaned.toUpperCase());
            if (m.find()) return m.group(1).toUpperCase();
            // Si tras quitar prefijo queda algo "razonable" (16 chars o
            // menos) lo devolvemos tal cual — algunos certificados
            // extranjeros usan formatos no-NIF en este campo.
            if (cleaned.length() > 0 && cleaned.length() <= 16) {
                return cleaned.toUpperCase();
            }
        }
        // 2) CN — FNMT pone "APELLIDO1 APELLIDO2, NOMBRE - 12345678Z".
        //    Buscamos el NIF al final, separado por guion.
        if (cn != null && !cn.isBlank()) {
            Matcher m = NIF_PATTERN.matcher(cn.toUpperCase());
            if (m.find()) return m.group(1).toUpperCase();
        }
        // 3) Subject DN completo — ultimo recurso, puede haber falsos
        //    positivos si hay otros numeros que parecen NIF.
        if (subjectDn != null) {
            Matcher m = NIF_PATTERN.matcher(subjectDn.toUpperCase());
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
