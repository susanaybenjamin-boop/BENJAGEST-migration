package com.benjagest.backend.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Lee el header Authorization: Bearer ... en cada peticion, valida el
 * JWT y, si es valido y es de tipo "access", construye el principal
 * AuthenticatedUser y lo deposita en el SecurityContext.
 *
 * Si el header falta o el token no vale, NO lanza error: simplemente
 * deja el SecurityContext vacio. Luego SecurityConfig decidira si la
 * ruta es publica o no.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;

    public JwtAuthenticationFilter(JwtService jwtService) {
        this.jwtService = jwtService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                     HttpServletResponse response,
                                     FilterChain chain) throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            String token = header.substring(BEARER_PREFIX.length()).trim();
            try {
                Claims claims = jwtService.parseAndValidate(token);
                if (jwtService.isAccessToken(claims)) {
                    AuthenticatedUser user = new AuthenticatedUser(
                            claims.getSubject(),
                            claims.get(JwtService.CLAIM_EMAIL, String.class),
                            claims.get(JwtService.CLAIM_DISPLAY_NAME, String.class),
                            claims.get(JwtService.CLAIM_GLOBAL_ROLE, String.class),
                            claims.get(JwtService.CLAIM_ACTIVE_COMPANY, String.class),
                            claims.get(JwtService.CLAIM_ROLE_IN_COMPANY, String.class)
                    );
                    UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                            user,
                            null,
                            List.of(
                                    new SimpleGrantedAuthority("ROLE_" + nullSafe(user.globalRole())),
                                    new SimpleGrantedAuthority("COMPANY_ROLE_" + nullSafe(user.roleInActiveCompany()))
                            )
                    );
                    SecurityContextHolder.getContext().setAuthentication(auth);
                }
            } catch (Exception ignored) {
                // Token invalido o expirado: dejamos el context vacio.
                // La ruta protegida respondera 401 mas adelante.
                SecurityContextHolder.clearContext();
            }
        }
        chain.doFilter(request, response);
    }

    private String nullSafe(String value) {
        return value == null ? "UNKNOWN" : value;
    }
}
