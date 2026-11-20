package com.benjagest.backend.billing.kpi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import ch.vorburger.mariadb4j.DB;
import ch.vorburger.mariadb4j.DBConfigurationBuilder;
import com.benjagest.backend.tenant.TenantContext;
import com.benjagest.backend.workspace.DashboardResponse;
import com.benjagest.backend.workspace.WorkspaceRepository;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * DASH-E — Verificacion EN EJECUCION (CLAUDE.md §10.ter) del bloque pedido por
 * Benjamin el 2026-11-20: <i>"los widgets de inicio 'facturado' y 'facturas' no
 * concuerdan con lo que tengo facturado"</i> + <i>"el dashboard de facturacion
 * en el modo empresario lo veo vacio"</i>.
 *
 * <p>Corre el SQL REAL contra la MariaDB embebida con TODAS las migraciones del
 * proyecto. Es lo unico que caza lo que "compila y los tests pasan" deja pasar:
 * en este mismo bloque se descubrio que {@code purchase_invoices.total} se
 * dropeo en la V45 y que la tabla {@code payrolls} de la V2 no la escribe
 * nadie (el padron real es {@code payslips}, V29) — el widget de nominas
 * mostraba 0,00 EUR desde siempre.
 *
 * <p>La semilla esta construida para que CADA exclusion tenga su trampa: un
 * borrador, una anulada, una factura del ejercicio anterior, una pareja
 * original-VOIDED + rectificativa (que netea a cero), una compra VOID, una
 * compra DRAFT y una nomina CANCELLED. Si alguien relaja el criterio, las
 * cifras esperadas dejan de salir.
 */
class CuadroMandoFacturacionIntegrationTest {

    private static final String EMPRESA = "dd000000-0000-0000-0000-00000000e001";
    /** Segunda empresa: comprueba que el TenantContext aisla de verdad. */
    private static final String OTRA = "dd000000-0000-0000-0000-00000000e002";

    /** Ejercicio en curso: las tarjetas del inicio son de ESTE ano. */
    private static final int YEAR = LocalDate.now().getYear();
    private static final int YEAR_PREV = YEAR - 1;

    private static DB db;
    private static JdbcTemplate jdbc;
    private static WorkspaceRepository workspace;
    private static BillingKpiService kpis;

    private static final class MutableTenant implements TenantContext {
        private String companyId;
        @Override public String getCurrentCompanyId() { return companyId; }
        @Override public void setCurrentCompanyId(String id) { this.companyId = id; }
    }

    private static final MutableTenant tenant = new MutableTenant();

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestdashetest");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestdashetest"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        workspace = new WorkspaceRepository(jdbc, tenant);
        kpis = new BillingKpiService(jdbc, tenant);

