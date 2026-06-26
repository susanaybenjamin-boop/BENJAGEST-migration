package com.benjagest.backend.settings;

import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.Properties;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de la pestana "Email" de Configuracion.
 *
 * Maneja:
 *   - GET: devuelve la configuracion guardada SIN exponer la password.
 *   - PUT: cifra la nueva password (si viene) y persiste el upsert.
 *   - POST /test-email: monta un JavaMailSender ad-hoc con la config
 *     guardada, descifra la password y envia un correo a la direccion
 *     indicada para verificar que las credenciales funcionan.
 */
@Service
public class EmailConfigService {

    private final EmailConfigRepository repository;
    private final StringEncryptor encryptor;

    public EmailConfigService(EmailConfigRepository repository, StringEncryptor encryptor) {
        this.repository = repository;
        this.encryptor = encryptor;
    }

    public EmailConfigResponse get() {
        Optional<EmailConfigRow> row = repository.findCurrent();
        if (row.isEmpty()) {
            return new EmailConfigResponse(null, null, null, false, null, null, null, true, true);
        }
        EmailConfigRow r = row.get();
        return new EmailConfigResponse(
                r.smtpHost(),
                r.smtpPort(),
                r.smtpUser(),
                StringUtils.hasText(r.passwordCiphertext()),
                r.fromAddress(),
                r.fromName(),
                r.replyTo(),
                r.tlsEnabled(),
                r.authRequired()
        );
    }

    public EmailConfigResponse update(EmailConfigUpdateRequest request) {
        String newCiphertext = null;
        if (StringUtils.hasText(request.smtpPassword())) {
            // Normaliza la contraseña de aplicación (Gmail/Outlook la muestran con
            // espacios xxxx xxxx xxxx xxxx; el valor real va sin ellos).
            newCiphertext = encryptor.encrypt(request.smtpPassword().replaceAll("\\s", ""));
        }
        EmailConfigRow row = new EmailConfigRow(
                blankToNull(request.smtpHost()),
                request.smtpPort(),
                blankToNull(request.smtpUser()),
                null,
                blankToNull(request.fromAddress()),
                blankToNull(request.fromName()),
                blankToNull(request.replyTo()),
                request.tlsEnabled() == null || request.tlsEnabled(),
                request.authRequired() == null || request.authRequired()
        );
        repository.upsert(row, newCiphertext);
        return get();
    }

    public void sendTestEmail(TestEmailRequest request) {
        EmailConfigRow row = repository.findCurrent().orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La empresa no tiene configurado el SMTP todavia"));
        if (!StringUtils.hasText(row.smtpHost()) || row.smtpPort() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta servidor SMTP o puerto");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(row.smtpHost());
        sender.setPort(row.smtpPort());
        if (row.authRequired() && StringUtils.hasText(row.smtpUser())) {
            sender.setUsername(row.smtpUser());
            if (StringUtils.hasText(row.passwordCiphertext())) {
                // Contraseñas de aplicación de Gmail/Outlook: se muestran con
                // espacios (xxxx xxxx xxxx xxxx) pero el valor real no los lleva.
                sender.setPassword(encryptor.decrypt(row.passwordCiphertext()).replaceAll("\\s", ""));
            }
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(row.authRequired()));
        props.put("mail.smtp.starttls.enable", String.valueOf(row.tlsEnabled()));
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "8000");
        props.put("mail.smtp.writetimeout", "8000");

        try {
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            String from = StringUtils.hasText(row.fromAddress()) ? row.fromAddress() : row.smtpUser();
            if (!StringUtils.hasText(from)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No hay direccion de remitente configurada (from_address o smtp_user)");
            }
            if (StringUtils.hasText(row.fromName())) {
                helper.setFrom(from, row.fromName());
            } else {
                helper.setFrom(from);
            }
            helper.setTo(request.recipient());
            if (StringUtils.hasText(row.replyTo())) {
                helper.setReplyTo(row.replyTo());
            }
            helper.setSubject("BENJAGEST - Email de prueba");
            helper.setText("Este es un correo de prueba enviado desde la pantalla de Configuracion de BENJAGEST. "
                    + "Si lo has recibido, el servidor SMTP esta correctamente configurado.", false);
            sender.send(message);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "No se pudo enviar el email: " + ex.getMessage()
            );
        }
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
