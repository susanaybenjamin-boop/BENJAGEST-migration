package com.benjagest.backend.profile;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * PORT-3 PERFIL — Preferencias personales del usuario logueado.
 *
 * <p>Endpoint single-row: cada usuario tiene una fila en
 * {@code user_settings}. Si no existe, los GET devuelven defaults y los
 * PUT hacen UPSERT. El PIN para el bloqueo por inactividad (PORT-3 LOCK)
 * vive en {@code user_accounts.pin_hash}; aquí solo el timeout.
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
                SELECT user_id, language, pin_timeout_min, screensaver_style,
                       ai_enabled, COALESCE(avatar_path, '') AS avatar_path,
                       COALESCE(workday_template, '') AS workday_template
                  FROM user_settings WHERE user_id = ?
                """, rs -> {
                    if (rs.next()) {
                        return new UserSettings(
                                rs.getString("user_id"),
                                rs.getString("language"),
                                rs.getInt("pin_timeout_min"),
                                rs.getString("screensaver_style"),
                                rs.getBoolean("ai_enabled"),
                                rs.getString("avatar_path"),
                                rs.getString("workday_template"));
                    }
                    return new UserSettings(user.userId(), "es", 0, "clock",
                            false, "", "");
                }, user.userId());
    }

    /** UPSERT atómico. */
    @Transactional
    public UserSettings save(UpdateRequest req) {
        AuthenticatedUser user = currentUserService.require();
        String language = req.language() == null || req.language().isBlank()
                ? "es" : req.language().trim().toLowerCase();
        if (!language.equals("es") && !language.equals("en")) language = "es";
        int pinTimeout = req.pinTimeoutMin() == null ? 0
                : Math.max(0, Math.min(120, req.pinTimeoutMin()));
        String style = req.screensaverStyle() == null || req.screensaverStyle().isBlank()
                ? "clock" : req.screensaverStyle().trim();
        boolean ai = req.aiEnabled() != null && req.aiEnabled();
        String avatar = req.avatarPath() == null ? null : req.avatarPath().trim();
        String workday = req.workdayTemplate() == null ? null : req.workdayTemplate().trim();
        jdbcTemplate.update("""
                INSERT INTO user_settings (user_id, language, pin_timeout_min,
                                            screensaver_style, ai_enabled,
                                            avatar_path, workday_template)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE
                    language = VALUES(language),
                    pin_timeout_min = VALUES(pin_timeout_min),
                    screensaver_style = VALUES(screensaver_style),
                    ai_enabled = VALUES(ai_enabled),
                    avatar_path = VALUES(avatar_path),
                    workday_template = VALUES(workday_template)
                """, user.userId(), language, pinTimeout, style, ai, avatar, workday);
        return getCurrent();
    }

    public record UserSettings(
            String userId,
            String language,
            int pinTimeoutMin,
            String screensaverStyle,
            boolean aiEnabled,
            String avatarPath,
            String workdayTemplate
    ) {}

    public record UpdateRequest(
            String language,
            Integer pinTimeoutMin,
            String screensaverStyle,
            Boolean aiEnabled,
            String avatarPath,
            String workdayTemplate
    ) {}
}
