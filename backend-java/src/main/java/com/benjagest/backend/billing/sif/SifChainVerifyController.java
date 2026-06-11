package com.benjagest.backend.billing.sif;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoint legal y único permitido sobre la cadena SIF: VERIFICACIÓN
 * a demanda.
 *
 * <p>El RD 1007/2023 art. 11 y la Orden HAC/1177/2024 establecen
 * que los registros del SIF son INALTERABLES: el sistema NO puede
 * disponer de ninguna función que permita cancelarlos, modificarlos
 * o suprimirlos. Por eso este controller solo expone {@code verify-now}
 * (operación de solo lectura sobre la cadena) y deliberadamente NO
 * contiene endpoints de reset, delete, ni update sobre
 * {@code sif_event_registry}.
 *
 * <p>El botón "Reiniciar cadena SIF" que existió hasta el commit
 * SIF-LEGAL-CLEAR (2026-06-11) se eliminó por completo precisamente
 * por este motivo. Si en desarrollo aparece un nuevo bug que rompe
 * cadenas y se quiere limpiar para depurar, se hace SQL directo
 * temporal y se reimplementa el endpoint solo cuando sea estrictamente
 * necesario — pero nunca en un build que pueda llegar a producción.
 */
@RestController
@RequestMapping("/api/billing/sif-events")
@RequiresModule("billing")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class SifChainVerifyController {

    private final AnomalyDetectionScheduler anomalyDetector;

    public SifChainVerifyController(AnomalyDetectionScheduler anomalyDetector) {
        this.anomalyDetector = anomalyDetector;
    }

    /**
     * Dispara una pasada de detección de anomalías SIF a demanda. UI
     * lo expone en Configuración → Auditoría → "Verificar cadena ahora"
     * para sesiones largas que no reinician el programa.
     */
    @PostMapping("/verify-now")
    public Map<String, Object> verifyNow() {
        anomalyDetector.run();
        return Map.of("ok", true);
    }
}
