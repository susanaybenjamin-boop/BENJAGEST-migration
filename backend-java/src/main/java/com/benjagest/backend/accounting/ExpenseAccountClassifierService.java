package com.benjagest.backend.accounting;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Clasifica un gasto en su cuenta PGC 6xx por palabras clave de la
 * descripción y/o nombre del proveedor.
 *
 * <p>Port de {@code detectarCuentaPorDescripcion} (línea 1571 de
 * {@code contabilidadService.js} de CONTENDO) y {@code mapCategoriaToCuenta}
 * (línea 1510). Reglas ordenadas de <i>más específica a menos específica</i>.
 *
 * <p>Caso 80/20: este classifier resuelve casos como "FACTURA LUZ ENDESA",
 * "ALQUILER LOCAL", "GOOGLE ADS", "MAPFRE SEGURO" sin necesidad de IA. Los
 * casos ambiguos los resuelve la IA (slice futuro) o el asesor manualmente
 * en el editor de asientos.
 *
 * <p>Diferencia con CONTENDO: aquí <b>solo devolvemos código</b> (3 dígitos);
 * el nombre estándar de la cuenta lo carga el resolver buscando en
 * {@code accounting_accounts} de la empresa. Si no encuentra esa cuenta en
 * la empresa, devuelve {@code Optional.empty()} para que el caller use el
 * fallback genérico 600 / 700.
 */
@Service
public class ExpenseAccountClassifierService {

    /** Una regla: regex que debe matchear el texto + opcional condición sobre nombre del proveedor. */
    private record Rule(Pattern pattern, String code, Pattern providerCondition) {
        Rule(String regex, String code) {
            this(Pattern.compile(regex), code, null);
        }
        Rule(String regex, String code, String providerRegex) {
            this(Pattern.compile(regex),
                    code,
                    providerRegex == null ? null : Pattern.compile(providerRegex));
        }
    }

