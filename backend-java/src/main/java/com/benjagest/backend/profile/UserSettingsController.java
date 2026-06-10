package com.benjagest.backend.profile;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * PORT-3 PERFIL — REST API de preferencias por usuario. Cualquier rol
 * tiene su perfil. Bajo {@code /api/profile/settings}.
 */
@RestController
@RequestMapping("/api/profile/settings")
@RequiresModule("profile")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class UserSettingsController {

    private final UserSettingsService service;

    public UserSettingsController(UserSettingsService service) {
        this.service = service;
    }

    @GetMapping
    public UserSettingsService.UserSettings get() {
        return service.getCurrent();
    }

    @PutMapping
    public UserSettingsService.UserSettings save(
            @RequestBody UserSettingsService.UpdateRequest req) {
        return service.save(req);
    }
}
