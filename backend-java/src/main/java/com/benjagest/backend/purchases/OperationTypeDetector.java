package com.benjagest.backend.purchases;

import java.math.BigDecimal;
import java.util.Set;

/**
 * OPTYPE-3 (2026-07-08) — deduce el tipo de operación a efectos de IVA a
 * partir del NIF del tercero y del IVA de la factura, para no clasificar a
 * mano cada gasto.
 *
 * <p>Regla clave: lo que decide NO es el país del vendedor sino si la factura
 * lleva IVA español. Una adquisición intracomunitaria B2B va con
 * <b>inversión del sujeto pasivo</b> (sin IVA repercutido; se autorrepercute
 * el comprador). Por eso solo se marca INTRACOM cuando:
 * <ol>
 *   <li>el NIF empieza por el prefijo VIES de un Estado UE distinto de ES, y</li>
 *   <li>la factura <b>no</b> lleva IVA.</li>
 * </ol>
 *
 * <p>Esto evita el falso positivo real de las compras de marketplace (Amazon
 * EU S.à r.l. factura con NIF de Luxemburgo LU… pero repercute IVA español;
 * incluso con vendedor de un tercer país —China— Amazon declara el IVA
 * español como plataforma): son operaciones INTERIORES, con IVA deducible.
 *
 * <p>Las importaciones (IMPORT) no se detectan de forma fiable desde el NIF,
 * así que quedan INTERIOR para que la asesoría las reclasifique a mano.
 */
public final class OperationTypeDetector {

    private OperationTypeDetector() {}

    /** Prefijos de IVA intracomunitario (VIES) de los Estados miembros de la
     *  UE, EXCEPTO ES. Grecia usa EL (no GR) a efectos de IVA. */
    private static final Set<String> EU_VAT_PREFIXES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE",
            "EL", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT",
            "RO", "SK", "SI", "SE");

    /**
     * Devuelve INTRACOM si el NIF es de un operador UE ≠ ES y la factura NO
     * lleva IVA (inversión del sujeto pasivo); en caso contrario INTERIOR.
     * Nunca devuelve null.
     *
     * @param supplierNif NIF del proveedor (puede venir en formato VIES).
     * @param vatAmount   cuota de IVA de la factura; si es &gt; 0 la operación
     *                    es interior (IVA español repercutido) aunque el
     *                    vendedor sea extranjero.
     */
    public static String detect(String supplierNif, BigDecimal vatAmount) {
        // Si la factura repercute IVA español, es una operación interior
        // (marketplace tipo Amazon EU), sea cual sea el país del vendedor.
        if (vatAmount != null && vatAmount.signum() > 0) return "INTERIOR";
        if (supplierNif == null) return "INTERIOR";
        String n = supplierNif.trim().toUpperCase().replaceAll("[\\s.\\-]", "");
        if (n.length() < 3) return "INTERIOR";
        String prefix = n.substring(0, 2);
        // El código de país va seguido del número (un dígito): así un NIF
        // español (una letra + dígitos) nunca colisiona con un prefijo UE.
        if (EU_VAT_PREFIXES.contains(prefix) && Character.isDigit(n.charAt(2))) {
            return "INTRACOM";
        }
        return "INTERIOR";
    }
}
