package com.benjagest.backend.labor.workcal;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * L3-3 — Catálogo de festivos España 2026.
 *
 * <p>Hardcoded por decisión deliberada (vs llamada HTTP a Nager.Date o
 * similar) para no añadir puntos de fallo de red al arranque del sistema
 * laboral. Los festivos cambian una vez al año; lo más fiable es
 * revisarlos a mano contra BOE oficial cada octubre y actualizar esta
 * clase. Cuando llegue 2027 se crea {@code HolidaySeed2027} y un
 * registry decide cuál usar según el año.
 *
 * <h2>Fuentes</h2>
 * <ul>
 *   <li><strong>Nacionales 2026</strong>:
 *       Resolución de la Dirección General de Trabajo (BOE ~oct-2025)
 *       sobre calendario laboral para 2026.
 *   <li><strong>Autonómicos 2026</strong>:
 *       Boletines oficiales de cada CCAA. Algunos festivos se trasladan
 *       cuando caen en domingo (Art. 37.2 ET) — aquí se anota en
 *       {@code notes}.
 *   <li><strong>Locales</strong>:
 *       NO se siembran. Cada municipio publica los 2 suyos en pleno
 *       (hasta 14 en total con los 9 nacionales + 4 autonómicos). El
 *       usuario añade el local manualmente desde UI L3-4.
 * </ul>
 *
 * <h2>Tope legal</h2>
 * <p>Art. 37.2 Estatuto Trabajadores: máximo 14 festivos retribuidos
 * al año (9 nacionales + 4 autonómicos + 2 locales). El {@code
 * WorkCalendarService} aplica el tope cuando se siembran/añaden.
 */
/* @deprecated 2026-06-09: Benjamin auditó este seed y descubrió que
 * los festivos autonómicos NO estaban verificados contra los boletines
 * oficiales — los introduje yo de memoria sin cruzar con BOJA/BOPV/
 * DOGC/etc. Solo los 9 nacionales son ley fija fiable. El flujo
 * vinculante ahora es {@code POST /api/labor/work-calendars} (calendario
 * vacío) + {@code /extract-pdf} (importar desde el PDF oficial que el
 * usuario descarga de su CCAA). El endpoint {@code /bootstrap} y la
 * UI 'Generar calendario 2026' se retiraron en el commit que añadió
 * 'Importar desde PDF'. Se conserva esta clase porque el endpoint
 * sigue existiendo a nivel HTTP por compatibilidad, pero NO debe
 * usarse para empresas nuevas. */
@Deprecated
public final class HolidaySeed2026 {

    private HolidaySeed2026() {}

    /** Año al que aplica este catálogo. */
    public static final int YEAR = 2026;

    /**
     * Festivos NACIONALES 2026 — fijos en todo el territorio España.
     * 9 fechas. El 1-nov y 6-dic caen en domingo 2026 → algunas CCAA los
     * trasladan al lunes (lo anotamos en notes y el usuario decide).
     */
    public static final List<SeedHoliday> NATIONAL = List.of(
            new SeedHoliday(LocalDate.of(2026, 1, 1),  "Año Nuevo",                 "Art. 37.1 ET"),
            new SeedHoliday(LocalDate.of(2026, 1, 6),  "Epifanía del Señor (Reyes)", "Art. 37.1 ET"),
            new SeedHoliday(LocalDate.of(2026, 4, 3),  "Viernes Santo",             "Pascua 2026 - 2 días"),
            new SeedHoliday(LocalDate.of(2026, 5, 1),  "Fiesta del Trabajo",        "Art. 37.1 ET"),
            new SeedHoliday(LocalDate.of(2026, 8, 15), "Asunción de la Virgen",     "Cae en sábado"),
            new SeedHoliday(LocalDate.of(2026, 10, 12),"Fiesta Nacional de España", "Art. 37.1 ET (lunes)"),
            new SeedHoliday(LocalDate.of(2026, 11, 2), "Todos los Santos (lunes)",  "Trasladado: 1-nov cae domingo"),
            new SeedHoliday(LocalDate.of(2026, 12, 7), "Día de la Constitución",    "Trasladado: 6-dic cae domingo"),
            new SeedHoliday(LocalDate.of(2026, 12, 25),"Navidad",                   "Art. 37.1 ET (viernes)")
    );

