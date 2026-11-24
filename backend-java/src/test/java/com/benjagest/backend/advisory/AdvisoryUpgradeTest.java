package com.benjagest.backend.advisory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.audit.AuditService;
import com.benjagest.backend.auth.AuthenticatedUser;
import com.benjagest.backend.auth.CurrentUserService;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * MODE-UP — Verificacion EN EJECUCION (CLAUDE.md §10.ter) de la subida de una
 * cuenta de EMPRESARIO (company_type='CLIENT') a ASESORIA ('ADVISORY').
 *
 * <p>Lo que mas importa aqui no es que el tipo cambie, sino que <b>no se pierda
 * ni se apague nada</b>: los datos de la empresa siguen en su sitio (mismo
 * company_id) y los modulos que el empresario ya tenia encendidos siguen
 * encendidos. Son los anti-objetivos del diseno
 * ({@code docs/design-subida-empresario-a-asesoria.md}).
 *
 * <p>Corre el SQL real contra MariaDB embebida con TODAS las migraciones. Cada
 * test monta su propia empresa, asi que no dependen del orden entre ellos.
 */
class AdvisoryUpgradeTest {

    private static DB db;
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestmodeup");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestmodeup"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    // ====================================================================
    //  Camino feliz
    // ====================================================================

    @Test
    void subeLaEmpresaAAdvisoryYActivaLosModulosQueLeFaltan() {
        String company = empresaCliente("Mi Negocio SL");

        AdvisoryUpgradeService.UpgradeResult result = servicioPara(company).upgradeActiveCompany();

        assertEquals("ADVISORY", result.companyType());
        assertEquals(company, result.companyId());
        assertEquals("ADVISORY", tipoDe(company));
        assertEquals(1, moduloActivo(company, "tax"));
        assertEquals(1, moduloActivo(company, "notifications"));
        assertEquals(1, moduloActivo(company, "team"));
        // Sin 'advisory' activo, /api/advisory/clients responde 403 por
        // @RequiresModule y la asesoria no ve ni su cartera ni "Mi gestion".
        // Lo encontro el smoke de ejecucion: ese modulo solo se activaba en el
        // seed V8, nunca en un alta ni en una conversion.
        assertEquals(1, moduloActivo(company, "advisory"));
    }

    /** Anti-objetivo: subir de modo no puede apagarle nada al empresario. */
    @Test
    void noApagaNingunModuloQueYaEstabaActivo() {
        String company = empresaCliente("Fichajes SL");

        servicioPara(company).upgradeActiveCompany();

        assertEquals(1, moduloActivo(company, "time-clock"));
        assertEquals(1, moduloActivo(company, "billing"));
    }

    /**
     * La rama del {@code ON DUPLICATE KEY}: el caso REAL mas probable es que la
     * empresa ya tenga fila de alguno de esos modulos, apagado desde
     * Configuracion -> Modulos (V43 y ModuleRepository#setActive dejan la fila
     * con active=FALSE y deactivated_at puesto). Subir tiene que reactivarla,
     * sin duplicarla y limpiando la marca de desactivacion.
     */
    @Test
    void reactivaUnModuloDeAsesoriaQueLaEmpresaTENIAApagado() {
        String company = empresaCliente("Fiscal Apagado SL");
        activarModulo(company, "tax");
        jdbc.update("""
                UPDATE company_modules cm
                  JOIN module_catalog m ON m.id = cm.module_id
                   SET cm.active = FALSE, cm.deactivated_at = CURRENT_TIMESTAMP
                 WHERE cm.company_id = ? AND m.slug = 'tax'
                """, company);

        servicioPara(company).upgradeActiveCompany();

        assertEquals(1, filasDeModulo(company, "tax"));
        assertEquals(1, moduloActivo(company, "tax"));
        assertEquals(0, filasConMarcaDeApagado(company, "tax"));
    }

    /**
     * La respuesta tiene que decir lo que se activo DE VERDAD, no la lista fija.
     * Si un slug deja de estar en el catalogo (renombrado, retirado), el INSERT
     * no mete ninguna fila y hasta ahora la respuesta seguia diciendo que si: el
     * 403 de la cartera volveria sin que nadie se enterase.
     */
    @Test
    void laRespuestaNoCuentaComoActivadoUnModuloQueNoSeActivo() {
        String company = empresaCliente("Catalogo Raro SL");
        jdbc.update("UPDATE module_catalog SET active_in_catalog = FALSE WHERE slug = 'team'");
        try {
            AdvisoryUpgradeService.UpgradeResult result =
                    servicioPara(company).upgradeActiveCompany();

            assertEquals(0, moduloActivo(company, "team"));
            assertFalse(result.activatedModules().contains("team"),
                    "no se activo 'team', asi que no puede venir en la respuesta");
            assertTrue(result.activatedModules().contains("advisory"));
        } finally {
            jdbc.update("UPDATE module_catalog SET active_in_catalog = TRUE WHERE slug = 'team'");
        }
    }