    // El orden importa: reglas más específicas primero.
    private static final List<Rule> RULES = List.of(
        // -------- SEGURIDAD SOCIAL / AUTÓNOMOS (642) --------
        new Rule("AUTONOMO|CUOTA\\s*(DE\\s*)?AUTONOMO|\\bRETA\\b|RECIBO\\s*(DE\\s*)?(EL\\s*)?AUTONOMO|COTIZACION\\s*AUTONOMO|REGIMEN\\s*ESPECIAL\\s*TRABAJADOR", "642"),
        new Rule("SEGURIDAD\\s*SOCIAL|TESORERIA\\s*GENERAL|\\bTGSS\\b|SEG\\.?\\s*SOC|S\\.?\\s*SOCIAL", "642"),
        new Rule("COTIZACION\\s*(SOCIAL|EMPRESA|TRABAJADOR)|CUOTA\\s*OBRERA|CUOTA\\s*PATRONAL", "642"),

        // -------- MUTUAS / PREVENCIÓN (649) --------
        new Rule("MUTUA\\s*(DE\\s*)?(ACCIDENTE|TRABAJO|LABORAL)|PREVENCION\\s*(DE\\s*)?RIESGOS|SERVICIO\\s*PREVENCION|RECONOCIMIENTO\\s*MEDICO", "649"),

        // -------- SUELDOS (640) --------
        new Rule("NOMINA|SUELDO|SALARIO|PAGA\\s*EXTRA|FINIQUITO|LIQUIDACION\\s*(DE\\s*)?(HABERES|SUELDO)", "640"),

        // -------- INDEMNIZACIONES (641) --------
        new Rule("INDEMNIZACION|DESPIDO|\\bERE\\b|\\bERTE\\b", "641"),

        // ===========================
        // GRUPO 60 - COMPRAS
        // ===========================

        // Devoluciones (608) — antes que cualquier patrón "COMPRA"
        new Rule("DEVOLUCION\\s*(DE\\s*)?(COMPRA|MERCADERIA|MERCANCIA|MATERIAL|PRODUCTO)|ABONO\\s*(DE\\s*)?(PROVEEDOR|COMPRA|MERCADERIA)|NOTA\\s*(DE\\s*)?CREDITO\\s*(PROVEEDOR|COMPRA)|RECTIFICATIVA\\s*(PROVEEDOR|COMPRA)", "608"),

        // Descuentos pronto pago (606)
        new Rule("DESCUENTO\\s*(POR\\s*)?(PRONTO\\s*PAGO|PAGO\\s*(ANTICIPADO|INMEDIATO))\\s*(COMPRA|PROVEEDOR)?|PRONTO\\s*PAGO\\s*(COMPRA|PROVEEDOR)", "606"),

        // Rappels (609)
        new Rule("RAPPEL(S)?\\s*(POR\\s*)?(COMPRA|VOLUMEN|PROVEEDOR)|DESCUENTO\\s*(POR\\s*)?(VOLUMEN|CANTIDAD)\\s*(DE\\s*)?(COMPRA|PROVEEDOR)|BONIFICACION\\s*(POR\\s*)?(VOLUMEN|COMPRA)", "609"),

        // Subcontratación (607) — antes que profesionales
        new Rule("SUBCONTRAT|TRABAJO(S)?\\s*(REALIZADO|ENCARGADO|EXTERNO)|EXTERNALIZACION|OUTSOURCING", "607"),

        // Materias primas (601) — antes que mercaderías
        new Rule("MATERIA(S)?\\s*PRIMA|COMPRA(S)?\\s*(DE\\s*)?MATERIA|SUMINISTRO\\s*(DE\\s*)?(MATERIA|CHAPA|ACERO|MADERA|TELA|TEJIDO|HILO|CUERO|PLASTICO|RESINA|METAL|HIERRO|ALUMINIO|COBRE|VIDRIO|CEMENTO|ARIDO|ARENA|GRAVA|HORMIGON)", "601"),

        // Otros aprovisionamientos (602) — combustible, embalajes, EPIs, recambios
        new Rule("EMBALAJE|ENVASE(S)?|EMPAQUETADO|PACKAGING|COMBUSTIBLE(S)?|GASOLINA|GASOIL|DIESEL|REPOSTAJE|CARBURANTE|RECAMBIO(S)?|REPUESTO(S)?|HERRAMIENTA(S)?\\s*(DE\\s*)?(TALLER|TRABAJO|PRODUCCION)|CONSUMIBLE(S)?\\s*(DE\\s*)?(PRODUCCION|TALLER|FABRICA)|\\bEPI(S)?\\b|EQUIPO(S)?\\s*(DE\\s*)?PROTECCION\\s*(INDIVIDUAL)?", "602"),

        // Mercaderías (600)
        new Rule("MERCADERIA|MERCANCIA|GENERO(S)?\\s*(PARA\\s*)?(VENTA|REVENTA|TIENDA)|COMPRA(S)?\\s*(DE\\s*)?(MERCADERIA|MERCANCIA|PRODUCTO|ARTICULO|STOCK|GENERO)|REPOSICION\\s*(DE\\s*)?(STOCK|MERCANCIA|TIENDA|ALMACEN)", "600"),

        // ===========================
        // GRUPO 62 - SERVICIOS EXTERIORES
        // ===========================

        // I+D (620)
        new Rule("INVESTIGACION\\s*(Y\\s*)?DESARROLLO|\\bI\\+D\\b|\\bR\\+D\\b|\\bR&D\\b|DESARROLLO\\s*(TECNOLOGICO|EXPERIMENTAL|CIENTIFICO)|INNOVACION\\s*(TECNOLOGICA)?", "620"),

        // Alquileres (621)
        new Rule("ALQUILER|ARRENDAMIENTO|RENTA\\s*(MENSUAL|LOCAL|OFICINA|NAVE)|LEASING", "621"),

        // Suministros (628)
        new Rule("\\b(LUZ|ELECTRIC|ENDESA|IBERDROLA|NATURGY|REPSOL\\s*LUZ|EDP|HOLALUZ|FACTOR\\s*ENERGIA)\\b", "628"),
        new Rule("\\b(GAS\\s*NATURAL|BUTANO|PROPANO|CALEFACCION)\\b", "628"),
        new Rule("\\b(AGUA|CANAL\\s*DE|AGUAS\\s*DE|EMASA|EMASESA|AQUALIA)\\b", "628"),
        new Rule("\\b(TELEFON|MOVISTAR|VODAFONE|ORANGE|MASMOVIL|YOIGO|DIGI\\b|O2\\b|FIBRA|INTERNET|ADSL)\\b", "628"),

        // Seguros (625)
        new Rule("SEGURO|POLIZA|PRIMA\\s*(DE\\s*)?SEGURO|ASEGURADORA|MAPFRE|ALLIANZ|\\bAXA\\b|ZURICH|GENERALI|MUTUA\\s*MADRILENA|LINEA\\s*DIRECTA", "625"),

        // Profesionales: sociedad (629) vs autónomo (623)
        // — si proveedor lleva S.L./S.A./S.C./S.COOP → 629
        new Rule("GESTORIA|ASESORIA|ASESOR\\s*(FISCAL|CONTABLE|LABORAL)|CONSULTORIA|ABOGADO|LETRADO|NOTARI|REGISTRO\\s*(MERCANTIL|PROPIEDAD)|PROCURADOR|AUDITORIA|AUDITOR",
                "629", "\\b(S\\.?L\\.?U?\\.?|S\\.?A\\.?|S\\.?C\\.?|S\\.?COOP\\.?)\\b"),
        // — fallback profesionales (autónomo) → 623
        new Rule("GESTORIA|ASESORIA|ASESOR\\s*(FISCAL|CONTABLE|LABORAL)|CONSULTORIA|ABOGADO|LETRADO|NOTARI|REGISTRO\\s*(MERCANTIL|PROPIEDAD)|PROCURADOR|AUDITORIA|AUDITOR", "623"),

        // Publicidad (627)
        new Rule("PUBLICIDAD|MARKETING|GOOGLE\\s*ADS|META\\s*ADS|FACEBOOK\\s*ADS|INSTAGRAM|CAMPANA\\s*(PUBLICITARIA|MARKETING)|\\bSEO\\b|\\bSEM\\b|PROPAGANDA", "627"),

        // Servicios bancarios (626)
        new Rule("COMISION\\s*(BANCARIA|BANCO|MANTENIMIENTO|TARJETA)|GASTOS\\s*BANCARIOS|INTERESES\\s*BANCARIOS|SWIFT|TRANSFERENCIA\\s*COMISION", "626"),

        // ===========================
        // GRUPO 66 - FINANCIEROS
        // ===========================
        new Rule("DIFERENCIA(S)?\\s*(NEGATIVA)?\\s*(DE\\s*)?CAMBIO|PERDIDA(S)?\\s*(POR\\s*)?(TIPO\\s*(DE\\s*)?CAMBIO|CAMBIO\\s*DIVISA)", "668"),
        new Rule("GASTO(S)?\\s*FINANCIERO|COSTE\\s*FINANCIERO|DESCUENTO\\s*(DE\\s*)?(EFECTO|PAGARE|LETRA|CONFIRMING)|FACTORING\\s*(COSTE|GASTO|COMISION)|COMISION\\s*(DE\\s*)?(AVAL|GARANTIA\\s*BANCARIA)", "669"),
        new Rule("INTERESES?\\s*(PRESTAMO|HIPOTECA|CREDITO|DEUDA|FINANC)", "662"),

        // Reparaciones (622)
        new Rule("REPARACION|MANTENIMIENTO|AVERIA|REVISION\\s*(TECNICA|VEHICULO|MAQUINARIA)", "622"),

        // Transporte (624)
        new Rule("TRANSPORTE|MENSAJERIA|ENVIO|CORREOS|\\bSEUR\\b|\\bMRW\\b|\\bDHL\\b|\\bUPS\\b|\\bFEDEX\\b|PAQUETERIA|PORTE", "624"),

        // Tributos (631)
        new Rule("\\bIBI\\b|IMPUESTO\\s*(BIENES|VEHICULO|CIRCULACION|ACTIVIDADES|MUNICIPAL)|\\bIAE\\b|BASURA|TASA\\s*(MUNICIPAL|BASURA|RESIDUOS)|PLUSVALIA", "631"),

        // ===========================
        // GRUPO 65 - OTROS DE GESTIÓN
        // ===========================
        new Rule("MULTA|SANCION\\s*(ADMINISTRATIVA|TRIBUTARIA|TRAFICO|HACIENDA|MUNICIPAL)?|PENALIZACION|RECARGO\\s*(POR\\s*)?(APREMIO|MORA|EXTEMPORANEO)|DONACION|DONATIVO", "659"),
        new Rule("INCOBRABLE|INSOLVENCIA|CREDITO(S)?\\s*(COMERCIAL)?\\s*(INCOBRABLE|FALLIDO|IMPAGADO)|FALLIDO(S)?", "650"),
        new Rule("DETERIORO\\s*(DE\\s*)?(CREDITO|VALOR)\\s*(COMERCIAL|CLIENTE)|PROVISION\\s*(POR\\s*)?(INSOLVENCIA|MOROSIDAD|IMPAGO|CREDITO)|DOTACION\\s*(PROVISION\\s*)?(INSOLVENCIA|MOROSIDAD)", "694"),

        // ===========================
        // GRUPO 67 - EXCEPCIONALES
        // ===========================
        new Rule("PERDIDA(S)?\\s*(POR\\s*)?(VENTA|BAJA|ENAJENACION)\\s*(DE(L)?\\s*)?(INMOVILIZADO|MAQUINARIA|VEHICULO|MOBILIARIO)|SINIESTRO\\s*(DE\\s*)?(MAQUINARIA|VEHICULO|EQUIPO)", "671"),
        new Rule("GASTO(S)?\\s*EXCEPCIONAL|PERDIDA(S)?\\s*EXCEPCIONAL|CATASTROFE|INUNDACION|INCENDIO\\s*(PERDIDA|DANO|SINIESTRO)", "678"),

        // ===========================
        // GRUPO 68 - AMORTIZACIONES (de específica a genérica)
        // ===========================
        new Rule("AMORTIZACION\\s*(DE(L)?\\s*)?(INMOVILIZADO\\s*INTANGIBLE|SOFTWARE|PROGRAMA|LICENCIA|PATENTE|MARCA|PROPIEDAD\\s*INDUSTRIAL|PROPIEDAD\\s*INTELECTUAL|FONDO\\s*(DE\\s*)?COMERCIO|APLICACION\\s*INFORMATICA)", "680"),
        new Rule("AMORTIZACION\\s*(DE\\s*)?(LAS?\\s*)?(INVERSION|INMUEBLE|LOCAL|NAVE|EDIFICIO)\\s*(INMOBILIARIA|ALQUILADO|EN\\s*ALQUILER|ARRENDADO|DE\\s*INVERSION)?|AMORTIZACION\\s*INMOBILIARIA", "682"),
        new Rule("AMORTIZACION|DEPRECIACION", "681")
    );

