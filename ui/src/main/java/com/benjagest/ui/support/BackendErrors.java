package com.benjagest.ui.support;

/** Humaniza errores del backend (extrae el "message" del JSON). Extraido en UIR-5. */
public final class BackendErrors {

    private BackendErrors() {}

    public static String humanize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        // Localiza "message":"..." sin importar lo que tenga delante.
        int idx = raw.indexOf("\"message\"");
        if (idx < 0) return raw;
        int colon = raw.indexOf(':', idx);
        if (colon < 0) return raw;
        int firstQuote = raw.indexOf('"', colon);
        if (firstQuote < 0) return raw;
        StringBuilder sb = new StringBuilder();
        boolean escape = false;
        for (int i = firstQuote + 1; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (escape) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> { /* drop */ }
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(c);
                }
                escape = false;
            } else if (c == '\\') {
                escape = true;
            } else if (c == '"') {
                String out = sb.toString().trim();
                return out.isBlank() ? raw : out;
            } else {
                sb.append(c);
            }
        }
        return raw;
    }
}
