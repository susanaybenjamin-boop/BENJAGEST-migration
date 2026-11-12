package com.benjagest.ui.support;

/**
 * Escape de cadenas JSON para los clientes HTTP de la UI (que construyen el
 * body a mano con StringBuilder).
 *
 * <p>TRB-JSON-FIX (2026-11-12): los helpers {@code escape()} de cada cliente
 * solo escapaban la barra invertida y la comilla (algunos tambien el salto de
 * linea). Cualquier caracter de control sin escapar (un INTRO dentro del
 * concepto de un trabajo, un TAB pegado desde Excel...) viajaba crudo dentro
 * de la cadena JSON y Jackson lo rechazaba en el backend con:
 * <pre>JSON parse error: Illegal unquoted character ((CTRL-CHAR, code 10))</pre>
 * El usuario veia "No se pudo guardar el trabajo" y no habia forma de guardar.
 *
 * <p>Aqui se escapa TODO lo que exige RFC 8259: comilla, barra invertida y
 * cualquier caracter por debajo de 0x20 (los conocidos con su secuencia corta,
 * el resto en forma uXXXX).
 */
public final class Json {

    private Json() {}

    /** Escapa el CONTENIDO de una cadena JSON (sin las comillas). null -> "". */
    public static String escape(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }

    /** Cadena JSON completa (con comillas). null -> literal {@code null}. */
    public static String quote(String value) {
        return value == null ? "null" : "\"" + escape(value) + "\"";
    }
}
