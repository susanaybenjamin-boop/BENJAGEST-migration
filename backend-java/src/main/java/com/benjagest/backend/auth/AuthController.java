package com.benjagest.backend.auth;

import com.benjagest.backend.auth.dto.LoginRequest;
import com.benjagest.backend.auth.dto.LoginResponse;
import com.benjagest.backend.auth.dto.LogoutRequest;
import com.benjagest.backend.auth.dto.MeResponse;
import com.benjagest.backend.auth.dto.RefreshRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
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
    private final RegisterService registerService;
    private final CurrentUserService currentUserService;

    public AuthController(AuthService authService, RegisterService registerService,
                          CurrentUserService currentUserService) {
        this.authService = authService;
        this.registerService = registerService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    /** Bloque REGISTRO — alta de cuenta (asesoría o empresa) + auto-login. */
    @PostMapping("/register")
    public LoginResponse register(@Valid @RequestBody com.benjagest.backend.auth.dto.RegisterRequest request) {
        return registerService.register(request);
    }

    /**
     * Estado de arranque (público): {@code hasAccounts=false} en una instalación
     * nueva → la app muestra el REGISTRO en vez del login.
     */
    @GetMapping("/bootstrap-status")
    public java.util.Map<String, Boolean> bootstrapStatus() {
        return java.util.Map.of(
                "hasAccounts", authService.hasAnyAccount(),
                "hasAdvisory", authService.hasAdvisory());
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

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void logout(@RequestBody(required = false) LogoutRequest request) {
        authService.logout(request == null ? null : request.refreshToken());
    }
}
