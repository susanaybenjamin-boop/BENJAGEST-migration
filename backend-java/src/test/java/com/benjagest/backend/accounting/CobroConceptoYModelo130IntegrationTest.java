package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.mockito.Mockito;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/**
 * COB-3 / M130-1 — Verificación EN EJECUCIÓN (CLAUDE.md §10.ter) de los dos
 * cambios pedidos por Benjamin el 2026-09-05:
 *
 * <ol>
 *   <li><b>COB-3</b>: el asiento de cobro/pago de un vencimiento puede llevar el
 *       concepto que escriba el asesor. Antes lo fijaba el backend
 *       ("Cobro vto. N") y no había forma de decir QUÉ se estaba cobrando.</li>
 *   <li><b>M130-1</b>: el cuadro de mando estima el modelo 130 a partir de los
 *       asientos, con la misma fórmula legal que el 130 oficial, y solo para
 *       quien lo presenta (autónomo en estimación directa).</li>
 * </ol>
 *
 * <p>No es un test de fórmula (eso ya lo cubre {@code AeatModel130CalcTest}):
 * corre el SQL real contra la MariaDB embebida con TODAS las migraciones del
 * proyecto, que es lo que pilla los fallos que "compila y los tests pasan" deja
 * escapar — un nombre de tabla equivocado, una columna que ya no existe, un
 * concepto que se trunca.
 */
class CobroConceptoYModelo130IntegrationTest {

    /** Autónomo en estimación directa: cobra facturas y presenta el 130. */
    private static final String AUTONOMO = "c0130000-0000-0000-0000-0000000000a1";
    /** Sociedad: no presenta 130 (presenta 200/202). */
    private static final String SOCIEDAD = "c0130000-0000-0000-0000-0000000000a2";
    /** Autónomo en MÓDULOS: tampoco presenta el 130 (presenta 131). */
    private static final String MODULOS = "c0130000-0000-0000-0000-0000000000a3";
    /** Ficha a medio rellenar (sin forma jurídica ni régimen). */
    private static final String SIN_FICHA = "c0130000-0000-0000-0000-0000000000a4";

    private static DB db;
    private static JdbcTemplate jdbc;
    private static PaymentScheduleService payments;
    private static SalesAndExpensesKpiService kpis;

    /** TenantContext de mentira: la empresa activa se cambia a mano por test. */
    private static final class MutableTenant implements TenantContext {
        private String companyId;
        @Override public String getCurrentCompanyId() { return companyId; }
        @Override public void setCurrentCompanyId(String id) { this.companyId = id; }
    }

    private static final MutableTenant tenant = new MutableTenant();

    /** Vencimiento que se cobra con concepto propio del asesor. */
    private static String vtoConConcepto;
    /** Vencimiento que se cobra sin tocar el concepto (debe salir el de siempre). */
    private static String vtoSinConcepto;

