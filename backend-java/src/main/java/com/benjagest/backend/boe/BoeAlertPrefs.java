package com.benjagest.backend.boe;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * BOE-FILTRO (2026-11-20) — qué avisos del BOE quiere recibir una empresa.
 *
 * <p>Benjamin: <i>"cuando llegan del BOE notificaciones, el filtro es light, y
 * se llena de noticias que no interesan, tendríamos que poder escoger qué
 * queremos que nos llegue"</i>. Hasta ahora el filtro era una constante
 * {@code static final} igual para todo el mundo.
 *
 * <p>Los tres filtros se aplican en CADENA (sección Y departamento Y palabra),
 * de más barato a más caro. Para cada uno, <b>lista vacía = no filtrar por
 * eso</b>; y que la empresa no tenga fila guardada significa "valores de
 * fábrica", no "sin filtro" (fail-open controlado: quien no configure nada
 * sigue recibiendo avisos útiles).
 */
public record BoeAlertPrefs(
        boolean enabled,
        List<String> sections,
        List<String> departments,
        List<String> keywords
) {

    /**
     * Secciones de fábrica: <b>I. Disposiciones generales</b> (donde salen los
     * reales decretos y órdenes que cambian normativa) y <b>III. Otras
     * disposiciones</b> (convenios colectivos, resoluciones de la AEAT y de la
     * Seguridad Social).
     *
     * <p>Quedan fuera a propósito las que más ruido meten en una asesoría y que
     * hasta hoy ni se leían del JSON: <b>II-A nombramientos</b>, <b>II-B
     * oposiciones y concursos</b> y <b>V anuncios</b> (contratación del sector
     * público). Medido sobre el BOE del 19-nov-2026, de los 13 avisos que
     * generaba el filtro viejo había nombramientos de personal colados por
     * contener "Seguridad Social" en el título.
     */
    public static final List<String> DEFAULT_SECTIONS = List.of("1", "3");

    /**
     * Palabras de fábrica. Respecto a la lista anterior se van CUATRO comodines
     * que eran el origen de casi todo el ruido:
     * <ul>
     *   <li>{@code "Real Decreto"} y {@code "RD "} — el BOE publica reales
     *       decretos a diario sobre subvenciones, nombramientos o condecoraciones;
     *       entraban todos. La normativa que de verdad importa ya la cazan las
     *       palabras temáticas de abajo.</li>
     *   <li>{@code "BOE-A-2026"} y {@code "BOE-A-2027"} — se comparaban contra el
     *       TÍTULO, no contra el identificador, así que no filtraban nada útil.</li>
     * </ul>
     * Y se añaden las que a una asesoría le faltaban (cotización, retenciones,
     * RETA, convenio colectivo, estimación objetiva).
     */
    public static final List<String> DEFAULT_KEYWORDS = List.of(
            "AEAT", "IVA", "IRPF", "Impuesto sobre Sociedades",
            "factura electrónica", "VeriFactu", "SIF", "Orden HAC",
            "retención", "retenciones", "estimación objetiva", "módulos",
            "SEPE", "Seguridad Social", "cotización", "cotizaciones",
            "salario", "salarial", "nómina", "nóminas",
            "autónomos", "RETA", "convenio colectivo",
            "fichaje", "registro horario",
            "contrato de trabajo", "Estatuto de los Trabajadores");

    /** Lo que recibe una empresa que no ha tocado nada. */
    public static BoeAlertPrefs defaults() {
        return new BoeAlertPrefs(true, DEFAULT_SECTIONS, List.of(), DEFAULT_KEYWORDS);
    }

    /**
     * Normaliza para que el resto del código no tenga que comprobar nulls:
     * una lista nula pasa a vacía, y vacía significa "no filtrar por esto".
     */
    public BoeAlertPrefs {
        sections = clean(sections);
        departments = clean(departments);
        keywords = clean(keywords);
    }

    /**
     * Lista inmutable, sin nulos y sin entradas en blanco. Se filtran los nulos
     * a mano porque {@code List.copyOf} revienta con ellos, y un cuerpo JSON
     * con {@code ["IVA", null]} llegaria del cliente como un 500 en vez de
     * como lo que es: una entrada que sobra.
     */
    private static List<String> clean(List<String> in) {
        if (in == null) return List.of();
        List<String> out = new ArrayList<>(in.size());
        for (String v : in) {
            if (v == null) continue;
            String t = v.trim();
            if (!t.isEmpty() && !out.contains(t)) out.add(t);
        }
        return List.copyOf(out);
    }

    // ------------------------------------------------------------------
    //  Serialización de las listas (ver el comentario de la V183)
    // ------------------------------------------------------------------

    /**
     * Lee una lista guardada como texto. {@code null} significa "no configurado"
     * y por eso devuelve el valor de fábrica que se le pase; una cadena en
     * blanco significa "no quiero filtrar por esto" y devuelve lista vacía. No
     * son lo mismo y confundirlos haría que desactivar un filtro lo resucitase.
     */
    public static List<String> parseList(String stored, List<String> fallback) {
        if (stored == null) return fallback;
        List<String> out = new ArrayList<>();
        for (String raw : stored.split("[\\r\\n,]")) {
            String v = raw.trim();
            if (!v.isEmpty() && !out.contains(v)) out.add(v);
        }
        return out;
    }

    /** Lo inverso: la lista a texto, un elemento por línea. Vacía -> "". */
    public static String joinList(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join("\n", values);
    }

    /** Las secciones van por coma porque son códigos cortos ("1,3"). */
    public static String joinSections(List<String> values) {
        if (values == null || values.isEmpty()) return "";
        return String.join(",", values);
    }

    /** Catálogo de secciones del BOE, para que la UI pueda ofrecerlas. */
    public record Section(String code, String name) {}

    public static List<Section> sectionCatalog() {
        return Arrays.asList(
                new Section("1", "I. Disposiciones generales"),
                new Section("2A", "II.A. Nombramientos, situaciones e incidencias"),
                new Section("2B", "II.B. Oposiciones y concursos"),
                new Section("3", "III. Otras disposiciones"),
                new Section("4", "IV. Administración de Justicia"),
                new Section("5A", "V.A. Anuncios - Contratación del Sector Público"),
                new Section("5B", "V.B. Anuncios - Otros anuncios oficiales"),
                new Section("5C", "V.C. Anuncios - Anuncios particulares"));
    }
}