        seed();
        tenant.setCurrentCompanyId(EMPRESA);
    }

    @AfterAll
    static void stop() throws Exception {
        if (db != null) {
            db.stop();
        }
    }

    // ====================================================================
    //  Semilla
    // ====================================================================

    private static void seed() {
        company(EMPRESA, "Empresa Benjamin SL");
        company(OTRA, "Empresa Ajena SL");

        String manolo = customer(EMPRESA, "Bar Manolo");
        String lucia = customer(EMPRESA, "Taller Lucia");

        // --- LO QUE SI CUENTA: 2 facturas emitidas vivas del ejercicio ---
        //   base 1000 + 500 = 1500 | IVA 210 + 105 = 315 | total 1210 + 605 = 1815
        //   La de marzo vence en el pasado -> cuenta como VENCIDA.
        // Los VENCIMIENTOS van relativos a hoy a proposito: con fechas fijas el
        // test caducaria (una "vencida" el 10-abr no lo esta si la suite corre
        // en febrero). Las fechas de FACTURA si son fijas, porque de ellas
        // dependen el rango y la serie mensual.
        salesInvoice(EMPRESA, manolo, "FRA-" + YEAR + "-0001", LocalDate.of(YEAR, 3, 10),
                LocalDate.now().minusDays(1), // siempre vencida
                "VALIDATED", "PENDING", "1000.00", "210.00", "0.00", "1210.00", "0.00", "NORMAL", null);
        salesInvoice(EMPRESA, lucia, "FRA-" + YEAR + "-0002", LocalDate.of(YEAR, 5, 20),
                LocalDate.now().plusYears(1), // nunca vencida
                "VALIDATED", "PENDING", "500.00", "105.00", "0.00", "605.00", "0.00", "NORMAL", null);

        // --- TRAMPAS que NO deben contar ---
        // (a) borrador sin emitir del ejercicio
        salesInvoice(EMPRESA, manolo, null, LocalDate.of(YEAR, 6, 1), null,
                "DRAFT", "PENDING", "777.00", "163.17", "0.00", "940.17", "0.00", "NORMAL", null);
        // (b) anulada suelta del ejercicio
        salesInvoice(EMPRESA, manolo, "FRA-" + YEAR + "-0003", LocalDate.of(YEAR, 6, 2), null,
                "VOIDED", "PENDING", "888.00", "186.48", "0.00", "1074.48", "0.00", "NORMAL", null);
        // (c) factura del EJERCICIO ANTERIOR (emitida y viva, pero de otro ano)
        salesInvoice(EMPRESA, manolo, "FRA-" + YEAR_PREV + "-0009", LocalDate.of(YEAR_PREV, 11, 5),
                LocalDate.of(YEAR_PREV, 12, 5),
                "VALIDATED", "PENDING", "9999.00", "2099.79", "0.00", "12098.79", "0.00", "NORMAL", null);
        // (d) original VOIDED + su rectificativa VALIDATED negativa: la pareja
        //     netea a cero. La original cae por status; la rectificativa cae
        //     por la condicion de netting. Si esta se colase, "facturado"
        //     saldria 1500 - 300 = 1200.
        String originalAnulada = salesInvoice(EMPRESA, lucia, "FRA-" + YEAR + "-0004",
                LocalDate.of(YEAR, 7, 1), null,
                "VOIDED", "PENDING", "300.00", "63.00", "0.00", "363.00", "0.00", "NORMAL", null);
        salesInvoice(EMPRESA, lucia, "FRE-" + YEAR + "-0001", LocalDate.of(YEAR, 7, 2), null,
                "VALIDATED", "PENDING", "-300.00", "-63.00", "0.00", "-363.00", "0.00",
                "RECTIFYING", originalAnulada);

        // Empresa ajena: si el aislamiento por tenant fallase, estas cifras
        // enormes apareceran en los asserts y se vera al instante.
        String ajeno = customer(OTRA, "Cliente Ajeno");
        salesInvoice(OTRA, ajeno, "X-1", LocalDate.of(YEAR, 4, 4), null,
                "VALIDATED", "PENDING", "50000.00", "10500.00", "0.00", "60500.00", "0.00",
                "NORMAL", null);

        // --- COMPRAS: solo la POSTED del ejercicio cuenta (base 300) ---
        purchase(EMPRESA, "SP-1", LocalDate.of(YEAR, 2, 2), "POSTED", "300.00");
        purchase(EMPRESA, "SP-2", LocalDate.of(YEAR, 2, 3), "VOID", "400.00");
        purchase(EMPRESA, "SP-3", LocalDate.of(YEAR, 2, 4), "DRAFT", "500.00");
        purchase(EMPRESA, "SP-4", LocalDate.of(YEAR_PREV, 2, 5), "POSTED", "600.00");

        // --- NOMINAS: solo la no cancelada del ejercicio cuenta (neto 1000) ---
        String emp = employee(EMPRESA, "Empleado Uno");
        payslip(EMPRESA, emp, YEAR, 1, "MONTHLY", "CALCULATED", "1000.00");
        payslip(EMPRESA, emp, YEAR, 2, "MONTHLY", "CANCELLED", "2000.00");
        payslip(EMPRESA, emp, YEAR_PREV, 3, "MONTHLY", "PAID", "3000.00");
    }

    private static void company(String id, String name) {
        jdbc.update("INSERT INTO companies (id, legal_name, company_type) VALUES (?, ?, 'INTERNAL')",
                id, name);
    }

    private static String customer(String companyId, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customers (id, company_id, legal_name, customer_type, active)
                VALUES (?, ?, ?, 'COMPANY', TRUE)
                """, id, companyId, name);
        return id;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static String salesInvoice(String companyId, String customerId, String number,
                                       LocalDate invoiceDate, LocalDate dueDate,
                                       String status, String paymentStatus,
                                       String subtotal, String vat, String retention,
                                       String total, String paid,
                                       String invoiceType, String originalId) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sales_invoices (id, company_id, customer_id, invoice_number,
                        invoice_date, due_date, invoice_type, status, payment_status,
                        subtotal, vat_total, retention_total, total, paid_amount, currency,
                        original_invoice_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'EUR', ?)
                """, id, companyId, customerId, number,
                Date.valueOf(invoiceDate),
                dueDate == null ? null : Date.valueOf(dueDate),
                invoiceType, status, paymentStatus,
                new BigDecimal(subtotal), new BigDecimal(vat), new BigDecimal(retention),
                new BigDecimal(total), new BigDecimal(paid), originalId);
        return id;
    }

    private static void purchase(String companyId, String number, LocalDate date,
                                  String status, String base) {
        jdbc.update("""
                INSERT INTO purchase_invoices (id, company_id, supplier_name, invoice_number,
                        invoice_date, status, base_amount, vat_amount, total_amount)
                VALUES (?, ?, 'Proveedor', ?, ?, ?, ?, 0, ?)
                """, UUID.randomUUID().toString(), companyId, number, Date.valueOf(date),
                status, new BigDecimal(base), new BigDecimal(base));
    }

    private static String employee(String companyId, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO employees (id, company_id, full_name, active)
                VALUES (?, ?, ?, TRUE)
                """, id, companyId, name);
        return id;
    }

    private static void payslip(String companyId, String employeeId, int year, int month,
                                 String type, String status, String net) {
        jdbc.update("""
                INSERT INTO payslips (id, company_id, employee_id, period_year, period_month,
                        payslip_type, net_amount, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), companyId, employeeId, year, month,
                type, new BigDecimal(net), status);
    }

    // ====================================================================
    //  DASH-E1 — tarjetas del inicio
    // ====================================================================

    @Test
    void elInicioCuentaSoloFacturasEmitidasVivasDelEjercicio() {
        tenant.setCurrentCompanyId(EMPRESA);
        DashboardResponse d = workspace.dashboard("BUSINESS");

        assertEquals(YEAR, d.periodYear(), "las tarjetas deben declarar el ejercicio");
        // Antes: 7 (todo lo de la tabla, cualquier ano y cualquier estado).
        assertEquals(2L, d.invoices(),
                "solo las 2 emitidas vivas del ejercicio; fuera borrador, anulada, "
                + "la del ano anterior y la pareja anulada+rectificativa");
    }

    @Test
    void facturadoEsLaBaseImponibleYNoElTotalConIva() {
        tenant.setCurrentCompanyId(EMPRESA);
        DashboardResponse d = workspace.dashboard("BUSINESS");
        // 1000 + 500. Si sumase `total` saldria 1815 (con IVA) y nunca cuadraria
        // con "Ingresos" del cuadro de mando de Contabilidad, que es base.
        assertEquals(0, new BigDecimal("1500.00").compareTo(d.billed()),
                "facturado = base imponible de las emitidas vivas del ejercicio, fue " + d.billed());
    }

    @Test
    void losGastosExcluyenAnuladasBorradoresYOtrosEjercicios() {
        tenant.setCurrentCompanyId(EMPRESA);
        DashboardResponse d = workspace.dashboard("BUSINESS");
        // Antes sumaba 300 + 400 + 500 + 600 = 1800 (total_amount, todos los
        // estados y todos los anos).
        assertEquals(0, new BigDecimal("300.00").compareTo(d.expenses()),
                "solo la compra POSTED del ejercicio, fue " + d.expenses());
    }

    @Test
    void elCosteDeNominasSaleDePayslipsYNoDeLaTablaMuertaPayrolls() {
        tenant.setCurrentCompanyId(EMPRESA);
        DashboardResponse d = workspace.dashboard("BUSINESS");
        // Antes leia `payrolls`, donde no escribe nadie -> 0,00 EUR siempre.
        assertEquals(0, new BigDecimal("1000.00").compareTo(d.payrollNet()),
                "neto de las nominas no canceladas del ejercicio, fue " + d.payrollNet());
    }

    @Test
    void elPendienteDeCobroNoSeRecortaAlEjercicio() {
        tenant.setCurrentCompanyId(EMPRESA);
        DashboardResponse d = workspace.dashboard("BUSINESS");
        // 1210 + 605 (de este ano) + 12098,79 (del anterior, sigue debiendose).
        // La rectificativa negativa NO resta: netea con su original VOIDED.
        assertEquals(0, new BigDecimal("13913.79").compareTo(d.pending()),
                "una deuda vieja sigue pendiente, fue " + d.pending());
    }

    @Test
    void elInicioEstaAisladoPorEmpresa() {
        tenant.setCurrentCompanyId(OTRA);
        DashboardResponse d = workspace.dashboard("BUSINESS");
        assertEquals(1L, d.invoices());
        assertEquals(0, new BigDecimal("50000.00").compareTo(d.billed()),
                "cada empresa ve lo suyo, fue " + d.billed());
    }

    // ====================================================================
    //  DASH-E2 — cuadro de mando de Facturacion
    // ====================================================================

    private BillingKpiService.BillingKpis delEjercicio() {
        tenant.setCurrentCompanyId(EMPRESA);
        return kpis.compute(LocalDate.of(YEAR, 1, 1), LocalDate.of(YEAR, 12, 31));
    }

    @Test
    void losKpisDeFacturacionCuadranConLaTarjetaDelInicio() {
        BillingKpiService.BillingKpis k = delEjercicio();
        assertEquals(2, k.issuedCount());
        assertEquals(0, new BigDecimal("1500.00").compareTo(k.billedBase()),
                "la misma base que la tarjeta del inicio, fue " + k.billedBase());
        assertEquals(0, new BigDecimal("315.00").compareTo(k.vatCharged()));
        assertEquals(0, new BigDecimal("1815.00").compareTo(k.billedTotal()));
        // 1815 / 2 facturas
        assertEquals(0, new BigDecimal("907.50").compareTo(k.avgTicket()),
                "factura media, fue " + k.avgTicket());
    }

    @Test
    void losBorradoresSeAvisanAparteYNoSumanComoFacturado() {
        BillingKpiService.BillingKpis k = delEjercicio();
        assertEquals(1, k.draftCount(), "el borrador se cuenta, pero aparte");
        assertEquals(0, new BigDecimal("777.00").compareTo(k.draftBase()));
        assertTrue(k.billedBase().compareTo(new BigDecimal("777.00")) != 0,
                "el borrador no puede estar dentro de lo facturado");
    }

    @Test
    void lasAnuladasSeCuentanComoAvisoNoComoFacturacion() {
        BillingKpiService.BillingKpis k = delEjercicio();
        // La suelta + la original de la pareja con rectificativa.
        assertEquals(2, k.voidedCount());
    }

    @Test
    void elPendienteDelRangoDistingueLoVencido() {
        BillingKpiService.BillingKpis k = delEjercicio();
        assertEquals(2, k.pendingCount(), "las dos emitidas estan sin cobrar");
        assertEquals(0, new BigDecimal("1815.00").compareTo(k.pendingCollections()),
                "fue " + k.pendingCollections());
        assertEquals(1, k.overdueCount(), "solo la de marzo tiene el vencimiento pasado");
        assertEquals(0, new BigDecimal("1210.00").compareTo(k.overdueAmount()),
                "fue " + k.overdueAmount());
    }

    @Test
    void elClienteQueMasFacturaEsElDeMayorBase() {
        BillingKpiService.BillingKpis k = delEjercicio();
        assertEquals(2, k.customersBilled());
        assertEquals("Bar Manolo", k.topCustomerName(), "Manolo factura 1000 y Lucia 500");
        assertEquals(0, new BigDecimal("1000.00").compareTo(k.topCustomerBase()));
    }

    @Test
    void unRangoSinFacturasDevuelveCerosYNoRevienta() {
        tenant.setCurrentCompanyId(EMPRESA);
        BillingKpiService.BillingKpis k =
                kpis.compute(LocalDate.of(1999, 1, 1), LocalDate.of(1999, 12, 31));
        assertEquals(0, k.issuedCount());
        assertEquals(0, BigDecimal.ZERO.compareTo(k.billedBase()));
        // Sin facturas no hay "cliente top": null, para que la UI oculte la
        // tarjeta en vez de pintar un guion sin significado.
        assertNull(k.topCustomerName());
        assertEquals(0, BigDecimal.ZERO.compareTo(k.avgTicket()), "no se divide por cero");
    }

    @Test
    void elRangoRecortaDeVerdad() {
        tenant.setCurrentCompanyId(EMPRESA);
        // Solo marzo: debe quedarse con la primera factura.
        BillingKpiService.BillingKpis k =
                kpis.compute(LocalDate.of(YEAR, 3, 1), LocalDate.of(YEAR, 3, 31));
        assertEquals(1, k.issuedCount());
        assertEquals(0, new BigDecimal("1000.00").compareTo(k.billedBase()),
                "fue " + k.billedBase());
    }

    @Test
    void laSerieMensualTiene12PuntosYColocaCadaFacturaEnSuMes() {
        tenant.setCurrentCompanyId(EMPRESA);
        List<BillingKpiService.BillingMonthPoint> serie = kpis.monthlySeries(YEAR);
        assertEquals(12, serie.size(), "siempre 12 puntos, los vacios a cero");

        BillingKpiService.BillingMonthPoint marzo = serie.get(2);
        assertEquals(3, marzo.month());
        assertEquals(1, marzo.count());
        assertEquals(0, new BigDecimal("1000.00").compareTo(marzo.base()));

        BillingKpiService.BillingMonthPoint mayo = serie.get(4);
        assertEquals(5, mayo.month());
        assertEquals(0, new BigDecimal("500.00").compareTo(mayo.base()));

        BillingKpiService.BillingMonthPoint enero = serie.get(0);
        assertEquals(0, enero.count(), "enero no tiene facturas");
        assertEquals(0, BigDecimal.ZERO.compareTo(enero.base()));

        // Julio tiene la pareja anulada+rectificativa: debe quedar A CERO.
        BillingKpiService.BillingMonthPoint julio = serie.get(6);
        assertEquals(0, julio.count(),
                "la anulada y su rectificativa netean: julio no factura nada");
        assertEquals(0, BigDecimal.ZERO.compareTo(julio.base()));

        // La suma de la serie debe ser la base del ejercicio: si una pantalla
        // dice 1500 y la otra 1200, es justo el bug que se esta arreglando.
        BigDecimal suma = serie.stream()
                .map(BillingKpiService.BillingMonthPoint::base)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("1500.00").compareTo(suma),
                "la serie mensual y el KPI del rango deben sumar lo mismo, fue " + suma);
    }

    @Test
    void laSerieMensualTambienEstaAisladaPorEmpresa() {
        tenant.setCurrentCompanyId(OTRA);
        List<BillingKpiService.BillingMonthPoint> serie = kpis.monthlySeries(YEAR);
        assertNotNull(serie);
        BigDecimal suma = serie.stream()
                .map(BillingKpiService.BillingMonthPoint::base)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, new BigDecimal("50000.00").compareTo(suma), "fue " + suma);
    }
}
