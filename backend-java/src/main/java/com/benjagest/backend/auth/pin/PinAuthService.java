package com.benjagest.backend.auth.pin;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthRepository;
import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.JwtService;
import com.benjagest.backend.auth.dto.LoginResponse;
import com.benjagest.backend.auth.dto.MembershipResponse;
import com.benjagest.backend.auth.pin.dto.PinLoginRequest;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * L4-2 — Login por PIN y gestión de PINs.
 *
 * <p>Login: resuelve dispositivo → asesoría → enumera empleados con
 * app_access=TRUE → bcrypt verify del PIN hasta encontrar el match.
 * Devuelve la misma {@link LoginResponse} que el login por email,
 * con JWT estándar — el resto del backend no distingue.
 *
 * <p>Cambio de PIN: SOLO el OWNER (acordado con Benjamin 2026-06-07).
 * Valida unicidad de PIN entre los empleados activos de la asesoría
 * con app_access (bcrypt no permite UK por hash, lo hacemos in-memory).
 *
 * <p>Anti-bruteforce: contador en memoria por device_id. 3 fallos →
 * bloqueo 30s. No es persistente: si reiniciamos el backend se resetea.
 * Suficiente para v1; mover a Redis si en producción hay clúster.
 */
@Service
public class PinAuthService {

    /** Intentos fallidos por device antes de bloqueo. */
    static final int MAX_FAILED_ATTEMPTS = 3;
    /** Duración del bloqueo tras superar los intentos. */
    static final long BLOCK_DURATION_SECONDS = 30;

    private final DeviceTokenService deviceTokenService;
    private final JdbcTemplate jdbcTemplate;
    private final PasswordEncoder passwordEncoder;
    private final AuthRepository authRepository;
    private final JwtService jwtService;
    private final AuditService auditService;

    private final Map<String, FailRecord> failByDevice = new ConcurrentHashMap<>();

    public PinAuthService(DeviceTokenService deviceTokenService,
                           JdbcTemplate jdbcTemplate,
                           PasswordEncoder passwordEncoder,
                           AuthRepository authRepository,
                           JwtService jwtService,
                           AuditService auditService) {
        this.deviceTokenService = deviceTokenService;
        this.jdbcTemplate = jdbcTemplate;
        this.passwordEncoder = passwordEncoder;
        this.authRepository = authRepository;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    // ============================================================
    //  Login por PIN
    // ============================================================

    @Transactional
    public LoginResponse loginByPin(PinLoginRequest req) {
        DeviceToken device = deviceTokenService.verifyAndTouch(req.deviceSecret())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Dispositivo no reconocido — empareja este equipo otra vez."));

        // Rate limit: si el device está bloqueado, fuera.
        FailRecord fr = failByDevice.get(device.id());
        if (fr != null && fr.blockedUntil != null
                && fr.blockedUntil.isAfter(Instant.now())) {
            long seconds = java.time.Duration.between(Instant.now(), fr.blockedUntil).getSeconds() + 1;
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,
                    "Demasiados intentos. Espera " + seconds + " s antes de reintentar.");
        }

        List<EmployeeAppRow> candidates = listAppAccessEmployees(device.companyId());
        EmployeeAppRow match = null;
        for (EmployeeAppRow e : candidates) {
            if (e.pinHash() == null) continue;
            if (passwordEncoder.matches(req.pin(), e.pinHash())) {
                match = e;
                break;
            }
        }

        if (match == null) {
            registerFailure(device.id());
            auditService.recordGeneric(device.companyId(), null,
                    "PIN_LOGIN_FAIL", "device_token", device.id(), "FAIL", null);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "PIN incorrecto");
        }

        // Login OK — limpia el contador del device.
        failByDevice.remove(device.id());

