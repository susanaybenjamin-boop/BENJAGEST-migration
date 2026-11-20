package com.benjagest.backend.boe;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BOE-FILTRO — decide si un artículo del BOE encaja con unas preferencias.
 *
 * <p>Clase PURA (sin BD ni HTTP) para poder probar el criterio con títulos
 * reales del BOE sin levantar nada.
 *
 * <p><b>Por qué existe</b>: el criterio anterior era
 * {@code titulo.toLowerCase().contains(palabra.toLowerCase())}, sin límites de
 * palabra. Medido contra el BOE del 19-nov-2026, la palabra <b>"IVA"</b> generó
 * 6 de los 13 avisos del día enganchando dentro de <i>"Pr<b>iva</b>da"</i>,
 * <i>"select<b>iva</b>s"</i>, <i>"educat<b>iva</b>"</i>, <i>"negat<b>iva</b>"</i>
 * y <i>"prevent<b>iva</b>"</i> — ninguno hablaba de IVA.
 */
public final class BoeMatcher {

    private BoeMatcher() {
    }

    /** Un artículo del sumario, ya extraído del JSON. */
    public record BoeItem(
            String id,
            String title,
            String url,
            String department,
            String sectionCode,
            String sectionName
    ) {}

    /**
     * Quita tildes y pasa a minúsculas, para que "nómina" encuentre "nomina" y
     * al revés. El BOE no es consistente con las mayúsculas en los títulos y
     * quien escriba las palabras clave en la pantalla tampoco tiene por qué
     * acordarse de las tildes.
     */
    public static String normalize(String s) {
        if (s == null) return "";
        String n = Normalizer.normalize(s, Normalizer.Form.NFD);
        StringBuilder sb = new StringBuilder(n.length());
        for (int i = 0; i < n.length(); i++) {
            char c = n.charAt(i);
            // Marcas diacríticas combinantes fuera (la tilde de "ó", la de "ñ"...).
            if (Character.getType(c) != Character.NON_SPACING_MARK) sb.append(c);
        }
        return sb.toString().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Patrón de palabra COMPLETA. Se usan lookarounds sobre letras y dígitos en
     * vez de {@code \b} porque las palabras clave pueden llevar espacios,
     * barras o puntos ("Orden HAC", "RD 1619/2012") y {@code \b} se comporta de
     * forma poco intuitiva junto a esos caracteres.
     */
    public static Pattern wordPattern(String keyword) {
        String k = normalize(keyword).trim();
        if (k.isEmpty()) return null;
        return Pattern.compile(
                "(?<![\\p{L}\\p{N}])" + Pattern.quote(k) + "(?![\\p{L}\\p{N}])");
    }

    /**
     * Palabras clave que aparecen en el título, en el orden de la lista.
     * Lista de palabras vacía = no se filtra por palabra, y entonces devuelve
     * una marca para que el aviso pueda explicar por qué llegó.
     */
    public static List<String> matchedKeywords(String title, List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) return List.of("(sin filtro de palabra)");
        String hay = normalize(title);
        if (hay.isBlank()) return List.of();
        List<String> hits = new ArrayList<>();
        for (String k : keywords) {
            Pattern p = wordPattern(k);
            if (p == null) continue;
            Matcher m = p.matcher(hay);
            if (m.find() && !hits.contains(k)) hits.add(k);
        }
        return hits;
    }

    /**
     * ¿Pasa este artículo los filtros de sección y departamento? Son los dos
     * baratos y van primero. El departamento se compara normalizado y por
     * "contiene", porque el BOE escribe nombres largos y cambiantes
     * ("MINISTERIO DE TRABAJO Y ECONOMÍA SOCIAL") y así guardar "trabajo"
     * sigue valiendo si el ministerio se renombra.
     */
    public static boolean passesScope(BoeItem item, BoeAlertPrefs prefs) {
        if (!prefs.sections().isEmpty()
                && !prefs.sections().contains(item.sectionCode())) {
            return false;
        }
        if (!prefs.departments().isEmpty()) {
            String dep = normalize(item.department());
            boolean any = false;
            for (String wanted : prefs.departments()) {
                String w = normalize(wanted).trim();
                if (!w.isEmpty() && dep.contains(w)) { any = true; break; }
            }
            if (!any) return false;
        }
        return true;
    }

    /** Resultado de evaluar un artículo: si entra y por qué. */
    public record Match(BoeItem item, List<String> keywords) {}

    /**
     * Aplica los tres filtros en cadena y devuelve los artículos que la empresa
     * quiere recibir. Si las preferencias están desactivadas, no quiere ninguno.
     */
    public static List<Match> filter(List<BoeItem> items, BoeAlertPrefs prefs) {
        List<Match> out = new ArrayList<>();
        if (!prefs.enabled()) return out;
        for (BoeItem it : items) {
            if (!passesScope(it, prefs)) continue;
            List<String> kws = matchedKeywords(it.title(), prefs.keywords());
            if (kws.isEmpty()) continue;
            out.add(new Match(it, kws));
        }
        return out;
    }
}
