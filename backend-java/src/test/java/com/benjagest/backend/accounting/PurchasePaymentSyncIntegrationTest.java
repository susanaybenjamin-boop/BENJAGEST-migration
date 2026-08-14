package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
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
 * PAGO-1 — Prueba de integración REAL, sobre la MariaDB embebida con las
 * migraciones del proyecto (incluida la V181), de que los DOS caminos de pago
 * de un gasto quedan sincronizados.
 *
 * <p>Había dos verdades desconectadas sobre si una factura de proveedor está
 * pagada: {@code purchase_invoices.paid} (botón "Registrar pago", GAS-2) e
 * {@code invoice_due_dates.status} (diálogo "Vencimientos / Pago", PV-1).
 * Consecuencias reales: un gasto pagado por vencimientos seguía contando como
 * pendiente para siempre, y se podía pagar DOS VECES por el otro camino
 * (segundo asiento 400→572 por el mismo gasto).
 *
 * <p>Aquí se comprueba la parte que se puede probar sin levantar todo Spring:
 * la migración de reparación V181 y las consultas que definen "pendiente de
 * pago". La sincronización en caliente (los tres puntos de
 * {@code PaymentScheduleService} y {@code PurchaseInvoiceService}) se verifica
 * por API contra el sandbox.
 */
class PurchasePaymentSyncIntegrationTest {

    private static final String COMPANY = "c0181000-0000-0000-0000-000000000181";

    private static DB db;
    private static JdbcTemplate jdbc;

    /** Gasto pagado por VENCIMIENTOS pero con el flag sin actualizar (el bug). */
    private static String facturaPagadaPorVencimientos;
    /** Gasto con dos vencimientos, solo uno pagado: sigue pendiente. */
    private static String facturaAMedias;
    /** Gasto sin vencimientos y sin pagar: la V181 no debe tocarlo. */
    private static String facturaSinVencimientos;
    /** Gasto ya marcado pagado: la V181 nunca desmarca. */
    private static String facturaYaPagada;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestpagotest");

        String url = cfg.getURL("benjagestpagotest");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "root", "");

        // Primero migramos hasta la V180 (ANTES de la reparación), sembramos el
        // estado roto que producía el bug, y luego aplicamos la V181: así se
        // prueba la migración de verdad, no un atajo.
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).target(org.flywaydb.core.api.MigrationVersion.fromVersion("180"))
                .load().migrate();

        jdbc = new JdbcTemplate(ds);
        seedEstadoRoto();

        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.stop();
        }
    }

    private static void seedEstadoRoto() {
        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, 'Empresa Pagos', 'INTERNAL')",
                COMPANY);

        facturaPagadaPorVencimientos = purchase("Proveedor Pagado", "P-001", "121.00");
        dueDate(facturaPagadaPorVencimientos, 1, "121.00", "PAID", "2026-07-15");

        facturaAMedias = purchase("Proveedor A Medias", "P-002", "200.00");
        dueDate(facturaAMedias, 1, "100.00", "PAID", "2026-07-15");
        dueDate(facturaAMedias, 2, "100.00", "PENDING", null);

        facturaSinVencimientos = purchase("Proveedor Sin Vencimientos", "P-003", "50.00");

        facturaYaPagada = purchase("Proveedor Ya Pagado", "P-004", "75.00");
        jdbc.update("UPDATE purchase_invoices SET paid = TRUE, paid_date = ? WHERE id = ?",
                Date.valueOf("2026-06-01"), facturaYaPagada);
    }

    private static String purchase(String supplier, String number, String total) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO purchase_invoices (id, company_id, supplier_name, invoice_number,
                        invoice_date, total_amount, status, paid)
                VALUES (?, ?, ?, ?, ?, ?, 'POSTED', FALSE)
                """, id, COMPANY, supplier, number,
                Date.valueOf("2026-05-10"), new BigDecimal(total));
        return id;
    }

    private static void dueDate(String invoiceId, int seq, String amount,
                                String status, String paidDate) {
        jdbc.update("""
                INSERT INTO invoice_due_dates (id, company_id, invoice_id, invoice_kind,
                        seq, due_date, amount, status, paid_date, treasury_account_code)
                VALUES (?, ?, ?, 'PURCHASE', ?, ?, ?, ?, ?, '572')
                """, UUID.randomUUID().toString(), COMPANY, invoiceId, seq,
                Date.valueOf(LocalDate.of(2026, 6, 30)), new BigDecimal(amount), status,
                paidDate == null ? null : Date.valueOf(paidDate));
    }

    private static boolean isPaid(String invoiceId) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT paid FROM purchase_invoices WHERE id = ?", Boolean.class, invoiceId));
    }

    @Test
    void v181_reparaElGastoPagadoPorVencimientos() {
        assertTrue(isPaid(facturaPagadaPorVencimientos),
                "pagado por vencimientos: la V181 debe marcar el flag");
        assertEquals(LocalDate.of(2026, 7, 15), jdbc.queryForObject(
                        "SELECT paid_date FROM purchase_invoices WHERE id = ?",
                        Date.class, facturaPagadaPorVencimientos).toLocalDate(),
                "la fecha de pago sale del vencimiento");
        assertEquals("572", jdbc.queryForObject(
                "SELECT payment_account_code FROM purchase_invoices WHERE id = ?",
                String.class, facturaPagadaPorVencimientos));
    }

    @Test
    void v181_noTocaLoQueNoEstaCompletamentePagado() {
        assertFalse(isPaid(facturaAMedias),
                "con un vencimiento PENDING el gasto NO esta pagado");
        assertFalse(isPaid(facturaSinVencimientos),
                "sin vencimientos no hay nada que deducir: se deja como esta");
    }

    @Test
    void v181_nuncaDesmarcaUnGastoYaPagado() {
        assertTrue(isPaid(facturaYaPagada),
                "la reparacion solo marca pagado, jamas al reves");
        assertEquals(LocalDate.of(2026, 6, 1), jdbc.queryForObject(
                        "SELECT paid_date FROM purchase_invoices WHERE id = ?",
                        Date.class, facturaYaPagada).toLocalDate(),
                "no se pisa la fecha que ya tenia");
    }

    @Test
    void elContadorDePendientesCuentaLoQueDebe() {
        // Es la consulta de ClientFinancialsService.unpaidPurchaseInvoices.
        Integer pendientes = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_invoices
                 WHERE company_id = ? AND paid = FALSE AND status <> 'VOID'
                """, Integer.class, COMPANY);
        assertEquals(2, pendientes,
                "quedan la de a medias y la que no tiene vencimientos");

        // Una anulada no debe contar como pendiente de pago.
        String anulada = purchase("Proveedor Anulado", "P-005", "999.00");
        jdbc.update("UPDATE purchase_invoices SET status = 'VOID' WHERE id = ?", anulada);
        Integer trasAnular = jdbc.queryForObject("""
                SELECT COUNT(*) FROM purchase_invoices
                 WHERE company_id = ? AND paid = FALSE AND status <> 'VOID'
                """, Integer.class, COMPANY);
        assertEquals(2, trasAnular, "una factura anulada no esta pendiente de pago");
        jdbc.update("DELETE FROM purchase_invoices WHERE id = ?", anulada);
    }
}
