package com.benjagest.backend.auth.pin;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.dto.LoginResponse;
import com.benjagest.backend.auth.pin.dto.DeviceListItem;
import com.benjagest.backend.auth.pin.dto.PairRequest;
import com.benjagest.backend.auth.pin.dto.PairResponse;
import com.benjagest.backend.auth.pin.dto.PinLoginRequest;
import com.benjagest.backend.auth.pin.dto.SetPinRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * L4-2 — Endpoints REST del modelo PIN multi-puesto.
 *
 * <p>Rutas:
 * <ul>
 *   <li>{@code POST /api/auth/devices/pair} — el OWNER empareja un PC
 *       con su email+password+nombre. Devuelve secret en plano UNA vez.
 *       Endpoint PÚBLICO (no requiere JWT).</li>
 *   <li>{@code POST /api/auth/pin-login} — login con device_secret + PIN.
 *       Devuelve el mismo LoginResponse que el login por email.
 *       Endpoint PÚBLICO.</li>
 *   <li>{@code GET /api/auth/devices} — el OWNER lista sus dispositivos
 *       activos. Requiere JWT.</li>
 *   <li>{@code DELETE /api/auth/devices/{id}} — revoca un dispositivo.
 *       Requiere JWT del OWNER.</li>
 *   <li>{@code PUT /api/auth/employees/{id}/pin} — el OWNER asigna o
 *       cambia el PIN de un empleado. Requiere JWT.</li>
 * </ul>
 *
 * <p>Las rutas públicas se añaden a la whitelist de SecurityConfig
 * (slice contiguo de este L4-2).
 */
@RestController
@RequestMapping("/api/auth")
public class PinAuthController {

    private final DeviceTokenService deviceTokenService;
    private final PinAuthService pinAuthService;
    private final CurrentUserService currentUserService;

    public PinAuthController(DeviceTokenService deviceTokenService,
                              PinAuthService pinAuthService,
                              CurrentUserService currentUserService) {
        this.deviceTokenService = deviceTokenService;
        this.pinAuthService = pinAuthService;
        this.currentUserService = currentUserService;
    }

    // ----- Públicos --------------------------------------------------------

    @PostMapping("/devices/pair")
    public PairResponse pair(@Valid @RequestBody PairRequest req) {
        return deviceTokenService.pair(req);
    }

    @PostMapping("/pin-login")
    public LoginResponse pinLogin(@Valid @RequestBody PinLoginRequest req) {
        return pinAuthService.loginByPin(req);
    }

    // ----- Autenticados ----------------------------------------------------

    @GetMapping("/devices")
    public List<DeviceListItem> listDevices() {
        var current = currentUserService.require();
        return deviceTokenService.listForCompany(current.activeCompanyId());
    }

    @DeleteMapping("/devices/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revokeDevice(@PathVariable("id") String id) {
        var current = currentUserService.require();
        deviceTokenService.revoke(id, current.activeCompanyId(), current.userId());
    }

    @PutMapping("/employees/{id}/pin")
    public Map<String, Object> setEmployeePin(@PathVariable("id") String employeeId,
                                                @Valid @RequestBody SetPinRequest req) {
        var current = currentUserService.require();
        pinAuthService.setEmployeePin(employeeId, req.pin(), current.userId());
        return Map.of("employeeId", employeeId, "updated", true);
    }
}
