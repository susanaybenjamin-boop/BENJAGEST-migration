package com.benjagest.backend.timeclock;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de fichaje (RD 8/2019).
 *
 * Reglas:
 *   - Un fichaje original NO se modifica jamas. Para corregir, se inserta
 *     una correccion vinculada (art. 34.9).
 *   - Cada fichaje recibe automaticamente un CSV (art. 35.8) que se
 *     entrega al trabajador (o se queda en cola para entregar).
 *   - El employeeId debe ser el del usuario que ficha; si un manager
 *     ficha por otro, debe quedar reflejado en origin/auditoria.
 */
@Service
public class TimeClockService {

    private static final SecureRandom RNG = new SecureRandom();
    private static final char[] CSV_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZ23456789".toCharArray();

    private final TimeClockRepository repository;
    private final CurrentUserService currentUserService;

    public TimeClockService(TimeClockRepository repository,
                            CurrentUserService currentUserService) {
        this.repository = repository;
        this.currentUserService = currentUserService;
    }

    /**
     * Registra un fichaje. Devuelve el evento creado + CSV emitido
     * (para mostrar al trabajador en el momento).
     */
    @Transactional
    public PunchResult punch(String employeeId, String eventType,
                             String customerId, String origin) {
        if (employeeId == null || employeeId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "employeeId requerido");
        }
        if (eventType == null
                || !List.of("IN", "OUT", "BREAK_START", "BREAK_END").contains(eventType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "eventType debe ser IN, OUT, BREAK_START o BREAK_END");
        }
        AuthenticatedUser actor = currentUserService.require();
        String eventId = UUID.randomUUID().toString();
        TimeClockEvent event = new TimeClockEvent(
                eventId, null, employeeId, customerId,
                eventType, Instant.now(),
                origin == null ? "WEB" : origin,
                "VALID", null);
        repository.insertEvent(event);

        String csv = generateCsv();
        repository.insertVerification(
                UUID.randomUUID().toString(), eventId, csv, actor.userId());

        return new PunchResult(event, csv);
    }

    public List<TimeClockEvent> recent(String employeeId, int limit) {
        return repository.findRecentByEmployee(employeeId, limit);
    }

    /**
     * Solicita correccion de un fichaje. NO toca el original — crea
     * una fila PENDING en time_clock_corrections.
     */
    @Transactional
    public void requestCorrection(String originalEventId, String correctionType,
                                   String proposedEventType, Instant proposedEventTime,
                                   String reason) {
        if (reason == null || reason.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "motivo requerido");
        }
        AuthenticatedUser actor = currentUserService.require();
        repository.insertCorrection(
                UUID.randomUUID().toString(), originalEventId,
                correctionType, proposedEventType, proposedEventTime,
                reason, actor.userId());
    }

    /**
     * Verificacion publica por CSV — no requiere sesion. Devuelve el
     * evento "tal cual fue grabado" — sin aplicar correcciones, porque
     * el verificador externo quiere ver el original, no la version
     * editada.
     */
    public TimeClockEvent verifyByCsv(String csvCode) {
        return repository.findEventByCsv(csvCode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "CSV no encontrado o revocado"));
    }

    /**
     * Genera un CSV de 16 caracteres en alfabeto Crockford-base32-like
     * (sin I/L/O/1/0 para evitar confusiones humanas). 16 chars × 32
     * opciones = 2^80 combinaciones, mas que suficiente para que no
     * choque ni se adivine.
     */
    private String generateCsv() {
        char[] buf = new char[16];
        for (int i = 0; i < buf.length; i++) {
            buf[i] = CSV_ALPHABET[RNG.nextInt(CSV_ALPHABET.length)];
        }
        // Formato visual con guion en mitad para legibilidad humana.
        return new String(buf, 0, 4) + "-" + new String(buf, 4, 4)
                + "-" + new String(buf, 8, 4) + "-" + new String(buf, 12, 4);
    }

    public record PunchResult(TimeClockEvent event, String csv) {}
}