    /**
     * Devuelve el código PGC sugerido o vacío si ninguna regla acierta.
     *
     * @param description línea descriptiva del gasto (concepto, observaciones…)
     * @param supplierName nombre del proveedor (puede ser null)
     */
    public Optional<String> classify(String description, String supplierName) {
        if ((description == null || description.isBlank())
                && (supplierName == null || supplierName.isBlank())) {
            return Optional.empty();
        }
        String text = normalize((description == null ? "" : description)
                + " " + (supplierName == null ? "" : supplierName));
        String providerUpper = supplierName == null ? "" : normalize(supplierName);

        for (Rule r : RULES) {
            if (!r.pattern.matcher(text).find()) continue;
            if (r.providerCondition != null) {
                if (providerUpper.isEmpty() || !r.providerCondition.matcher(providerUpper).find()) {
                    continue;
                }
            }
            return Optional.of(r.code);
        }
        return Optional.empty();
    }

    /**
     * Fallback por categoría libre (port de mapCategoriaToCuenta). Solo se
     * usa cuando el cliente externo (importador, OCR) ya nos dio una
     * categoría textual y no hay descripción libre suficiente.
     */
    public Optional<String> classifyByCategory(String category) {
        if (category == null || category.isBlank()) return Optional.empty();
        String cat = category.toLowerCase(Locale.ROOT).trim();
        return switch (cat) {
            case "compras", "mercaderias", "mercancia", "productos", "stock",
                 "inventario", "materiales", "material" -> Optional.of("600");
            case "materias_primas" -> Optional.of("601");
            case "combustible", "gasolina", "herramientas" -> Optional.of("602");
            case "subcontratacion" -> Optional.of("607");
            case "alquiler" -> Optional.of("621");
            case "reparaciones" -> Optional.of("622");
            case "profesionales" -> Optional.of("623");
            case "transporte" -> Optional.of("624");
            case "seguros" -> Optional.of("625");
            case "bancarios" -> Optional.of("626");
            case "publicidad" -> Optional.of("627");
            case "suministros" -> Optional.of("628");
            case "material_oficina", "otros_gastos", "general" -> Optional.of("629");
            case "tributos" -> Optional.of("631");
            case "multas" -> Optional.of("659");
            case "sueldos" -> Optional.of("640");
            case "ss_empresa" -> Optional.of("642");
            case "amortizacion" -> Optional.of("681");
            case "intereses" -> Optional.of("662");
            default -> Optional.empty();
        };
    }

    private static String normalize(String s) {
        String t = s.toUpperCase(Locale.ROOT);
        t = java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
        return t;
    }
}