        if (match.userId() == null || match.userId().isBlank()) {
            // Inconsistencia: empleado con app_access=TRUE pero sin
            // user_account asociado. L4-4 garantiza que no debería ocurrir,
            // pero si pasa avisamos en lugar de petar.
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "El empleado no tiene cuenta de usuario vinculada — contacta con el OWNER.");
        }

        AuthRepository.UserRecord user = authRepository.findUserById(match.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Usuario no encontrado"));
        List<AuthRepository.MembershipRecord> memberships =
                authRepository.findMembershipsForUser(user.id());

        // Buscamos la membership en la company del device — el empleado
        // PUEDE tener varias memberships (es OWNER de su asesoría y miembro
        // en otra, p. ej.). El device manda qué empresa es "su sitio".
        AuthRepository.MembershipRecord active = memberships.stream()
                .filter(m -> device.companyId().equals(m.companyId()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "El empleado no tiene acceso a esta asesoría."));

        AuthenticatedUser authenticated = new AuthenticatedUser(
                user.id(), user.email(), user.displayName(), user.globalRole(),
                active.companyId(), active.roleName());

        auditService.recordGeneric(device.companyId(), user.id(),
                "PIN_LOGIN_OK", "device_token", device.id(), "OK", null);

        return new LoginResponse(
                jwtService.createAccessToken(authenticated),
                jwtService.createRefreshToken(user.id()),
                jwtService.accessTtlSeconds(),
                user.id(),
                user.email(),
                user.displayName(),
                user.globalRole(),
                active.companyId(),
                active.roleName(),
                memberships.stream()
                        .map(m -> new MembershipResponse(
                                m.companyId(), m.companyLegalName(),
                                m.companyTradeName(), m.companyType(), m.roleName()))
                        .toList()
        );
    }

    // ============================================================
    //  Cambio de PIN (solo OWNER)
    // ============================================================

    /**
     * Asigna o cambia el PIN de un empleado. {@code byOwnerUserId} debe
     * ser OWNER de la misma asesoría a la que pertenece el empleado.
     */
    @Transactional
    public void setEmployeePin(String employeeId, String newPlainPin, String byOwnerUserId) {
        EmployeeAppRow target = findEmployeeRow(employeeId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Empleado no encontrado"));

        if (!isOwnerOf(byOwnerUserId, target.companyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Solo el OWNER puede cambiar PINs.");
        }

        if (!target.appAccess()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Activa primero 'Acceso a la app' para este empleado.");
        }

        // Comprobar colisión: no debe haber otro empleado con app_access=TRUE
        // en la misma asesoría cuyo bcrypt matchee este PIN.
        List<EmployeeAppRow> others = listAppAccessEmployees(target.companyId());
        for (EmployeeAppRow e : others) {
            if (e.id().equals(target.id())) continue;
            if (e.pinHash() == null) continue;
            if (passwordEncoder.matches(newPlainPin, e.pinHash())) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Otro empleado ya tiene ese PIN en esta asesoría — elige otro.");
            }
        }

        String newHash = passwordEncoder.encode(newPlainPin);
        jdbcTemplate.update("UPDATE employees SET pin_hash = ? WHERE id = ?",
                newHash, employeeId);

        auditService.recordGeneric(target.companyId(), byOwnerUserId,
                "EMPLOYEE_PIN_CHANGED", "employee", employeeId, "OK", null);
    }

    // ============================================================
    //  Helpers internos
    // ============================================================

    private List<EmployeeAppRow> listAppAccessEmployees(String companyId) {
        return jdbcTemplate.query("""
                SELECT id, company_id, user_id, pin_hash, app_access, full_name
                  FROM employees
                 WHERE company_id = ?
                   AND app_access = TRUE
                   AND active = TRUE
                """, (rs, i) -> new EmployeeAppRow(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("user_id"),
                rs.getString("pin_hash"),
                rs.getBoolean("app_access"),
                rs.getString("full_name")), companyId);
    }

    private Optional<EmployeeAppRow> findEmployeeRow(String employeeId) {
        List<EmployeeAppRow> rows = jdbcTemplate.query("""
                SELECT id, company_id, user_id, pin_hash, app_access, full_name
                  FROM employees
                 WHERE id = ?
                """, (rs, i) -> new EmployeeAppRow(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("user_id"),
                rs.getString("pin_hash"),
                rs.getBoolean("app_access"),
                rs.getString("full_name")), employeeId);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private boolean isOwnerOf(String userId, String companyId) {
        Integer n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM company_memberships
                 WHERE company_id = ? AND user_id = ?
                   AND role_name = 'OWNER' AND active = TRUE
                """, Integer.class, companyId, userId);
        return n != null && n > 0;
    }

    private void registerFailure(String deviceId) {
        failByDevice.compute(deviceId, (k, prev) -> {
            int count = prev == null ? 1 : prev.attempts + 1;
            Instant blockedUntil = count >= MAX_FAILED_ATTEMPTS
                    ? Instant.now().plusSeconds(BLOCK_DURATION_SECONDS)
                    : null;
            return new FailRecord(count, blockedUntil);
        });
    }

    /** Fila proyectada del empleado para los flujos PIN, sin pasar
     *  por EmployeeService (que tiene su propio scope multi-tenant). */
    private record EmployeeAppRow(
            String id, String companyId, String userId,
            String pinHash, boolean appAccess, String fullName) {}

    /** Contador de fallos por device. Estado en memoria; aceptamos
     *  reset por reinicio del backend (slice 1). */
    private record FailRecord(int attempts, Instant blockedUntil) {}
}
