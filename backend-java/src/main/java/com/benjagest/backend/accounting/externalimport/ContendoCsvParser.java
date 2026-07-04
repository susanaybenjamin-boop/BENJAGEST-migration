package com.benjagest.backend.accounting.externalimport;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parser + clasificador del "diario contable" exportado por CONTENDO.
 *
 * <p>Formato del fichero (separado por {@code ;}, una fila por apunte):
 * <pre>Asiento;Fecha;Cuenta;Nombre Cuenta;Debe;Haber;Concepto</pre>
 * Fechas {@code d/M/yyyy} (p.ej. {@code 6/1/2026}); importes con PUNTO decimal
 * ({@code 12.55}, sin separador de miles). Los numeros de asiento pueden tener
 * huecos (asientos anulados en origen) — no se conservan como {@code entry_number}
 * (regla de no renumerar): al importar reciben correlativos POSTED nuevos.
 *
 * <p>Clase sin estado ni dependencias de Spring: se testea en aislamiento.
 * Solo parsea y clasifica; NO escribe en base de datos. La reconstruccion de
 * facturas/terceros/cobros vive en {@code ContendoImportService}.
 *
 * <p>Clasificacion de cada asiento (estructura de cuentas + regex de concepto):
 * <ul>
 *   <li><b>VENTA</b>: debe 43xx (cliente) + haber 70x (ingreso) [+ haber 477 IVA].</li>
 *   <li><b>RECTIFICATIVA</b>: concepto "rectificativa" (o haber 43xx + debe 70x);
 *       numero termina en R.</li>
 *   <li><b>COBRO</b>: debe 57x (tesoreria) + haber 43xx + concepto "cobro".</li>
 *   <li><b>GASTO</b> (solo con IVA): haber 40xx (proveedor) + debe 6xx + linea 472.</li>
 *   <li><b>PAGO</b>: debe 40xx + haber 57x + concepto "pago".</li>
 *   <li><b>OTRO</b>: el resto (SS/autonomo sin IVA, nominas, traspasos): solo asiento.</li>
 * </ul>
 */
public final class ContendoCsvParser {

    private ContendoCsvParser() {}

    public enum Kind { VENTA, RECTIFICATIVA, COBRO, GASTO, PAGO, OTRO }

    /** Un apunte (linea) del asiento. */
    public record Line(String accountCode, String accountName,
                       BigDecimal debit, BigDecimal credit, String description) {}

    /**
     * Un asiento clasificado. Los campos {@code base/vat/total} son magnitudes
     * POSITIVAS (el importador decide el signo: la rectificativa se inserta en
     * negativo). {@code total} en cobro/pago es el importe movido en tesoreria.
     */
    public record Asiento(
            int number,
            LocalDate date,
            String concept,
            List<Line> lines,
            Kind kind,
            String invoiceNumber,          // FRA-... (venta/rectificativa/cobro) o null
            String originalInvoiceNumber,  // rectificativa: numero sin la R final
            String partyName,              // cliente o proveedor (sin prefijo) o null
            String partyAccountCode,       // 43xx / 40xx o null
            BigDecimal base,               // base imponible (magnitud) o null
            BigDecimal vat,                // cuota IVA (magnitud) o null
            BigDecimal total,              // total factura / importe cobrado o pagado
            String treasuryCode            // 570/572 en cobro/pago, null resto
    ) {}

    public record ParsedFile(List<Asiento> asientos, List<String> errors) {}

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d/M/yyyy");
    private static final String[] EXPECTED_HEADERS =
            {"asiento", "fecha", "cuenta", "nombre cuenta", "debe", "haber", "concepto"};
    /** Numero de factura tipo FRA-2026-0001 / FRA-2026-0006R (case-insensitive). */
    private static final Pattern INVOICE_NUM =
            Pattern.compile("([A-Za-z]{2,6}-\\d{3,6}-\\d{2,8}[A-Za-z]?)");
    /** Importe estricto punto-decimal: NO reutilizar el parseAmount de
     *  ExternalImportService, que hace replace(".","") y destroza 12.55 -> 1255. */
    private static final Pattern AMOUNT_OK = Pattern.compile("-?\\d+(\\.\\d{1,2})?");
    private static final BigDecimal CENT = new BigDecimal("0.01");

