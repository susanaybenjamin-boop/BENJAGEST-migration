package com.benjagest.backend.boe;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.benjagest.backend.boe.BoeMatcher.BoeItem;
import com.benjagest.backend.boe.BoeMatcher.Match;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * BOE-FILTRO — Benjamin: <i>"cuando llegan del BOE notificaciones, el filtro es
 * light, y se llena de noticias que no interesan, tendríamos que poder escoger
 * qué queremos que nos llegue"</i>.
 *
 * <p>Se prueba contra el <b>sumario REAL y sin tocar</b> del BOE del 19 de
 * noviembre de 2026 (104 artículos), descargado de la API de datos abiertos y
 * guardado como fixture. Con títulos inventados estos fallos no se ven: los dos
 * que se arreglan aquí salieron de mirar ese día concreto.
 */
class BoeFiltroTest {

    /** El Real Decreto que fija el SALARIO MÍNIMO para 2027: el que NO se puede perder. */
    private static final String SMI = "BOE-A-2026-24189";
    /** Primer artículo del día: una subvención a una fundación. Ruido puro. */
    private static final String SUBVENCION_FUNDACION = "BOE-A-2026-24187";

    private static List<BoeItem> items;

    @BeforeAll
    static void cargar() throws Exception {
        String json;
        try (InputStream in = BoeFiltroTest.class
                .getResourceAsStream("/boe/sumario-20261119.json")) {
            json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // Solo se ejercita el parser: jdbc / notificaciones / prefs no se tocan
        // en parseItems, así que no hace falta levantarlos.
        BoeAlertService svc = new BoeAlertService(null, null, null, new ObjectMapper());
        items = svc.parseItems(json);
    }

    // ====================================================================
    //  El parser
    // ====================================================================

    /**
     * HALLAZGO al escribir este test, que ningun analisis a ojo habria dado:
     * el sumario tiene <b>243</b> articulos, no 104. Los 104 cuelgan de
     * {@code departamento -> epigrafe -> item}; los otros <b>139 cuelgan
     * DIRECTAMENTE del departamento</b>, sin epigrafe intermedio, y son todos
     * {@code BOE-B-*} (seccion V, anuncios y contratacion).
     *
     * <p>El parser viejo <b>no los veia nunca</b>: su regex de identificador
     * era {@code "BOE-A-[^"]+"}, asi que los BOE-B quedaban fuera por accidente,
     * no por decision. Ahora se leen todos y es el filtro de SECCION el que
     * decide — por eso las secciones de fabrica son I y III, que es justo lo
     * que a una asesoria le importa.
     */
    @Test
    void seLeenTodosLosArticulosDelDiaIncluidosLosQueNoTienenEpigrafe() {
        assertEquals(243, items.size(),
                "104 bajo epigrafe + 139 colgando directos del departamento");
        assertEquals(104, items.stream().filter(i -> i.id().startsWith("BOE-A-")).count(),
                "disposiciones y resoluciones");
        assertEquals(139, items.stream().filter(i -> i.id().startsWith("BOE-B-")).count(),
                "anuncios: el parser viejo ni los miraba");
    }

    /** Ningun articulo se cuela dos veces aunque se recorran los dos caminos. */
    @Test
    void ningunArticuloSeDuplica() {
        long distintos = items.stream().map(BoeItem::id).distinct().count();
        assertEquals(items.size(), distintos, "hay identificadores repetidos");
    }

    /**
     * REGRESIÓN del bug gordo: el parser viejo recorría el JSON con cuatro
     * {@code Matcher.find()} independientes, así que el PDF de un artículo
     * podía ser el de otro. Se veía ya en el primero: el propio sumario del día
     * trae su {@code url_pdf} antes que ningún artículo, de modo que el primer
     * aviso se llevaba el PDF del boletín ENTERO en vez del suyo.
     */
    @Test
    void cadaArticuloSeQuedaConSuPropioPdf() {
        for (BoeItem it : items) {
            if (it.url() == null || it.url().isBlank()) continue;
            assertTrue(it.url().endsWith("/" + it.id() + ".pdf"),
                    "el PDF de " + it.id() + " deberia ser el suyo y es " + it.url());
        }
    }

    @Test
    void seLeenSeccionYDepartamentoDeCadaArticulo() {
        BoeItem smi = buscar(SMI);
        assertEquals("1", smi.sectionCode(), "el SMI va en I. Disposiciones generales");
        assertTrue(smi.department().toUpperCase(Locale.ROOT).contains("TRABAJO"),
                "lo publica Trabajo, y fue " + smi.department());
        assertTrue(smi.title().toLowerCase(Locale.ROOT).contains("salario"));
    }

    // ====================================================================
    //  El criterio de palabra
    // ====================================================================

    /**
     * El fallo que más ruido metía: {@code contains()} sin límites de palabra.
     * "IVA" enganchaba dentro de Privada, selectivas, educativa, negativa y
     * preventiva — 6 de los 13 avisos de aquel día, ninguno sobre IVA.
     */
    @Test
    void ivaYaNoEnganchaDentroDeOtrasPalabras() {
        List<String> soloIva = List.of("IVA");
        for (String palabra : List.of(
                "Fundación Privada Centro de Estudios",
                "convocan pruebas selectivas de ingreso",
                "cooperación educativa",
                "contra la negativa de la registradora",
                "anotación preventiva de embargo")) {
            assertTrue(BoeMatcher.matchedKeywords(palabra, soloIva).isEmpty(),
                    "no deberia disparar IVA: " + palabra);
        }
        assertFalse(BoeMatcher.matchedKeywords(
                        "Orden por la que se modifica el tipo de IVA aplicable", soloIva).isEmpty(),
                "pero un IVA de verdad SI debe entrar");
    }

    @Test
    void lasTildesNoImportanEnNingunSentido() {
        assertFalse(BoeMatcher.matchedKeywords("Modelo de NOMINA mensual", List.of("nómina")).isEmpty(),
                "la palabra con tilde debe encontrar el titulo sin ella");
        assertFalse(BoeMatcher.matchedKeywords("Cambios en la nómina", List.of("nomina")).isEmpty(),
                "y al reves");
    }

    @Test
    void laPalabraCompletaRespetaLosPlurales() {
        // "retención" no debe disparar con "retenciones" y por eso van las dos
        // en la lista de fabrica: es palabra COMPLETA, no prefijo.
        assertTrue(BoeMatcher.matchedKeywords("Tabla de retenciones", List.of("retención")).isEmpty());
        assertFalse(BoeMatcher.matchedKeywords("Tabla de retenciones", List.of("retenciones")).isEmpty());
        assertTrue(BoeAlertPrefs.DEFAULT_KEYWORDS.contains("retención"));
        assertTrue(BoeAlertPrefs.DEFAULT_KEYWORDS.contains("retenciones"));
    }

    @Test
    void fueraLosComodinesQueMetianElRuido() {
        for (String comodin : List.of("Real Decreto", "RD ", "BOE-A-2026", "BOE-A-2027")) {
            assertFalse(BoeAlertPrefs.DEFAULT_KEYWORDS.contains(comodin),
                    comodin + " era un comodin: el BOE publica eso a diario sobre cualquier cosa");
        }
    }

    // ====================================================================
    //  El efecto conjunto, sobre el dia real
    // ====================================================================

    /**
     * La cifra que resume el bloque. Con el filtro viejo ese día llegaban 13
     * avisos y solo uno servía. Con el de fábrica llegan 2, y el bueno sigue
     * dentro.
     */
    @Test
    void elDiaRealPasaDeTreceAvisosADos() {
        List<Match> m = BoeMatcher.filter(items, BoeAlertPrefs.defaults());
        assertEquals(2, m.size(),
                "con el filtro de fabrica el 19-nov-2026 deja 2 avisos, y fueron: "
                        + m.stream().map(x -> x.item().title()).toList());
        assertTrue(m.stream().anyMatch(x -> x.item().id().equals(SMI)),
                "el RD del salario minimo para 2027 NO se puede perder");
        assertTrue(m.stream().noneMatch(x -> x.item().id().equals(SUBVENCION_FUNDACION)),
                "la subvencion a la fundacion era ruido y ya no entra");
    }

    @Test
    void elFiltroDeSeccionDejaFueraNombramientosYOposiciones() {
        // Sin filtro de seccion entran mas (nombramientos con "Seguridad Social"
        // en el titulo); con el de fabrica, no.
        BoeAlertPrefs sinSeccion = new BoeAlertPrefs(
                true, List.of(), List.of(), BoeAlertPrefs.DEFAULT_KEYWORDS);
        int conTodas = BoeMatcher.filter(items, sinSeccion).size();
        int soloNormativa = BoeMatcher.filter(items, BoeAlertPrefs.defaults()).size();
        assertTrue(conTodas > soloNormativa,
                "la seccion tiene que recortar: " + conTodas + " vs " + soloNormativa);
        for (Match x : BoeMatcher.filter(items, BoeAlertPrefs.defaults())) {
            assertTrue(List.of("1", "3").contains(x.item().sectionCode()));
        }
    }

    @Test
    void elFiltroDeDepartamentoSeQuedaConElMinisterioPedido() {
        BoeAlertPrefs soloTrabajo = new BoeAlertPrefs(
                true, List.of("1", "3"), List.of("TRABAJO"), BoeAlertPrefs.DEFAULT_KEYWORDS);
        List<Match> m = BoeMatcher.filter(items, soloTrabajo);
        assertTrue(m.stream().anyMatch(x -> x.item().id().equals(SMI)),
                "el SMI lo publica Trabajo, asi que sigue");
        for (Match x : m) {
            assertTrue(BoeMatcher.normalize(x.item().department()).contains("trabajo"));
        }
    }

    @Test
    void sePuedeApagarDelTodo() {
        BoeAlertPrefs apagado = new BoeAlertPrefs(
                false, List.of(), List.of(), BoeAlertPrefs.DEFAULT_KEYWORDS);
        assertTrue(BoeMatcher.filter(items, apagado).isEmpty(),
                "con enabled=false no llega nada");
    }

    @Test
    void listaDePalabrasVaciaSignificaNoFiltrarPorPalabra() {
        // Vacio = "no quiero filtrar por esto"; distinto de no haberlo configurado.
        BoeAlertPrefs soloSeccion = new BoeAlertPrefs(
                true, List.of("1"), List.of(), List.of());
        List<Match> m = BoeMatcher.filter(items, soloSeccion);
        assertTrue(m.size() > 2, "sin filtro de palabra entra toda la seccion I");
        for (Match x : m) {
            assertEquals("1", x.item().sectionCode());
        }
    }

    // ====================================================================
    //  Serializacion de las preferencias
    // ====================================================================

    @Test
    void nullEsNoConfiguradoYVacioEsSinFiltro() {
        assertEquals(BoeAlertPrefs.DEFAULT_KEYWORDS,
                BoeAlertPrefs.parseList(null, BoeAlertPrefs.DEFAULT_KEYWORDS),
                "null = nunca lo configure -> valores de fabrica");
        assertTrue(BoeAlertPrefs.parseList("", BoeAlertPrefs.DEFAULT_KEYWORDS).isEmpty(),
                "vacio = no quiero filtrar por esto");
        assertTrue(BoeAlertPrefs.parseList("   \n  ", BoeAlertPrefs.DEFAULT_KEYWORDS).isEmpty());
    }

    @Test
    void lasListasVanYVuelvenIgual() {
        List<String> kws = List.of("IVA", "Seguridad Social", "convenio colectivo");
        assertEquals(kws, BoeAlertPrefs.parseList(BoeAlertPrefs.joinList(kws), List.of()));
        List<String> secs = List.of("1", "3");
        assertEquals(secs, BoeAlertPrefs.parseList(BoeAlertPrefs.joinSections(secs), List.of()));
    }

    // ====================================================================
    //  Dias sin boletin (BOE-DOMINGO, 2026-11-22)
    // ====================================================================

    /**
     * El BOE publica de <b>lunes a sabado</b>: los domingos su API responde
     * 404. Eso NO es un fallo.
     *
     * <p>Se vio en produccion el primer domingo con la supervision puesta: la
     * pantalla "Estado del sistema" marcaba <i>"Alertas BOE — Ha fallado —
     * Sin boletin"</i> en rojo. El motivo viajaba dentro del campo
     * {@code error} del resultado y el supervisor lo tomaba por un fallo, asi
     * que la app habria dado un aviso falso <b>todos los domingos</b> — justo
     * el ruido que la supervision existe para evitar.
     */
    @Test
    void unDiaSinBoletinNoEsUnFallo() {
        BoeAlertService.RunSummary r =
                BoeAlertService.RunSummary.noBulletin("2026-11-22");

        assertTrue(r.noBulletin(), "hay que poder distinguirlo");
        assertNull(r.error(), "y NO puede viajar como error: eso lo pintaba en rojo");
        assertEquals(0, r.totalArticles());
    }

    @Test
    void unFalloDeVerdadSiLlevaMotivo() {
        BoeAlertService.RunSummary r =
                BoeAlertService.RunSummary.failed("2026-11-23", 0, 0, "PKIX path building failed");

        assertFalse(r.noBulletin());
        assertNotNull(r.error());
    }

    /** Cero avisos no es un fallo: es el filtro haciendo su trabajo. */
    @Test
    void unDiaConBoletinYSinAvisosEsExito() {
        BoeAlertService.RunSummary r = BoeAlertService.RunSummary.ok("2026-11-21", 0, 0, 283);

        assertFalse(r.noBulletin());
        assertNull(r.error());
        assertEquals(283, r.totalArticles());
    }

    /**
     * El umbral de ausencia tiene que absorber un domingo. Con 2 dias, un
     * domingo mas un lunes con el equipo apagado ya disparaba un aviso falso.
     */
    @Test
    void elUmbralDelBoeAbsorbeUnDomingo() {
        assertTrue(com.benjagest.backend.supervision.SupervisedJob.BOE_ALERTS
                        .maxDaysWithoutSuccess() >= 3,
                "el BOE no publica los domingos: 2 dias se queda corto");
    }

    // ====================================================================
    //  Acentos de los avisos viejos (BOE-ACENTOS, 2026-11-22)
    // ====================================================================

    /** Una barra invertida, montada a mano: ver la nota de abajo. */
    private static final String BS = String.valueOf((char) 92);

    /**
     * Benjamin: <i>"sigue sin colocarse bien los acentos, comas o n"</i>.
     *
     * <p>No era el parser de ahora: el ANTERIOR desescapaba a mano y solo
     * conocia la comilla, el salto de linea y la barra, nunca los escapes
     * unicode. Los avisos guardados antes de la v0.1.76 llevan en la BD
     * literales como "Resoluci" + barra + "u00f3n". Se veia clarisimo en su
     * pantalla: las filas del 20-nov (parser nuevo) con sus tildes y las de
     * octubre sin ellas.
     *
     * <p>Las cadenas de prueba se montan concatenando {@link #BS} a proposito:
     * Java descodifica los escapes unicode del CODIGO FUENTE antes de
     * compilar, asi que escribirlos dentro de una cadena literal da un
     * resultado distinto del que uno cree estar escribiendo.
     */
    @Test
    void losAvisosViejosRecuperanSusTildes() {
        assertEquals("Resolución de 2 de octubre",
                BoeAlertService.decodeEscapes("Resoluci" + BS + "u00f3n de 2 de octubre"));
        assertEquals("España", BoeAlertService.decodeEscapes("Espa" + BS + "u00f1a"));
        assertEquals("Ejército", BoeAlertService.decodeEscapes("Ej" + BS + "u00e9rcito"));
    }

    @Test
    void unTituloYaCorrectoSeQuedaIgual() {
        String bueno = "Real Decreto 1013/2026, por el que se fija el salario mínimo";
        assertEquals(bueno, BoeAlertService.decodeEscapes(bueno));
        assertNull(BoeAlertService.decodeEscapes(null));
    }

    /** Lo que no es un escape de verdad no puede romper nada. */
    @Test
    void loQueNoEsUnEscapeDeVerdadSeDejaTalCual() {
        assertEquals(BS + "uZZZZ", BoeAlertService.decodeEscapes(BS + "uZZZZ"));
        assertEquals(BS + "u00", BoeAlertService.decodeEscapes(BS + "u00"));
        assertEquals("sin escapes", BoeAlertService.decodeEscapes("sin escapes"));
    }

    private static BoeItem buscar(String id) {
        return items.stream().filter(i -> i.id().equals(id)).findFirst()
                .orElseThrow(() -> new AssertionError("no esta en el fixture: " + id));
    }
}
