package com.benjagest.ui.support;

import com.benjagest.ui.i18n.I18n;

/**
 * Humaniza los errores que llegan del backend para que el usuario lea algo
 * entendible y no lenguaje de programador.
 *
 * <p>Dos pasos:
 * <ol>
 *   <li><b>Extraer</b> el campo {@code "message"} del JSON de error de Spring
 *       (si lo trae). Esto ya se hacia desde UIR-5.</li>
 *   <li><b>Traducir</b> (TRB-ERR, 2026-11-12) los mensajes tecnicos conocidos
 *       a una frase en el idioma de la UI. El usuario veia cosas como
 *       <i>"JSON parse error: Illegal unquoted character ((CTRL-CHAR, code
 *       10))..."</i>, que no dicen nada de que hacer.</li>
 * </ol>
 *
 * <p>Si el backend ya devuelve una frase humana ("El cliente es obligatorio")
 * se respeta tal cual: solo se sustituyen los textos claramente tecnicos.
 * Cuando no se reconoce el error se da una frase generica y el detalle tecnico
 * queda en una segunda linea, para poder reportarlo.
 */
public final class BackendErrors {

    private BackendErrors() {}

    /** Longitud maxima del detalle tecnico que se adjunta al mensaje generico. */
    private static final int MAX_DETAIL = 300;

    public static String humanize(String raw) {
        if (raw == null || raw.isBlank()) return "";
        String msg = extractMessage(raw);
        boolean extracted = !msg.equals(raw);

        // El backend ya habla en humano -> se respeta su texto.
        if (extracted && !looksTechnical(msg)) return msg;

        String friendly = friendly(msg, raw);
        if (friendly != null) return friendly;

        if (looksTechnical(msg)) {
            return I18n.t("error.friendly.unknown") + "\n\n"
                    + I18n.t("error.friendly.detail").replace("{detail}", shorten(msg));
        }
        return msg;
    }

    /**
     * Traduce los errores tecnicos conocidos. Devuelve {@code null} si no
     * reconoce el patron.
     */
    private static String friendly(String msg, String raw) {
        String all = (msg + " \n " + raw).toLowerCase(java.util.Locale.ROOT);

        // El caso que disparo esta mejora: un INTRO (o un TAB) dentro de un
        // campo de texto rompia el JSON que la UI enviaba.
        if (contains(all, "illegal unquoted character", "json parse error",
                "ctrl-char", "jsonparseexception", "jsonmappingexception",
                "cannot deserialize", "not a valid")) {
            return I18n.t("error.friendly.json_parse");
        }
        if (contains(all, "data too long", "value too long",
                "string or binary data would be truncated")) {
            return I18n.t("error.friendly.too_long");
        }
        if (contains(all, "duplicate entry", "duplicate key", "unique constraint",
                "dataintegrityviolation", "constraint violation", "foreign key")) {
            return I18n.t("error.friendly.conflict");
        }
        if (contains(all, "connection refused", "connectexception", "unknownhost",
                "no route to host", "failed to connect", "connection reset",
                "network is unreachable")) {
            return I18n.t("error.friendly.offline");
        }
        if (contains(all, "timed out", "timeout", "httptimeoutexception")) {
            return I18n.t("error.friendly.timeout");
        }

        int status = httpStatus(raw);
        if (status == 401) return I18n.t("error.friendly.session");
        if (status == 403) return I18n.t("error.friendly.forbidden");
        if (status == 404) return I18n.t("error.friendly.not_found");
        if (status == 409) return I18n.t("error.friendly.conflict");
        if (status == 413) return I18n.t("error.friendly.too_long");
        if (status >= 500) {
            return I18n.t("error.friendly.server") + "\n\n"
                    + I18n.t("error.friendly.detail").replace("{detail}", shorten(msg));
        }
        if (status == 400 && looksTechnical(msg)) return I18n.t("error.friendly.bad_request");
        return null;
    }

    /** Codigo HTTP del mensaje "HTTP 400: {...}" que lanzan los clientes. */
    private static int httpStatus(String raw) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("HTTP\\s+(\\d{3})").matcher(raw);
        if (m.find()) return Integer.parseInt(m.group(1));
        java.util.regex.Matcher s = java.util.regex.Pattern
                .compile("\"status\"\\s*:\\s*(\\d{3})").matcher(raw);
        return s.find() ? Integer.parseInt(s.group(1)) : 0;
    }

    /** Huele a traza o jerga de programador (eso no se le ensena al usuario). */
    private static boolean looksTechnical(String msg) {
        String s = msg.toLowerCase(java.util.Locale.ROOT);
        return contains(s, "exception", "java.", "jakarta.", "org.springframework",
                "com.benjagest", "sql", "jdbc", "json", "parse error", "stacktrace",
                "\tat ", " at com.", "nested", "constraint", "null pointer",
                "could not execute", "servlet", "{\"timestamp\"",
                "illegal", "deserial", "unexpected character", "http 5");
    }

    private static boolean contains(String haystack, String... needles) {
        for (String n : needles) {
            if (haystack.contains(n)) return true;
        }
        return false;
    }

    private static String shorten(String s) {
        String one = s.replace('\n', ' ').replace('\r', ' ').trim();
        return one.length() <= MAX_DETAIL ? one : one.substring(0, MAX_DETAIL) + "...";
    }

    /** Extrae el campo "message" del JSON de error; si no lo hay, devuelve raw. */
    private static String extractMessage(String raw) {
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
