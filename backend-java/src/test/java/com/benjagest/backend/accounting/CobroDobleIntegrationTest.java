package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
import org.mockito.Mockito;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.web.server.ResponseStatusException;

/**
 * COB-PAID — Verificacion EN EJECUCION (CLAUDE.md §10.ter) de que una factura
 * ya cobrada no se puede volver a cobrar por Vencimientos, salvo que antes se
 * deshaga (devuelva) el cobro.
 *
 * <p>Bug que cierra: "Cobrar varias" marca la venta PAID en
 * {@code sales_invoices.paid_amount} sin tocar sus vencimientos. Al abrir luego
 * Vencimientos, el vencimiento seguia PENDING y se podia cobrar otra vez, con un
 * segundo asiento 572/430. Lo mismo en compras con {@code purchase_invoices.paid}.
 *
 * <p>Corre el SQL real contra MariaDB embebida con TODAS las migraciones.
 */
class CobroDobleIntegrationTest {

    private static final String COMPANY = "c0b00000-0000-0000-0000-0000000000d1";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static PaymentScheduleService payments;

    private static final class FixedTenant implements TenantContext {
        @Override public String getCurrentCompanyId() { return COMPANY; }
        @Override public void setCurrentCompanyId(String id) { /* fija */ }
    }

    private static String customerId;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestcobrodoble");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestcobrodoble"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        payments = new PaymentScheduleService(jdbc, new FixedTenant(),
                Mockito.mock(com.benjagest.backend.auth.CurrentUserService.class),
                Mockito.mock(com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService.class),
                Mockito.mock(com.benjagest.backend.billing.tpb.BillingAgreementGuard.class),
                new InvoiceCounterpartyAccountResolver(jdbc));

        jdbc.update("""
                INSERT INTO companies (id, legal_name, company_type, legal_form)
                VALUES (?, 'Cobro Doble SL', 'INTERNAL', 'SL')
                """, COMPANY);
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                VALUES (?, ?, 2026, ?, ?, 'OPEN')
                """, UUID.randomUUID().toString(), COMPANY,
                Date.valueOf("2026-01-01"), Date.valueOf("2026-12-31"));
        account("430", "Clientes", "ASSET");
        account("400", "Proveedores", "LIABILITY");
        account("572", "Bancos", "ASSET");
        customerId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customers (id, company_id, legal_name, customer_type, active)
                VALUES (?, ?, 'Bar Manolo', 'COMPANY', TRUE)
                """, customerId, COMPANY);
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) db.stop();
    }

    private static void account(String code, String name, String type) {
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """, UUID.randomUUID().toString(), COMPANY, code, name, type);
    }

    private static String salesInvoice(String number, String paymentStatus, String paid) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sales_invoices (id, company_id, customer_id, invoice_number,
                        invoice_date, invoice_type, status, payment_status,
                        subtotal, vat_total, retention_total, total, paid_amount, currency)
                VALUES (?, ?, ?, ?, ?, 'HISTORICAL', 'VALIDATED', ?,
                        1000.00, 210.00, 0, 1210.00, ?, 'EUR')
                """, id, COMPANY, customerId, number, Date.valueOf("2026-02-15"),
                paymentStatus, new BigDecimal(paid));
        return id;
    }

    private static String dueDate(String kind, String invoiceId, String amount) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO invoice_due_dates (id, company_id, invoice_id, invoice_kind,
                        seq, due_date, amount, status)
                VALUES (?, ?, ?, ?, 1, ?, ?, 'PENDING')
                """, id, COMPANY, invoiceId, kind, Date.valueOf("2026-03-15"), new BigDecimal(amount));
        return id;
    }

    private static int cobrosDe(String dueDateId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM journal_entries
                 WHERE company_id = ? AND source_type = 'DUE_DATE_PAYMENT' AND source_id = ?
                """, Integer.class, COMPANY, dueDateId);
        return n == null ? 0 : n;
    }

    private static String paymentStatusDe(String invoiceId) {
        return jdbc.queryForObject("SELECT payment_status FROM sales_invoices WHERE id = ?",
                String.class, invoiceId);
    }

    private static PaymentScheduleService.PayRequest banco() {
        return new PaymentScheduleService.PayRequest("572", LocalDate.of(2026, 3, 15), "TRANSFER", null);
    }

    /** El caso real: cobrada por "Cobrar varias", vencimiento aun PENDING. */
    @Test
    void unaVentaCobradaPorCobrarVariasNoSeCobraOtraVezPorVencimientos() {
        String inv = salesInvoice("CD-1", "PAID", "1210.00");
        String vto = dueDate("SALES", inv, "1210.00");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> payments.pay(vto, banco()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(0, cobrosDe(vto), "no debe generarse un segundo asiento de cobro");
        assertEquals("PAID", paymentStatusDe(inv));
    }

    /** Cobrada a medias por otra via: no se puede cobrar un vencimiento mayor que lo pendiente. */
    @Test
    void unVencimientoMayorQueLoPendienteSeRechaza() {
        String inv = salesInvoice("CD-2", "PARTIAL", "500.00");
        String vto = dueDate("SALES", inv, "1210.00");

        assertThrows(ResponseStatusException.class, () -> payments.pay(vto, banco()));
        assertEquals(0, cobrosDe(vto));
    }

    /** Devolucion: cobrar -> deshacer cobro -> se puede cobrar de nuevo. */
    @Test
    void trasDeshacerElCobroSePuedeVolverACobrar() {
        String inv = salesInvoice("CD-3", "PENDING", "0");
        String vto = dueDate("SALES", inv, "1210.00");

        payments.pay(vto, banco());
        assertEquals("PAID", paymentStatusDe(inv));
        assertEquals(1, cobrosDe(vto));

        payments.unpay(vto);
        assertEquals("PENDING", paymentStatusDe(inv), "al devolver el cobro vuelve a pendiente");
        assertEquals(0, cobrosDe(vto), "deshacer el cobro borra su asiento");

        payments.pay(vto, banco());
        assertEquals("PAID", paymentStatusDe(inv));
        assertEquals(1, cobrosDe(vto), "un solo asiento de cobro, no dos");
    }

    /** Compras: pagada por "Registrar pago" (GAS-2), vencimiento aun PENDING. */
    @Test
    void unaCompraYaPagadaNoSePagaOtraVezPorVencimientos() {
        String inv = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO purchase_invoices (id, company_id, supplier_name, invoice_number,
                        invoice_date, total_amount, status, paid)
                VALUES (?, ?, 'Ferreteria Paco', 'P-1', ?, 300.00, 'POSTED', TRUE)
                """, inv, COMPANY, Date.valueOf("2026-05-10"));
        String vto = dueDate("PURCHASE", inv, "300.00");

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> payments.pay(vto, banco()));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals(0, cobrosDe(vto));
    }
}
