package com.benjagest.backend.aeat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.accounting.FiscalYearGuardService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tax.TaxLedgerService;
import com.benjagest.backend.tenant.TenantContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Date;
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
 * LIQ-111 — Prueba de integración REAL del modelo 111, sobre la MariaDB
 * embebida (MariaDB4j) con las migraciones Flyway del proyecto aplicadas.
 * Reproduce el 2T 2026 de Benjamin (1 empleado, base 1.503,48, IRPF 30,07,
 * casilla 30 = 30,07) y verifica de punta a punta:
 *
 * <ol>
 *   <li>{@code generate111} calcula las casillas desde las nóminas del
 *       trimestre — y EXCLUYE una nómina de enero (1T) para probar el filtro.</li>
 *   <li>{@code TaxLedgerService.onPaid} genera el asiento {@code 4751 → 572}
 *       por 30,07 que vacía la retención acumulada.</li>
 * </ol>
 *
 * <p>Reutiliza la empresa demo sembrada por V3/V4
 * ({@code 11111111-…}), que ya trae las cuentas 4751 y 572 del PGC.
 */
class Model111IntegrationTest {

    private static final String COMPANY = "c0111111-0000-0000-0000-000000000111";
    // La empresa real usa la 475 genérica (la que siembra V147), NO la subcuenta
    // 4751 — así el test prueba el mismo plan contable que tiene Benjamin.
    private static final String ACC_475 = "a0475000-0000-0000-0000-000000000111";
    private static final String ACC_572 = "a5720000-0000-0000-0000-000000000111";

    private static DB db;
    private static JdbcTemplate jdbc;

    private static AeatExtraModelsService aeatService;
    private static TaxLedgerService taxLedger;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0); // 0 = MariaDB4j busca un puerto libre
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagest111test");

        String url = cfg.getURL("benjagest111test");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "root", "");

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate();

        jdbc = new JdbcTemplate(ds);

        // Colaboradores: todos mocks (TenantContext tiene varios métodos).
        TenantContext tenant = mock(TenantContext.class);
        when(tenant.getCurrentCompanyId()).thenReturn(COMPANY);
        CurrentUserService currentUser = mock(CurrentUserService.class);
        when(currentUser.require()).thenThrow(new RuntimeException("sin usuario en test"));
        FiscalYearGuardService fiscalGuard = mock(FiscalYearGuardService.class);

        aeatService = new AeatExtraModelsService(jdbc, tenant, new ObjectMapper(), currentUser);
        taxLedger = new TaxLedgerService(jdbc, tenant, currentUser, fiscalGuard);

        seedFixture();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    /** Fixture del 2T de Benjamin + una nómina de enero (1T) que NO debe contar. */
    private static void seedFixture() {
        // Empresa propia + sus cuentas del PGC (la demo de V3/V4 la limpian
        // migraciones posteriores, así que no dependemos de ella).
        jdbc.update("""
                INSERT INTO companies (id, legal_name, company_type)
                VALUES (?, 'Empresa Test 111', 'INTERNAL')
                """, COMPANY);
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, '475', 'Hacienda Pública, acreedora por conceptos fiscales', 'LIABILITY', TRUE)
                """, ACC_475, COMPANY);
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, '572', 'Bancos', 'ASSET', TRUE)
                """, ACC_572, COMPANY);

        // Ejercicio 2026 abierto.
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                VALUES (?, ?, 2026, ?, ?, 'OPEN')
                """, UUID.randomUUID().toString(), COMPANY,
                Date.valueOf("2026-01-01"), Date.valueOf("2026-12-31"));

        String emp = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO employees (id, company_id, full_name) VALUES (?, ?, ?)",
                emp, COMPANY, "Empleado Prueba 2T");

        // 2T: Abr/May/Jun -> Σ bruto 1.503,48 ; Σ IRPF 30,07.
        insertPayslip(emp, 2026, 4, "501.16", "10.02");
        insertPayslip(emp, 2026, 5, "501.16", "10.02");
        insertPayslip(emp, 2026, 6, "501.16", "10.03");
        // 1T (enero): NO debe aparecer en el 2T (prueba del filtro trimestral).
        insertPayslip(emp, 2026, 1, "999.00", "50.00");
    }

    private static void insertPayslip(String emp, int year, int month, String gross, String irpf) {
        jdbc.update("""
                INSERT INTO payslips (id, company_id, employee_id, period_year, period_month,
                        payslip_type, gross_amount, irpf_amount, status)
                VALUES (?, ?, ?, ?, ?, 'MONTHLY', ?, ?, 'CALCULATED')
                """, UUID.randomUUID().toString(), COMPANY, emp, year, month,
                new BigDecimal(gross), new BigDecimal(irpf));
    }

    @Test
    void generate111_reproduceElSegundoTrimestreReal() {
        var v = aeatService.generate111(2026, 2, false);

        assertEquals(1, v.trabajoPerceptores(), "casilla 01: un solo perceptor");
        assertAmount("1503.48", v.trabajoBase(), "casilla 02: percepciones");
        assertAmount("30.07", v.trabajoRetencion(), "casilla 03: retenciones");
        assertEquals(0, v.actividadesPerceptores(), "sin actividades económicas");
        assertAmount("30.07", v.resultado(), "casilla 30: resultado a ingresar");
    }

    @Test
    void generate111_excluyeLaNominaDeOtroTrimestre() {
        // El 1T incluye la nómina de enero (999,00 / 50,00), NO la del 2T.
        var q1 = aeatService.generate111(2026, 1, false);
        assertAmount("999.00", q1.trabajoBase(), "el 1T solo ve enero");
        assertAmount("50.00", q1.trabajoRetencion(), "el 1T solo ve enero");
    }

    @Test
    void onPaid_generaAsiento4751contra572_porElImporteDelModelo() {
        String filing = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO tax_filings (id, company_id, tax_model_code, period_year,
                        period_quarter, status, total_amount)
                VALUES (?, ?, '111', 2026, 2, 'PAID', ?)
                """, filing, COMPANY, new BigDecimal("30.07"));

        String entryId = taxLedger.onPaid(filing);
        assertNotNull(entryId, "onPaid debe crear el asiento de pago del 111");

        List<Map<String, Object>> lines = jdbc.queryForList("""
                SELECT a.code AS code, l.debit AS debit, l.credit AS credit
                  FROM journal_entry_lines l
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ?
                 ORDER BY a.code
                """, entryId);

        assertEquals(2, lines.size(), "el asiento tiene dos líneas");
        // La empresa tiene la 475 (no la 4751): el pago debe caer a la 475.
        boolean debe475 = lines.stream().anyMatch(r ->
                String.valueOf(r.get("code")).startsWith("475")
                        && new BigDecimal("30.07").compareTo((BigDecimal) r.get("debit")) == 0);
        boolean haber572 = lines.stream().anyMatch(r ->
                String.valueOf(r.get("code")).startsWith("572")
                        && new BigDecimal("30.07").compareTo((BigDecimal) r.get("credit")) == 0);
        assertTrue(debe475, "Debe 475/4751 por 30,07 (vacía la retención practicada)");
        assertTrue(haber572, "Haber 572 por 30,07 (sale del banco)");
    }

    private static void assertAmount(String expected, BigDecimal actual, String msg) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                msg + " — esperado " + expected + " pero fue " + actual);
    }
}
