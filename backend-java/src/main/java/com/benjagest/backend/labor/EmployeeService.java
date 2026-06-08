package com.benjagest.backend.labor;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.modules.RequiresModule;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.security.SecureRandom;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Servicio de Empleados. La tabla `employees` ya existia desde V2; en
 * V29 se enriquece con NUSS, direccion, IBAN, regimen SS, fecha de
 * alta, situacion familiar...
 *
 * Diseno de campos pensado para que el modelo 111/190 y las
 * comunicaciones con SS puedan tirar directamente.
 */
@Service
public class EmployeeService {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final CurrentUserService currentUserService;
    private final SecureRandom secureRandom = new SecureRandom();

    public EmployeeService(JdbcTemplate jdbcTemplate, TenantContext tenantContext,
                            PasswordEncoder passwordEncoder, AuditService auditService,
                            CurrentUserService currentUserService) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
        this.currentUserService = currentUserService;
    }

    public List<EmployeeView> list(boolean includeInactive) {
        return jdbcTemplate.query("""
                SELECT id, full_name, tax_identifier, social_security_number,
                       email, phone, birth_date, gender, marital_status,
                       dependent_children, dependent_disabled,
                       address_line, city, province, postal_code, country,
                       iban, work_type, ss_regime, hire_date, termination_date,
                       termination_reason, geolocation_enabled, active,
                       app_access, user_id,
                       pin_hash IS NOT NULL AS has_pin,
                       created_at, updated_at
                  FROM employees
                 WHERE company_id = ?
                   AND (? = TRUE OR active = TRUE)
                 ORDER BY active DESC, full_name
                """, this::mapView,
                tenantContext.getCurrentCompanyId(), includeInactive);
    }

    public EmployeeView get(String id) {
        return findById(id);
    }

    @Transactional
    public EmployeeView create(UpsertRequest req) {
        validate(req);
        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO employees (
                    id, company_id, full_name, tax_identifier, social_security_number,
                    email, phone, birth_date, gender, marital_status,
                    dependent_children, dependent_disabled,
                    address_line, city, province, postal_code, country,
                    iban, work_type, ss_regime, hire_date, termination_date,
                    termination_reason, geolocation_enabled, active
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id, tenantContext.getCurrentCompanyId(),
                req.fullName(), blank(req.taxIdentifier()), blank(req.socialSecurityNumber()),
                blank(req.email()), blank(req.phone()),
                req.birthDate(), blank(req.gender()), blank(req.maritalStatus()),
                req.dependentChildren(), req.dependentDisabled(),
                blank(req.addressLine()), blank(req.city()), blank(req.province()),
                blank(req.postalCode()), blank(req.country()),
                blank(req.iban()), blank(req.workType()), blank(req.ssRegime()),
                req.hireDate(), req.terminationDate(), blank(req.terminationReason()),
                req.geolocationEnabled() != null && req.geolocationEnabled(),
                req.active() == null || req.active()
        );
        // L4-4: si el caller pidió app_access=TRUE en el alta, lo
        // provisionamos en la misma transacción para que la fila
        // employees quede inmediatamente consistente.
        if (Boolean.TRUE.equals(req.appAccess())) {
            provisionAppAccess(id, blank(req.pin()), blank(req.roleInCompany()));
        }
        return findById(id);
    }

    @Transactional
    public EmployeeView update(String id, UpsertRequest req) {
        validate(req);
        // Leemos el estado previo del empleado para detectar transiciones
        // de app_access (FALSE→TRUE provisiona, TRUE→FALSE revoca).
        EmployeeView prev = findById(id);
        int n = jdbcTemplate.update("""
                UPDATE employees
                   SET full_name = ?, tax_identifier = ?, social_security_number = ?,
                       email = ?, phone = ?, birth_date = ?, gender = ?, marital_status = ?,
                       dependent_children = ?, dependent_disabled = ?,
                       address_line = ?, city = ?, province = ?, postal_code = ?, country = ?,
                       iban = ?, work_type = ?, ss_regime = ?, hire_date = ?,
                       termination_date = ?, termination_reason = ?,
                       geolocation_enabled = ?, active = ?
                 WHERE id = ? AND company_id = ?
                """,
                req.fullName(), blank(req.taxIdentifier()), blank(req.socialSecurityNumber()),
                blank(req.email()), blank(req.phone()),
                req.birthDate(), blank(req.gender()), blank(req.maritalStatus()),
                req.dependentChildren(), req.dependentDisabled(),
                blank(req.addressLine()), blank(req.city()), blank(req.province()),
                blank(req.postalCode()), blank(req.country()),
                blank(req.iban()), blank(req.workType()), blank(req.ssRegime()),
                req.hireDate(), req.terminationDate(), blank(req.terminationReason()),
                req.geolocationEnabled() != null && req.geolocationEnabled(),
                req.active() == null || req.active(),
                id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado");
        }
        // L4-4: gestionar transición de app_access. Si req.appAccess() es
        // null, no tocamos nada. Solo procesamos las TRANSICIONES.
        if (req.appAccess() != null) {
            if (req.appAccess() && !prev.appAccess()) {
                provisionAppAccess(id, blank(req.pin()), blank(req.roleInCompany()));
            } else if (!req.appAccess() && prev.appAccess()) {
                revokeAppAccess(id);
            } else if (req.appAccess() && prev.appAccess() && blank(req.pin()) != null) {
                // Sigue con acceso pero cambian el PIN (caso "el OWNER
                // resetea el PIN desde el editor de empleado").
                setEmployeePinChecked(id, blank(req.pin()));
            }
        }
        return findById(id);
    }

    /**
     * Soft delete: marca inactivo + fija termination_date si no hay.
     * No borramos porque hay foreign keys de contratos, fichajes, nominas.
     */
    @Transactional
    public void delete(String id) {
        int n = jdbcTemplate.update("""
                UPDATE employees
                   SET active = FALSE,
                       termination_date = COALESCE(termination_date, CURRENT_DATE())
                 WHERE id = ? AND company_id = ?
                """, id, tenantContext.getCurrentCompanyId());
        if (n == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado");
        }
    }

    private EmployeeView findById(String id) {
        return jdbcTemplate.query("""
                SELECT id, full_name, tax_identifier, social_security_number,
                       email, phone, birth_date, gender, marital_status,
                       dependent_children, dependent_disabled,
                       address_line, city, province, postal_code, country,
                       iban, work_type, ss_regime, hire_date, termination_date,
                       termination_reason, geolocation_enabled, active,
                       app_access, user_id,
                       pin_hash IS NOT NULL AS has_pin,
                       created_at, updated_at
                  FROM employees
                 WHERE id = ? AND company_id = ?
                """, this::mapView, id, tenantContext.getCurrentCompanyId())
                .stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Empleado no encontrado"));
    }

    private void validate(UpsertRequest req) {
        if (!StringUtils.hasText(req.fullName())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Nombre requerido");
        }
        if (req.ssRegime() != null && !req.ssRegime().isBlank()
                && !List.of("RETA", "GENERAL", "AUTONOMO_SOCIETARIO", "ARTISTAS", "MAR", "AGRARIO", "OTHER")
                        .contains(req.ssRegime())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Regimen SS invalido");
        }
        // L4-4: validación de PIN si viene en la request — formato 4-8
        // dígitos. La unicidad por empresa la valida setEmployeePinChecked
        // contra la BD (bcrypt no permite UK por hash).
        if (req.pin() != null && !req.pin().isBlank()) {
            if (!req.pin().matches("\\d{4,8}")) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "El PIN debe tener entre 4 y 8 dígitos numéricos.");
            }
        }
    }

    // ============================================================
    //  L4-4 — Provisión / revocación de acceso a la app
    // ============================================================

    /**
     * Da acceso a la app a un empleado existente: encuentra o crea su
     * user_account, asegura la membership en la empresa actual, opcional
     * graba el PIN. Idempotente: si el empleado ya tiene user_account,
     * lo reusa (decisión Benjamin 2026-06-08).
     */
    @Transactional
    public void provisionAppAccess(String employeeId, String plainPin, String roleInCompany) {
        String companyId = tenantContext.getCurrentCompanyId();
        EmployeeView emp = findById(employeeId);

        // 1) Resolver user_account: reutilizar el existente o crear uno
        // nuevo con email sintético si el empleado no tiene email real.
        String userId = emp.userId();
        if (userId == null || userId.isBlank()) {
            // Si el empleado tiene email real lo intentamos primero —
            // permite vincular cuentas creadas por otra vía (invitación).
            if (emp.email() != null && !emp.email().isBlank()) {
                userId = jdbcTemplate.query("""
                        SELECT id FROM user_accounts
                         WHERE LOWER(email) = LOWER(?) AND active = TRUE
                         LIMIT 1
                        """, (rs, i) -> rs.getString("id"), emp.email())
                        .stream().findFirst().orElse(null);
            }
            if (userId == null) {
                userId = createUserAccountFor(emp);
            }
            jdbcTemplate.update("UPDATE employees SET user_id = ? WHERE id = ?",
                    userId, employeeId);
        }

        // 2) Asegurar la membership en la company actual (rol EMPLOYEE
        // por defecto si el caller no especifica, decisión 2026-06-08).
        String role = roleInCompany == null || roleInCompany.isBlank()
                ? "EMPLOYEE" : roleInCompany;
        ensureMembership(userId, companyId, role);

        // 3) PIN opcional. Si el caller no lo pasa, queda con pin_hash
        // NULL y el OWNER tendrá que asignarlo después (también desde
        // este editor o desde el módulo Equipo).
        if (plainPin != null && !plainPin.isBlank()) {
            setEmployeePinChecked(employeeId, plainPin);
        }

        // 4) Marcar app_access = TRUE.
        jdbcTemplate.update("UPDATE employees SET app_access = TRUE WHERE id = ?",
                employeeId);

        auditService.recordGeneric(companyId, safeCurrentUserId(),
                "EMPLOYEE_APP_ACCESS_GRANTED", "employee", employeeId, "OK", null);
    }

    /**
     * Revoca el acceso a la app de un empleado: pone app_access=FALSE +
     * desactiva la membership en la empresa actual. NO borra el
     * user_account — se conserva por trazabilidad de audit_events.
     */
    @Transactional
    public void revokeAppAccess(String employeeId) {
        String companyId = tenantContext.getCurrentCompanyId();
        EmployeeView emp = findById(employeeId);

        jdbcTemplate.update("UPDATE employees SET app_access = FALSE WHERE id = ?",
                employeeId);
        if (emp.userId() != null && !emp.userId().isBlank()) {
            jdbcTemplate.update("""
                    UPDATE company_memberships
                       SET active = FALSE
                     WHERE user_id = ? AND company_id = ?
                    """, emp.userId(), companyId);
        }

        auditService.recordGeneric(companyId, safeCurrentUserId(),
                "EMPLOYEE_APP_ACCESS_REVOKED", "employee", employeeId, "OK", null);
    }

    /**
     * Hashea un PIN con bcrypt y lo guarda. Valida unicidad in-memory
     * contra los demás empleados de la empresa con app_access=TRUE.
     * Mismo patrón que PinAuthService.setEmployeePin — duplicación
     * controlada para no introducir acoplamiento entre los dos
     * servicios.
     */
    private void setEmployeePinChecked(String employeeId, String plainPin) {
        String companyId = tenantContext.getCurrentCompanyId();
        // Listado de PINs hash existentes en la empresa para el check
        // de colisión, EXCLUYENDO al propio empleado.
        List<String> otherHashes = jdbcTemplate.queryForList("""
                SELECT pin_hash FROM employees
                 WHERE company_id = ?
                   AND id <> ?
                   AND app_access = TRUE
                   AND active = TRUE
                   AND pin_hash IS NOT NULL
                """, String.class, companyId, employeeId);
        for (String h : otherHashes) {
            if (passwordEncoder.matches(plainPin, h)) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Otro empleado ya tiene ese PIN en esta asesoría — elige otro.");
            }
        }
        String newHash = passwordEncoder.encode(plainPin);
        jdbcTemplate.update("UPDATE employees SET pin_hash = ? WHERE id = ?",
                newHash, employeeId);
        auditService.recordGeneric(companyId, safeCurrentUserId(),
                "EMPLOYEE_PIN_CHANGED", "employee", employeeId, "OK", null);
    }

    /**
     * Crea un user_account nuevo para un empleado que aún no tiene uno.
     * Email sintético si el empleado no tiene email real — sirve para
     * que la columna NOT NULL pase sin pedir al OWNER ese dato. La
     * password queda con un secret aleatorio (~32 bytes base64url):
     * NO se usa porque el empleado entra por PIN, pero NOT NULL nos
     * obliga a poner algo. El campo es bcrypt de un secret que solo
     * existe en RAM brevemente y nadie conoce, lo cual es seguro.
     */
    private String createUserAccountFor(EmployeeView emp) {
        String userId = UUID.randomUUID().toString();
        String email = (emp.email() != null && !emp.email().isBlank())
                ? emp.email()
                : "pin-" + emp.id() + "@local";
        // Password de relleno — el empleado entra por PIN, no por password.
        byte[] randomBytes = new byte[32];
        secureRandom.nextBytes(randomBytes);
        String randomSecret = Base64.getUrlEncoder().withoutPadding().encodeToString(randomBytes);
        String passwordHash = passwordEncoder.encode(randomSecret);

        jdbcTemplate.update("""
                INSERT INTO user_accounts
                       (id, email, password_hash, display_name, global_role, active)
                VALUES (?, ?, ?, ?, 'USER', TRUE)
                """, userId, email, passwordHash,
                emp.fullName() == null || emp.fullName().isBlank()
                        ? "Empleado " + emp.id().substring(0, 8) : emp.fullName());
        return userId;
    }

    /**
     * Asegura que existe una membership ACTIVA del user en la company
     * con el rol indicado. Si ya existe (incluso inactiva), la reactiva.
     * No sobreescribe el rol si ya hay membership — el rol se decide
     * al alta y se cambia luego desde otro punto del Equipo si hace falta.
     */
    private void ensureMembership(String userId, String companyId, String roleName) {
        Integer existing = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM company_memberships
                 WHERE user_id = ? AND company_id = ?
                """, Integer.class, userId, companyId);
        if (existing != null && existing > 0) {
            // Reactivar si estaba desactivada.
            jdbcTemplate.update("""
                    UPDATE company_memberships
                       SET active = TRUE
                     WHERE user_id = ? AND company_id = ?
                    """, userId, companyId);
        } else {
            jdbcTemplate.update("""
                    INSERT INTO company_memberships
                           (id, company_id, user_id, role_name, active)
                    VALUES (?, ?, ?, ?, TRUE)
                    """, UUID.randomUUID().toString(),
                    companyId, userId, roleName);
        }
    }

    private String safeCurrentUserId() {
        try {
            return currentUserService.require().userId();
        } catch (Exception ex) {
            return null;
        }
    }

    private String blank(String v) {
        return v == null || v.isBlank() ? null : v.trim();
    }

    private EmployeeView mapView(ResultSet rs, int rowNum) throws SQLException {
        java.sql.Date bd = rs.getDate("birth_date");
        java.sql.Date hd = rs.getDate("hire_date");
        java.sql.Date td = rs.getDate("termination_date");
        Timestamp ca = rs.getTimestamp("created_at");
        Timestamp ua = rs.getTimestamp("updated_at");
        return new EmployeeView(
                rs.getString("id"),
                rs.getString("full_name"),
                rs.getString("tax_identifier"),
                rs.getString("social_security_number"),
                rs.getString("email"),
                rs.getString("phone"),
                bd == null ? null : bd.toLocalDate(),
                rs.getString("gender"),
                rs.getString("marital_status"),
                (Integer) rs.getObject("dependent_children"),
                (Integer) rs.getObject("dependent_disabled"),
                rs.getString("address_line"),
                rs.getString("city"),
                rs.getString("province"),
                rs.getString("postal_code"),
                rs.getString("country"),
                rs.getString("iban"),
                rs.getString("work_type"),
                rs.getString("ss_regime"),
                hd == null ? null : hd.toLocalDate(),
                td == null ? null : td.toLocalDate(),
                rs.getString("termination_reason"),
                rs.getBoolean("geolocation_enabled"),
                rs.getBoolean("active"),
                rs.getBoolean("app_access"),
                rs.getString("user_id"),
                rs.getBoolean("has_pin"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant()
        );
    }

    public record EmployeeView(
            String id, String fullName, String taxIdentifier, String socialSecurityNumber,
            String email, String phone, LocalDate birthDate, String gender, String maritalStatus,
            Integer dependentChildren, Integer dependentDisabled,
            String addressLine, String city, String province, String postalCode, String country,
            String iban, String workType, String ssRegime,
            LocalDate hireDate, LocalDate terminationDate, String terminationReason,
            boolean geolocationEnabled,
            boolean active,
            /** L4-4: TRUE si el empleado tiene login en BENJAGEST.
             *  Coincide con employees.app_access. La UI lo usa para
             *  mostrar/ocultar la sección de PIN en el editor. */
            boolean appAccess,
            /** L4-4: user_id asociado si existe (NULL si no se ha
             *  provisionado acceso). */
            String userId,
            /** L4-4: TRUE si pin_hash IS NOT NULL — la UI muestra
             *  "PIN configurado" sin exponer el hash. */
            boolean hasPin,
            Instant createdAt, Instant updatedAt
    ) {}

    public record UpsertRequest(
            String fullName, String taxIdentifier, String socialSecurityNumber,
            String email, String phone, LocalDate birthDate, String gender, String maritalStatus,
            Integer dependentChildren, Integer dependentDisabled,
            String addressLine, String city, String province, String postalCode, String country,
            String iban, String workType, String ssRegime,
            LocalDate hireDate, LocalDate terminationDate, String terminationReason,
            Boolean geolocationEnabled,
            Boolean active,
            /** L4-4: ¿este empleado tiene/tendrá login con PIN en BENJAGEST?
             *  null = no tocar el valor actual. TRUE = activar (crea
             *  user_account si no existe + membership). FALSE = revocar
             *  (membership.active=FALSE, user_account se conserva por
             *  histórico de audit_events). */
            Boolean appAccess,
            /** L4-4: PIN 4-8 dígitos. null = no tocar. Solo se persiste
             *  si appAccess es TRUE. Bcrypt + check de colisión con
             *  los demás empleados con app_access de la misma empresa. */
            String pin,
            /** L4-4: rol en company_memberships al provisionar (EMPLOYEE
             *  por defecto). Solo se aplica al crear la membership; si
             *  ya existe no se sobreescribe. */
            String roleInCompany
    ) {}

    @RestController
    @RequestMapping("/api/labor/employees")
    @RequiresModule("labor")
    @RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
    public static class EmployeeController {
        private final EmployeeService service;

        public EmployeeController(EmployeeService service) { this.service = service; }

        @GetMapping
        public List<EmployeeView> list(@RequestParam(value = "includeInactive", defaultValue = "false") boolean inc) {
            return service.list(inc);
        }

        @GetMapping("/{id}")
        public EmployeeView get(@PathVariable("id") String id) { return service.get(id); }

        @PostMapping
        public EmployeeView create(@RequestBody UpsertRequest req) { return service.create(req); }

        @PutMapping("/{id}")
        public EmployeeView update(@PathVariable("id") String id, @RequestBody UpsertRequest req) {
            return service.update(id, req);
        }

        @DeleteMapping("/{id}")
        @ResponseStatus(HttpStatus.NO_CONTENT)
        public void delete(@PathVariable("id") String id) { service.delete(id); }
    }
}
