package com.benjagest.backend.auth;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Properties;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * REG-3 / GOOGLE-UNIFICADO — Envío de correo por la API de Gmail (OAuth), como
 * alternativa al SMTP con contraseña de aplicación. Usa el refresh token de la
 * empresa (almacenado por {@link GoogleOAuthService}) para obtener un access
 * token y enviar. Lo invoca {@code EmailSenderService} cuando la empresa tiene
 * Gmail conectado.
 */
@Service
public class GmailApiService {

    private static final String SEND_ENDPOINT =
            "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";

    private final GoogleOAuthService googleOAuth;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public GmailApiService(GoogleOAuthService googleOAuth) {
        this.googleOAuth = googleOAuth;
    }

    public boolean enabledFor(String companyId) {
        return companyId != null && googleOAuth.gmailEnabled(companyId);
    }

    /** Envía un correo (con adjunto opcional) por la cuenta Gmail conectada. */
    public void send(String companyId, String from, String fromName, String to, String subject,
                     String body, byte[] attachmentBytes, String attachmentName) {
        String accessToken = googleOAuth.freshAccessToken(companyId);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Google no devolvió access token para Gmail.");
        }
        String raw = buildRawMessage(from, fromName, to, subject, body, attachmentBytes, attachmentName);
        String json = "{\"raw\":\"" + raw + "\"}";
        HttpResponse<String> resp;
        try {
            resp = http.send(HttpRequest.newBuilder(URI.create(SEND_ENDPOINT))
                    .timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + accessToken)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build(), HttpResponse.BodyHandlers.ofString());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "No se pudo enviar por Gmail: " + ex.getMessage());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "Gmail rechazó el envío (HTTP " + resp.statusCode() + "): " + resp.body());
        }
    }

    private String buildRawMessage(String from, String fromName, String to, String subject,
                                   String body, byte[] attachmentBytes, String attachmentName) {
        try {
            boolean hasAttachment = attachmentBytes != null && attachmentBytes.length > 0
                    && StringUtils.hasText(attachmentName);
            MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
            MimeMessageHelper helper = new MimeMessageHelper(message, hasAttachment, "UTF-8");
            if (StringUtils.hasText(fromName)) helper.setFrom(from, fromName);
            else helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject == null ? "" : subject);
            helper.setText(body == null ? "" : body, false);
            if (hasAttachment) helper.addAttachment(attachmentName, new ByteArrayResource(attachmentBytes));
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            message.writeTo(baos);
            return Base64.getUrlEncoder().encodeToString(baos.toByteArray());
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error construyendo el correo: " + ex.getMessage());
        }
    }
}
