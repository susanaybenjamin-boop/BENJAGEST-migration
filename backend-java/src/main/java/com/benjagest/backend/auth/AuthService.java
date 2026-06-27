package com.benjagest.backend.auth;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.dto.LoginRequest;
import com.benjagest.backend.auth.dto.LoginResponse;
import com.benjagest.backend.auth.dto.MeResponse;
import com.benjagest.backend.auth.dto.MembershipResponse;
import io.jsonwebtoken.Claims;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de autenticacion:
 *   - login: verifica email + password, decide empresa activa,
 *            emite par de tokens (access + refresh).
 *   - refresh: a partir de un refresh token valido, emite un access
 *            nuevo. No emite nuevo refresh (rotacion = futuro slice).
 *   - me: lee el usuario del SecurityContext y devuelve snapshot
 *            con sus memberships.
 *   - switchCompany: cambia la empresa activa y emite tokens nuevos
 *            con el nuevo claim activeCompanyId.
 */
@Service
public class AuthService {

    private final AuthRepository repository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;
    private final RevokedRefreshTokenRepository revokedTokens;

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService,
                       RevokedRefreshTokenRepository revokedTokens) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.revokedTokens = revokedTokens;
    }

    /** ¿Hay alguna cuenta en el sistema? (arranque: registro vs login). */
    public boolean hasAnyAccount() {
        return repository.hasAnyAccount();
    }

    /** ¿Hay alguna asesoría? (multi-puesto solo para asesoría). */
    public boolean hasAdvisory() {
        return repository.hasAdvisoryCompany();
    }

    /**
     * Emite una sesión para un usuario por EMAIL, sin contraseña. Lo usa el alta/
     * login con Google (la identidad ya la verificó Google). Misma respuesta que
     * {@link #login}.
     */
    public LoginResponse issueSession(String email) {
        AuthRepository.UserRecord user = repository.findUserByEmail(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Usuario no encontrado"));
        List<AuthRepository.MembershipRecord> memberships = repository.findMembershipsForUser(user.id());
        if (memberships.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "El usuario no está vinculado a ninguna empresa");
        }
        AuthRepository.MembershipRecord primary = memberships.get(0);
        AuthenticatedUser authenticated = new AuthenticatedUser(
                user.id(), user.email(), user.displayName(), user.globalRole(),
                primary.companyId(), primary.roleName());
        auditService.recordLoginOk(user.id(), primary.companyId());
        return new LoginResponse(
                jwtService.createAccessToken(authenticated), jwtService.createRefreshToken(user.id()),
                jwtService.accessTtlSeconds(), user.id(), user.email(), user.displayName(),
                user.globalRole(), primary.companyId(), primary.roleName(), toMembershipDtos(memberships));
    }

    public LoginResponse login(LoginRequest request) {
        AuthRepository.UserRecord user = repository.findUserByEmail(request.email()).orElse(null);
        if (user == null) {
            auditService.recordLoginFail(request.email(), "USER_NOT_FOUND");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales no validas");
        }

        if (user.passwordHash() == null || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            auditService.recordLoginFail(request.email(), "BAD_PASSWORD");
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales no validas");
        }

        // REG-VERIFY — gate: email sin verificar no entra. El código EMAIL_NOT_VERIFIED
        // lo reconoce la UI para mostrar la pantalla del PIN + reenvío.
        if (!repository.isEmailVerified(user.id())) {
            auditService.recordLoginFail(request.email(), "EMAIL_NOT_VERIFIED");
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "EMAIL_NOT_VERIFIED");
        }

        List<AuthRepository.MembershipRecord> memberships = repository.findMembershipsForUser(user.id());
        if (memberships.isEmpty()) {
            auditService.recordLoginFail(request.email(), "NO_MEMBERSHIPS");
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "El usuario no esta vinculado a ninguna empresa"
            );
        }

        AuthRepository.MembershipRecord primary = memberships.get(0);
        AuthenticatedUser authenticated = new AuthenticatedUser(
                user.id(),
                user.email(),
                user.displayName(),
                user.globalRole(),
                primary.companyId(),
                primary.roleName()
        );

        auditService.recordLoginOk(user.id(), primary.companyId());
        return new LoginResponse(
                jwtService.createAccessToken(authenticated),
                jwtService.createRefreshToken(user.id()),
                jwtService.accessTtlSeconds(),
                user.id(),
                user.email(),
                user.displayName(),
                user.globalRole(),
                primary.companyId(),
                primary.roleName(),
                toMembershipDtos(memberships)
        );
    }

    public LoginResponse refresh(String refreshToken) {
        Claims claims;
        try {
            claims = jwtService.parseAndValidate(refreshToken);
        } catch (Exception exception) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token no valido");
        }
        if (!jwtService.isRefreshToken(claims)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Tipo de token incorrecto");
        }
        String jti = claims.getId();
        if (jti != null && revokedTokens.isRevoked(jti)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Refresh token revocado");
        }

        String userId = claims.getSubject();
        AuthRepository.UserRecord user = repository.findUserById(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Usuario inexistente"));
        List<AuthRepository.MembershipRecord> memberships = repository.findMembershipsForUser(user.id());
        if (memberships.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Usuario sin memberships");
        }

        AuthRepository.MembershipRecord primary = memberships.get(0);
        AuthenticatedUser authenticated = new AuthenticatedUser(
                user.id(),
                user.email(),
                user.displayName(),
                user.globalRole(),
                primary.companyId(),
                primary.roleName()
        );

        return new LoginResponse(
                jwtService.createAccessToken(authenticated),
                refreshToken, // no rotamos: el cliente reusa el refresh hasta su caducidad
                jwtService.accessTtlSeconds(),
                user.id(),
                user.email(),
                user.displayName(),
                user.globalRole(),
                primary.companyId(),
                primary.roleName(),
                toMembershipDtos(memberships)
        );
    }

    public MeResponse me(AuthenticatedUser current) {
        List<AuthRepository.MembershipRecord> memberships = repository.findMembershipsForUser(current.userId());
        return new MeResponse(
                current.userId(),
                current.email(),
                current.displayName(),
                current.globalRole(),
                current.activeCompanyId(),
                current.roleInActiveCompany(),
                toMembershipDtos(memberships)
        );
    }

    public LoginResponse switchCompany(AuthenticatedUser current, String targetCompanyId) {
        AuthRepository.MembershipRecord membership = repository.findMembership(current.userId(), targetCompanyId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.FORBIDDEN,
                        "No tienes acceso a esa empresa"
                ));

        AuthenticatedUser updated = new AuthenticatedUser(
                current.userId(),
                current.email(),
                current.displayName(),
                current.globalRole(),
                membership.companyId(),
                membership.roleName()
        );

        List<AuthRepository.MembershipRecord> memberships = repository.findMembershipsForUser(current.userId());
        auditService.recordCompanySwitched(current.userId(), current.activeCompanyId(), membership.companyId());
        return new LoginResponse(
                jwtService.createAccessToken(updated),
                jwtService.createRefreshToken(current.userId()),
                jwtService.accessTtlSeconds(),
                current.userId(),
                current.email(),
                current.displayName(),
                current.globalRole(),
                membership.companyId(),
                membership.roleName(),
                toMembershipDtos(memberships)
        );
    }

    /**
     * Revoca un refresh token. Si el token es valido, anade su jti a la
     * denylist; si esta caducado o malformado lo ignoramos (de todos
     * modos ya no sirve). Idempotente.
     */
    public void logout(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            return;
        }
        try {
            Claims claims = jwtService.parseAndValidate(refreshToken);
            if (jwtService.isRefreshToken(claims) && claims.getId() != null) {
                revokedTokens.revoke(claims.getId(), claims.getSubject());
            }
        } catch (Exception ignored) {
            // Token invalido o caducado: nada que revocar.
        }
    }

    private List<MembershipResponse> toMembershipDtos(List<AuthRepository.MembershipRecord> memberships) {
        return memberships.stream()
                .map(m -> new MembershipResponse(
                        m.companyId(),
                        m.companyLegalName(),
                        m.companyTradeName(),
                        m.companyType(),
                        m.roleName()
                ))
                .toList();
    }
}
