package com.benjagest.backend.auth.pin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthRepository;
import com.benjagest.backend.auth.pin.dto.PairResponse;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/**
 * PIN-PAIR-SESSION — Emparejar ESTE equipo desde la sesión ya abierta.
 *
 * <p>El emparejado de siempre ({@code pair}) pide email + contraseña, y una
 * cuenta que entra con Google no tiene contraseña: no hay forma de emparejar y
 * por tanto de abrir la app con PIN. Esta variante usa al usuario que ya está
 * autenticado, así que sirve igual con Google — pero <b>no rebaja ninguna otra
 * regla</b>: sigue siendo cosa del OWNER de una asesoría y sigue el tope de
 * equipos.
 */
class PairFromSessionTest {

    private static DB db;
    private static JdbcTemplate jdbc;
    private static DeviceTokenService service;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestpairsession");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestpairsession"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        service = new DeviceTokenService(new DeviceTokenRepository(jdbc),
                new AuthRepository(jdbc), new BCryptPasswordEncoder(),
                Mockito.mock(AuditService.class));
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    @Test
    void elOwnerDeUnaAsesoriaEmparejaConLaSesionAbierta() {
        String company = empresa("ADVISORY", "Asesoria Con Sesion SL");
        String user = usuario(company, "OWNER", "owner-sesion");

        PairResponse r = service.pairFromSession(user, company, "PC del despacho");

        assertNotNull(r.deviceSecret());
        assertTrue(r.deviceSecret().length() > 20, "el secret se devuelve en plano una vez");
        assertEquals(company, r.companyId());
        assertEquals(1, equiposDe(company));
    }

    /** Una cuenta sin contraseña (Google) puede emparejar por este camino. */
    @Test
    void funcionaAunqueLaCuentaNoTengaContrasena() {
        String company = empresa("ADVISORY", "Asesoria Google SL");
        String user = usuario(company, "OWNER", "owner-google");
        jdbc.update("UPDATE user_accounts SET password_hash = NULL, google_id = ? WHERE id = ?",
                "google-" + user, user);

        PairResponse r = service.pairFromSession(user, company, "Portatil");

        assertNotNull(r.deviceSecret());
        assertEquals(1, equiposDe(company));
    }

    @Test
    void unAdminNoPuedeEmparejar() {
        String company = empresa("ADVISORY", "Asesoria Con Admin SL");
        String user = usuario(company, "ADMIN", "admin-sesion");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pairFromSession(user, company, "PC de un admin"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(0, equiposDe(company));
    }

    /** El multi-puesto por PIN es de asesorías: un empresario no empareja. */
    @Test
    void elOwnerDeUnaEmpresaQueNoEsAsesoriaNoPuedeEmparejar() {
        String company = empresa("CLIENT", "Empresario Con Sesion SL");
        String user = usuario(company, "OWNER", "owner-empresario");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pairFromSession(user, company, "Mi PC"));

        assertEquals(HttpStatus.FORBIDDEN, ex.getStatusCode());
        assertEquals(0, equiposDe(company));
    }

    @Test
    void respetaElTopeDeEquiposActivos() {
        String company = empresa("ADVISORY", "Asesoria Llena SL");
        String user = usuario(company, "OWNER", "owner-tope");
        for (int i = 1; i <= 5; i++) {
            service.pairFromSession(user, company, "Equipo " + i);
        }

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> service.pairFromSession(user, company, "El sexto"));

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(5, equiposDe(company));
    }

    // ====================================================================
    //  Con qué entra la cuenta (lo que la pantalla de Sesión va a mostrar)
    // ====================================================================

    @Test
    void laCuentaDeSiempreEntraConContrasena() {
        String company = empresa("ADVISORY", "Asesoria Clasica SL");
        String user = usuario(company, "OWNER", "owner-clasico");

        AuthRepository.LoginMethods m = new AuthRepository(jdbc).loginMethods(user);

        assertTrue(m.hasPassword());
        assertEquals(false, m.googleLinked());
    }

    @Test
    void laCuentaDeGoogleNoTieneContrasena() {
        String company = empresa("ADVISORY", "Asesoria Solo Google SL");
        String user = usuario(company, "OWNER", "owner-solo-google");
        jdbc.update("UPDATE user_accounts SET password_hash = NULL, google_id = ? WHERE id = ?",
                "google-" + user, user);

        AuthRepository.LoginMethods m = new AuthRepository(jdbc).loginMethods(user);

        assertEquals(false, m.hasPassword());
        assertTrue(m.googleLinked());
    }

    // ---- fixture ----

    private static String empresa(String tipo, String nombre) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO companies (id, legal_name, trade_name, tax_identifier,
                                       company_type, active)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """, id, nombre, nombre,
                "B" + id.replace("-", "").substring(0, 8).toUpperCase(), tipo);
        return id;
    }

    private static String usuario(String companyId, String rol, String alias) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO user_accounts (id, email, password_hash, display_name,
                                           global_role, forced_password_change, active)
                VALUES (?, ?, ?, ?, 'USER', FALSE, TRUE)
                """, id, alias + "-" + id.substring(0, 8) + "@test.local",
                new BCryptPasswordEncoder().encode("Prueba123456$"), alias);
        jdbc.update("""
                INSERT INTO company_memberships (id, company_id, user_id, role_name, active)
                VALUES (?, ?, ?, ?, TRUE)
                """, UUID.randomUUID().toString(), companyId, id, rol);
        return id;
    }

    private static int equiposDe(String companyId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM device_tokens WHERE company_id = ? AND revoked_at IS NULL",
                Integer.class, companyId);
        return n == null ? 0 : n;
    }
}
