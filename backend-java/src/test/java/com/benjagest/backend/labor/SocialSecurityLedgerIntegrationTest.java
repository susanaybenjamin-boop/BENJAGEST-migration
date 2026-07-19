package com.benjagest.backend.labor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.accounting.FiscalYearGuardService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * LIQ-SS — Prueba de integración REAL del pago de la Seguridad Social, sobre la
 * MariaDB embebida (MariaDB4j) con las migraciones Flyway aplicadas. Verifica
 * que {@link SocialSecurityLedgerService#payMonth} genera el asiento
 * {@code Debe 476 / Haber 572} por la cuota total del mes (empresa + trabajador)
 * y que es idempotente (pagar dos veces no duplica el asiento).
 */
class SocialSecurityLedgerIntegrationTest {

    private static final String COMPANY = "c0555111-0000-0000-0000-000000000476";
    private static final String ACC_476 = "a4760000-0000-0000-0000-000000000476";
    private static final String ACC_572 = "a5720000-0000-0000-0000-000000000476";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static SocialSecurityLedgerService service;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestsstest");

        String url = cfg.getURL("benjagestsstest");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        TenantContext tenant = mock(TenantContext.class);
        when(tenant.getCurrentCompanyId()).thenReturn(COMPANY);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.require()).thenThrow(new RuntimeException("sin usuario en test"));
        FiscalYearGuardService fiscalGuard = mock(FiscalYearGuardService.class);

        service = new SocialSecurityLedgerService(jdbc, tenant, currentUser, fiscalGuard);

        seedFixture();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    private static void seedFixture() {
        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, 'Empresa Test SS', 'INTERNAL')",
                COMPANY);
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, '476', 'Organismos de la Seguridad Social, acreedores', 'LIABILITY', TRUE)
                """, ACC_476, COMPANY);
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, '572', 'Bancos', 'ASSET', TRUE)
                """, ACC_572, COMPANY);
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                VALUES (?, ?, 2026, ?, ?, 'OPEN')
                """, UUID.randomUUID().toString(), COMPANY,
                Date.valueOf("2026-01-01"), Date.valueOf("2026-12-31"));

        // Meses distintos por test (comparten BD; el orden de JUnit no se garantiza).
        // Abril: empresa 300,00 + trabajador 100,00 = 400,00.
        insertContribution(2026, 4, "EMPLOYER_COMMON", "300.00");
        insertContribution(2026, 4, "EMPLOYEE_COMMON", "100.00");
        // Mayo: empresa 200,00 + trabajador 50,00 = 250,00.
        insertContribution(2026, 5, "EMPLOYER_COMMON", "200.00");
        insertContribution(2026, 5, "EMPLOYEE_COMMON", "50.00");
    }

    private static void insertContribution(int year, int month, String type, String amount) {
        jdbc.update("""
                INSERT INTO social_security_contributions (id, company_id, employee_id,
                        period_year, period_month, contribution_type, base_amount,
                        contribution_amount, status)
                VALUES (?, ?, NULL, ?, ?, ?, 0, ?, 'CALCULATED')
                """, UUID.randomUUID().toString(), COMPANY, year, month, type,
                new BigDecimal(amount));
    }

    @Test
    void payMonth_generaAsiento476contra572_porLaCuotaTotalDelMes() {
        String entryId = service.payMonth(2026, 4, LocalDate.of(2026, 5, 31));
        assertNotNull(entryId, "payMonth debe crear el asiento de pago de SS");

        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT a.code AS code, l.debit AS debit, l.credit AS credit
                  FROM journal_entry_lines l
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ?
                """, entryId);

        assertEquals(2, lines.size(), "el asiento tiene dos líneas");
        boolean debe476 = lines.stream().anyMatch(r ->
                String.valueOf(r.get("code")).startsWith("476")
                        && new BigDecimal("400.00").compareTo((BigDecimal) r.get("debit")) == 0);
        boolean haber572 = lines.stream().anyMatch(r ->
                String.valueOf(r.get("code")).startsWith("572")
                        && new BigDecimal("400.00").compareTo((BigDecimal) r.get("credit")) == 0);
        assertTrue(debe476, "Debe 476 por 400,00 (salda la cuota a la TGSS)");
        assertTrue(haber572, "Haber 572 por 400,00 (sale del banco)");
    }

    @Test
    void payMonth_esIdempotente_noDuplicaElAsiento() {
        // Usa MAYO (independiente del test de abril, corra en el orden que corra).
        String first = service.payMonth(2026, 5, LocalDate.of(2026, 6, 30));
        assertNotNull(first, "el primer pago de mayo crea el asiento");
        String second = service.payMonth(2026, 5, LocalDate.of(2026, 6, 30));
        assertNull(second, "un segundo pago del mismo mes no crea otro asiento");

        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id = ? AND source_type = 'SS_PAYMENT' AND source_id = 'SS-2026-05'
                """, Integer.class, COMPANY);
        assertEquals(1, count, "solo un asiento de pago para mayo 2026");
    }

    @Test
    void previewPending_listaElMesConCuota() {
        var pend = service.previewPending(2026);
        var abril = pend.stream().filter(p -> p.month() == 4).findFirst().orElse(null);
        assertNotNull(abril, "abril aparece en el preview");
        assertEquals(0, new BigDecimal("400.00").compareTo(abril.amount()), "cuota de abril = 400,00");
    }
}
