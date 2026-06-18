package com.benjagest.backend.auth.pin;

import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthRepository;
import com.benjagest.backend.auth.pin.dto.DeviceListItem;
import com.benjagest.backend.auth.pin.dto.PairRequest;
import com.benjagest.backend.auth.pin.dto.PairResponse;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * L4-2 — Empareja ordenadores físicos con la asesoría y verifica los
 * tokens emitidos.
 *
 * <p>Pair: el OWNER introduce email + password una vez en cada PC nuevo;
 * el backend genera un secret aleatorio de 32 bytes (base64url ≈ 43
 * chars), guarda su bcrypt en BD y devuelve el secret en plano UNA
 * vez para que la UI lo persista en su config local. Las siguientes
 * peticiones desde ese PC mandan el secret en el handshake (verify).
 *
 * <p>Límite: máximo 5 tokens activos por asesoría (acordado con
 * Benjamin 2026-06-07). El 6º intento devuelve 409 con el mensaje
 * de que el OWNER debe revocar uno desde "Mis equipos" primero.
 */
@Service
public class DeviceTokenService {

    /** Máximo dispositivos activos por asesoría (decisión 2026-06-07). */
    static final int MAX_ACTIVE_DEVICES_PER_COMPANY = 5;

    /** Bytes del secret aleatorio. 32 = 256 bits, espacio brute-force
     *  imposible. Base64url-sin-padding lo convierte en ~43 chars. */
    private static final int SECRET_BYTES = 32;

    /** Chars del secret que se guardan en token_prefix (clarado para
     *  el OWNER en "Mis equipos"). */
    static final int TOKEN_PREFIX_CHARS = 8;

    private final DeviceTokenRepository repository;
    private final AuthRepository authRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;
    private final SecureRandom secureRandom = new SecureRandom();

    public DeviceTokenService(DeviceTokenRepository repository,
                               AuthRepository authRepository,
                               PasswordEncoder passwordEncoder,
                               AuditService auditService) {
        this.repository = repository;
        this.authRepository = authRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    /**
     * Empareja un PC con la asesoría del OWNER que da credenciales.
     *
     * <p>Reglas:
     * <ul>
     *   <li>El usuario debe existir y la password coincidir.</li>
     *   <li>El usuario debe ser OWNER de UNA asesoría INTERNAL/ADVISORY
     *       (en V70 emparejado solo lo hace OWNER de asesoría — para
     *       empresarios CLIENT viene en un slice posterior si hiciese
     *       falta).</li>
     *   <li>La asesoría no debe tener ya {@code MAX_ACTIVE_DEVICES_PER_COMPANY}
     *       dispositivos activos.</li>
     * </ul>
     */
    public PairResponse pair(PairRequest req) {
        AuthRepository.UserRecord user = authRepository.findUserByEmail(req.email())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Credenciales no válidas"));
        if (user.passwordHash() == null
                || !passwordEncoder.matches(req.password(), user.passwordHash())) {
            // No revelamos en el error si el email existía: respuesta uniforme.
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                    "Credenciales no válidas");
        }

        // Buscamos la asesoría sobre la que es OWNER. Para v1 asumimos que
        // un mismo user_account solo es OWNER de una asesoría — si en el
        // futuro alguien es OWNER de dos, habrá que pedir cuál emparejar.
        AuthRepository.MembershipRecord ownerMembership = authRepository
                .findMembershipsForUser(user.id()).stream()
                .filter(m -> "OWNER".equals(m.roleName()))
                .filter(m -> "INTERNAL".equalsIgnoreCase(m.companyType())
                        || "ADVISORY".equalsIgnoreCase(m.companyType()))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Solo el OWNER de una asesoría puede emparejar dispositivos"));

        int activos = repository.countActiveByCompany(ownerMembership.companyId());
        if (activos >= MAX_ACTIVE_DEVICES_PER_COMPANY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Has alcanzado el límite de " + MAX_ACTIVE_DEVICES_PER_COMPANY
                            + " dispositivos. Revoca uno desde 'Mis equipos' para emparejar otro.");
        }