    /** Anti-objetivo: nada de datos se mueve — mismo company_id. */
    @Test
    void dejaLosDatosDeLaEmpresaIntactos() {
        String company = empresaCliente("Con Clientes SL");
        String customer = cliente(company, "Bar Manolo");

        servicioPara(company).upgradeActiveCompany();

        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM customers WHERE id = ? AND company_id = ?",
                Integer.class, customer, company);
        assertEquals(1, n);
    }

    /** Sin el self-link, "Mi gestion" no encuentra la propia empresa. */
    @Test
    void creaElSelfLinkDeLaAsesoria() {
        String company = empresaCliente("Self Link SL");

        servicioPara(company).upgradeActiveCompany();

        assertEquals(1, selfLinks(company));
        assertEquals("SELF:" + company, jdbc.queryForObject("""
                SELECT token FROM advisory_invitations
                 WHERE advisory_company_id = ? AND invited_company_id = ?
                """, String.class, company, company));
    }

    /**
     * Punto de revision 1 — si el OWNER pulsa el boton mientras esta actuando
     * como cliente, se sube SU empresa (activeCompanyId del JWT), no la del
     * cliente que esta mirando.
     */
    @Test
    void subeLaEmpresaDelUsuarioNoLaDelTenantContext() {
        String mia = empresaCliente("La Mia SL");
        String delCliente = empresaCliente("La Del Cliente SL");

        servicioPara(mia).upgradeActiveCompany();

        assertEquals("ADVISORY", tipoDe(mia));
        assertEquals("CLIENT", tipoDe(delCliente));
    }

    // ====================================================================
    //  Rechazos
    // ====================================================================

    @Test
    void laSegundaLlamadaRechazaConAlreadyAdvisoryYNoDuplicaNada() {
        String company = empresaCliente("Dos Veces SL");
        AdvisoryUpgradeService service = servicioPara(company);
        service.upgradeActiveCompany();

        ResponseStatusException ex =
                assertThrows(ResponseStatusException.class, service::upgradeActiveCompany);

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("ALREADY_ADVISORY", ex.getReason());
        assertEquals(1, selfLinks(company));
        assertEquals(1, filasDeModulo(company, "team"));
    }

    /**
     * Una empresa vinculada a otra asesoria no se puede convertir: perderia el
     * modulo Comunicacion con su gestor (ModuleRepository#hasCommLink cuenta
     * hijos CLIENT, y no tiene ninguno).
     */
    @Test
    void rechazaSiLaEmpresaEstaVinculadaAOtraAsesoria() {
        String asesoria = empresaCliente("Mi Asesoria SL");
        String company = empresaCliente("Cliente Vinculado SL");
        jdbc.update("UPDATE companies SET parent_company_id = ? WHERE id = ?", asesoria, company);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> servicioPara(company).upgradeActiveCompany());

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("LINKED_TO_ADVISORY", ex.getReason());
        assertEquals("CLIENT", tipoDe(company));
        assertEquals(0, selfLinks(company));
    }

    @Test
    void rechazaSiLaEmpresaNoExiste() {
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> servicioPara(UUID.randomUUID().toString()).upgradeActiveCompany());

        assertEquals(HttpStatus.NOT_FOUND, ex.getStatusCode());
        assertEquals("COMPANY_NOT_FOUND", ex.getReason());
    }

    // ====================================================================
    //  Fixture
    // ====================================================================

    /** Empresa de empresario recien dada de alta: tipo CLIENT y sus modulos. */
    private static String empresaCliente(String nombre) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO companies (id, legal_name, trade_name, tax_identifier,
                                       company_type, active)
                VALUES (?, ?, ?, ?, 'CLIENT', TRUE)
                """, id, nombre, nombre, "B" + id.replace("-", "").substring(0, 8).toUpperCase());
        activarModulo(id, "billing");
        activarModulo(id, "time-clock");
        return id;
    }

    private static void activarModulo(String companyId, String slug) {
        jdbc.update("""
                INSERT INTO company_modules (id, company_id, module_id, active)
                SELECT UUID(), ?, m.id, TRUE FROM module_catalog m WHERE m.slug = ?
                """, companyId, slug);
    }

    private static String cliente(String companyId, String nombre) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customers (id, company_id, legal_name, customer_type, active)
                VALUES (?, ?, ?, 'COMPANY', TRUE)
                """, id, companyId, nombre);
        return id;
    }

    private static AdvisoryUpgradeService servicioPara(String companyId) {
        CurrentUserService currentUser = Mockito.mock(CurrentUserService.class);
        Mockito.when(currentUser.require()).thenReturn(new AuthenticatedUser(
                UUID.randomUUID().toString(), "benjamin@test.local", "Benjamin",
                "USER", companyId, "OWNER"));
        return new AdvisoryUpgradeService(jdbc, currentUser, Mockito.mock(AuditService.class));
    }

    private static String tipoDe(String companyId) {
        return jdbc.queryForObject(
                "SELECT company_type FROM companies WHERE id = ?", String.class, companyId);
    }

    /** 1 si el modulo esta activo para la empresa, 0 si no (o si no hay fila). */
    private static int moduloActivo(String companyId, String slug) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM company_modules cm
                  JOIN module_catalog m ON m.id = cm.module_id
                 WHERE cm.company_id = ? AND m.slug = ? AND cm.active = TRUE
                """, Integer.class, companyId, slug);
        return n == null ? 0 : n;
    }

    private static int filasDeModulo(String companyId, String slug) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM company_modules cm
                  JOIN module_catalog m ON m.id = cm.module_id
                 WHERE cm.company_id = ? AND m.slug = ?
                """, Integer.class, companyId, slug);
        return n == null ? 0 : n;
    }

    /** Filas del modulo que siguen con la marca de "desactivado" puesta. */
    private static int filasConMarcaDeApagado(String companyId, String slug) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM company_modules cm
                  JOIN module_catalog m ON m.id = cm.module_id
                 WHERE cm.company_id = ? AND m.slug = ?
                   AND cm.deactivated_at IS NOT NULL
                """, Integer.class, companyId, slug);
        return n == null ? 0 : n;
    }

    private static int selfLinks(String companyId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM advisory_invitations
                 WHERE advisory_company_id = ? AND invited_company_id = ?
                   AND status = 'ACCEPTED'
                """, Integer.class, companyId, companyId);
        return n == null ? 0 : n;
    }
}