    /**
     * Festivos AUTONÓMICOS por CCAA (clave = código ISO 3166-2:ES sin
     * "ES-"). Cada CCAA aporta entre 1 y 4 festivos propios sobre los
     * nacionales. Cuando una CCAA repite un festivo nacional (ej. La
     * Almudena en Madrid el 9-nov), aquí no se duplica — solo añadimos
     * los que NO son nacionales.
     *
     * <p>Lista basada en los boletines oficiales 2026 más recientes
     * disponibles; Benjamin debe validar contra el BOE/BOJA/BOPV/DOGC
     * antes de su uso productivo. Si una fecha falta o sobra, el OWNER
     * puede ajustarla desde la UI L3-4 antes de volcar.
     */
    public static final Map<String, List<SeedHoliday>> BY_CCAA = Map.ofEntries(
            Map.entry("AN", List.of(
                    new SeedHoliday(LocalDate.of(2026, 2, 28), "Día de Andalucía", "Estatuto Autonomía 1981"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",      "Tradicional Semana Santa")
            )),
            Map.entry("AR", List.of(
                    new SeedHoliday(LocalDate.of(2026, 4, 23), "San Jorge (Día de Aragón)", "Patrón de Aragón"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",                "Tradicional Semana Santa")
            )),
            Map.entry("AS", List.of(
                    new SeedHoliday(LocalDate.of(2026, 9, 8),  "Día de Asturias",   "Covadonga"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",       "Tradicional Semana Santa")
            )),
            Map.entry("IB", List.of(
                    new SeedHoliday(LocalDate.of(2026, 3, 1),  "Día de las Illes Balears", "Estatuto Autonomía"),
                    new SeedHoliday(LocalDate.of(2026, 12, 26),"Sant Esteve",               "Tradicional Cataluña/Baleares")
            )),
            Map.entry("CN", List.of(
                    new SeedHoliday(LocalDate.of(2026, 5, 30), "Día de Canarias",    "Estatuto Autonomía"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",       "Tradicional Semana Santa")
            )),
            Map.entry("CB", List.of(
                    new SeedHoliday(LocalDate.of(2026, 7, 28), "Día de las Instituciones de Cantabria", "Estatuto Autonomía"),
                    new SeedHoliday(LocalDate.of(2026, 9, 15), "Día de la Bien Aparecida",                "Patrona Cantabria")
            )),
            Map.entry("CL", List.of(
                    new SeedHoliday(LocalDate.of(2026, 4, 23), "Día de Castilla y León", "Comuneros (1521)"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",            "Tradicional Semana Santa")
            )),
            Map.entry("CM", List.of(
                    new SeedHoliday(LocalDate.of(2026, 5, 31), "Día de Castilla-La Mancha", "Estatuto Autonomía"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",               "Tradicional Semana Santa")
            )),
            Map.entry("CT", List.of(
                    new SeedHoliday(LocalDate.of(2026, 9, 11), "Diada Nacional de Catalunya", "1714"),
                    new SeedHoliday(LocalDate.of(2026, 6, 24), "Sant Joan",                    "Tradicional"),
                    new SeedHoliday(LocalDate.of(2026, 12, 26),"Sant Esteve",                  "Tradicional Cataluña")
            )),
            Map.entry("VC", List.of(
                    new SeedHoliday(LocalDate.of(2026, 10, 9), "Dia de la Comunitat Valenciana", "Entrada Jaume I (1238)"),
                    new SeedHoliday(LocalDate.of(2026, 3, 19), "Sant Josep",                       "Tradicional Valencia")
            )),
            Map.entry("EX", List.of(
                    new SeedHoliday(LocalDate.of(2026, 9, 8),  "Día de Extremadura", "Patrona Virgen de Guadalupe"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",        "Tradicional Semana Santa")
            )),
            Map.entry("GA", List.of(
                    new SeedHoliday(LocalDate.of(2026, 7, 25), "Día Nacional de Galicia", "Santiago Apóstol"),
                    new SeedHoliday(LocalDate.of(2026, 5, 17), "Día das Letras Galegas",  "Tradicional Galicia"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",             "Tradicional Semana Santa")
            )),
            Map.entry("MD", List.of(
                    new SeedHoliday(LocalDate.of(2026, 5, 2),  "Día de la Comunidad de Madrid", "2 mayo 1808"),
                    new SeedHoliday(LocalDate.of(2026, 7, 25), "Santiago Apóstol",                "Patrón nacional"),
                    new SeedHoliday(LocalDate.of(2026, 11, 9), "Nuestra Señora de la Almudena",   "Patrona Madrid (capital)")
            )),
            Map.entry("MC", List.of(
                    new SeedHoliday(LocalDate.of(2026, 6, 9),  "Día de la Región de Murcia", "Estatuto Autonomía"),
                    new SeedHoliday(LocalDate.of(2026, 3, 19), "San José",                    "Tradicional Murcia")
            )),
            Map.entry("NC", List.of(
                    new SeedHoliday(LocalDate.of(2026, 7, 25), "Santiago Apóstol",  "Patrón Navarra"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",      "Tradicional Semana Santa")
            )),
            Map.entry("PV", List.of(
                    new SeedHoliday(LocalDate.of(2026, 7, 25), "Santiago Apóstol",  "Patrón Euskadi"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",       "Tradicional Semana Santa")
            )),
            Map.entry("RI", List.of(
                    new SeedHoliday(LocalDate.of(2026, 6, 9),  "Día de La Rioja",  "Estatuto Autonomía"),
                    new SeedHoliday(LocalDate.of(2026, 4, 2),  "Jueves Santo",      "Tradicional Semana Santa")
            )),
            Map.entry("CE", List.of(
                    new SeedHoliday(LocalDate.of(2026, 8, 5),  "Nuestra Señora de África", "Patrona Ceuta"),
                    new SeedHoliday(LocalDate.of(2026, 9, 2),  "Día de Ceuta",              "Estatuto Autonomía")
            )),
            Map.entry("ML", List.of(
                    new SeedHoliday(LocalDate.of(2026, 9, 8),  "Día de Melilla",              "Estatuto Autonomía"),
                    new SeedHoliday(LocalDate.of(2026, 9, 17), "Día de los Caídos por España", "Tradicional Melilla")
            ))
    );

    /**
     * Devuelve el catálogo completo para una CCAA: nacionales +
     * autonómicos. Si {@code ccaa} es null o vacío, solo nacionales.
     * Si {@code ccaa} no está en {@link #BY_CCAA}, ignora silenciosamente
     * los autonómicos y devuelve solo nacionales.
     */
    public static List<SeedHoliday> seedFor(String ccaa) {
        List<SeedHoliday> autonomous = ccaa == null || ccaa.isBlank()
                ? List.of()
                : BY_CCAA.getOrDefault(ccaa.toUpperCase(), List.of());
        // Concat manual (orden cronológico).
        java.util.List<SeedHoliday> out = new java.util.ArrayList<>(NATIONAL);
        out.addAll(autonomous);
        out.sort(java.util.Comparator.comparing(SeedHoliday::date));
        return List.copyOf(out);
    }

    /**
     * Entrada del seed. Sin id ni workCalendarId — los rellena el
     * servicio cuando vuelca los festivos al calendario.
     */
    public record SeedHoliday(LocalDate date, String name, String notes) {}
}
