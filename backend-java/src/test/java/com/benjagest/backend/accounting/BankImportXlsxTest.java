package com.benjagest.backend.accounting;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;

/**
 * F1-BANCO (2026-07-10) — Import de extracto .xlsx (BBVA no ofrece N43).
 * El fixture sintético replica el layout REAL del export "Últimos
 * movimientos" de BBVA verificado hoy: 4 filas de título/vacías, cabecera en
 * la fila 5 (F.Valor | Fecha | Concepto | Movimiento | Importe | Divisa |
 * Disponible | Divisa | Observaciones), fechas dd/MM/yyyy como TEXTO e
 * importes como NÚMERO nativo con punto decimal.
 *
 * <p>Cobertura clave: dos movimientos del MISMO día con el MISMO importe
 * (legítimos) se distinguen por la referencia externa construida con el
 * saldo posterior — sin ella, la clave de idempotencia del import los
 * colapsaría como duplicados. Y el NIF con puntos ("74.668.351-R", formato
 * típico de los extractos) se normaliza a 8 dígitos + letra.
 */
class BankImportXlsxTest {

    private static void assertAmount(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual),
                "esperado " + expected + " pero fue " + actual);
    }

    // ---- Constructor de .xlsx mínimo (zip + xml a mano) ----

    private static String col(int idx) {
        StringBuilder sb = new StringBuilder();
        idx++;
        while (idx > 0) { int r = (idx - 1) % 26; sb.insert(0, (char) ('A' + r)); idx = (idx - 1) / 26; }
        return sb.toString();
    }

    /** rows: String = texto (inlineStr) · empezando por '#' = celda numérica. */
    private static byte[] xlsx(String[][] rows) throws Exception {
        StringBuilder sheet = new StringBuilder(
                "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\"><sheetData>");
        for (int i = 0; i < rows.length; i++) {
            sheet.append("<row r=\"").append(i + 1).append("\">");
            for (int j = 0; j < rows[i].length; j++) {
                String v = rows[i][j];
                if (v == null || v.isEmpty()) continue;
                String ref = col(j) + (i + 1);
                if (v.startsWith("#")) {
                    sheet.append("<c r=\"").append(ref).append("\"><v>")
                         .append(v.substring(1)).append("</v></c>");
                } else {
                    sheet.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"><is><t>")
                         .append(v.replace("&", "&amp;").replace("<", "&lt;"))
                         .append("</t></is></c>");
                }
            }
            sheet.append("</row>");
        }
        sheet.append("</sheetData></worksheet>");

        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("[Content_Types].xml"));
            z.write("<?xml version=\"1.0\"?><Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\"/>".getBytes(StandardCharsets.UTF_8));
            z.putNextEntry(new ZipEntry("xl/worksheets/sheet1.xml"));
            z.write(sheet.toString().getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    private static byte[] bbvaFixture() throws Exception {
        return xlsx(new String[][] {
            {"", "", ""},
            {"", "Últimos movimientos"},
            {"", "Fecha de generación 10/07/2026"},
            {},
            {"F.Valor", "Fecha", "Concepto", "Movimiento", "Importe", "Divisa", "Disponible", "Divisa", "Observaciones"},
            {"10/07/2026", "10/07/2026", "Super patri", "Pago con tarjeta", "#-60.47", "EUR", "#16709.46", "EUR", "5529310010346561 SUPER PATRI"},
            {"10/07/2026", "10/07/2026", "Transferencia realizada", "74.668.351-r benjamin", "#-238.93", "EUR", "#16769.93", "EUR", "74.668.351-R BENJAMIN RECIO"},
            // Dos pagos LEGÍTIMOS idénticos el mismo día (solo cambia el saldo).
            {"07/07/2026", "08/07/2026", "Cafetería", "Pago con tarjeta", "#-2.50", "EUR", "#17008.86", "EUR", ""},
            {"07/07/2026", "08/07/2026", "Cafetería", "Pago con tarjeta", "#-2.50", "EUR", "#17011.36", "EUR", ""},
            {"01/07/2026", "01/07/2026", "Transferencia recibida", "Cliente Forjados", "#1500", "EUR", "#17013.86", "EUR", "B18456789 FORJADOS SL FRA 26-101"},
            // Pie de resumen sin importe → se ignora.
            {"", "", "Saldo final", "", "", "", "#16709.46"},
        });
    }

    @Test
    void bbva_cabeceraEnFila5_fechasImportesYSaldos() throws Exception {
        List<BankImportService.ParsedRow> rows = BankImportService.parseXlsxBank(bbvaFixture());
        assertEquals(5, rows.size());
        assertEquals(LocalDate.of(2026, 7, 10), rows.get(0).operationDate);
        // F.Valor distinto de Fecha (fila de la cafetería: oper 08/07... ojo:
        // BBVA pone F.Valor primero — aquí valor=07/07, operación=08/07).
        assertEquals(LocalDate.of(2026, 7, 8), rows.get(2).operationDate);
        assertEquals(LocalDate.of(2026, 7, 7), rows.get(2).valueDate);
        assertAmount("-60.47", rows.get(0).amount);
        assertAmount("16709.46", rows.get(0).balanceAfter);
        assertAmount("1500", rows.get(4).amount);
        assertTrue(rows.get(0).description.contains("Super patri"));
    }

    @Test
    void bbva_nifConPuntosYGuion_seNormaliza() throws Exception {
        List<BankImportService.ParsedRow> rows = BankImportService.parseXlsxBank(bbvaFixture());
        assertEquals("74668351R", rows.get(1).counterpartyNif);
        assertEquals("B18456789", rows.get(4).counterpartyNif);
    }

    @Test
    void bbva_movimientosIdenticosMismoDia_referenciasDistintasPorSaldo() throws Exception {
        List<BankImportService.ParsedRow> rows = BankImportService.parseXlsxBank(bbvaFixture());
        assertEquals("SALDO:17008.86", rows.get(2).externalRef);
        assertEquals("SALDO:17011.36", rows.get(3).externalRef);
        assertNotEquals(rows.get(2).externalRef, rows.get(3).externalRef);
    }

    /**
     * Diagnóstico OPCIONAL contra un export real (no va al repo): correr con
     * {@code -Dbenjagest.bank.xlsx=C:/ruta/al/export.xlsx}. Imprime resumen.
     */
    @Test
    void diagnostico_extractoReal_siSeIndicaRuta() throws Exception {
        String path = System.getProperty("benjagest.bank.xlsx");
        org.junit.jupiter.api.Assumptions.assumeTrue(path != null && !path.isBlank());
        byte[] data = Files.readAllBytes(Path.of(path));
        List<BankImportService.ParsedRow> rows = BankImportService.parseXlsxBank(data);
        assertTrue(rows.size() > 0, "el extracto real no produjo movimientos");
        BigDecimal total = rows.stream().map(r -> r.amount).reduce(BigDecimal.ZERO, BigDecimal::add);
        long conFecha = rows.stream().filter(r -> r.operationDate != null).count();
        long conSaldo = rows.stream().filter(r -> r.balanceAfter != null).count();
        long conNif = rows.stream().filter(r -> r.counterpartyNif != null).count();
        System.out.println("[diagnostico xlsx] movimientos=" + rows.size()
                + " sumaNeta=" + total + " conFecha=" + conFecha
                + " conSaldo=" + conSaldo + " conNif=" + conNif);
        assertEquals(rows.size(), conFecha, "todas las filas deben tener fecha");
    }
}
