package com.benjagest.backend.settings;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.profile.UserSettingsService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PORT-4 SESSION — Configuración de sesión personal: timeout, PIN de
 * sesión y salvapantallas. Bajo {@code /api/settings/session}.
 *
 * <p>Las preferencias (timeout + screensaver) viven en
 * {@link UserSettingsService} (V84). El PIN vive en
 * {@link SessionPinService} (V87, {@code user_accounts.session_pin_hash}).
 */
@RestController
@RequestMapping("/api/settings/session")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class SessionController {

    private final UserSettingsService userSettings;
    private final SessionPinService sessionPin;

    public SessionController(UserSettingsService userSettings,
                              SessionPinService sessionPin) {
        this.userSettings = userSettings;
        this.sessionPin = sessionPin;
    }

    @GetMapping
    public SessionStatus status() {
        UserSettingsService.UserSettings s = userSettings.getCurrent();
        SessionPinService.Status pin = sessionPin.status();
        return new SessionStatus(s.pinTimeoutMin(), s.screensaverStyle(),
                pin.configured());
    }

    @PutMapping
    public SessionStatus save(@RequestBody UpdateRequest req) {
        // V87 simplificó UserSettings: solo quedan pin_timeout_min y
        // screensaver_style. Idioma vive en el header, AI no se hará,
        // avatar = logo empresa, workday_template no aplica.
        UserSettingsService.UpdateRequest u = new UserSettingsService.UpdateRequest(
                req.pinTimeoutMin(),
                req.screensaverStyle());
        userSettings.save(u);
        return status();
    }

    @PostMapping("/pin")
    public void setPin(@RequestBody SessionPinService.SetRequest req) {
        sessionPin.set(req.currentPin(), req.newPin());
    }

    @DeleteMapping("/pin")
    public void clearPin(@RequestBody(required = false) SessionPinService.SetRequest req) {
        String current = req == null ? null : req.currentPin();
        sessionPin.clear(current);
    }

    @PostMapping("/pin/verify")
    public SessionPinService.VerifyResponse verifyPin(
            @RequestBody SessionPinService.VerifyRequest req) {
        return new SessionPinService.VerifyResponse(sessionPin.verify(req.pin()));
    }

    public record SessionStatus(int pinTimeoutMin, String screensaverStyle,
                                 boolean pinConfigured) {}

    public record UpdateRequest(Integer pinTimeoutMin, String screensaverStyle) {}
}
