package com.benjagest.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.auth.dto.RegisterRequest;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * REG-ADVISORY-MOD — Con qué módulos nace una cuenta recién registrada.
 *
 * <p>Lo que cierra: una ASESORÍA dada de alta por {@code /api/auth/register}
 * nacía sin el módulo {@code advisory}, que solo se activaba en el seed V8. Sin
 * él, {@code @RequiresModule} corta {@code /api/advisory/clients} con un 403 y
 * la asesoría no ve ni su cartera ni "Mi gestión" — el mismo 403 que apareció
 * en el smoke de MODE-UP, pero por el otro camino. Igual con {@code team}, que
 * la V69 solo activó para las asesorías que ya existían.
 *
 * <p>El reparto empresario/asesoría NO cambia: los módulos fiscales y el buzón
 * siguen siendo de la asesoría (decisión de Benjamin, 2026-06-26).
 */
class RegisterModulesTest {

    private static DB db;
    private static JdbcTemplate jdbc;
    private static RegisterService service;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestregmod");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestregmod"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        service = new RegisterService(jdbc, new BCryptPasswordEncoder(),
                Mockito.mock(AuthService.class),
                Mockito.mock(EmailVerificationService.class));
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    @Test
    void unaAsesoriaNuevaNaceConSuCarteraYSuEquipo() {
        String company = alta("ADVISORY", "Asesoria Nueva SL");

        assertEquals(1, moduloActivo(company, "advisory"));
        assertEquals(1, moduloActivo(company, "team"));
    }

    @Test
    void unaAsesoriaNuevaSigueLlevandoLoFiscalYElBuzon() {
        String company = alta("ADVISORY", "Asesoria Fiscal SL");

        assertEquals(1, moduloActivo(company, "tax"));
        assertEquals(1, moduloActivo(company, "notifications"));
    }

    /** El reparto no cambia: lo de la asesoría NO se le da al empresario. */
    @Test
    void unEmpresarioNuevoNoSeLlevaLosModulosDeAsesoria() {
        String company = alta("BUSINESS", "Empresario Nuevo SL");

        assertEquals(0, moduloActivo(company, "advisory"));
        assertEquals(0, moduloActivo(company, "team"));
        assertEquals(0, moduloActivo(company, "tax"));
        assertEquals(0, moduloActivo(company, "notifications"));
        assertEquals(1, moduloActivo(company, "time-clock"));
        assertEquals(1, moduloActivo(company, "billing"));
    }

    // ---- utilidades ----

    /** Da de alta una cuenta y devuelve el id de su empresa. */
    private static String alta(String accountType, String legalName) {
        String email = "reg-" + UUID.randomUUID() + "@test.local";
        String nif = "B" + String.valueOf(System.nanoTime()).substring(0, 8);
        service.register(new RegisterRequest(accountType, legalName, nif,
                "Calle Mayor 1", "Madrid", "Madrid", "28001",
                email, "Titular de Prueba", "Prueba123456$"));
        return jdbc.queryForObject("""
                SELECT c.id FROM companies c
                  JOIN company_memberships m ON m.company_id = c.id
                  JOIN user_accounts u ON u.id = m.user_id
                 WHERE u.email = ?
                """, String.class, email);
    }

    private static int moduloActivo(String companyId, String slug) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM company_modules cm
                  JOIN module_catalog m ON m.id = cm.module_id
                 WHERE cm.company_id = ? AND m.slug = ? AND cm.active = TRUE
                """, Integer.class, companyId, slug);
        return n == null ? 0 : n;
    }
}