    /**
     * Parsea el contenido completo del fichero. Lanza
     * {@link IllegalArgumentException} si la cabecera no es la de un diario
     * CONTENDO (el llamador la traduce a 400). Los asientos que no cuadran o
     * tienen datos invalidos NO abortan el fichero: van a {@code errors}.
     */
    public static ParsedFile parse(String content) {
        List<String> errors = new ArrayList<>();
        if (content == null || content.isBlank()) {
            throw new IllegalArgumentException("El fichero esta vacio.");
        }
        if (content.charAt(0) == '﻿') content = content.substring(1); // BOM
        String[] rawLines = content.split("\\r?\\n");
        String[] header = rawLines[0].split(";", -1);
        if (!headerMatches(header)) {
            throw new IllegalArgumentException(
                    "El fichero no parece un diario CONTENDO. Cabecera esperada: "
                    + "Asiento;Fecha;Cuenta;Nombre Cuenta;Debe;Haber;Concepto");
        }
        // Agrupar por numero de asiento, preservando el orden de aparicion.
        Map<Integer, List<String[]>> groups = new LinkedHashMap<>();
        for (int i = 1; i < rawLines.length; i++) {
            String raw = rawLines[i];
            if (raw == null || raw.isBlank()) continue;
            String[] c = raw.split(";", -1);
            if (c.length < 7) {
                errors.add("Linea " + (i + 1) + ": columnas insuficientes");
                continue;
            }
            int num;
            try {
                num = Integer.parseInt(c[0].trim());
            } catch (Exception ex) {
                errors.add("Linea " + (i + 1) + ": numero de asiento invalido '" + c[0].trim() + "'");
                continue;
            }
            groups.computeIfAbsent(num, k -> new ArrayList<>()).add(c);
        }
        List<Asiento> asientos = new ArrayList<>();
        for (Map.Entry<Integer, List<String[]>> e : groups.entrySet()) {
            try {
                asientos.add(buildAsiento(e.getKey(), e.getValue()));
            } catch (Exception ex) {
                errors.add("Asiento " + e.getKey() + ": " + ex.getMessage());
            }
        }
        return new ParsedFile(asientos, errors);
    }

    private static Asiento buildAsiento(int number, List<String[]> rows) {
        LocalDate date = null;
        String concept = null;
        List<Line> lines = new ArrayList<>();
        BigDecimal sumD = BigDecimal.ZERO, sumH = BigDecimal.ZERO;
        for (String[] c : rows) {
            LocalDate d = parseDate(c[1].trim());
            if (date == null) date = d;
            String code = c[2].trim();
            String name = c[3].trim();
            BigDecimal debit = parseAmount(c[4].trim());
            BigDecimal credit = parseAmount(c[5].trim());
            String desc = c[6].trim();
            if (concept == null && !desc.isEmpty()) concept = desc;
            lines.add(new Line(code, name, debit, credit, desc));
            sumD = sumD.add(debit);
            sumH = sumH.add(credit);
        }
        if (date == null) throw new IllegalStateException("sin fecha");
        if (concept == null) concept = "Importado";
        if (sumD.subtract(sumH).abs().compareTo(CENT) > 0) {
            throw new IllegalStateException(
                    "descuadre debe/haber: " + sumD.toPlainString() + " vs " + sumH.toPlainString());
        }
        return classify(number, date, concept, lines);
    }

