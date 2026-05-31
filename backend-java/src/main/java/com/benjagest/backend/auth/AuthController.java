package com.benjagest.backend.auth;

import com.benjagest.backend.auth.dto.LoginRequest;
import com.benjagest.backend.auth.dto.LoginResponse;
import com.benjagest.backend.auth.dto.MeResponse;
import com.benjagest.backend.auth.dto.RefreshRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST de autenticacion principal (email + password + JWT).
 *
 * El PIN sigue colgando del workspace/AuthController (/api/auth/pin)
 * sin tocar, para fichaje en kiosko y futuro desbloqueo de pantalla.
 *
 * Rutas:
 *   POST /api/auth/login              login con email + password
 *   POST /api/auth/refresh            renovar access token con refresh
 *   GET  /api/auth/me                 datos del usuario logueado
 *   POST /api/auth/switch-company/{id} cambiar empresa activa
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final CurrentUserService currentUserService;

    public AuthController(AuthService authService, CurrentUserService currentUserService) {
        this.authService = authService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @PostMapping("/refresh")
    public LoginResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    @GetMapping("/me")
    public MeResponse me() {
        return authService.me(currentUserService.require());
    }

    @PostMapping("/switch-company/{companyId}")
    public LoginResponse switchCompany(@PathVariable("companyId") String companyId) {
        return authService.switchCompany(currentUserService.require(), companyId);
    }
}
