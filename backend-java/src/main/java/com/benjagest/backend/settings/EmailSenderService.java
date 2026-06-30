package com.benjagest.backend.settings;

import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.Properties;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatus;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Punto unico de envio de email para la empresa actual usando la
 * configuracion SMTP guardada en company_email_config (C3). Lo usan:
 *
 *   - EmailConfigService.sendTestEmail (cuando el usuario pulsa
 *     "Enviar email de prueba" desde Configuracion -> Email).
 *   - InvoiceEmailService.sendInvoiceToCustomer (envio de factura PDF
 *     adjunto al cliente — slice F-EMAIL).
 *   - Cualquier slice futuro que necesite mandar email transaccional
 *     desde el backend.
 *
 * Encapsula:
 *   - Lectura de la config SMTP de la empresa actual.
 *   - Descifrado de la password con Jasypt.
 *   - Validacion previa (host/puerto/remitente) con mensajes de error
 *     claros (400 si falta config, 502 si AEAT/SMTP falla en runtime).
 *   - Construccion del JavaMailSenderImpl + MimeMessageHelper con
 *     soporte de attachments.
 */
@Service
public class EmailSenderService {

    private final EmailConfigRepository repository;
    private final StringEncryptor encryptor;
    private final com.benjagest.backend.tenant.TenantContext tenantContext;
    private final com.benjagest.backend.auth.GmailApiService gmailApiService;
    private final com.benjagest.backend.auth.GoogleOAuthService googleOAuthService;

    public EmailSenderService(EmailConfigRepository repository, StringEncryptor encryptor,
                              com.benjagest.backend.tenant.TenantContext tenantContext,
                              com.benjagest.backend.auth.GmailApiService gmailApiService,
                              com.benjagest.backend.auth.GoogleOAuthService googleOAuthService) {
        this.repository = repository;
        this.encryptor = encryptor;
        this.tenantContext = tenantContext;
        this.gmailApiService = gmailApiService;
        this.googleOAuthService = googleOAuthService;
    }

    /**
     * Si la empresa tiene Gmail conectado por OAuth, envía por la API de Gmail y
     * devuelve true. Si no, devuelve false para que el llamante use SMTP. Así
     * facturas/nóminas/TPB usan Gmail automáticamente sin tocar cada sitio.
     */
    private boolean sentViaGmail(String companyId, String to, String subject, String body,
                                 byte[] attachmentBytes, String attachmentName) {
        if (companyId == null || !gmailApiService.enabledFor(companyId)) return false;
        var conn = googleOAuthService.apiConnection(companyId);
        gmailApiService.send(companyId, conn.email(), null, to, subject, body, attachmentBytes, attachmentName);
        return true;
    }

    private String currentCompanyIdSafe() {
        try { return tenantContext.getCurrentCompanyId(); } catch (Exception ex) { return null; }
    }

    /**
     * Envia un email con un adjunto opcional. Si {@code attachmentBytes}
     * es null o vacio, va sin adjuntos.
     *
     * @param to                destinatario (obligatorio).
     * @param subject           asunto (obligatorio).
     * @param body              cuerpo de texto plano (no HTML para
     *                          maximizar compatibilidad — los clientes
     *                          que renderizan factura+PDF estan en todas
     *                          las gestorias y prefieren texto plano).
     * @param attachmentBytes   bytes del adjunto, o null si no hay.
     * @param attachmentName    nombre visible del adjunto (con .pdf, etc).
     */
    public void send(String to, String subject, String body,
                     byte[] attachmentBytes, String attachmentName) {
        Optional<EmailConfigRow> smtp = repository.findCurrent();
        try {
            if (sentViaGmail(currentCompanyIdSafe(), to, subject, body, attachmentBytes, attachmentName)) return;
        } catch (RuntimeException gmailEx) {
            // Gmail esta habilitado para la empresa pero el envio fallo (p.ej. la
            // Gmail API no esta habilitada en el proyecto de Google Cloud, o el
            // token/scope caduco). Si hay SMTP configurado, lo usamos de respaldo
            // para que el correo salga igualmente; si no, propagamos el error REAL
            // de Gmail (no el generico de SMTP, que despistaria).
            if (smtp.isEmpty() || !StringUtils.hasText(smtp.get().smtpHost())) throw gmailEx;
        }
        EmailConfigRow row = smtp.orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La empresa no tiene configurado el SMTP. Configuralo en Configuracion -> Email."));
        sendWith(row, to, subject, body, attachmentBytes, attachmentName);
    }