    private static Asiento classify(int number, LocalDate date, String concept, List<Line> lines) {
        String cl = concept.toLowerCase();
        boolean debClient = false, credClient = false, debTreasury = false, credTreasury = false,
                credSupplier = false, debSupplier = false, income = false, vatSoportado = false,
                expense = false;
        Line clientLine = null, supplierLine = null, treasuryLine = null;
        BigDecimal incomeSum = BigDecimal.ZERO, vatRepSum = BigDecimal.ZERO, vatSopSum = BigDecimal.ZERO,
                expenseSum = BigDecimal.ZERO, clientDebit = BigDecimal.ZERO, clientCredit = BigDecimal.ZERO,
                supplierCredit = BigDecimal.ZERO, treasuryDebit = BigDecimal.ZERO, treasuryCredit = BigDecimal.ZERO;
        for (Line l : lines) {
            String code = l.accountCode();
            BigDecimal d = l.debit(), h = l.credit();
            boolean dp = d.signum() > 0, hp = h.signum() > 0;
            if (code.startsWith("43")) {
                if (dp) debClient = true;
                if (hp) credClient = true;
                if (clientLine == null) clientLine = l;
                clientDebit = clientDebit.add(d);
                clientCredit = clientCredit.add(h);
            } else if (code.startsWith("477")) {
                vatRepSum = vatRepSum.add(dp ? d : h);
            } else if (code.startsWith("472")) {
                vatSoportado = true;
                vatSopSum = vatSopSum.add(dp ? d : h);
            } else if (code.startsWith("40")) {
                if (dp) debSupplier = true;
                if (hp) credSupplier = true;
                if (supplierLine == null) supplierLine = l;
                supplierCredit = supplierCredit.add(h);
            } else if (code.startsWith("57")) {
                if (dp) debTreasury = true;
                if (hp) credTreasury = true;
                if (treasuryLine == null) treasuryLine = l;
                treasuryDebit = treasuryDebit.add(d);
                treasuryCredit = treasuryCredit.add(h);
            } else if (code.startsWith("7")) {
                income = true;
                incomeSum = incomeSum.add(hp ? h : d);
            } else if (code.startsWith("6")) {
                expense = true;
                expenseSum = expenseSum.add(d);
            }
        }
        String invNum = extractInvoiceNumber(concept, lines);
        String clientCode = clientLine != null ? clientLine.accountCode() : null;
        String supplierCode = supplierLine != null ? supplierLine.accountCode() : null;
        String treasuryCodeVal = treasuryLine != null ? treasuryLine.accountCode() : null;
        String clientName = clientLine != null ? stripPartyPrefix(clientLine.accountName()) : partyFromConcept(concept);
        String supplierName = supplierLine != null ? stripPartyPrefix(supplierLine.accountName()) : partyFromConcept(concept);

        // 1. RECTIFICATIVA — concepto "rectificativa", o estructura invertida
        //    (cliente al haber + ingreso al debe) con numero acabado en R.
        boolean rectByStruct = credClient && income && !debClient;
        boolean numEndsR = invNum != null && invNum.toUpperCase().endsWith("R");
        if (cl.contains("rectificativa") || (rectByStruct && numEndsR)) {
            String orig = numEndsR ? invNum.substring(0, invNum.length() - 1) : null;
            return new Asiento(number, date, concept, lines, Kind.RECTIFICATIVA,
                    invNum, orig, clientName, clientCode,
                    incomeSum, vatRepSum, clientCredit, null);
        }
        // 2. VENTA — cliente al debe + ingreso al haber.
        if (debClient && income) {
            return new Asiento(number, date, concept, lines, Kind.VENTA,
                    invNum, null, clientName, clientCode,
                    incomeSum, vatRepSum, clientDebit, null);
        }
        // 3. COBRO — tesoreria al debe + cliente al haber + concepto "cobro".
        if (debTreasury && credClient && cl.contains("cobro")) {
            return new Asiento(number, date, concept, lines, Kind.COBRO,
                    invNum, null, clientName, clientCode,
                    null, null, treasuryDebit, treasuryCodeVal);
        }
        // 4. GASTO con IVA — proveedor al haber + gasto 6xx al debe + linea 472.
        //    Sin 472 (SS/autonomo) NO crea factura recibida: cae a OTRO (D8).
        if (credSupplier && expense && vatSoportado) {
            return new Asiento(number, date, concept, lines, Kind.GASTO,
                    invNum, null, supplierName, supplierCode,
                    expenseSum, vatSopSum, supplierCredit, null);
        }
        // 5. PAGO — proveedor al debe + tesoreria al haber + concepto "pago".
        if (debSupplier && credTreasury && cl.contains("pago")) {
            return new Asiento(number, date, concept, lines, Kind.PAGO,
                    invNum, null, supplierName, supplierCode,
                    null, null, treasuryCredit, treasuryCodeVal);
        }
        // 6. OTRO — solo asiento.
        return new Asiento(number, date, concept, lines, Kind.OTRO,
                null, null, null, null, null, null, null, null);
    }

    private static BigDecimal parseAmount(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        s = s.trim();
        if (!AMOUNT_OK.matcher(s).matches()) {
            throw new IllegalStateException("importe no valido '" + s + "'");
        }
        return new BigDecimal(s);
    }

    private static LocalDate parseDate(String s) {
        try {
            return LocalDate.parse(s, DATE);
        } catch (Exception ex) {
            throw new IllegalStateException("fecha no valida '" + s + "'");
        }
    }

    private static String extractInvoiceNumber(String concept, List<Line> lines) {
        Matcher m = INVOICE_NUM.matcher(concept);
        if (m.find()) return m.group(1).toUpperCase();
        for (Line l : lines) {
            Matcher m2 = INVOICE_NUM.matcher(l.description() == null ? "" : l.description());
            if (m2.find()) return m2.group(1).toUpperCase();
        }
        return null;
    }

    private static String stripPartyPrefix(String name) {
        if (name == null) return null;
        String n = name.trim()
                .replaceFirst("(?i)^clientes\\s*-\\s*", "")
                .replaceFirst("(?i)^proveedores\\s*-\\s*", "")
                .trim();
        return n.isBlank() ? null : n;
    }

    /** Fallback: nombre tras el ultimo " - " del concepto. */
    private static String partyFromConcept(String concept) {
        int idx = concept.lastIndexOf(" - ");
        if (idx >= 0 && idx + 3 < concept.length()) {
            String s = concept.substring(idx + 3).trim();
            return s.isBlank() ? null : s;
        }
        return null;
    }

    private static boolean headerMatches(String[] header) {
        if (header.length < EXPECTED_HEADERS.length) return false;
        for (int i = 0; i < EXPECTED_HEADERS.length; i++) {
            String h = header[i] == null ? "" : header[i].trim().toLowerCase();
            if (!h.isEmpty() && h.charAt(0) == '﻿') h = h.substring(1);
            if (!h.equals(EXPECTED_HEADERS[i])) return false;
        }
        return true;
    }
}
