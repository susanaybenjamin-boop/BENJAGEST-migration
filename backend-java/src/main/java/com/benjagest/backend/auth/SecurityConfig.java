package com.benjagest.backend.auth;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

/**
 * Configuracion de Spring Security:
 *   - CSRF desactivado (API REST, sin formularios HTML).
 *   - Sesiones STATELESS (no usamos JSESSIONID).
 *   - Rutas publicas: /api/auth/login, /api/auth/refresh, /api/auth/pin
 *     (PIN legacy), /actuator/health, /error.
 *   - El resto require JWT valido (lo aplica JwtAuthenticationFilter).
 *
 * El password encoder es BCrypt 10 (default), que es lo que usamos
 * para sembrar las contrasenas en V8.
 */
@Configuration
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtFilter) {
        this.jwtFilter = jwtFilter;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authz -> authz
                        .requestMatchers(
                                "/api/auth/login",
                                "/api/auth/register",
                                "/api/auth/bootstrap-status",
                                "/api/auth/google/config",
                                "/api/auth/google/login",
                                "/api/auth/google/register",
                                "/api/auth/refresh",
                                "/api/auth/logout",
                                "/api/auth/pin",
                                // L4-2 — Multi-puesto PIN. /pair acepta
                                // email+password del OWNER y /pin-login
                                // acepta device_secret+PIN. Las dos son
                                // PRE-JWT: aún no hay sesión.
                                "/api/auth/devices/pair",
                                "/api/auth/pin-login",
                                "/api/health",
                                "/actuator/health",
                                // RD 8/2019 art. 35.8: verificación pública por CSV
                                // — un tercero (Inspección de Trabajo, juzgado…)
                                // debe poder validar un fichaje sin credenciales.
                                "/api/public/timeclock/verify",
                                // TPB Magic Link (V104): el cliente sin cuenta
                                // accede via enlace email para leer y firmar
                                // el acuerdo. Token unico + OTP en la URL.
                                "/api/public/tpb/**",
                                // FM-2: kiosco de fichaje. activate canjea el QR;
                                // la sesión (config/identify/estado/fichaje) se
                                // valida con KioskToken (KioskTokenInterceptor),
                                // no con JWT de usuario.
                                "/api/public/kiosk/**",
                                // MEMP-1: PWA del empleado. activate canjea la
                                // invitación y empareja el móvil; luego el empleado
                                // entra por /api/auth/pin-login (ya público).
                                "/api/public/empleado/**",
                                "/error"
                        ).permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}
