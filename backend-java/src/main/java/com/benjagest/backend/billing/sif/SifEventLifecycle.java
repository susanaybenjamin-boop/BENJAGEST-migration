package com.benjagest.backend.billing.sif;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Hooks del ciclo de vida del SIF (RD 1007/2023 + Orden HAC/1177/2024,
 * eventos 1 y 2 de la lista oficial):
 *
 *   - SYSTEM_START al arrancar Spring.
 *   - SUMMARY_SHUTDOWN + SYSTEM_STOP al parar Spring.
 *
 * Estos eventos son OBLIGATORIOS solo en modalidad NO VeriFactu, por
 * eso recorremos la lista de empresas activas en NO_VERIFACTU y
 * emitimos un evento por cada una. El servicio se encarga de filtrar
 * por modalidad antes de insertar (defensa en profundidad).
 *
 * El payload incluye un timestamp + version del SIF — se ajustara
 * cuando tengamos numero de version del programa (slice paralelo).
 */
@Component
public class SifEventLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SifEventLifecycle.class);
    private static final String SIF_VERSION = "BENJAGEST-0.x";

    private final SifEventService eventService;

    public SifEventLifecycle(SifEventService eventService) {
        this.eventService = eventService;
    }

    /**
     * Arranque del SIF: emite SYSTEM_START por cada empresa en
     * NO_VERIFACTU. Se ejecuta cuando Spring termina de inicializar
     * todos los beans (incluido el datasource + Flyway).
     *
     * Defensivo: si la BD aun no esta lista (raro pero posible en
     * ciertos entornos), capturamos el error sin reventar el arranque
     * — los eventos perdidos se anyadiran cuando el siguiente evento
     * ejecute el flujo. La integridad de la cadena no se compromete
     * porque el primer evento que llegue tendra Huella="" y arrancara
     * la cadena correctamente.
     */
    @PostConstruct
    public void onStart() {
        try {
            String payload = buildPayload("startedAt");
            for (String companyId : eventService.listCompaniesInNoVerifactu()) {
                try {
                    eventService.recordForCompany(companyId, "SYSTEM_START", payload);
                } catch (RuntimeException ex) {
                    log.warn("No se pudo registrar SYSTEM_START para empresa {}", companyId, ex);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudo iniciar el registro SIF (BD aun no disponible?)", ex);
        }
    }

    /**
     * Apagado del SIF: emite primero un SUMMARY_SHUTDOWN (resumen
     * obligatorio segun Orden HAC antes del cierre) y despues
     * SYSTEM_STOP. Por cada empresa NO_VERIFACTU.
     *
     * NOTA: @PreDestroy se ejecuta antes del cierre del contexto pero
     * NO antes de un kill -9 ni un crash. Para esos casos, el siguiente
     * SYSTEM_START dejara la cadena visible como "se reinicio sin
     * cierre limpio" — eso es una se al adicional que ayuda a la
     * AEAT a detectar inestabilidad del sistema, no un bug.
     */
    @PreDestroy
    public void onStop() {
        try {
            String payload = buildPayload("stoppedAt");
            for (String companyId : eventService.listCompaniesInNoVerifactu()) {
                try {
                    eventService.recordForCompany(companyId, "SUMMARY_SHUTDOWN", payload);
                    eventService.recordForCompany(companyId, "SYSTEM_STOP", payload);
                } catch (RuntimeException ex) {
                    log.warn("No se pudo registrar SHUTDOWN para empresa {}", companyId, ex);
                }
            }
        } catch (RuntimeException ex) {
            log.warn("No se pudo cerrar el registro SIF limpiamente", ex);
        }
    }

    private String buildPayload(String tsField) {
        // JSON simple — el servicio escape() lo proteje del separador
        // canonico del hash. Se mantiene en una sola linea para que
        // sea facil de leer desde un export.
        return "{\"" + tsField + "\":\"" + java.time.Instant.now().toString()
                + "\",\"sifVersion\":\"" + SIF_VERSION + "\"}";
    }
}
