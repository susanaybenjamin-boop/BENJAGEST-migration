package com.benjagest.backend.realtime;

import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.auth.RequiresRole;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * NOTIF-RT — Endpoint SSE al que se conectan el escritorio (Bearer) y la PWA
 * (token por query, ya que EventSource no manda cabeceras; el filtro JWT acepta
 * {@code ?token=} solo en /api/realtime/). La empresa sale del claim del JWT
 * (activeCompanyId); el empleado se resuelve por user_id si tiene ficha.
 */
@RestController
@RequestMapping("/api/realtime")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE", "USER"})
public class RealtimeController {

    private final RealtimeService realtime;
    private final CurrentUserService currentUser;
    private final JdbcTemplate jdbc;

    public RealtimeController(RealtimeService realtime, CurrentUserService currentUser, JdbcTemplate jdbc) {
        this.realtime = realtime;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream() {
        AuthenticatedUser user = currentUser.require();
        String companyId = user.activeCompanyId();
        String employeeId = resolveEmployeeId(user.userId(), companyId);
        return realtime.subscribe(companyId, employeeId);
    }

    /** employees.id del usuario en esa empresa, o null si no tiene ficha. */
    private String resolveEmployeeId(String userId, String companyId) {
        if (userId == null || companyId == null) return null;
        try {
            return jdbc.query("""
                    SELECT id FROM employees WHERE user_id = ? AND company_id = ? LIMIT 1
                    """,
                    rs -> rs.next() ? rs.getString("id") : null,
                    userId, companyId);
        } catch (Exception e) {
            return null;
        }
    }
}
