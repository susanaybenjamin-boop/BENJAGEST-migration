package com.benjagest.backend.labor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.accounting.FiscalYearGuardService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * NOM / LIQ — Verifica el motor de reversión de asientos de nómina, del que
 * depende que borrar una nómina NO deje asientos huérfanos (bug del 640 inflado).
 *
 * <ul>
 *   <li><b>Año abierto</b>: revertir un devengo POSTED crea su contraasiento
 *       (MANUAL_REVERSAL) — nunca queda huérfano sin contra.</li>
 *   <li><b>Año cerrado</b>: revertir LANZA (fiscalGuard 409). Como
 *       {@code PayslipService.delete} es @Transactional y ya NO se traga la
 *       excepción, ese 409 aborta el borrado entero y la nómina se conserva —
 *       antes se tragaba y la nómina se borraba dejando el devengo POSTED colgado.</li>
 * </ul>
 */
class PayslipReversalIntegrationTest {

    private static final String COMPANY = "c0640000-0000-0000-0000-000000000640";
    private static final String EMP = "e0640000-0000-0000-0000-000000000640";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static PayslipJournalEntryService service;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestrevtest");
        String url = cfg.getURL("benjagestrevtest");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        TenantContext tenant = mock(TenantContext.class);
        when(tenant.getCurrentCompanyId()).thenReturn(COMPANY);
        // El guard REAL (lee el estado del ejercicio de la BD), no un mock.
        FiscalYearGuardService fiscalGuard = new FiscalYearGuardService(jdbc, tenant);
        service = new PayslipJournalEntryService(jdbc, tenant, fiscalGuard);

        seed();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    private static void seed() {
        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, 'Empresa Rev', 'INTERNAL')", COMPANY);
        acc("640", "Sueldos y salarios", "EXPENSE");
        acc("465", "Remuneraciones pendientes de pago", "LIABILITY");
        acc("476", "Organismos de la Seguridad Social, acreedores", "LIABILITY");
        acc("475", "Hacienda Pública, acreedora por conceptos fiscales", "LIABILITY");
        jdbc.update("INSERT INTO employees (id, company_id, full_name) VALUES (?, ?, ?)", EMP, COMPANY, "Empleado Rev");
        // Dos ejercicios ABIERTOS; el test del año cerrado bloquea el 2025 él mismo.
        fiscalYear(2025);
        fiscalYear(2026);
    }

    private static void acc(String code, String name, String type) {
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """, UUID.randomUUID().toString(), COMPANY, code, name, type);
    }

    private static void fiscalYear(int year) {
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                VALUES (?, ?, ?, ?, ?, 'OPEN')
                """, UUID.randomUUID().toString(), COMPANY, year,
                Date.valueOf(year + "-01-01"), Date.valueOf(year + "-12-31"));
    }

    /** Crea un devengo y lo deja VALIDADO (POSTED). Devuelve [payslipId, entryId].
     *  El contraasiento guarda como source_id el id del ASIENTO original. */
    private static String[] accrualPosted(int year, int month) {
        String payslipId = UUID.randomUUID().toString();
        var p = new PayslipJournalEntryService.PayslipAccrual(
                payslipId, EMP, "Empleado Rev", year, month,
                new BigDecimal("1000.00"), new BigDecimal("100.00"));
        String entryId = service.createAccrual(p, null);
        assertNotNull(entryId, "el devengo debe crearse");
        jdbc.update("UPDATE journal_entries SET status='POSTED' WHERE id=?", entryId);
        return new String[]{payslipId, entryId};
    }

    private static int contraCount(String origEntryId) {
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id=? AND source_type='MANUAL_REVERSAL' AND source_id=?
                """, Integer.class, COMPANY, origEntryId);
    }

    @Test
    void anioAbierto_revertirDevengoPosted_creaContraasientoConLineas() {
        String[] ids = accrualPosted(2026, 6);

        service.reverseAll(ids[0]);

        assertEquals(1, contraCount(ids[1]),
                "se crea el contraasiento (el devengo POSTED no queda huérfano)");
        // El bug era que el contra nacía SIN líneas: verificamos que copió las 4
        // (640 / 476 / 4751-475 / 465), con debe y haber invertidos y cuadrados.
        var totals = jdbc.queryForMap("""
                SELECT COUNT(*) n, COALESCE(SUM(l.debit),0) debe, COALESCE(SUM(l.credit),0) haber
                  FROM journal_entry_lines l
                  JOIN journal_entries je ON je.id = l.journal_entry_id
                 WHERE je.company_id=? AND je.source_type='MANUAL_REVERSAL' AND je.source_id=?
                """, COMPANY, ids[1]);
        assertEquals(4L, ((Number) totals.get("n")).longValue(), "el contra copia las 4 líneas");
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) totals.get("haber")),
                "el haber del contra = el debe original (640 revertido)");
        assertEquals(0, new BigDecimal("1000.00").compareTo((BigDecimal) totals.get("debe")),
                "el debe del contra = el haber original");
    }

    @Test
    void reverseAll_esIdempotente_noDuplicaElContraasiento() {
        String[] ids = accrualPosted(2026, 8);
        service.reverseAll(ids[0]);
        service.reverseAll(ids[0]); // segunda vez (p.ej. otro borrado/recálculo)
        assertEquals(1, contraCount(ids[1]),
                "un asiento ya contrarrestado no se contrarresta otra vez");
    }

    @Test
    void recalcularNominaValidada_contrarrestaElViejo_noLoDuplica() {
        // createAccrual dos veces sobre el MISMO payslip, validando en medio =
        // el flujo de "recalcular una nómina ya validada".
        String payslipId = UUID.randomUUID().toString();
        var p = new PayslipJournalEntryService.PayslipAccrual(
                payslipId, EMP, "Empleado Rev", 2026, 9,
                new BigDecimal("1000.00"), new BigDecimal("100.00"));
        String a1 = service.createAccrual(p, null);
        assertNotNull(a1);
        jdbc.update("UPDATE journal_entries SET status='POSTED' WHERE id=?", a1);

        // Recalcular: debe contrarrestar A1 (una vez) y crear un devengo NUEVO.
        String a2 = service.createAccrual(p, null);
        assertNotNull(a2, "el recálculo crea un devengo nuevo");
        assertNotEquals(a1, a2, "es un asiento distinto, no reusa el validado");
        assertEquals(1, contraCount(a1),
                "el devengo viejo se contrarresta UNA vez (antes se quedaba sumando -> 640 doblado)");

        // Un tercer recálculo no debe re-contrarrestar A1 (idempotencia).
        jdbc.update("UPDATE journal_entries SET status='POSTED' WHERE id=?", a2);
        service.createAccrual(p, null);
        assertEquals(1, contraCount(a1), "A1 sigue con un solo contra tras otro recálculo");
    }

    @Test
    void anioCerrado_revertirDevengoPosted_lanza_yNoDejaHuerfano() {
        String[] ids = accrualPosted(2025, 6);
        // Cerramos el 2025 DESPUÉS de crear/validar el devengo.
        jdbc.update("UPDATE fiscal_years SET status='LOCKED' WHERE company_id=? AND year_number=2025", COMPANY);

        // reverseAll debe LANZAR (el guard 409). Con delete @Transactional que ya
        // no se traga la excepción, esto aborta el borrado -> la nómina se conserva.
        assertThrows(RuntimeException.class, () -> service.reverseAll(ids[0]),
                "revertir un asiento de ejercicio cerrado debe lanzar, no borrar en silencio");
        assertEquals(0, contraCount(ids[1]),
                "en año cerrado no se crea contra: la operación se aborta entera");
    }
}