    @BeforeAll
    static void boot() throws Exception {
        DBConfigurationBuilder cfg = DBConfigurationBuilder.newBuilder();
        cfg.setPort(0);
        db = DB.newEmbeddedDB(cfg.build());
        db.start();
        db.createDB("benjagestcobrotest");

        DriverManagerDataSource ds =
                new DriverManagerDataSource(cfg.getURL("benjagestcobrotest"), "root", "");
        Flyway.configure().dataSource(ds).locations("classpath:db/migration")
                .outOfOrder(true).load().migrate();
        jdbc = new JdbcTemplate(ds);

        payments = new PaymentScheduleService(jdbc, tenant,
                Mockito.mock(com.benjagest.backend.auth.CurrentUserService.class),
                Mockito.mock(com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService.class),
                Mockito.mock(com.benjagest.backend.billing.tpb.BillingAgreementGuard.class));
        kpis = new SalesAndExpensesKpiService(jdbc, tenant);

        seed();
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
        company(AUTONOMO, "Autonomo Estimacion Directa", "AUTONOMO");
        company(SOCIEDAD, "Sociedad SL", "SL");
        company(MODULOS, "Autonomo Modulos", "AUTONOMO");
        advisoryConfig(MODULOS, "MODULOS");
        company(SIN_FICHA, "Cliente Sin Ficha", null);

        fiscalYear(AUTONOMO);
        account(AUTONOMO, "700", "Ventas de mercaderias", "INCOME");
        account(AUTONOMO, "600", "Compras de mercaderias", "EXPENSE");
        account(AUTONOMO, "430", "Clientes", "ASSET");
        account(AUTONOMO, "473", "H.P. retenciones y pagos a cuenta", "ASSET");
        account(AUTONOMO, "572", "Bancos", "ASSET");

        // Factura de venta + dos vencimientos: uno se cobrara con concepto
        // propio y el otro sin tocarlo.
        String customerId = customer(AUTONOMO, "Bar Manolo");
        String invoiceId = salesInvoice(AUTONOMO, customerId, "A-1", "1210.00");
        vtoConConcepto = dueDate(AUTONOMO, invoiceId, 1, "605.00");
        vtoSinConcepto = dueDate(AUTONOMO, invoiceId, 2, "605.00");

        // Asientos POSTED que alimentan el 130 estimado:
        //   ingresos 10.000 (haber 700) / gastos 4.000 (debe 600)
        //   retenciones 200 (debe 473)
        entryConLinea(AUTONOMO, "2026-02-15", "Venta del trimestre", "700", null, "10000.00");
        entryConLinea(AUTONOMO, "2026-02-20", "Compras del trimestre", "600", "4000.00", null);
        entryConLinea(AUTONOMO, "2026-02-25", "Retenciones soportadas", "473", "200.00", null);
    }

    private static void company(String id, String name, String legalForm) {
        jdbc.update("""
                INSERT INTO companies (id, legal_name, company_type, legal_form)
                VALUES (?, ?, 'INTERNAL', ?)
                """, id, name, legalForm);
    }

    private static void advisoryConfig(String companyId, String taxRegime) {
        jdbc.update("""
                INSERT INTO client_advisory_config (company_id, tax_regime) VALUES (?, ?)
                """, companyId, taxRegime);
    }

    private static void fiscalYear(String companyId) {
        jdbc.update("""
                INSERT INTO fiscal_years (id, company_id, year_number, start_date, end_date, status)
                VALUES (?, ?, 2026, ?, ?, 'OPEN')
                """, UUID.randomUUID().toString(), companyId,
                Date.valueOf("2026-01-01"), Date.valueOf("2026-12-31"));
    }

    private static void account(String companyId, String code, String name, String type) {
        jdbc.update("""
                INSERT INTO accounting_accounts (id, company_id, code, name, account_type, active)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """, UUID.randomUUID().toString(), companyId, code, name, type);
    }

