package com.benjagest.backend.settings;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCrypt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * PORT-4 SESSION (sesión 2026-06-10) — PIN de sesión.
 *
 * <p>Diferente del PIN de vinculación (que vive en
 * {@code device_tokens.pin_hash} y empareja un dispositivo con la app
 * multi-puesto). Este PIN se usa solo para desbloquear tras un
 * timeout de inactividad — sin él el lock screen no tiene cómo
 * verificar.
 *
 * <p>Tipos de usuario:
 * <ul>
 *   <li><b>Asesoría con dispositivo emparejado</b>: el frontend puede
 *       reusar el PIN de vinculación (lo manda como newPin → guardamos
 *       hash aquí también; usuario ve "PIN sincronizado con dispositivo").</li>
 *   <li><b>Empresario sin vinculación</b>: define un PIN propio.</li>
 * </ul>
 */
@Service
public class SessionPinService {

    private static final int MIN_LEN = 4;
    private static final int MAX_LEN = 8;

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public SessionPinService(JdbcTemplate jdbcTemplate,
                              CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    public Status status() {
        AuthenticatedUser u = currentUserService.require();
        String existing = jdbcTemplate.query(
                "SELECT session_pin_hash FROM user_accounts WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                u.userId());
        return new Status(existing != null && !existing.isBlank());
    }

    /**
     * Define o cambia el PIN. Si ya hay uno, {@code currentPin} es
     * obligatorio y debe coincidir.
     */
    @Transactional
    public void set(String currentPin, String newPin) {
        if (newPin == null || newPin.length() < MIN_LEN || newPin.length() > MAX_LEN
                || !newPin.matches("\\d+")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El PIN debe ser numérico, entre " + MIN_LEN + " y " + MAX_LEN + " dígitos.");
        }
        AuthenticatedUser u = currentUserService.require();
        String existing = jdbcTemplate.query(
                "SELECT session_pin_hash FROM user_accounts WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                u.userId());
        if (existing != null && !existing.isBlank()) {
            if (currentPin == null || currentPin.isBlank()
                    || !BCrypt.checkpw(currentPin, existing)) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "El PIN actual no coincide.");
            }
        }
        String hash = BCrypt.hashpw(newPin, BCrypt.gensalt(10));
        jdbcTemplate.update(
                "UPDATE user_accounts SET session_pin_hash = ? WHERE id = ?",
                hash, u.userId());
    }

    /**
     * Borra el PIN — el lock screen ya no tendrá cómo verificar; lo
     * práctico es ponerle también timeout=0.
     */
    @Transactional
    public void clear(String currentPin) {
        AuthenticatedUser u = currentUserService.require();
        String existing = jdbcTemplate.query(
                "SELECT session_pin_hash FROM user_accounts WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                u.userId());
        if (existing == null || existing.isBlank()) return;
        if (currentPin == null || currentPin.isBlank()
                || !BCrypt.checkpw(currentPin, existing)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "El PIN actual no coincide.");
        }
        jdbcTemplate.update(
                "UPDATE user_accounts SET session_pin_hash = NULL WHERE id = ?",
                u.userId());
    }

    /**
     * Verifica un PIN entrante (usado por el lock screen para
     * desbloquear). True si coincide. Sin tirar excepciones — el lock
     * screen muestra error si false.
     */
    public boolean verify(String pin) {
        if (pin == null || pin.isBlank()) return false;
        AuthenticatedUser u = currentUserService.require();
        String existing = jdbcTemplate.query(
                "SELECT session_pin_hash FROM user_accounts WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                u.userId());
        if (existing == null || existing.isBlank()) return false;
        return BCrypt.checkpw(pin, existing);
    }

    public record Status(boolean configured) {}
    public record SetRequest(String currentPin, String newPin) {}
    public record VerifyRequest(String pin) {}
    public record VerifyResponse(boolean valid) {}
}
