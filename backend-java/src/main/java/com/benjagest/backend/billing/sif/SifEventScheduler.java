package com.benjagest.backend.billing.sif;

import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Resumen periodico del SIF cada 6 horas, requerido por la Orden
 * HAC/1177/2024 ("evento de resumen cada 6 horas de operacion + uno
 * justo antes de apagar el sistema").
 *
 * El evento de apagado (SUMMARY_SHUTDOWN) lo emite SifEventLifecycle
 * en @PreDestroy. Aqui solo cubrimos el periodico en operacion normal.
 *
 * Frecuencia: fixedDelay para que NO se solapen ejecuciones (a
 * diferencia de fixedRate, fixedDelay arranca la siguiente N ms
 * despues de que termine la anterior). Si el job tarda en una empresa,
 * la siguiente esperara — preferimos retraso a duplicacion.
 *
 * initialDelay: damos 10 minutos al arrancar antes del primer
 * SUMMARY_6H, asi no compite con SYSTEM_START en el mismo segundo
 * (ambos generarian hashes encadenados con el mismo generation_time
 * truncado a segundo, y el orden podria ser ambiguo).
 *
 * El evento solo se emite si la empresa esta en modalidad NO_VERIFACTU
 * (SifEventService.recordForCompany filtra eso internamente). Asi
 * empresas que esten en VeriFactu no generan ruido.
 */
@Component
public class SifEventScheduler {

    private static final Logger log = LoggerFactory.getLogger(SifEventScheduler.class);
    private static final long SIX_HOURS_MS = 6L * 60L * 60L * 1000L;
    private static final long INITIAL_DELAY_MS = 10L * 60L * 1000L;

    private final SifEventService eventService;

    public SifEventScheduler(SifEventService eventService) {
        this.eventService = eventService;
    }

    @Scheduled(fixedDelay = SIX_HOURS_MS, initialDelay = INITIAL_DELAY_MS)
    public void emitSummary6h() {
        String payload = "{\"emittedAt\":\"" + Instant.now().toString()
                + "\",\"period\":\"PT6H\"}";
        for (String companyId : eventService.listCompaniesInNoVerifactu()) {
            try {
                eventService.recordForCompany(companyId, "SUMMARY_6H", payload);
            } catch (RuntimeException ex) {
                // No queremos que una empresa rota tumbe el job para
                // las demas. Loggeamos y seguimos con la siguiente.
                log.warn("No se pudo registrar SUMMARY_6H para empresa {}", companyId, ex);
            }
        }
    }
}
