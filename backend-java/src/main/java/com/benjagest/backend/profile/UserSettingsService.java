package com.benjagest.backend.profile;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PORT-3 PERFIL — Preferencias personales del usuario logueado.
 *
 * <p>Tras la revisión 2026-06-10 tarde, V87 dropeó las columnas
 * {@code language}, {@code ai_enabled}, {@code avatar_path} y
 * {@code workday_template} de {@code user_settings} porque ya no
 * tienen sentido:
 * <ul>
 *   <li>idioma → vive en el botón ES/EN del header.</li>
 *   <li>AI Copilot → no se hará en BENJAGEST.</li>
 *   <li>avatar → consolidado en el logo de empresa.</li>
 *   <li>plantilla jornada → no aplica para PORT-2.</li>
 * </ul>
 *
 * <p>Lo que queda en {@code user_settings}: {@code pin_timeout_min}
 * (minutos de inactividad antes del lock) y {@code screensaver_style}
 * (clock/logo/dark/carousel).
 */
@Service
public class UserSettingsService {

    private final JdbcTemplate jdbcTemplate;
    private final CurrentUserService currentUserService;

    public UserSettingsService(JdbcTemplate jdbcTemplate,
                                CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.currentUserService = currentUserService;
    }

    /**
     * Devuelve las preferencias del usuario actual. Si la fila no
     * existe, devuelve un record con valores por defecto (sin crearla).
     */
    public UserSettings getCurrent() {
        AuthenticatedUser user = currentUserService.require();
        return jdbcTemplate.query("""
                SELECT user_id, pin_timeout_min, screensaver_style
                  FROM user_settings WHERE user_id = ?
                """, rs -> {
                    if (rs.next()) {
                        return new UserSettings(
                                rs.getString("user_id"),
                                rs.getInt("pin_timeout_min"),
                                rs.getString("screensaver_style"));
                    }
                    return new UserSettings(user.userId(), 0, "clock");
                }, user.userId());
    }

    /** UPSERT atómico. */
    @Transactional
    public UserSettings save(UpdateRequest req) {
        AuthenticatedUser user = currentUserService.require();
        int pinTimeout = req.pinTimeoutMin() == null ? 0
                : Math.max(0, Math.min(120, req.pinTimeoutMin()));
        String style = req.screensaverStyle() == null || req.screensaverStyle().isBlank()
                ? "clock" : req.screensaverStyle().trim();
        jdbcTemplate.update("""
                INSERT INTO user_settings (user_id, pin_timeout_min, screensaver_style)
                VALUES (?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    pin_timeout_min = VALUES(pin_timeout_min),
                    screensaver_style = VALUES(screensaver_style)
                """, user.userId(), pinTimeout, style);
        return getCurrent();
    }

    public record UserSettings(
            String userId,
            int pinTimeoutMin,
            String screensaverStyle
    ) {}

    public record UpdateRequest(
            Integer pinTimeoutMin,
            String screensaverStyle
    ) {}
}
