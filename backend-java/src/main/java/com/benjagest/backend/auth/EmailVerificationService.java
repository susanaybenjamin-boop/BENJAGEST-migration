package com.benjagest.backend.auth;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * REG-VERIFY — Verificación del email por PIN tras registrarse con
 * email+contraseña. Genera un PIN de 6 dígitos (caduca en 30 min, máx 6
 * intentos), lo guarda HASHEADO en {@code user_accounts} y lo envía por la
 * cuenta central ({@link CentralMailService}). El login queda bloqueado hasta
 * que {@code email_verified = 1}.
 */
@Service
public class EmailVerificationService {

    private static final int EXPIRY_MINUTES = 30;
    private static final int MAX_ATTEMPTS = 6;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JdbcTemplate jdbc;
    private final PasswordEncoder encoder;
    private final CentralMailService mail;

    public EmailVerificationService(JdbcTemplate jdbc, PasswordEncoder encoder, CentralMailService mail) {
        this.jdbc = jdbc;
        this.encoder = encoder;
        this.mail = mail;
    }

    /** Genera + guarda + envía un PIN nuevo para una cuenta (por id). */
    public void start(String userId, String email, String displayName) {
        String pin = String.format("%06d", RANDOM.nextInt(1_000_000));
        jdbc.update("""
                UPDATE user_accounts
                   SET email_verified = 0,
                       verification_pin_hash = ?,
                       verification_expires_at = ?,
                       verification_attempts = 0
                 WHERE id = ?
                """,
                encoder.encode(pin),
                Timestamp.from(Instant.now().plus(EXPIRY_MINUTES, ChronoUnit.MINUTES)),
                userId);

        String name = displayName == null || displayName.isBlank() ? "" : " " + displayName;
        String subject = "Tu código de verificación de BENJAGEST";
        String body = "Hola" + name + ",\n\n"
                + "Tu código de verificación es: " + pin + "\n\n"
                + "Introdúcelo en BENJAGEST para activar tu cuenta. Caduca en "
                + EXPIRY_MINUTES + " minutos.\n\n"
                + "Si no has solicitado este registro, ignora este correo.";
        // El UPDATE del PIN queda en la transacción del registro; el correo se
        // envía DESPUÉS del commit (si falla, la cuenta ya existe y se reenvía).
        Runnable sendIt = () -> mail.send(email, subject, body);
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { sendIt.run(); }
                    });
        } else {
            sendIt.run();
        }
    }

    /** Reenvía un PIN nuevo a una cuenta que sigue sin verificar. */
    public void resend(String email) {
        Map<String, Object> row = loadByEmail(email);
        if (((Number) row.get("ev")).intValue() == 1) {
            return; // ya verificada → nada que reenviar
        }
        start((String) row.get("id"), (String) row.get("email"), (String) row.get("display_name"));
    }

    /** Verifica el PIN. Devuelve true si la cuenta queda verificada. */
    public boolean verify(String email, String pin) {
        Map<String, Object> row = loadByEmail(email);
        if (((Number) row.get("ev")).intValue() == 1) {
            return true; // idempotente: ya estaba verificada
        }
        Object expObj = row.get("exp");
        String hash = (String) row.get("hash");
        int attempts = ((Number) row.get("attempts")).intValue();
        if (hash == null || expObj == null) {
            throw bad("No hay una verificación pendiente. Pide un código nuevo.");
        }
        if (attempts >= MAX_ATTEMPTS) {
            throw bad("Demasiados intentos. Pide un código nuevo.");
        }
        Instant expires = ((Timestamp) expObj).toInstant();
        if (Instant.now().isAfter(expires)) {
            throw bad("El código ha caducado. Pide uno nuevo.");
        }
        if (pin == null || !encoder.matches(pin.trim(), hash)) {
            jdbc.update("UPDATE user_accounts SET verification_attempts = verification_attempts + 1 WHERE id = ?",
                    row.get("id"));
            return false;
        }
        jdbc.update("""
                UPDATE user_accounts
                   SET email_verified = 1, verification_pin_hash = NULL,
                       verification_expires_at = NULL, verification_attempts = 0
                 WHERE id = ?
                """, row.get("id"));
        return true;
    }

    private Map<String, Object> loadByEmail(String email) {
        try {
            return jdbc.queryForMap("""
                    SELECT id, email, display_name,
                           email_verified AS ev,
                           verification_pin_hash AS hash,
                           verification_expires_at AS exp,
                           verification_attempts AS attempts
                      FROM user_accounts
                     WHERE email = ?
                    """, email == null ? null : email.trim().toLowerCase());
        } catch (org.springframework.dao.EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No existe una cuenta con ese email.");
        }
    }

    private static ResponseStatusException bad(String m) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, m);
    }
}