    /**
     * Variante para enviar con la config SMTP de una empresa concreta
     * (ignorando el tenant del header). Usado en flujos donde una
     * asesoria opera "como" cliente: ej. TPB Magic Link, el email del
     * enlace debe salir del SMTP configurado por la asesoria, no del
     * cliente actual (que normalmente no tiene SMTP).
     */
    public void sendAs(String companyId, String to, String subject, String body,
                         byte[] attachmentBytes, String attachmentName) {
        if (sentViaGmail(companyId, to, subject, body, attachmentBytes, attachmentName)) return;
        EmailConfigRow row = repository.findForCompany(companyId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "La asesoria no tiene SMTP configurado. Configuralo en Configuracion -> Email."));
        sendWith(row, to, subject, body, attachmentBytes, attachmentName);
    }

    private void sendWith(EmailConfigRow row, String to, String subject, String body,
                            byte[] attachmentBytes, String attachmentName) {
        if (!StringUtils.hasText(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Destinatario requerido");
        }
        if (!StringUtils.hasText(row.smtpHost()) || row.smtpPort() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta servidor SMTP o puerto en la configuracion");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(row.smtpHost());
        sender.setPort(row.smtpPort());
        if (row.authRequired() && StringUtils.hasText(row.smtpUser())) {
            sender.setUsername(row.smtpUser());
            if (StringUtils.hasText(row.passwordCiphertext())) {
                // Gmail/Outlook muestran las "contraseñas de aplicación" en grupos
                // separados por ESPACIOS (xxxx xxxx xxxx xxxx); el valor real son
                // 16 caracteres sin espacios. Si el usuario las pega con espacios,
                // la auth SMTP falla → los quitamos al usar la contraseña.
                sender.setPassword(encryptor.decrypt(row.passwordCiphertext()).replaceAll("\\s", ""));
            }
        }
        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", String.valueOf(row.authRequired()));
        props.put("mail.smtp.starttls.enable", String.valueOf(row.tlsEnabled()));
        props.put("mail.smtp.connectiontimeout", "8000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");

        try {
            // multipart=true cuando hay adjunto; false ahorra un wrapper
            // si no hay nada que adjuntar.
            boolean hasAttachment = attachmentBytes != null && attachmentBytes.length > 0
                    && StringUtils.hasText(attachmentName);
            MimeMessage message = sender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, hasAttachment, "UTF-8");

            String from = Optional.ofNullable(row.fromAddress())
                    .filter(StringUtils::hasText)
                    .orElse(row.smtpUser());
            if (!StringUtils.hasText(from)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "No hay direccion de remitente (from_address o smtp_user)");
            }
            if (StringUtils.hasText(row.fromName())) {
                helper.setFrom(from, row.fromName());
            } else {
                helper.setFrom(from);
            }
            helper.setTo(to);
            if (StringUtils.hasText(row.replyTo())) {
                helper.setReplyTo(row.replyTo());
            }
            helper.setSubject(subject);
            helper.setText(body == null ? "" : body, false);
            if (hasAttachment) {
                helper.addAttachment(attachmentName, new ByteArrayResource(attachmentBytes));
            }
            sender.send(message);
        } catch (ResponseStatusException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "No se pudo enviar el email: " + ex.getMessage());
        }
    }
}
