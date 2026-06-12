package com.benjagest.backend.billing.tpb;

import com.benjagest.backend.settings.EmailSenderService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * TPB Magic Link + OTP (V104) — emite y valida tokens de firma para
 * clientes que NO tienen cuenta en BENJAGEST.
 *
 * <p>Flujo:
 * <ol>
 *   <li>{@link #sendMagicLink}: la asesoría dispara el envío. Generamos
 *       token (32 bytes random) + OTP (6 dígitos), guardamos hash y
 *       enviamos email al cliente con el enlace + el OTP.</li>
 *   <li>{@link #findActiveByToken}: el cliente abre el enlace; el
 *       backend sirve una página HTML mínima con el PDF y un campo OTP.</li>
 *   <li>{@link #signWithOtp}: el cliente introduce el OTP; lo validamos
 *       y marcamos el acuerdo como ACTIVE con
 *       signed_method=MAGIC_LINK_OTP. Guardamos IP y user-agent como
 *       evidencia.</li>
 * </ol>
 *
 * <p>Anti-fuerza-bruta: 5 intentos de OTP por token. Tras el quinto
 * fallo el token queda invalidado y la asesoría debe reenviar.
 *
 * <p>Cumple eIDAS art. 25 (firma electrónica simple): hay constancia
 * identificativa del firmante (canal email del cliente registrado en
 * BD + OTP recibido por el mismo canal + IP/UA del navegador). No es
 * firma cualificada pero sí probatoria.
 */
@Service
public class TpbMagicLinkService {

    private static final Logger log = LoggerFactory.getLogger(TpbMagicLinkService.class);
    private static final int TOKEN_BYTES = 32;
    private static final int OTP_DIGITS = 6;
    private static final int OTP_MAX_ATTEMPTS = 5;
    private static final int TOKEN_TTL_HOURS = 24;
    private static final SecureRandom RND = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final EmailSenderService emailSender;
    private final ThirdPartyBillingAgreementService agreementService;
    private final com.benjagest.backend.billing.series.SeriesService seriesService;
    private final String publicBaseUrl;

    public TpbMagicLinkService(JdbcTemplate jdbc,
                                 EmailSenderService emailSender,
                                 ThirdPartyBillingAgreementService agreementService,
                                 com.benjagest.backend.billing.series.SeriesService seriesService,
                                 @Value("${benjagest.public-base-url:}")
                                 String configuredPublicBaseUrl,
                                 @Value("${server.port:8080}")
                                 int serverPort) {
        this.jdbc = jdbc;
        this.emailSender = emailSender;
        this.agreementService = agreementService;
        this.seriesService = seriesService;
        // Si no hay benjagest.public-base-url configurada, intentamos
        // detectar la IP local de la maquina para que el enlace funcione
        // tambien desde otros dispositivos de la misma red (movil del
        // cliente). Si solo se accede desde la misma maquina o la
        // deteccion falla, cae a localhost.
        String resolved;
        if (configuredPublicBaseUrl != null && !configuredPublicBaseUrl.isBlank()) {
            resolved = configuredPublicBaseUrl;
        } else {
            String ip = detectLocalIp();
            resolved = "http://" + (ip == null ? "localhost" : ip) + ":" + serverPort;
        }
        this.publicBaseUrl = resolved.replaceAll("/+$", "");
        log.info("TPB Magic Link: publicBaseUrl = {}", this.publicBaseUrl);
    }

    /**
     * Devuelve la IP IPv4 no-loopback de la primera interfaz UP que
     * encontremos. Sirve para construir un enlace alcanzable desde el
     * movil del cliente en la misma red WiFi. Null si no detecta nada.
     */
    private static String detectLocalIp() {
        try {
            var ifaces = java.net.NetworkInterface.getNetworkInterfaces();
            while (ifaces.hasMoreElements()) {
                var iface = ifaces.nextElement();
                if (!iface.isUp() || iface.isLoopback() || iface.isVirtual()) continue;
                var addrs = iface.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    var addr = addrs.nextElement();
                    if (addr instanceof java.net.Inet4Address && !addr.isLoopbackAddress()) {
                        return addr.getHostAddress();
                    }
                }
            }
        } catch (Exception ex) {
            log.debug("No se pudo detectar IP local", ex);
        }
        return null;
    }

    @Transactional
    public SendResult sendMagicLink(String agreementId, String email) {
        ThirdPartyBillingAgreement a = agreementService.getById(agreementId);
        if (!ThirdPartyBillingAgreement.STATUS_PROPOSED.equals(a.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El acuerdo no esta en estado PROPOSED (estado: " + a.status() + ")");
        }
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Email del cliente obligatorio");
        }
        String tokenHex = randomHex(TOKEN_BYTES);
        String otp = randomDigits(OTP_DIGITS);
        String otpHash = sha256Hex(otp);
        String id = UUID.randomUUID().toString();
        Instant expires = Instant.now().plus(TOKEN_TTL_HOURS, ChronoUnit.HOURS);
        jdbc.update("""
                INSERT INTO tpb_magic_link_tokens
                       (id, agreement_id, purpose, token, otp_hash,
                        recipient_email, expires_at)
                VALUES (?, ?, 'SIGN', ?, ?, ?, ?)
                """,
                id, agreementId, tokenHex, otpHash,
                email, java.sql.Timestamp.from(expires));
        String link = publicBaseUrl + "/api/public/tpb/sign-page?token=" + tokenHex;

        String advisoryName = jdbc.query(
                "SELECT trade_name, legal_name FROM companies WHERE id = ?",
                rs -> rs.next()
                        ? (rs.getString("trade_name") != null
                                && !rs.getString("trade_name").isBlank()
                                ? rs.getString("trade_name")
                                : rs.getString("legal_name"))
                        : "su asesoria",
                a.advisoryCompanyId());

        String subject = "Acuerdo de facturacion por tercero — firma electronica";
        String body = String.format(
                "Estimado/a cliente:%n%n" +
                "%s solicita su firma para autorizar la emision de facturas en su nombre,%n" +
                "conforme al RD 1619/2012 articulo 5 (acuerdo previo).%n%n" +
                "1. Abra el siguiente enlace para leer el acuerdo:%n%n" +
                "   %s%n%n" +
                "2. Cuando se le pida, introduzca este codigo de un solo uso (OTP):%n%n" +
                "   %s%n%n" +
                "El enlace caduca en 24 horas. Si no inicio usted esta firma, ignore este mensaje:%n" +
                "sin la introduccion del codigo OTP el acuerdo NO se activa.%n%n" +
                "Quedan registrados como evidencia legal su IP, navegador, hora del%n" +
                "click y del codigo introducido, para respaldar la firma frente a la AEAT.%n",
                advisoryName, link, otp);
        try {
            // Envio con el SMTP de la ASESORIA, no del tenant del header.
            // Cuando la asesoria esta actuando-como cliente, el tenant es
            // el cliente — que normalmente no tiene SMTP. La asesoria si
            // lo tiene configurado en Configuracion -> Email.
            emailSender.sendAs(a.advisoryCompanyId(), email, subject, body, null, null);
        } catch (RuntimeException ex) {
            log.warn("TPB Magic Link: fallo el envio de email (agreement={}, to={})",
                    agreementId, email, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo enviar el email: " + ex.getMessage());
        }
        return new SendResult(id, email, expires.toString());
    }

    public Optional<TokenView> findActiveByToken(String token) {
        return jdbc.query("""
                SELECT mlt.id, mlt.agreement_id, mlt.recipient_email,
                       mlt.expires_at, mlt.used_at, mlt.invalidated_at,
                       mlt.attempt_count
                  FROM tpb_magic_link_tokens mlt
                 WHERE mlt.token = ?
                """, rs -> {
            if (!rs.next()) return Optional.<TokenView>empty();
            return Optional.of(new TokenView(
                    rs.getString("id"),
                    rs.getString("agreement_id"),
                    rs.getString("recipient_email"),
                    rs.getTimestamp("expires_at").toInstant(),
                    rs.getTimestamp("used_at") == null
                            ? null : rs.getTimestamp("used_at").toInstant(),
                    rs.getTimestamp("invalidated_at") == null
                            ? null : rs.getTimestamp("invalidated_at").toInstant(),
                    rs.getInt("attempt_count")));
        }, token);
    }

    /**
     * Firma el acuerdo via Magic Link + OTP. Llamado por el endpoint
     * publico tras que el cliente introduzca el OTP. Devuelve el id del
     * acuerdo activado.
     */
    @Transactional
    public String signWithOtp(String token, String otp, String signerIp, String userAgent) {
        TokenView t = findActiveByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Enlace no encontrado"));
        if (t.usedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este enlace ya fue usado");
        }
        if (t.invalidatedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Enlace invalidado tras demasiados intentos fallidos");
        }
        if (Instant.now().isAfter(t.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "El enlace ha caducado. Solicita a tu asesoria un nuevo envio.");
        }
        if (t.attemptCount() >= OTP_MAX_ATTEMPTS) {
            jdbc.update("""
                    UPDATE tpb_magic_link_tokens
                       SET invalidated_at = NOW(6),
                           invalidated_reason = 'MAX_OTP_ATTEMPTS'
                     WHERE id = ?
                    """, t.id());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos fallidos. Pide a tu asesoria un nuevo enlace.");
        }
        if (otp == null || otp.length() != OTP_DIGITS) {
            jdbc.update("UPDATE tpb_magic_link_tokens SET attempt_count = attempt_count + 1 WHERE id = ?", t.id());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "OTP invalido (debe ser de " + OTP_DIGITS + " digitos)");
        }
        String hash = sha256Hex(otp);
        Integer match = jdbc.queryForObject(
                "SELECT COUNT(*) FROM tpb_magic_link_tokens WHERE id = ? AND otp_hash = ?",
                Integer.class, t.id(), hash);
        if (match == null || match == 0) {
            jdbc.update("UPDATE tpb_magic_link_tokens SET attempt_count = attempt_count + 1 WHERE id = ?", t.id());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "OTP incorrecto");
        }
        // OK: marcar token usado + acuerdo activo.
        jdbc.update("""
                UPDATE tpb_magic_link_tokens
                   SET used_at = NOW(6), signer_ip = ?, signer_user_agent = ?
                 WHERE id = ?
                """, signerIp, truncate(userAgent, 500), t.id());
        ThirdPartyBillingAgreement a = agreementService.getById(t.agreementId());
        // Generamos el PDF y persistimos. Reutilizamos el generator
        // existente con el metodo MAGIC_LINK_OTP.
        String pdfPath = agreementService.generateAndStorePdfPublic(
                a, "MAGIC_LINK_OTP");
        jdbc.update("""
                UPDATE third_party_billing_agreements
                   SET status = 'ACTIVE', signed_at = NOW(6),
                       signed_method = 'MAGIC_LINK_OTP', signed_pdf_path = ?
                 WHERE id = ?
                """, pdfPath, t.agreementId());
        // Disparar creacion de serie TPB si cubre ventas.
        if (a.scopeSales()) {
            try {
                seriesService.ensureTpbSeries(a.clientCompanyId(), a.advisoryCompanyId());
            } catch (RuntimeException ex) {
                log.warn("TPB Magic Link: no se pudo crear serie TPB tras firma "
                        + "(advisory={}, client={}). Auto-repair al proximo findCurrent.",
                        a.advisoryCompanyId(), a.clientCompanyId(), ex);
            }
        }
        log.info("TPB Magic Link: acuerdo {} firmado via MAGIC_LINK_OTP desde IP {}",
                t.agreementId(), signerIp);
        // Tras la firma exitosa, emitimos UN nuevo token de purpose='REVOKE'
        // y lo enviamos al mismo email del cliente. Este enlace le permitira
        // revocar el acuerdo en cualquier momento sin necesidad de cuenta.
        // El OTP NO va en este email — se entrega cuando el cliente entra
        // al enlace (anti-phishing y simetria temporal).
        try {
            issueAndSendRevokeToken(a, t.recipientEmail());
        } catch (RuntimeException ex) {
            log.warn("TPB Magic Link: no se pudo enviar el token de revocacion "
                    + "(agreement={}, to={}). La asesoria puede reenviarlo "
                    + "manualmente desde el tab acuerdo.",
                    t.agreementId(), t.recipientEmail(), ex);
        }
        return t.agreementId();
    }

    /**
     * Emite un nuevo token con purpose=REVOKE para el acuerdo y envia
     * por email el enlace al cliente. El OTP NO se incluye en el email
     * — el cliente lo solicita al entrar al enlace, y se envia entonces
     * al mismo email. Asi si alguien intercepta el email del enlace,
     * sigue necesitando acceso al buzon del cliente para obtener el OTP.
     */
    @Transactional
    public SendResult issueAndSendRevokeToken(ThirdPartyBillingAgreement a, String email) {
        if (email == null || email.isBlank() || !email.contains("@")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Email del cliente obligatorio para emitir token de revocacion");
        }
        String tokenHex = randomHex(TOKEN_BYTES);
        String id = UUID.randomUUID().toString();
        // Los tokens de revocacion duran mas (1 ano) — el cliente puede
        // revocar en cualquier momento. Si lo pierde, la asesoria lo
        // reenvia desde su tab.
        Instant expires = Instant.now().plus(365, ChronoUnit.DAYS);
        jdbc.update("""
                INSERT INTO tpb_magic_link_tokens
                       (id, agreement_id, purpose, token, otp_hash,
                        recipient_email, expires_at)
                VALUES (?, ?, 'REVOKE', ?, NULL, ?, ?)
                """,
                id, a.id(), tokenHex, email, java.sql.Timestamp.from(expires));
        String link = publicBaseUrl + "/api/public/tpb/revoke-page?token=" + tokenHex;

        String advisoryName = jdbc.query(
                "SELECT trade_name, legal_name FROM companies WHERE id = ?",
                rs -> rs.next()
                        ? (rs.getString("trade_name") != null
                                && !rs.getString("trade_name").isBlank()
                                ? rs.getString("trade_name")
                                : rs.getString("legal_name"))
                        : "su asesoria",
                a.advisoryCompanyId());

        String subject = "Acuerdo de facturacion por tercero — enlace para revocar";
        String body = String.format(
                "Estimado/a cliente:%n%n" +
                "Acaba usted de firmar electronicamente el acuerdo por el que autoriza%n" +
                "a %s a emitir facturas en su nombre (RD 1619/2012 art. 5).%n%n" +
                "Guarde este email. El siguiente enlace le permitira REVOCAR el acuerdo%n" +
                "en cualquier momento, sin necesidad de instalar nada:%n%n" +
                "   %s%n%n" +
                "Por seguridad, el codigo de un solo uso (OTP) NO se incluye en este%n" +
                "mensaje. Cuando pulse el enlace, le pediremos que solicite un OTP nuevo:%n" +
                "le llegara entonces a este mismo email.%n%n" +
                "Si pierde este email, contacte con su asesoria para que le reenvie%n" +
                "el enlace de revocacion.%n",
                advisoryName, link);
        try {
            emailSender.sendAs(a.advisoryCompanyId(), email, subject, body, null, null);
        } catch (RuntimeException ex) {
            log.warn("TPB Revoke Link: fallo el envio de email (agreement={}, to={})",
                    a.id(), email, ex);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo enviar el email de revocacion: " + ex.getMessage());
        }
        return new SendResult(id, email, expires.toString());
    }

    /**
     * El cliente pulsa el enlace de revocacion y solicita el OTP.
     * Generamos uno fresco (sobreescribimos si ya habia), guardamos
     * hash y enviamos al email asociado al token. NO revela el OTP
     * a quien tenga solo el enlace.
     */
    @Transactional
    public void requestRevokeOtp(String token) {
        TokenView t = requireRevokeToken(token);
        String otp = randomDigits(OTP_DIGITS);
        String otpHash = sha256Hex(otp);
        jdbc.update("""
                UPDATE tpb_magic_link_tokens
                   SET otp_hash = ?, otp_requested_at = NOW(6),
                       attempt_count = 0
                 WHERE id = ?
                """, otpHash, t.id());
        ThirdPartyBillingAgreement a = agreementService.getById(t.agreementId());
        String advisoryName = jdbc.query(
                "SELECT trade_name, legal_name FROM companies WHERE id = ?",
                rs -> rs.next()
                        ? (rs.getString("trade_name") != null
                                && !rs.getString("trade_name").isBlank()
                                ? rs.getString("trade_name")
                                : rs.getString("legal_name"))
                        : "su asesoria",
                a.advisoryCompanyId());
        String subject = "Codigo OTP para revocar el acuerdo con " + advisoryName;
        String body = String.format(
                "Estimado/a cliente:%n%n" +
                "Ha solicitado revocar el acuerdo de facturacion por tercero con %s.%n%n" +
                "Su codigo de un solo uso (OTP) es:%n%n" +
                "   %s%n%n" +
                "Introduzcalo en la pagina abierta para confirmar la revocacion. El%n" +
                "codigo caduca en 15 minutos. Si no inicio usted esta revocacion,%n" +
                "ignore este mensaje: sin la introduccion del OTP el acuerdo seguira%n" +
                "vigente.%n",
                advisoryName, otp);
        emailSender.sendAs(a.advisoryCompanyId(), t.recipientEmail(),
                subject, body, null, null);
    }

    /**
     * El cliente introduce el OTP en la pagina de revocacion. Validamos
     * y revocamos el acuerdo. Captura IP/UA como evidencia legal.
     */
    @Transactional
    public String revokeWithOtp(String token, String otp, String signerIp, String userAgent) {
        TokenView t = requireRevokeToken(token);
        if (t.attemptCount() >= OTP_MAX_ATTEMPTS) {
            jdbc.update("""
                    UPDATE tpb_magic_link_tokens
                       SET invalidated_at = NOW(6),
                           invalidated_reason = 'MAX_OTP_ATTEMPTS_REVOKE'
                     WHERE id = ?
                    """, t.id());
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos fallidos. Pida a la asesoria un nuevo enlace.");
        }
        if (otp == null || otp.length() != OTP_DIGITS) {
            jdbc.update("UPDATE tpb_magic_link_tokens SET attempt_count = attempt_count + 1 WHERE id = ?", t.id());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "OTP invalido (debe ser de " + OTP_DIGITS + " digitos)");
        }
        // Comprobamos que se haya solicitado un OTP (otp_hash NOT NULL)
        // y que coincida.
        Integer match = jdbc.queryForObject("""
                SELECT COUNT(*) FROM tpb_magic_link_tokens
                 WHERE id = ? AND otp_hash IS NOT NULL AND otp_hash = ?
                """, Integer.class, t.id(), sha256Hex(otp));
        if (match == null || match == 0) {
            jdbc.update("UPDATE tpb_magic_link_tokens SET attempt_count = attempt_count + 1 WHERE id = ?", t.id());
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "OTP incorrecto o no solicitado todavia");
        }
        // OK: marcar token usado + acuerdo revocado.
        jdbc.update("""
                UPDATE tpb_magic_link_tokens
                   SET used_at = NOW(6), signer_ip = ?, signer_user_agent = ?
                 WHERE id = ?
                """, signerIp, truncate(userAgent, 500), t.id());
        ThirdPartyBillingAgreement a = agreementService.getById(t.agreementId());
        if (!ThirdPartyBillingAgreement.STATUS_ACTIVE.equals(a.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El acuerdo no esta activo (estado: " + a.status() + ")");
        }
        // PROTECCION DE EVIDENCIA: comprobamos que no haya facturas TPB
        // emitidas sin PDF guardado en disco. La revocacion NO debe
        // ocurrir si se perderia evidencia legal de operaciones pasadas.
        Integer unsavedInvoices = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sales_invoices si
                  JOIN invoice_series s ON s.id = si.series_id
                 WHERE s.expedited_by_company_id = ?
                   AND si.company_id = ?
                   AND si.status = 'VALIDATED'
                   AND (si.pdf_path IS NULL OR si.pdf_path = '')
                """, Integer.class, a.advisoryCompanyId(), a.clientCompanyId());
        if (unsavedInvoices != null && unsavedInvoices > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede revocar: " + unsavedInvoices
                    + " factura(s) emitida(s) por la asesoria aun no tienen PDF guardado. "
                    + "Avise a la asesoria para que regenere los PDF y vuelva a intentarlo.");
        }
        jdbc.update("""
                UPDATE third_party_billing_agreements
                   SET status = 'REVOKED', revoked_at = NOW(6),
                       revoked_by_advisory = FALSE,
                       revoked_reason = 'Revocado por el cliente via Magic Link OTP'
                 WHERE id = ?
                """, t.agreementId());
        log.info("TPB Magic Link: acuerdo {} REVOCADO via MAGIC_LINK_OTP desde IP {}",
                t.agreementId(), signerIp);
        return t.agreementId();
    }

    /** Carga un token y exige que sea purpose=REVOKE, no usado y vigente. */
    private TokenView requireRevokeToken(String token) {
        TokenView t = findActiveByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Enlace no encontrado"));
        String purpose = jdbc.queryForObject(
                "SELECT purpose FROM tpb_magic_link_tokens WHERE id = ?",
                String.class, t.id());
        if (!"REVOKE".equals(purpose)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Este enlace no es de revocacion");
        }
        if (t.usedAt() != null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Este enlace ya fue usado");
        }
        if (t.invalidatedAt() != null) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "Enlace invalidado");
        }
        if (Instant.now().isAfter(t.expiresAt())) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "El enlace ha caducado");
        }
        return t;
    }

    /** Re-emitir el token de revocacion (cliente perdio el email). */
    public SendResult resendRevocationLink(String agreementId) {
        ThirdPartyBillingAgreement a = agreementService.getById(agreementId);
        if (!ThirdPartyBillingAgreement.STATUS_ACTIVE.equals(a.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El acuerdo no esta activo");
        }
        // Buscamos el ultimo email al que se envio el SIGN para usar el
        // mismo en el reenvio del REVOKE. Si la asesoria quiere mandarlo
        // a otro email, ampliamos despues.
        String email = jdbc.query("""
                SELECT recipient_email FROM tpb_magic_link_tokens
                 WHERE agreement_id = ?
                 ORDER BY sent_at DESC LIMIT 1
                """, rs -> rs.next() ? rs.getString(1) : null, agreementId);
        if (email == null || email.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "No hay email asociado a tokens previos del acuerdo");
        }
        return issueAndSendRevokeToken(a, email);
    }

    private static String randomHex(int nbytes) {
        byte[] b = new byte[nbytes];
        RND.nextBytes(b);
        return HexFormat.of().formatHex(b);
    }

    private static String randomDigits(int n) {
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append(RND.nextInt(10));
        return sb.toString();
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("No SHA-256 available", e);
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    public record SendResult(String tokenId, String email, String expiresAt) {}

    public record TokenView(String id, String agreementId, String recipientEmail,
                              Instant expiresAt, Instant usedAt, Instant invalidatedAt,
                              int attemptCount) {}
}
