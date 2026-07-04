package com.benjagest.backend.accounting.externalimport;

import static org.junit.jupiter.api.Assertions.*;

import com.benjagest.backend.accounting.externalimport.ContendoCsvParser.Asiento;
import com.benjagest.backend.accounting.externalimport.ContendoCsvParser.Kind;
import com.benjagest.backend.accounting.externalimport.ContendoCsvParser.ParsedFile;
import java.math.BigDecimal;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Test del parser/clasificador contra una muestra fiel del diario CONTENDO
 * que Benjamin exporto (diario_contable_2026.csv). Cubre un asiento de cada
 * clase: VENTA, COBRO, RECTIFICATIVA, GASTO (con IVA), PAGO y OTRO
 * (recibo de autonomo sin IVA -> solo asiento, no factura).
 */
class ContendoCsvParserTest {

    // Cabecera real + 6 asientos representativos (punto decimal, fechas d/M/yyyy).
    private static final String CSV = """
            Asiento;Fecha;Cuenta;Nombre Cuenta;Debe;Haber;Concepto
            3;6/1/2026;600;Compras de mercaderias;12.55;0.00;Gasto: Toner - Amazon EU S.a.r.L.
            3;6/1/2026;472;Hacienda Publica, IVA soportado;2.64;0.00;Gasto: Toner - Amazon EU S.a.r.L.
            3;6/1/2026;4000001;Proveedores - Amazon EU S.a.r.L.;0.00;15.19;Gasto: Toner - Amazon EU S.a.r.L.
            14;6/1/2026;4000001;Proveedores - Amazon EU S.a.r.L.;15.19;0.00;Pago - Amazon EU S.a.r.L. - Toner
            14;6/1/2026;572;Bancos e instituciones de credito;0.00;15.19;Pago - Amazon EU S.a.r.L. - Toner
            1;22/1/2026;4300001;Clientes - MANUEL ALBENDIN CANETE;1347.50;0.00;Factura FRA-2026-0001
            1;22/1/2026;705;Prestaciones de servicios;0.00;1225.00;Factura FRA-2026-0001
            1;22/1/2026;477;Hacienda Publica, IVA repercutido;0.00;122.50;IVA factura FRA-2026-0001
            5;30/1/2026;642;Seguridad Social a cargo de la empresa;299.57;0.00;Gasto: RECIBO DE AUTONOMO ENERO-2026 - TGSS
            5;30/1/2026;476;Organismos de la SS, acreedores;0.00;299.57;Gasto: RECIBO DE AUTONOMO ENERO-2026 - TGSS
            12;12/2/2026;572;Bancos e instituciones de credito;1347.50;0.00;Cobro fact. FRA-2026-0001 - MANUEL ALBENDIN CANETE
            12;12/2/2026;4300001;Clientes - MANUEL ALBENDIN CANETE;0.00;1347.50;Cobro fact. FRA-2026-0001 - MANUEL ALBENDIN CANETE
            84;31/5/2026;4300004;Clientes - GONZALO GERVILLA MUNOZ;0.00;701.80;Rectificativa FRA-2026-0006R
            84;31/5/2026;705;Prestaciones de servicios;580.00;0.00;Rectificativa FRA-2026-0006R
            84;31/5/2026;477;Hacienda Publica, IVA repercutido;121.80;0.00;IVA rectificativa FRA-2026-0006R
            """;

    private Map<Integer, Asiento> parseByNumber() {
        ParsedFile pf = ContendoCsvParser.parse(CSV);
        assertTrue(pf.errors().isEmpty(), "no deberia haber errores: " + pf.errors());
        return pf.asientos().stream().collect(Collectors.toMap(Asiento::number, Function.identity()));
    }

    @Test
    void classifiesAllSixKinds() {
        Map<Integer, Asiento> a = parseByNumber();
        assertEquals(6, a.size());
        assertEquals(Kind.GASTO, a.get(3).kind());
        assertEquals(Kind.PAGO, a.get(14).kind());
        assertEquals(Kind.VENTA, a.get(1).kind());
        assertEquals(Kind.OTRO, a.get(5).kind());
        assertEquals(Kind.COBRO, a.get(12).kind());
        assertEquals(Kind.RECTIFICATIVA, a.get(84).kind());
    }

    @Test
    void ventaExtractsNumberPartyAndAmounts() {
        Asiento v = parseByNumber().get(1);
        assertEquals("FRA-2026-0001", v.invoiceNumber());
        assertEquals("MANUEL ALBENDIN CANETE", v.partyName());
        assertEquals("4300001", v.partyAccountCode());
        assertEquals(0, new BigDecimal("1225.00").compareTo(v.base()));
        assertEquals(0, new BigDecimal("122.50").compareTo(v.vat()));
        assertEquals(0, new BigDecimal("1347.50").compareTo(v.total()));
    }

    @Test
    void gastoWithVatExtractsSupplierAndBase() {
        Asiento g = parseByNumber().get(3);
        assertEquals("Amazon EU S.a.r.L.", g.partyName());
        assertEquals("4000001", g.partyAccountCode());
        assertEquals(0, new BigDecimal("12.55").compareTo(g.base()));
        assertEquals(0, new BigDecimal("2.64").compareTo(g.vat()));
        assertEquals(0, new BigDecimal("15.19").compareTo(g.total()));
    }

    @Test
    void cobroCarriesInvoiceNumberAndTreasury() {
        Asiento c = parseByNumber().get(12);
        assertEquals("FRA-2026-0001", c.invoiceNumber());
        assertEquals("572", c.treasuryCode());
        assertEquals(0, new BigDecimal("1347.50").compareTo(c.total()));
    }

    @Test
    void rectificativaLinksOriginalNumber() {
        Asiento r = parseByNumber().get(84);
        assertEquals("FRA-2026-0006R", r.invoiceNumber());
        assertEquals("FRA-2026-0006", r.originalInvoiceNumber());
        // magnitudes positivas; el importador las inserta en negativo
        assertEquals(0, new BigDecimal("580.00").compareTo(r.base()));
        assertEquals(0, new BigDecimal("701.80").compareTo(r.total()));
    }

    @Test
    void pagoCarriesSupplierAndTreasury() {
        Asiento p = parseByNumber().get(14);
        assertEquals("4000001", p.partyAccountCode());
        assertEquals("572", p.treasuryCode());
        assertEquals(0, new BigDecimal("15.19").compareTo(p.total()));
    }

    @Test
    void rejectsNonContendoHeader() {
        assertThrows(IllegalArgumentException.class,
                () -> ContendoCsvParser.parse("foo;bar;baz\n1;2;3"));
    }

    @Test
    void dotDecimalIsNotMangled() {
        // Regresion del bug de ExternalImportService.parseAmount: 12.55 debe
        // seguir siendo 12.55, no 1255.
        Asiento g = parseByNumber().get(3);
        assertEquals(0, new BigDecimal("12.55").compareTo(g.base()));
    }
}
