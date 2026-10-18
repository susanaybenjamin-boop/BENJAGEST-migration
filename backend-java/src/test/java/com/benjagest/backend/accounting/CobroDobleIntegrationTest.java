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
    private static com.benjagest.backend.billing.payments.MultiAllocationPaymentService multi;

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
        multi = new com.benjagest.backend.billing.payments.MultiAllocationPaymentService(jdbc,
                new FixedTenant(),
                Mockito.mock(com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService.class),
                Mockito.mock(com.benjagest.backend.auth.CurrentUserService.class),
                Mockito.mock(com.benjagest.backend.billing.tpb.BillingAgreementGuard.class),
                payments);

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

    /**
     * Datos HEREDADOS: una venta que el "Cobrar varias" de antes de COB-MULTI dejó
     * PAGADA sin tocar su vencimiento (sigue PENDING).
     */
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

    // ====================================================================
    //  COB-MULTI — "Cobrar varias" cobra por vencimientos
    // ====================================================================

    private static int filasCobrarVarias(String invoiceId) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM sales_invoice_payments WHERE invoice_id = ? AND due_date_id IS NOT NULL",
                Integer.class, invoiceId);
        return n == null ? 0 : n;
    }

    private static java.util.List<java.util.Map<String, Object>> vencimientosDe(String invoiceId) {
        return jdbc.queryForList("""
                SELECT id, amount, status, journal_entry_id FROM invoice_due_dates
                 WHERE invoice_kind = 'SALES' AND invoice_id = ? ORDER BY seq
                """, invoiceId);
    }

    private static void cobrarVarias(String invoiceId, String amount) {
        multi.registerMultiAllocation(new com.benjagest.backend.billing.payments
                .MultiAllocationPaymentService.MultiAllocationRequest(
                LocalDate.of(2026, 3, 20), new BigDecimal(amount), "BANK_TRANSFER", "TRF-77", null,
                java.util.List.of(new com.benjagest.backend.billing.payments
                        .MultiAllocationPaymentService.Allocation(invoiceId, new BigDecimal(amount)))));
    }

    /** El cobro completo salda el vencimiento con asiento, y Vencimientos lo ve PAGADO. */
    @Test
    void cobrarVariasSaldaElVencimientoConAsiento() {
        String inv = salesInvoice("CV-1", "PENDING", "0");
        cobrarVarias(inv, "1210.00");

        var vtos = vencimientosDe(inv);
        assertEquals(1, vtos.size());
        assertEquals("PAID", vtos.get(0).get("status"), "Vencimientos debe verla cobrada");
        String vto = (String) vtos.get(0).get("id");
        assertEquals(1, cobrosDe(vto), "el cobro debe tener su asiento 572/430");
        assertEquals("PAID", paymentStatusDe(inv));
        assertEquals(1, filasCobrarVarias(inv), "el registro del cobro queda enlazado al vencimiento");
        String concepto = jdbc.queryForObject("""
                SELECT concept FROM journal_entries WHERE source_id = ?
                """, String.class, vto);
        assertEquals("Cobro fra. CV-1 - TRF-77", concepto);
    }

    /** Cobro parcial: el vencimiento se parte en lo cobrado + un resto PENDIENTE. */
    @Test
    void cobrarVariasParcialParteElVencimiento() {
        String inv = salesInvoice("CV-2", "PENDING", "0");
        cobrarVarias(inv, "500.00");

        var vtos = vencimientosDe(inv);
        assertEquals(2, vtos.size(), "debe quedar el cobrado + el resto pendiente");
        assertEquals("PAID", vtos.get(0).get("status"));
        assertEquals(0, new BigDecimal("500.00").compareTo((BigDecimal) vtos.get(0).get("amount")));
        assertEquals("PENDING", vtos.get(1).get("status"));
        assertEquals(0, new BigDecimal("710.00").compareTo((BigDecimal) vtos.get(1).get("amount")));
        assertEquals("PARTIAL", paymentStatusDe(inv));

        // Y el resto se puede cobrar después por Vencimientos.
        payments.pay((String) vtos.get(1).get("id"), banco());
        assertEquals("PAID", paymentStatusDe(inv));
    }

    /** Deshacer un cobro de "Cobrar varias": fuera asiento y registro, factura pendiente. */
    @Test
    void deshacerUnCobroDeCobrarVariasLoQuitaDelTodo() {
        String inv = salesInvoice("CV-3", "PENDING", "0");
        cobrarVarias(inv, "1210.00");
        String vto = (String) vencimientosDe(inv).get(0).get("id");

        payments.unpay(vto);
        assertEquals("PENDING", paymentStatusDe(inv));
        assertEquals(0, cobrosDe(vto), "sin asiento de cobro");
        assertEquals(0, filasCobrarVarias(inv), "sin fila en el registro de cobros");

        // Devuelto el cobro, se puede volver a cobrar (otra vez por Cobrar varias).
        cobrarVarias(inv, "1210.00");
        assertEquals("PAID", paymentStatusDe(inv));
    }

    /** No se puede cobrar por "Cobrar varias" más de lo pendiente. */
    @Test
    void cobrarVariasNoCobraMasDeLoPendiente() {
        String inv = salesInvoice("CV-4", "PENDING", "0");
        cobrarVarias(inv, "1210.00");
        assertThrows(ResponseStatusException.class, () -> cobrarVarias(inv, "10.00"));
    }

    // ====================================================================
    //  COB-VOID-LINK — vencimiento cuyo asiento de cobro se anuló
    // ====================================================================

    private static ManualJournalEntryService manual() {
        return new ManualJournalEntryService(jdbc, new FixedTenant(),
                Mockito.mock(com.benjagest.backend.auth.CurrentUserService.class),
                Mockito.mock(FiscalYearGuardService.class),
                Mockito.mock(TerceroAccountResolverService.class),
                Mockito.mock(AccountingLearningService.class),
                Mockito.mock(com.benjagest.backend.accounting.importpdf.ImportedPdfStorageService.class),
                Mockito.mock(com.benjagest.backend.purchases.pdfimport.PdfTextExtractor.class),
                Mockito.mock(com.benjagest.backend.purchases.pdfimport.InvoiceFieldsExtractor.class));
    }

    private static String asientoDe(String dueDateId) {
        return jdbc.queryForObject("SELECT journal_entry_id FROM invoice_due_dates WHERE id = ?",
                String.class, dueDateId);
    }

    private static String estadoAsiento(String entryId) {
        return jdbc.queryForObject("SELECT status FROM journal_entries WHERE id = ?", String.class, entryId);
    }

    /** Reclasificar el cobro mueve el enlace del vencimiento al asiento nuevo. */
    @Test
    void reclasificarElCobroReapuntaElVencimiento() {
        String inv = salesInvoice("RV-1", "PENDING", "0");
        String vto = dueDate("SALES", inv, "1210.00");
        payments.pay(vto, banco());
        String viejo = asientoDe(vto);
        String linea572 = jdbc.queryForObject("""
                SELECT l.id FROM journal_entry_lines l JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ? AND a.code = '572'
                """, String.class, viejo);
        account("570", "Caja", "ASSET");
        String caja = jdbc.queryForObject(
                "SELECT id FROM accounting_accounts WHERE company_id = ? AND code = '570'", String.class, COMPANY);

        var nuevo = manual().reclassifyPostedAccount(viejo, linea572, caja, "fue en efectivo", false);

        assertEquals("VOIDED", estadoAsiento(viejo));
        assertEquals(nuevo.id(), asientoDe(vto), "el vencimiento debe apuntar al asiento vivo");

        // Y deshacer quita el VIVO y conserva el anulado como rastro.
        payments.unpay(vto);
        assertEquals("PENDING", paymentStatusDe(inv));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE id = ?",
                Integer.class, nuevo.id()), "el cobro vivo se va");
        assertEquals("VOIDED", estadoAsiento(viejo), "el anulado se conserva");
    }

    /** Datos heredados: enlace al anulado + sustituto vivo con el mismo origen. */
    @Test
    void deshacerConEnlaceAlAnuladoQuitaElSustitutoVivo() {
        String inv = salesInvoice("RV-2", "PENDING", "0");
        String vto = dueDate("SALES", inv, "1210.00");
        payments.pay(vto, banco());
        String viejo = asientoDe(vto);
        // Simula una reclasificacion ANTERIOR al arreglo: nuevo asiento con el
        // mismo source_id, el viejo anulado y el vencimiento sin reapuntar.
        String vivo = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO journal_entries (id, company_id, fiscal_year_id, entry_number, entry_date,
                        concept, source_type, source_id, status)
                SELECT ?, company_id, fiscal_year_id, entry_number + 1000, entry_date,
                        'Cobro (reclasificado)', source_type, source_id, 'POSTED'
                  FROM journal_entries WHERE id = ?
                """, vivo, viejo);
        jdbc.update("UPDATE journal_entries SET status = 'VOIDED' WHERE id = ?", viejo);

        payments.unpay(vto);
        assertEquals("PENDING", paymentStatusDe(inv));
        assertEquals(0, jdbc.queryForObject("SELECT COUNT(*) FROM journal_entries WHERE id = ?",
                Integer.class, vivo));
        assertEquals("VOIDED", estadoAsiento(viejo));
    }

    /** Cobro anulado por DUPLICADO (el real es otro asiento): no se deshace. */
    @Test
    void deshacerUnCobroAnuladoPorDuplicadoSeRechaza() {
        String inv = salesInvoice("RV-3", "PENDING", "0");
        String vto = dueDate("SALES", inv, "1210.00");
        payments.pay(vto, banco());
        String viejo = asientoDe(vto);
        jdbc.update("UPDATE journal_entries SET status = 'VOIDED' WHERE id = ?", viejo);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () -> payments.unpay(vto));
        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
        assertEquals("PAID", paymentStatusDe(inv), "la factura sigue cobrada");
        assertEquals("VOIDED", estadoAsiento(viejo), "el anulado no se borra");
    }
}
