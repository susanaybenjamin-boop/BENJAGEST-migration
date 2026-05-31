package com.benjagest.backend.auth;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Lee el usuario autenticado del SecurityContext de Spring Security.
 * Lo deposita ahi el JwtAuthenticationFilter cuando el header
 * Authorization: Bearer ... lleva un JWT valido.
 */
@Service
public class CurrentUserService {

    public AuthenticatedUser require() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AuthenticatedUser user)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No hay sesion activa");
        }
        return user;
    }
}