    private static String customer(String companyId, String name) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO customers (id, company_id, legal_name, customer_type, active)
                VALUES (?, ?, ?, 'COMPANY', TRUE)
                """, id, companyId, name);
        return id;
    }

    private static String salesInvoice(String companyId, String customerId,
                                       String number, String total) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO sales_invoices (id, company_id, customer_id, invoice_number,
                        invoice_date, invoice_type, status, payment_status,
                        subtotal, vat_total, retention_total, total, paid_amount, currency)
                VALUES (?, ?, ?, ?, ?, 'HISTORICAL', 'VALIDATED', 'PENDING',
                        1000.00, 210.00, 0, ?, 0, 'EUR')
                """, id, companyId, customerId, number,
                Date.valueOf("2026-02-15"), new BigDecimal(total));
        return id;
    }

    private static String dueDate(String companyId, String invoiceId, int seq, String amount) {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO invoice_due_dates (id, company_id, invoice_id, invoice_kind,
                        seq, due_date, amount, status)
                VALUES (?, ?, ?, 'SALES', ?, ?, ?, 'PENDING')
                """, id, companyId, invoiceId, seq,
                Date.valueOf("2026-03-15"), new BigDecimal(amount));
        return id;
    }

    /** Asiento POSTED de una sola linea (basta para los sumatorios del 130). */
    private static void entryConLinea(String companyId, String date, String concept,
                                      String accountCode, String debit, String credit) {
        String fiscalYearId = jdbc.queryForObject(
                "SELECT id FROM fiscal_years WHERE company_id = ?", String.class, companyId);
        Integer max = jdbc.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0) FROM journal_entries WHERE company_id = ?
                """, Integer.class, companyId);
        String entryId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO journal_entries (id, company_id, fiscal_year_id, entry_number,
                        entry_date, concept, status)
                VALUES (?, ?, ?, ?, ?, ?, 'POSTED')
                """, entryId, companyId, fiscalYearId, (max == null ? 0 : max) + 1,
                Date.valueOf(date), concept);
        String accountId = jdbc.queryForObject(
                "SELECT id FROM accounting_accounts WHERE company_id = ? AND code = ?",
                String.class, companyId, accountCode);
        jdbc.update("""
                INSERT INTO journal_entry_lines (id, journal_entry_id, account_id, description, debit, credit)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID().toString(), entryId, accountId, concept,
                debit == null ? BigDecimal.ZERO : new BigDecimal(debit),
                credit == null ? BigDecimal.ZERO : new BigDecimal(credit));
    }

    private static String conceptoDelAsientoDe(String dueDateId) {
        return jdbc.queryForObject("""
                SELECT e.concept FROM journal_entries e
                  JOIN invoice_due_dates d ON d.journal_entry_id = e.id
                 WHERE d.id = ?
                """, String.class, dueDateId);
    }

    // ====================================================================
    //  COB-3 — concepto editable
    // ====================================================================

    @Test
    void elAsesorPuedeEscribirElConceptoDelCobro() {
        tenant.setCurrentCompanyId(AUTONOMO);
        payments.pay(vtoConConcepto, new PaymentScheduleService.PayRequest(
                "572", LocalDate.of(2026, 3, 15), "TRANSFER",
                "Cobro fra. A-1 Bar Manolo — primer plazo"));

        assertEquals("Cobro fra. A-1 Bar Manolo — primer plazo",
                conceptoDelAsientoDe(vtoConConcepto),
                "el asiento debe llevar el concepto que escribio el asesor");
    }

    @Test
    void sinConceptoSigueSaliendoElAutomaticoDeSiempre() {
        tenant.setCurrentCompanyId(AUTONOMO);
        payments.pay(vtoSinConcepto, new PaymentScheduleService.PayRequest(
                "572", LocalDate.of(2026, 3, 15), "TRANSFER", null));

        assertEquals("Cobro vto. 2", conceptoDelAsientoDe(vtoSinConcepto),
                "quien no toque el campo debe obtener EXACTAMENTE el asiento de antes");
    }

    // ====================================================================
    //  M130-1 — estimacion del modelo 130
    // ====================================================================

    @Test
    void elCuadroDeMandoEstimaEl130ConLaFormulaOficial() {
        tenant.setCurrentCompanyId(AUTONOMO);
        var k = kpis.compute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));

        // ingresos 10.000 - gastos 4.000        = 6.000 rendimiento previo
        // 5% dificil justificacion (tope 2.000) =   300
        // rendimiento neto                      = 5.700
        // 20%                                   = 1.140 de cuota
        // - 200 de retenciones                  =   940 a pagar
        assertEquals(0, new BigDecimal("940.00").compareTo(k.model130Estimated()),
                "el 130 estimado debe cuadrar con compute130, que es la formula legal");
        assertTrue(k.model130Applicable(), "un autonomo en estimacion directa SI presenta 130");
    }

    /**
     * M130-3 — Benjamin: "cuando filtro las fechas el 303 se actualiza de lujo,
     * pero el 130 no, esta fijo en todo el anio". La cifra debe ser el pago DEL
     * TRIMESTRE del "hasta": acumulado de enero, menos los trimestres anteriores.
     *
     * <p>T1 dio 940. En T2 no hay movimientos nuevos, asi que el acumulado sigue
     * siendo 940 y, restado lo de T1, en T2 no hay nada que pagar. Antes de este
     * arreglo la tarjeta repetia 940 en los cuatro trimestres.
     */
    @Test
    void elTrimestreSiguienteDescuentaLoDelAnterior() {
        tenant.setCurrentCompanyId(AUTONOMO);

        var t1 = kpis.compute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        assertEquals(0, new BigDecimal("940.00").compareTo(t1.model130Estimated()));
        assertEquals(1, t1.model130Quarter());

        var t2 = kpis.compute(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30));
        assertEquals(0, BigDecimal.ZERO.compareTo(t2.model130Estimated()),
                "sin ingresos nuevos en T2 y con los 940 de T1 ya descontados, "
                        + "en T2 no hay nada que pagar");
        assertEquals(2, t2.model130Quarter(), "la cifra debe decir a que trimestre va");
    }

    /** Un mes suelto muestra lo que llevas de SU trimestre, no del anio. */
    @Test
    void unMesSueltoSeInterpretaDentroDeSuTrimestre() {
        tenant.setCurrentCompanyId(AUTONOMO);
        var mayo = kpis.compute(LocalDate.of(2026, 5, 1), LocalDate.of(2026, 5, 31));
        assertEquals(2, mayo.model130Quarter(), "mayo cae en T2");
        assertEquals(0, BigDecimal.ZERO.compareTo(mayo.model130Estimated()));
    }

    /**
     * Si el 130 de T1 esta PRESENTADO en Fiscal, manda ESE importe y no el
     * calculado (decision Benjamin: la estimacion no debe contradecir a lo que
     * ya se mando a Hacienda).
     */
    @Test
    void elImportePresentadoEnFiscalManda() {
        tenant.setCurrentCompanyId(AUTONOMO);
        jdbc.update("""
                INSERT INTO tax_filings (id, company_id, tax_model_code, period_year,
                        period_quarter, status, total_amount)
                VALUES (?, ?, '130', 2026, 1, 'PRESENTED', 500.00)
                """, UUID.randomUUID().toString(), AUTONOMO);
        try {
            // Acumulado 940; presentado en T1 = 500 -> en T2 quedan 440.
            var t2 = kpis.compute(LocalDate.of(2026, 4, 1), LocalDate.of(2026, 6, 30));
            assertEquals(0, new BigDecimal("440.00").compareTo(t2.model130Estimated()),
                    "debe descontar los 500 PRESENTADOS, no los 940 calculados");
        } finally {
            jdbc.update("DELETE FROM tax_filings WHERE company_id = ? AND tax_model_code = '130'",
                    AUTONOMO);
        }
    }

    @Test
    void laTarjetaNoSePintaParaQuienNoPresentaEl130() {
        tenant.setCurrentCompanyId(SOCIEDAD);
        assertFalse(kpis.compute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
                .model130Applicable(), "una SL no presenta el modelo 130");

        tenant.setCurrentCompanyId(MODULOS);
        assertFalse(kpis.compute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
                .model130Applicable(), "en modulos se presenta el 131, no el 130");
    }

    // ====================================================================
    //  COB-4 — el OTRO camino de pago: "Registrar pago" de Compras
    // ====================================================================

    /**
     * Hay dos caminos de pago en la app y los dos fijaban el concepto. Este es
     * el de Compras (GAS-2), que no pasa por el cuadro de vencimientos.
     * Se prueba el metodo que construye el asiento, que es donde vivia el
     * texto fijo.
     */
    @Test
    void enComprasElConceptoDelPagoTambienEsEditable() {
        var purchase = Mockito.mock(
                com.benjagest.backend.purchases.PurchaseInvoice.class);
        Mockito.when(purchase.invoiceNumber()).thenReturn("F-2026-77");

        assertEquals("Pago Fra. F-2026-77 - Ferreteria Paco",
                com.benjagest.backend.purchases.PurchaseJournalEntryService
                        .defaultPaymentConcept(purchase, "Ferreteria Paco"),
                "el concepto automatico debe seguir siendo EXACTAMENTE el de antes: "
                        + "es el que la UI prerrellena en el campo editable");
    }

    /** Un gasto sin numero de factura tampoco cambia de formato. */
    @Test
    void elConceptoAutomaticoDeComprasAguantaSinNumeroDeFactura() {
        var purchase = Mockito.mock(
                com.benjagest.backend.purchases.PurchaseInvoice.class);
        Mockito.when(purchase.invoiceNumber()).thenReturn(null);

        assertEquals("Pago - Ferreteria Paco",
                com.benjagest.backend.purchases.PurchaseJournalEntryService
                        .defaultPaymentConcept(purchase, "Ferreteria Paco"));
    }

    /** El PayRequest de Compras tambien tiene que mapear el campo nuevo. */
    @Test
    void elJsonDePagoDeComprasMapeaElConcepto() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var req = mapper.readValue(
                "{\"paymentDate\":\"2026-03-15\",\"bankAccountCode\":\"572\","
                        + "\"concept\":\"Pago ferreteria obra Lopez\"}",
                com.benjagest.backend.purchases.PurchaseInvoiceService.PayRequest.class);
        assertEquals("Pago ferreteria obra Lopez", req.concept());

        var viejo = mapper.readValue(
                "{\"paymentDate\":\"2026-03-15\",\"bankAccountCode\":\"572\"}",
                com.benjagest.backend.purchases.PurchaseInvoiceService.PayRequest.class);
        org.junit.jupiter.api.Assertions.assertNull(viejo.concept(),
                "un cliente antiguo que no manda el campo debe seguir funcionando");
    }

    // ====================================================================
    //  Contrato JSON con la UI (lo que viaja de verdad por el cable)
    // ====================================================================

    /**
     * El cuerpo es EXACTAMENTE el que arma {@code DueDateApiClient.pay}. Si el
     * record dejara de mapear el campo nuevo, el concepto del asesor se
     * perderia en silencio y el asiento saldria con el texto automatico.
     */
    @Test
    void elJsonQueManda_laUiSeMapeaAlPayRequest() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        String body = "{\"treasuryAccountCode\":\"572\",\"paidDate\":\"2026-03-15\","
                + "\"paymentMethod\":\"TRANSFER\",\"concept\":\"Cobro fra. A-1\"}";
        var req = mapper.readValue(body, PaymentScheduleService.PayRequest.class);

        assertEquals("Cobro fra. A-1", req.concept());
        assertEquals("572", req.treasuryAccountCode());
        assertEquals(LocalDate.of(2026, 3, 15), req.paidDate());
    }

    /** Un cliente antiguo que no manda el campo no debe romper: concept = null. */
    @Test
    void unClienteViejoSinConceptoSigueFuncionando() throws Exception {
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var req = mapper.readValue(
                "{\"treasuryAccountCode\":\"572\",\"paidDate\":\"2026-03-15\",\"paymentMethod\":\"CASH\"}",
                PaymentScheduleService.PayRequest.class);
        org.junit.jupiter.api.Assertions.assertNull(req.concept());
    }

    /**
     * Los dos campos nuevos tienen que salir con el nombre que la UI busca
     * ({@code AccountingApiClient.kpisSalesAndExpenses} los lee por regex sobre
     * el JSON, asi que un nombre distinto se traduce en un 0 mudo).
     */
    @Test
    void elKpiSerializaLosCamposQueLaUiBusca() throws Exception {
        tenant.setCurrentCompanyId(AUTONOMO);
        var k = kpis.compute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31));
        String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(k);

        assertTrue(json.contains("\"model130Estimated\":940"),
                "la UI lee model130Estimated por regex; JSON = " + json);
        assertTrue(json.contains("\"model130Applicable\":true"),
                "la UI lee model130Applicable por regex; JSON = " + json);
    }

    @Test
    void conLaFichaSinRellenarSeMuestraIgualmente() {
        tenant.setCurrentCompanyId(SIN_FICHA);
        assertTrue(kpis.compute(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 3, 31))
                        .model130Applicable(),
                "sin forma juridica ni regimen se muestra (fail-open): el caso tipico "
                        + "del cliente no vinculado es el autonomo pequenio");
    }
}
