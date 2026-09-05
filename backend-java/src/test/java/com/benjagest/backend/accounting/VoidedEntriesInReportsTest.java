package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * CONTA-4 — Los asientos ANULADOS se estaban sumando en Sumas y Saldos, en el
 * Balance de Situación y en la Cuenta de Pérdidas y Ganancias.
 *
 * <p>Benjamin, con producción delante: <i>"en libro mayor la 430 está a cero,
 * pero si me voy a sumas y saldos, la genérica 430 tiene un saldo acreedor de
 * 6112.92"</i>, y lo mismo en proveedores. Esos 6.112,92 eran exactamente los
 * cinco cobros ANULADOS de esa cuenta.
 *
 * <p>La causa era un LEFT JOIN mal filtrado: la condición
 * {@code je.status = 'POSTED'} vivía en el ON, así que un asiento anulado hacía
 * fallar el join y dejaba {@code je} a NULL — <b>pero la línea seguía ahí</b> y
 * {@code SUM(l.debit)} la contaba igual. De paso, al quedar
 * {@code je.entry_date} a NULL, esas líneas se colaban también por el filtro de
 * fechas.
 *
 * <p>Contaminaba muchas más cuentas que la 430: bancos, ventas, IVA... o sea,
 * las cifras de los tres informes.
 */
class VoidedEntriesInReportsTest {

    private static final String COMPANY = "c0conta4-0000-0000-0000-000000000001";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static JournalQueryService queryService;

    private static final class FixedTenant implements TenantContext {
        @Override public String getCurrentCompanyId() { return COMPANY; }
        @Override public void setCurrentCompanyId(String id) { /* fijo */ }
    }

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestconta4test");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestconta4test"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        queryService = new JournalQueryService(jdbc, new FixedTenant());

        jdbc.update("INSERT INTO companies (id, legal_name, company_type) "
                + "VALUES (?, 'Asesoria', 'INTERNAL')", COMPANY);
        String fy = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                VALUES (?, ?, 2026, ?, ?, 'OPEN')
                """, fy, COMPANY, Date.valueOf("2026-01-01"), Date.valueOf("2026-12-31"));

        String c430 = cuenta("430", "Clientes");
        String c572 = cuenta("572", "Bancos");

        // Un cobro VIVO de 1.000 y otro ANULADO de 6.112,92 (el caso real).
        String vivo = asiento(fy, 1, "2026-03-10", "Cobro bueno", "POSTED");
        linea(vivo, c572, "1000.00", "0");
        linea(vivo, c430, "0", "1000.00");

        String anulado = asiento(fy, 2, "2026-03-11", "Cobro anulado", "VOIDED");
        linea(anulado, c572, "6112.92", "0");
        linea(anulado, c430, "0", "6112.92");

        // Y uno POSTED pero FUERA del rango que se consultara.
        String fuera = asiento(fy, 3, "2026-11-30", "Cobro de noviembre", "POSTED");
        linea(fuera, c572, "500.00", "0");
        linea(fuera, c430, "0", "500.00");
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.stop();
        }
    }

    private static String cuenta(String code, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, ?, ?, 'ASSET', TRUE)
                """, id, COMPANY, code, name);
        return id;
    }

    private static String asiento(String fy, int num, String date, String concept, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO journal_entries (id, company_id, fiscal_year_id, entry_number,
                        entry_date, concept, status)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, COMPANY, fy, num, Date.valueOf(date), concept, status);
        return id;
    }

    private static void linea(String entryId, String accountId, String debit, String credit) {
        jdbc.update("""
                INSERT INTO journal_entry_lines (id, journal_entry_id, account_id, debit, credit)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), entryId, accountId,
                new BigDecimal(debit), new BigDecimal(credit));
    }

    private static JournalQueryService.BalanceRow fila(String code, LocalDate from, LocalDate to) {
        for (var r : queryService.balance(from, to, null)) {
            if (code.equals(r.code())) return r;
        }
        return null;
    }

    @Test
    void unAsientoAnuladoNoSumaEnSumasYSaldos() {
        var r = fila("430", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, new BigDecimal("1000.00").compareTo(r.totalCredit()),
                "solo debe contar el cobro VIVO de 1.000; el anulado de 6.112,92 no");
        assertEquals(0, BigDecimal.ZERO.compareTo(r.totalDebit()));
    }

    /** El mismo apunte anulado tampoco puede aparecer en la contrapartida. */
    @Test
    void tampocoContaminaLaCuentaDeBancos() {
        var r = fila("572", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, new BigDecimal("1000.00").compareTo(r.totalDebit()),
                "6.112,92 del asiento anulado no son un movimiento de banco");
    }

    /**
     * Segundo efecto del mismo fallo: al quedar {@code je.entry_date} a NULL en
     * los anulados, esas lineas se colaban por el filtro de fechas. Y un asiento
     * POSTED de noviembre no debe aparecer en un rango que acaba en junio.
     */
    @Test
    void elRangoDeFechasSeRespeta() {
        var junio = fila("430", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, new BigDecimal("1000.00").compareTo(junio.totalCredit()),
                "el cobro de noviembre esta fuera del rango");

        var anio = fila("430", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
        assertEquals(0, new BigDecimal("1500.00").compareTo(anio.totalCredit()),
                "en el anio completo si entran los dos cobros vivos (1.000 + 500)");
    }

    /** Una cuenta cuyo unico movimiento esta anulado no debe ni aparecer. */
    @Test
    void unaCuentaSoloConAnuladosNoSale() {
        String fy = jdbc.queryForObject(
                "SELECT id FROM fiscal_years WHERE company_id = ?", String.class, COMPANY);
        String c555 = cuenta("555", "Partidas pendientes");
        String e = asiento(fy, 90, "2026-04-01", "Solo anulado", "VOIDED");
        linea(e, c555, "999.00", "0");
        try {
            assertTrue(fila("555", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)) == null,
                    "sin movimientos vivos, la cuenta no tiene por que listarse");
        } finally {
            jdbc.update("DELETE l FROM journal_entry_lines l WHERE l.journal_entry_id = ?", e);
            jdbc.update("DELETE FROM journal_entries WHERE id = ?", e);
            jdbc.update("DELETE FROM accounting_accounts WHERE id = ?", c555);
        }
    }
}