        String secret = newSecret();
        String prefix = secret.substring(0, TOKEN_PREFIX_CHARS);
        String hash = passwordEncoder.encode(secret);
        String tokenId = UUID.randomUUID().toString();

        DeviceToken token = new DeviceToken(tokenId, ownerMembership.companyId(),
                hash, prefix, req.deviceName(),
                null, user.id(), null, null, null);
        repository.insert(token);

        auditService.recordGeneric(ownerMembership.companyId(), user.id(),
                "DEVICE_TOKEN_PAIRED", "device_token", tokenId, "OK",
                "{\"deviceName\":\"" + escape(req.deviceName()) + "\"}");

        return new PairResponse(tokenId, secret, ownerMembership.companyId(),
                ownerMembership.companyLegalName(), user.id());
    }

    /**
     * MEMP-1 — Empareja el móvil personal de un empleado SIN credenciales
     * OWNER. La autorización la da una invitación one-time ya validada por
     * {@code EmployeeAppService}; aquí solo se crea el device_token ligado a
     * la empresa del empleado, igual que {@link #pair} pero sin el handshake
     * email+password. El empleado luego entra por PIN (loginByPin).
     */
    public PairResponse pairEmployeeDevice(String companyId, String deviceName, String byUserId) {
        String secret = newSecret();
        String prefix = secret.substring(0, TOKEN_PREFIX_CHARS);
        String hash = passwordEncoder.encode(secret);
        String tokenId = UUID.randomUUID().toString();

        DeviceToken token = new DeviceToken(tokenId, companyId,
                hash, prefix, deviceName,
                null, byUserId, null, null, null);
        repository.insert(token);

        auditService.recordGeneric(companyId, byUserId,
                "DEVICE_TOKEN_PAIRED", "device_token", tokenId, "OK",
                "{\"deviceName\":\"" + escape(deviceName) + "\",\"channel\":\"EMPLOYEE_APP\"}");

        return new PairResponse(tokenId, secret, companyId, null, byUserId);
    }

    /**
     * Resuelve qué dispositivo es el secret en plano que viene del PC.
     * Filtra por prefijo (los primeros 8 chars) para minimizar verificaciones
     * bcrypt. Si encuentra el match correcto actualiza last_seen.
     *
     * <p>Empty si el secret no corresponde a ningún token activo (PC
     * desemparejado, secret falsificado, etc.) — el caller responde 401.
     */
    public Optional<DeviceToken> verifyAndTouch(String secret) {
        if (secret == null || secret.length() < TOKEN_PREFIX_CHARS) {
            return Optional.empty();
        }
        String prefix = secret.substring(0, TOKEN_PREFIX_CHARS);
        List<DeviceToken> candidates = repository.findActiveByPrefix(prefix);
        for (DeviceToken t : candidates) {
            if (passwordEncoder.matches(secret, t.tokenHash())) {
                repository.touchLastSeen(t.id());
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }

    /** Lista los dispositivos activos de una asesoría para "Mis equipos". */
    public List<DeviceListItem> listForCompany(String companyId) {
        return repository.listActiveByCompany(companyId).stream()
                .map(t -> new DeviceListItem(t.id(), t.name(), t.tokenPrefix(),
                        t.pairedAt(), t.pairedByUserId(), t.lastSeenAt()))
                .toList();
    }

    /**
     * Revoca un dispositivo. Solo el OWNER de la asesoría dueña del token
     * puede revocarlo. Es irreversible (no se reactiva — se empareja de nuevo).
     */
    public void revoke(String tokenId, String currentCompanyId, String byUserId) {
        DeviceToken t = repository.findById(tokenId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "Dispositivo no encontrado"));
        if (!currentCompanyId.equals(t.companyId())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ese dispositivo no pertenece a tu asesoría.");
        }
        repository.revoke(tokenId, byUserId);
        auditService.recordGeneric(t.companyId(), byUserId,
                "DEVICE_TOKEN_REVOKED", "device_token", tokenId, "OK",
                "{\"deviceName\":\"" + escape(t.name()) + "\"}");
    }

    private String newSecret() {
        byte[] bytes = new byte[SECRET_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String escape(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
