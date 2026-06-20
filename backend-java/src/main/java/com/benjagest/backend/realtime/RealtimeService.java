package com.benjagest.backend.realtime;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * NOTIF-RT — Bus de notificaciones en tiempo real vía SSE (Server-Sent Events).
 *
 * <p>Sustituye al sondeo periódico (Timelines del escritorio) por push: cuando algo
 * cambia en el backend (un fichaje, una solicitud de ausencia, una nómina…), se
 * publica un evento a las conexiones interesadas y cliente (escritorio JavaFX o PWA)
 * refresca solo lo afectado, sin recargar continuamente.
 *
 * <p>Es HTTP estándar (text/event-stream): funciona por el túnel y no necesita
 * infraestructura nueva. Las conexiones se indexan por empresa (para avisos de
 * empresario/asesoría) y por empleado (para avisos dirigidos al empleado en la PWA).
 */
@Service
public class RealtimeService {

    /** Una conexión SSE viva. {@code employeeId} puede ser null (usuario sin ficha). */
    private record Conn(String companyId, String employeeId, SseEmitter emitter) {}

    private final List<Conn> connections = new CopyOnWriteArrayList<>();

    /** Registra una conexión SSE para una empresa (+ empleado opcional). */
    public SseEmitter subscribe(String companyId, String employeeId) {
        SseEmitter emitter = new SseEmitter(0L); // sin timeout: lo mantenemos con heartbeat
        Conn conn = new Conn(companyId, employeeId, emitter);
        connections.add(conn);
        emitter.onCompletion(() -> connections.remove(conn));
        emitter.onTimeout(() -> { connections.remove(conn); emitter.complete(); });
        emitter.onError(e -> connections.remove(conn));
        try {
            // Padding inicial (~2 KB de comentario): fuerza a los proxies (Cloudflare)
            // a vaciar el buffer y empezar a entregar el stream de inmediato.
            emitter.send(SseEmitter.event().comment(" ".repeat(2048)));
            emitter.send(SseEmitter.event().name("ready").data("{}"));
        } catch (IOException ignored) {
            connections.remove(conn);
        }
        return emitter;
    }

    /** Evento para todas las conexiones de una empresa (empresario/asesoría/empleados). */
    public void publishToCompany(String companyId, String type, String json) {
        if (companyId == null) return;
        afterCommit(() -> dispatch(c -> companyId.equals(c.companyId()), type, json));
    }

    /** Evento dirigido a un empleado concreto (sus pantallas en la PWA). */
    public void publishToEmployee(String companyId, String employeeId, String type, String json) {
        if (companyId == null || employeeId == null) return;
        afterCommit(() -> dispatch(
                c -> companyId.equals(c.companyId()) && employeeId.equals(c.employeeId()), type, json));
    }

    /**
     * Dispara la publicación TRAS el commit si hay transacción activa (para que el
     * cliente, al re-consultar, vea ya el dato). Si no hay transacción, ahora mismo.
     */
    private void afterCommit(Runnable action) {
        if (org.springframework.transaction.support.TransactionSynchronizationManager.isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override public void afterCommit() { action.run(); }
                    });
        } else {
            action.run();
        }
    }

    private void dispatch(Predicate<Conn> match, String type, String json) {
        for (Conn c : connections) {
            if (!match.test(c)) continue;
            try {
                c.emitter().send(SseEmitter.event().name(type).data(json == null ? "{}" : json));
            } catch (Exception e) {
                connections.remove(c);
            }
        }
    }

    /** Mantiene vivas las conexiones (Cloudflare/navegador cierran inactivas). */
    @Scheduled(fixedRate = 25000)
    public void heartbeat() {
        for (Conn c : connections) {
            try {
                c.emitter().send(SseEmitter.event().comment("hb"));
            } catch (Exception e) {
                connections.remove(c);
            }
        }
    }

    /** Nº de conexiones vivas (diagnóstico). */
    public int activeConnections() {
        return connections.size();
    }
}
