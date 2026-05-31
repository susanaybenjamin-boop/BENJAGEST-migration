package com.benjagest.backend.auth;

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

    public AuthService(AuthRepository repository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.repository = repository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public LoginResponse login(LoginRequest request) {
        AuthRepository.UserRecord user = repository.findUserByEmail(request.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales no validas"));

        if (user.passwordHash() == null || !passwordEncoder.matches(request.password(), user.passwordHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Credenciales no validas");
        }

        List<AuthRepository.MembershipRecord> memberships = repository.findMembershipsForUser(user.id());
        if (memberships.isEmpty()) {
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
