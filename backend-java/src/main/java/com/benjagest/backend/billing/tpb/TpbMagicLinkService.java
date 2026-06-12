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
                                 @Value("${benjagest.public-base-url:http://localhost:8080}")
                                 String publicBaseUrl) {
        this.jdbc = jdbc;
        this.emailSender = emailSender;
        this.agreementService = agreementService;
        this.seriesService = seriesService;
        this.publicBaseUrl = publicBaseUrl.replaceAll("/+$", "");
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
                       (id, agreement_id, token, otp_hash,
                        recipient_email, expires_at)
                VALUES (?, ?, ?, ?, ?, ?)
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
            emailSender.send(email, subject, body, null, null);
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
        return t.agreementId();
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
