package com.benjagest.backend.billing.catalog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * CONC-1 — Prueba de integración REAL del catálogo de conceptos, sobre la
 * MariaDB embebida (MariaDB4j) con las migraciones Flyway del proyecto
 * aplicadas (incluida la V180 que enciende {@code catalog_items}).
 *
 * <p>Verifica lo que de verdad puede romperse:
 * <ul>
 *   <li>que el histórico se agrupe por concepto contando usos y quedándose
 *       con el precio del ÚLTIMO uso (no el del primero);</li>
 *   <li>que las facturas de OTRA empresa no se cuelen (multi-tenant);</li>
 *   <li>que las CANCELLED no ensucien las sugerencias;</li>
 *   <li>que guardar un concepto sea idempotente y lo saque del histórico
 *       (no duplicado en la lista);</li>
 *   <li>que la baja sea LÓGICA (la fila sigue ahí para las FK de facturas
 *       ya emitidas).</li>
 * </ul>
 */
class CatalogConceptsIntegrationTest {

    private static final String COMPANY = "c0180000-0000-0000-0000-000000000180";
    private static final String OTHER_COMPANY = "c0180000-0000-0000-0000-000000000999";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static CatalogItemService service;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0); // 0 = MariaDB4j busca un puerto libre
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestconctest");

        String url = cfg.getURL("benjagestconctest");
        DriverManagerDataSource ds = new DriverManagerDataSource(url, "root", "");

        Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .outOfOrder(true)
                .load()
                .migrate();

        jdbc = new JdbcTemplate(ds);

        TenantContext tenant = mock(TenantContext.class);
        when(tenant.getCurrentCompanyId()).thenReturn(COMPANY);
        service = new CatalogItemService(new CatalogItemRepository(jdbc, tenant));

        seedFixture();
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.stop();
        }
    }

    private static void seedFixture() {
        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, 'Empresa Conceptos', 'INTERNAL')",
                COMPANY);
        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, 'Empresa Vecina', 'INTERNAL')",
                OTHER_COMPANY);

        String customer = customer(COMPANY, "Cliente Conceptos", "B00000180");
        String otherCustomer = customer(OTHER_COMPANY, "Cliente Vecino", "B00000999");

        // Enero: "Mantenimiento mensual" a 100 (precio VIEJO).
        String enero = invoice(COMPANY, customer, "2026-01-31", "VALIDATED");
        line(enero, "Mantenimiento mensual", "100.00", "21.00", "0.00");
        line(enero, "Desplazamiento", "30.00", "21.00", "0.00");

        // Marzo: el mismo concepto SUBE a 120 (precio NUEVO) + uno nuevo con retención.
        String marzo = invoice(COMPANY, customer, "2026-03-31", "VALIDATED");
        line(marzo, "Mantenimiento mensual", "120.00", "21.00", "0.00");
        line(marzo, "Asesoramiento fiscal", "250.00", "21.00", "15.00");

        // Un borrador CANCELADO no debe sugerirse nunca.
        String anulada = invoice(COMPANY, customer, "2026-04-01", "CANCELLED");
        line(anulada, "Concepto de una factura anulada", "999.00", "21.00", "0.00");

        // Factura de OTRA empresa: no puede aparecer en el catálogo de la nuestra.
        String vecina = invoice(OTHER_COMPANY, otherCustomer, "2026-05-01", "VALIDATED");
        line(vecina, "Concepto de la empresa vecina", "777.00", "21.00", "0.00");
    }

    private static String customer(String companyId, String name, String nif) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customers (id, company_id, legal_name, tax_identifier, customer_type)
                VALUES (?, ?, ?, ?, 'COMPANY')
                """, id, companyId, name, nif);
        return id;
    }

    private static String invoice(String companyId, String customerId, String date, String status) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sales_invoices (id, company_id, customer_id, invoice_date, invoice_type, status)
                VALUES (?, ?, ?, ?, 'NORMAL', ?)
                """, id, companyId, customerId, Date.valueOf(date), status);
        return id;
    }

    private static void line(String invoiceId, String description, String price,
                             String vat, String retention) {
        jdbc.update("""
                INSERT INTO sales_invoice_lines (id, invoice_id, description, quantity,
                        unit_price, vat_percent, retention_percent)
                VALUES (?, ?, ?, 1, ?, ?, ?)
                """, UUID.randomUUID().toString(), invoiceId, description,
                new BigDecimal(price), new BigDecimal(vat), new BigDecimal(retention));
    }

    private static InvoiceConcept find(List<InvoiceConcept> concepts, String name) {
        return concepts.stream().filter(c -> name.equals(c.name())).findFirst().orElse(null);
    }

    @Test
    void historico_agrupaPorConceptoYSeQuedaConElPrecioDelUltimoUso() {
        List<InvoiceConcept> concepts = service.listConcepts();

        InvoiceConcept mantenimiento = find(concepts, "Mantenimiento mensual");
        assertNotNull(mantenimiento, "el concepto usado dos veces debe sugerirse");
        assertEquals(InvoiceConcept.SOURCE_HISTORY, mantenimiento.source());
        assertNull(mantenimiento.id(), "un concepto del histórico no tiene fila que editar");
        assertEquals(2, mantenimiento.usageCount(), "usado en enero y en marzo");
        assertEquals(0, new BigDecimal("120.00").compareTo(mantenimiento.unitPrice()),
                "debe traer el precio del ULTIMO uso (marzo), no el de enero");
        assertEquals("2026-03-31", mantenimiento.lastUsedAt().toString());

        InvoiceConcept asesoramiento = find(concepts, "Asesoramiento fiscal");
        assertNotNull(asesoramiento);
        assertEquals(1, asesoramiento.usageCount());
        assertEquals(0, new BigDecimal("15.00").compareTo(asesoramiento.retentionPercent()),
                "la retención de la línea viaja con el concepto");
    }

    @Test
    void noSeCuelanNiLasAnuladasNiLasDeOtraEmpresa() {
        List<InvoiceConcept> concepts = service.listConcepts();
        assertNull(find(concepts, "Concepto de una factura anulada"),
                "una factura CANCELLED no debe sugerir su concepto");
        assertNull(find(concepts, "Concepto de la empresa vecina"),
                "el catálogo es por empresa: no puede ver las facturas de otra");
    }

    @Test
    void guardarConceptoEsIdempotenteYLoSacaDelHistorico() {
        // Guardamos un concepto que YA existe en el histórico: debe aparecer
        // UNA sola vez, ahora como guardado y conservando sus usos reales.
        CatalogItem saved = service.create(new CatalogItemService.UpsertRequest(
                "Mantenimiento mensual", "Mantenimiento mensual", null, null,
                new BigDecimal("135.00"), new BigDecimal("21.00"), BigDecimal.ZERO, null));
        assertNotNull(saved.id());

        List<InvoiceConcept> concepts = service.listConcepts();
        List<InvoiceConcept> repes = concepts.stream()
                .filter(c -> "Mantenimiento mensual".equals(c.name())).toList();
        assertEquals(1, repes.size(), "no puede salir dos veces (guardado + histórico)");
        InvoiceConcept only = repes.get(0);
        assertEquals(InvoiceConcept.SOURCE_CATALOG, only.source());
        assertEquals(0, new BigDecimal("135.00").compareTo(only.unitPrice()),
                "manda el precio que guardó el usuario");
        assertEquals(2, only.usageCount(), "los usos siguen saliendo del histórico real");

        // Segundo guardado con el mismo nombre: actualiza, no duplica.
        CatalogItem again = service.create(new CatalogItemService.UpsertRequest(
                "Mantenimiento mensual", "Mantenimiento mensual", null, null,
                new BigDecimal("140.00"), new BigDecimal("21.00"), BigDecimal.ZERO, null));
        assertEquals(saved.id(), again.id(), "mismo nombre = misma fila, no un duplicado");
        assertEquals(1, (int) jdbc.queryForObject("""
                SELECT COUNT(*) FROM catalog_items
                 WHERE company_id = ? AND active = TRUE AND name = 'Mantenimiento mensual'
                """, Integer.class, COMPANY));

        // La baja es LÓGICA: desaparece del selector pero la fila sigue ahí
        // (las líneas de facturas emitidas apuntan aquí por FK).
        service.delete(saved.id());
        List<InvoiceConcept> tras = service.listConcepts();
        InvoiceConcept vuelve = find(tras, "Mantenimiento mensual");
        assertNotNull(vuelve, "al quitarlo del catálogo vuelve a ofrecerse desde el histórico");
        assertEquals(InvoiceConcept.SOURCE_HISTORY, vuelve.source());
        assertEquals(1, (int) jdbc.queryForObject(
                "SELECT COUNT(*) FROM catalog_items WHERE id = ?", Integer.class, saved.id()),
                "la fila NO se borra: baja logica");
        assertFalse(jdbc.queryForObject(
                "SELECT active FROM catalog_items WHERE id = ?", Boolean.class, saved.id()));
    }

    @Test
    void conceptoCreadoAManoSinHistoricoSaleConCeroUsos() {
        CatalogItem nuevo = service.create(new CatalogItemService.UpsertRequest(
                "Alta de sociedad", "Tramitación completa del alta de sociedad", null, null,
                new BigDecimal("500.00"), new BigDecimal("21.00"), new BigDecimal("15.00"), null));

        InvoiceConcept c = find(service.listConcepts(), "Alta de sociedad");
        assertNotNull(c);
        assertEquals(InvoiceConcept.SOURCE_CATALOG, c.source());
        assertEquals(0, c.usageCount());
        assertNull(c.lastUsedAt());
        assertEquals("Tramitación completa del alta de sociedad", c.description(),
                "el texto que se vuelca en la línea es la descripción, no el nombre");

        service.delete(nuevo.id());
        assertNull(find(service.listConcepts(), "Alta de sociedad"),
                "sin histórico, al quitarlo desaparece del todo");
    }
}
