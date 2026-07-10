package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

/**
 * F1-N43 (2026-07-10) — Parser del Cuaderno 43 AEB fijado con un fichero
 * SINTÉTICO pero fiel a la norma (posiciones exactas de los registros
 * 11/22/23/33/88). Caza los tres bugs corregidos hoy:
 *
 *   1. El importe (posiciones 29-42, 14 dígitos con 2 decimales) se cortaba
 *      a 12 dígitos → 1.234,56 € se importaba como 12,34 (÷100 y sin céntimos).
 *   2. El concepto del registro 23 empieza en la posición 5 → se comía el
 *      primer carácter ("TRANSFERENCIA" → "RANSFERENCIA").
 *   3. La referencia externa mezclaba los 2 últimos dígitos del importe con
 *      el nº de documento.
 *
 * Además el parser ahora VALIDA el cuadre contra el registro 33 (nº de
 * apuntes y totales debe/haber): un fichero que no cuadra se rechaza.
 */
class BankImportParserTest {

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + " pero fue " + actual);
    }

    private static String pad(String s, int len) {
        StringBuilder sb = new StringBuilder(s == null ? "" : s);
        while (sb.length() < len) sb.append(' ');
        return sb.substring(0, len);
    }

    /** Fichero N43 conforme: cuenta con saldo inicial, un abono de 1.234,56
     *  (transferencia con registro 23 y NIF), un cargo de 89,90 (comisión)
     *  y registro 33 que CUADRA. */
    private static String n43File() {
        String r11 = "11" + "0049" + "1234" + "0123456789" + "260301" + "260331"
                + "2" + "00000010000000" + "978" + "1" + pad("EMPRESA DEMO SL", 26);
        String r22a = "22" + "    " + "1234" + "260305" + "260306" + "02" + "003"
                + "2" + "00000000123456" + "0000000001" + pad("REF1AAAAAAAA", 12)
                + pad("TRANSF ACME SL", 16);
        String r23a = "23" + "01" + pad("TRANSFERENCIA DE ACME SL B12345678", 76);
        String r22b = "22" + "    " + "1234" + "260310" + "260310" + "04" + "017"
                + "1" + "00000000008990" + "0000000002" + pad("REF1BBBBBBBB", 12)
                + pad("COMISION MANT.", 16);
        String r33 = "33" + "0049" + "1234" + "0123456789"
                + "00001" + "00000000008990"    // 1 apunte al debe, 89,90
                + "00001" + "00000000123456"    // 1 apunte al haber, 1.234,56
                + "2" + "00000010114466" + "978" + pad("", 4);
        String r88 = "88" + "9".repeat(20) + "000006" + pad("", 52);
        return String.join("\n", r11, r22a, r23a, r22b, r33, r88);
    }

    @Test
    void n43_importesConCentimosYSigno_posiciones29a42() {
        List<BankImportService.ParsedRow> rows = BankImportService.parseN43(n43File());
        assertEquals(2, rows.size());
        // Haber (clave 2) = positivo, CON los céntimos (bug 1: antes 12,34).
        assertAmount("1234.56", rows.get(0).amount);
        // Debe (clave 1) = negativo.
        assertAmount("-89.90", rows.get(1).amount);
        assertEquals(LocalDate.of(2026, 3, 5), rows.get(0).operationDate);
        assertEquals(LocalDate.of(2026, 3, 6), rows.get(0).valueDate);
        assertEquals(LocalDate.of(2026, 3, 10), rows.get(1).operationDate);
    }

    @Test
    void n43_conceptoDelRegistro23_completoConPrimerCaracterYNif() {
        List<BankImportService.ParsedRow> rows = BankImportService.parseN43(n43File());
        // Bug 2: antes "RANSFERENCIA..." (sin la T). La ref2 del 22 va delante.
        assertTrue(rows.get(0).description.contains("TRANSFERENCIA DE ACME SL"),
                "descripción fue: " + rows.get(0).description);
        assertEquals("B12345678", rows.get(0).counterpartyNif);
        // Sin registro 23, la descripción cae a la referencia 2 del 22.
        assertEquals("COMISION MANT.", rows.get(1).description);
        assertNull(rows.get(1).counterpartyNif);
    }

    @Test
    void n43_referenciaExterna_documentoMasRef1_sinRestosDelImporte() {
        List<BankImportService.ParsedRow> rows = BankImportService.parseN43(n43File());
        // Bug 3: antes empezaba por "56..." (los céntimos del importe).
        assertEquals("0000000001REF1AAAAAAAA", rows.get(0).externalRef);
        assertEquals("0000000002REF1BBBBBBBB", rows.get(1).externalRef);
    }

    @Test
    void n43_queNoCuadraConElRegistro33_seRechaza() {
        // Mismo fichero pero el 33 declara un total al haber de 9.999,99.
        String malo = n43File().replace("00001" + "00000000123456",
                "00001" + "00000000999999");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
                () -> BankImportService.parseN43(malo));
        assertTrue(String.valueOf(ex.getReason()).contains("no cuadra"));
    }

    @Test
    void n43_sinRegistro33_noValidaYParseaIgual() {
        String sin33 = n43File().lines().filter(l -> !l.startsWith("33"))
                .reduce((a, b) -> a + "\n" + b).orElseThrow();
        assertEquals(2, BankImportService.parseN43(sin33).size());
    }

    @Test
    void csv_fechasEsp_importesConComaYMiles_yCabecera() {
        String csv = String.join("\n",
                "Fecha;Valor;Concepto;Importe;Saldo",
                "05/03/2026;06/03/2026;TRANSF DE ACME SL B12345678;1.234,56;10.234,56",
                "2026-03-10;2026-03-10;RECIBO LUZ IBERDROLA;-56,78;10.177,78");
        List<BankImportService.ParsedRow> rows = BankImportService.parseCsv(csv);
        assertEquals(2, rows.size());
        assertEquals(LocalDate.of(2026, 3, 5), rows.get(0).operationDate);
        assertAmount("1234.56", rows.get(0).amount);
        assertAmount("10234.56", rows.get(0).balanceAfter);
        assertEquals("B12345678", rows.get(0).counterpartyNif);
        assertAmount("-56.78", rows.get(1).amount);
    }
}
