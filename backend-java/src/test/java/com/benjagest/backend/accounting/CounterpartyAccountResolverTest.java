package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
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
 * CONTA-1 — El cobro y el pago tienen que saldar la MISMA cuenta de tercero que
 * cargó la factura, no la genérica.
 *
 * <p>Bug encontrado por Benjamin en producción: <i>"teniendo un cliente con
 * todas las facturas cobradas, me sale en sumas y saldos un saldo deudor de la
 * última factura"</i>. La factura cargaba {@code 4300005 "Clientes - 3R
 * Consultores"} y el cobro abonaba la {@code 430} genérica, así que el cliente
 * se quedaba con +1.724,25 y la genérica con −1.724,25.
 *
 * <p>Afectaba a los DOS caminos que saldan una factura ({@link
 * PaymentScheduleService} y {@link BankMovementService}), porque los dos hacían
 * la misma búsqueda por prefijo. De ahí que la resolución viva ahora en una
 * única pieza compartida.
 */
class CounterpartyAccountResolverTest {

    private static final String COMPANY = "c0conta1-0000-0000-0000-000000000001";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static InvoiceCounterpartyAccountResolver resolver;

    private static String cuentaGenerica430;
    private static String cuentaCliente3R;
    private static String cuentaGenerica400;
    private static String cuentaProveedor;
    private static String facturaVentaId;
    private static String facturaCompraId;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestconta1test");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestconta1test"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);
        resolver = new InvoiceCounterpartyAccountResolver(jdbc);

        jdbc.update("INSERT INTO companies (id, legal_name, company_type) "
                + "VALUES (?, 'Asesoria', 'INTERNAL')", COMPANY);
        String fy = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                VALUES (?, ?, 2026, ?, ?, 'OPEN')
                """, fy, COMPANY, Date.valueOf("2026-01-01"), Date.valueOf("2026-12-31"));

        // La generica tiene el codigo MAS CORTO: es la que devolvia la busqueda
        // por prefijo y la que provocaba el descuadre.
        cuentaGenerica430 = cuenta("430", "Clientes");
        cuentaCliente3R = cuenta("4300005", "Clientes - 3R Consultoria");
        cuentaGenerica400 = cuenta("400", "Proveedores");
        cuentaProveedor = cuenta("4000018", "Proveedores - CAT Sierra Verde");
        String cuenta700 = cuenta("700", "Ventas");
        String cuenta600 = cuenta("600", "Compras");

        // Factura de VENTA: carga la subcuenta del cliente (DEBE).
        facturaVentaId = UUID.randomUUID().toString();
        String asientoVenta = asiento(fy, 1, "SALES_INVOICE", facturaVentaId, "Factura 3R");
        linea(asientoVenta, cuentaCliente3R, "1724.25", "0");
        linea(asientoVenta, cuenta700, "0", "1724.25");

        // Factura de COMPRA: abona la subcuenta del proveedor (HABER).
        facturaCompraId = UUID.randomUUID().toString();
        String asientoCompra = asiento(fy, 2, "PURCHASE_INVOICE", facturaCompraId, "Fra. CAT Sierra");
        linea(asientoCompra, cuenta600, "1999.98", "0");
        linea(asientoCompra, cuentaProveedor, "0", "1999.98");
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

    private static String asiento(String fiscalYearId, int num, String sourceType,
                                  String sourceId, String concept) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO journal_entries (id, company_id, fiscal_year_id, entry_number,
                        entry_date, concept, source_type, source_id, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED')
                """, id, COMPANY, fiscalYearId, num,
                Date.valueOf("2026-03-01"), concept, sourceType, sourceId);
        return id;
    }

    private static void linea(String entryId, String accountId, String debit, String credit) {
        jdbc.update("""
                INSERT INTO journal_entry_lines (id, journal_entry_id, account_id, debit, credit)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), entryId, accountId,
                new BigDecimal(debit), new BigDecimal(credit));
    }

    @Test
    void elCobroSaldaLaCuentaDelCliente_noLaGenerica() {
        String cuenta = resolver.resolve(COMPANY, "SALES", facturaVentaId);
        assertEquals(cuentaCliente3R, cuenta,
                "el cobro debe abonar 4300005, la MISMA que cargo la factura");
        org.junit.jupiter.api.Assertions.assertNotEquals(cuentaGenerica430, cuenta,
                "abonar la 430 generica es justo el bug: deja al cliente con saldo deudor");
    }

    @Test
    void elPagoSaldaLaCuentaDelProveedor_noLaGenerica() {
        String cuenta = resolver.resolve(COMPANY, "PURCHASE", facturaCompraId);
        assertEquals(cuentaProveedor, cuenta,
                "el pago debe cargar 4000018, la MISMA que abono la factura");
        org.junit.jupiter.api.Assertions.assertNotEquals(cuentaGenerica400, cuenta);
    }

    /**
     * Si el asesor reclasifica la factura a otra cuenta, el cobro debe SEGUIRLA.
     * Por eso la cuenta se saca del asiento de la factura y no se le vuelve a
     * preguntar al resolver de terceros.
     */
    @Test
    void siSeReclasificaLaFacturaElCobroLaSigue() {
        String otraCuenta = cuenta("4300099", "Clientes - 3R (reclasificada)");
        jdbc.update("""
                UPDATE journal_entry_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id
                   SET l.account_id = ?
                 WHERE e.source_type = 'SALES_INVOICE' AND e.source_id = ? AND l.debit > 0
                """, otraCuenta, facturaVentaId);
        try {
            assertEquals(otraCuenta, resolver.resolve(COMPANY, "SALES", facturaVentaId));
        } finally {
            jdbc.update("""
                    UPDATE journal_entry_lines l
                      JOIN journal_entries e ON e.id = l.journal_entry_id
                       SET l.account_id = ?
                     WHERE e.source_type = 'SALES_INVOICE' AND e.source_id = ? AND l.debit > 0
                    """, cuentaCliente3R, facturaVentaId);
        }
    }

    /** Sin asiento de factura no se bloquea el cobro: se cae a la generica. */
    @Test
    void sinAsientoDeFacturaSeUsaLaGenericaComoAntes() {
        assertEquals(cuentaGenerica430,
                resolver.resolve(COMPANY, "SALES", UUID.randomUUID().toString()),
                "sin factura enlazada, el comportamiento anterior (generica) es el menos malo");
        assertEquals(cuentaGenerica400,
                resolver.resolve(COMPANY, "PURCHASE", null));
    }

    /** Un asiento ANULADO no debe dictar la cuenta del cobro. */
    @Test
    void unAsientoAnuladoNoCuenta() {
        jdbc.update("UPDATE journal_entries SET status='VOIDED' "
                + "WHERE source_type='PURCHASE_INVOICE' AND source_id=?", facturaCompraId);
        try {
            assertEquals(cuentaGenerica400, resolver.resolve(COMPANY, "PURCHASE", facturaCompraId),
                    "con la factura anulada no hay cuenta que seguir: generica");
        } finally {
            jdbc.update("UPDATE journal_entries SET status='POSTED' "
                    + "WHERE source_type='PURCHASE_INVOICE' AND source_id=?", facturaCompraId);
        }
    }

    @Test
    void elPrefijoGenericoEsElEsperado() {
        assertEquals("430", InvoiceCounterpartyAccountResolver.genericPrefix("SALES"));
        assertEquals("400", InvoiceCounterpartyAccountResolver.genericPrefix("PURCHASE"));
        assertNotNull(cuentaGenerica430);
    }
}
