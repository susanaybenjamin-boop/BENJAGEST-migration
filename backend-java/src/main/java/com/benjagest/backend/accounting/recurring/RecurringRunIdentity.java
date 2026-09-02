package com.benjagest.backend.accounting.recurring;

import com.benjagest.backend.auth.AuthRepository;
import com.benjagest.backend.auth.AuthenticatedUser;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

/**
 * REC-CRON-AUTH (2026-09-02) — da identidad al hilo del cron de recurrentes.
 *
 * <p><b>El bug que arregla:</b> {@code @Scheduled} corre en el hilo
 * {@code scheduling-1}, donde el {@code SecurityContext} está vacío (no hay
 * petición HTTP ni JWT). {@code PurchaseInvoiceService.save()} llama a
 * {@code currentUserService.require()} sin protección, así que TODA ejecución
 * automática de un recurrente {@code PURCHASE} moría con
 * {@code 401 "No hay sesion activa"} — nunca funcionó desde que existe el
 * motor. Evidencia: {@code recurring_task_runs} solo tiene OK en las
 * ejecuciones manuales (run-now, con JWT) y ERROR en todas las del cron.
 *
 * <p><b>Cómo lo arregla:</b> en vez de ir capando {@code require()} uno a uno
 * (hay ~70 en el backend), el scheduler monta un {@link SecurityContext} con
 * el usuario que CREÓ la tarea ({@code recurring_tasks.created_by_user_id}).
 * Así la ejecución automática es indistinguible de la manual: mismo
 * {@code uploader_company_id} en la factura, misma atribución en la auditoría,
 * mismos guards. Y cualquier otro {@code require()} sin proteger de las rutas
 * de compras / ventas / asientos queda cubierto de golpe.
 *
 * <p><b>Qué empresa activa se elige</b> (importa: es lo que acaba en
 * {@code uploader_company_id}):
 * <ul>
 *   <li>Si el creador tiene membership en la empresa de la tarea → esa
 *       (caso normal: el titular en su propia empresa).</li>
 *   <li>Si no la tiene → su primera membership, que es exactamente lo que
 *       {@code AuthService.login()} pone en el JWT. Es el caso de la asesoría
 *       que creó la recurrente "actuando como cliente": la activa es la
 *       asesoría y el tenant es el cliente, igual que en una petición real.</li>
 * </ul>
 *
 * <p>Si el usuario no se puede resolver (tarea antigua sin
 * {@code created_by_user_id}, cuenta borrada o desactivada, sin memberships)
 * NO se instala nada y la tarea corre como antes. Se avisa en el log: es un
 * dato que hay que arreglar en la tarea, no un fallo del motor.
 *
 * <p>No toca {@code AuthService} ni la emisión de JWT (CLAUDE.md 11.2): solo
 * lee de {@link AuthRepository}.
 */
@Component
public class RecurringRunIdentity {

    private static final Logger log = LoggerFactory.getLogger(RecurringRunIdentity.class);

    private final AuthRepository authRepository;

    public RecurringRunIdentity(AuthRepository authRepository) {
        this.authRepository = authRepository;
    }

    /**
     * Instala en el hilo actual la identidad del creador de la tarea.
     *
     * <p>Devuelve un handle que RESTAURA la autenticación anterior al
     * cerrarse — no la limpia. Importante porque {@code runForDate} también
     * se invoca desde {@code POST /run-all}, es decir sobre el hilo de una
     * petición HTTP que ya tiene su propio usuario en el contexto.
     */
    public Handle install(String createdByUserId, String taskCompanyId) {
        Authentication previous = SecurityContextHolder.getContext().getAuthentication();
        resolve(createdByUserId, taskCompanyId).ifPresent(user ->
                SecurityContextHolder.getContext().setAuthentication(
                        new UsernamePasswordAuthenticationToken(
                                user,
                                null,
                                List.of(
                                        new SimpleGrantedAuthority("ROLE_" + nullSafe(user.globalRole())),
                                        new SimpleGrantedAuthority(
                                                "COMPANY_ROLE_" + nullSafe(user.roleInActiveCompany()))))));
        return () -> SecurityContextHolder.getContext().setAuthentication(previous);
    }

    /**
     * Reconstruye el {@link AuthenticatedUser} del creador con los mismos
     * campos que pondría el login en el JWT. Vacío si no hay usuario usable.
     */
    public Optional<AuthenticatedUser> resolve(String createdByUserId, String taskCompanyId) {
        if (createdByUserId == null || createdByUserId.isBlank()) {
            log.warn("[recurring] tarea sin created_by_user_id — se ejecuta sin identidad; "
                    + "los kinds que requieren usuario fallarán con 401");
            return Optional.empty();
        }
        Optional<AuthRepository.UserRecord> found = authRepository.findUserById(createdByUserId);
        if (found.isEmpty()) {
            log.warn("[recurring] el creador {} de la tarea no existe o está inactivo — "
                    + "se ejecuta sin identidad", createdByUserId);
            return Optional.empty();
        }
        AuthRepository.UserRecord user = found.get();

        AuthRepository.MembershipRecord active = authRepository
                .findMembership(createdByUserId, taskCompanyId)
                .orElse(null);
        if (active == null) {
            List<AuthRepository.MembershipRecord> all =
                    authRepository.findMembershipsForUser(createdByUserId);
            if (all.isEmpty()) {
                log.warn("[recurring] el creador {} no tiene ninguna empresa activa — "
                        + "se ejecuta sin identidad", createdByUserId);
                return Optional.empty();
            }
            active = all.get(0);
        }
        return Optional.of(new AuthenticatedUser(
                user.id(), user.email(), user.displayName(), user.globalRole(),
                active.companyId(), active.roleName()));
    }

    private static String nullSafe(String value) {
        return value == null ? "UNKNOWN" : value;
    }

    /** Handle de restauración. {@code close()} no lanza. */
    @FunctionalInterface
    public interface Handle extends AutoCloseable {
        @Override
        void close();
    }
}
