package com.benjagest.backend.workspace;

import jakarta.validation.Valid;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Auth legacy via PIN, mantenida para fichaje en kiosko y futuro
 * desbloqueo de pantalla (decision 6 de architecture memory).
 * El login principal de la app pasa por auth/AuthController (JWT).
 */
@RestController
@RequestMapping("/api/auth")
public class PinAuthController {

    private final WorkspaceRepository repository;

    public PinAuthController(WorkspaceRepository repository) {
        this.repository = repository;
    }

    @PostMapping("/pin")
    public PinLoginResponse loginWithPin(@Valid @RequestBody PinLoginRequest request) {
        return repository.findEmployeeByPinHash(sha256(request.pin().trim()))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "PIN no reconocido"));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                hash.append(String.format("%02x", current));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
