package com.benjagest.backend.auth;

import jakarta.mail.internet.MimeMessage;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * REG-VERIFY — Envío de correos TRANSACCIONALES del producto (p. ej. el PIN de
 * verificación al registrarse) ANTES de que el usuario configure su propio SMTP.
 *
 * <p>Usa la cuenta CENTRAL de BENJAGEST ({@code benjagest.mail.central.*}, que
 * vive en {@code google-secrets.yml}, fuera del repo). Si no está configurada,
 * falla con 503 para que la capa de registro lo trate. No tiene nada que ver con
 * el SMTP por-empresa de {@code EmailSenderService}.
 */
@Service
public class CentralMailService {

    private final String host;
    private final int port;
    private final String username;
    private final String from;
    private final String appPassword;

    public CentralMailService(
            @Value("${benjagest.mail.central.host:}") String host,
            @Value("${benjagest.mail.central.port:587}") int port,
            @Value("${benjagest.mail.central.username:}") String username,
            @Value("${benjagest.mail.central.from:}") String from,
            @Value("${benjagest.mail.central.app-password:}") String appPassword) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.from = from == null || from.isBlank() ? username : from;
        this.appPassword = appPassword;
    }

    public boolean isConfigured() {
        return host != null && !host.isBlank() && appPassword != null && !appPassword.isBlank();
    }

    public void send(String to, String subject, String body) {
        if (!isConfigured()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "El correo central no está configurado (falta benjagest.mail.central.*).");
        }
        try {
            JavaMailSenderImpl sender = new JavaMailSenderImpl();
            sender.setHost(host);
            sender.setPort(port);
            sender.setUsername(username);
            // Las contraseñas de aplicación de Gmail se muestran con espacios; se quitan.
            sender.setPassword(appPassword.replaceAll("\\s", ""));
            Properties props = sender.getJavaMailProperties();
            props.put("mail.smtp.auth", "true");
            props.put("mail.smtp.starttls.enable", "true");
            props.put("mail.transport.protocol", "smtp");

            MimeMessage msg = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setFrom(from);
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            sender.send(msg);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo enviar el correo de verificación: " + ex.getMessage());
        }
    }
}
