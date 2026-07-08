package com.benjagest.backend.purchases;

import java.util.Set;

/**
 * OPTYPE-3 (2026-07-08) — deduce el tipo de operación a efectos de IVA a
 * partir del NIF del tercero, para no clasificar a mano cada gasto.
 *
 * <p>Heurística: un NIF que empieza por el código de país (prefijo VIES) de
 * un Estado miembro de la UE distinto de España → adquisición
 * intracomunitaria (INTRACOM). Un NIF español (CIF/NIE/DNI, nunca empieza por
 * dos letras de código UE) o cualquier otro → INTERIOR por defecto.
 *
 * <p>Las importaciones (IMPORT) no se detectan de forma fiable desde el NIF
 * (los operadores de terceros países no siguen un formato común), así que se
 * dejan como INTERIOR para que la asesoría las reclasifique a mano.
 */
public final class OperationTypeDetector {

    private OperationTypeDetector() {}

    /** Prefijos de IVA intracomunitario (VIES) de los Estados miembros de la
     *  UE, EXCEPTO ES. Grecia usa EL (no GR) a efectos de IVA. */
    private static final Set<String> EU_VAT_PREFIXES = Set.of(
            "AT", "BE", "BG", "HR", "CY", "CZ", "DK", "EE", "FI", "FR", "DE",
            "EL", "HU", "IE", "IT", "LV", "LT", "LU", "MT", "NL", "PL", "PT",
            "RO", "SK", "SI", "SE");

    /** Devuelve INTRACOM si el NIF es de un operador intracomunitario UE; en
     *  caso contrario INTERIOR. Nunca devuelve null. */
    public static String detect(String supplierNif) {
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
