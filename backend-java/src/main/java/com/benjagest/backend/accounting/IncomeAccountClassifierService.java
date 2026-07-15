package com.benjagest.backend.accounting;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Espejo de {@link ExpenseAccountClassifierService} para ventas: devuelve
 * la cuenta 7xx sugerida según la descripción de la factura emitida y/o el
 * nombre del cliente.
 *
 * <p>CONTENDO no tiene un equivalente directo de esta lógica para ventas
 * (las ventas las clasifica todo a 700 por defecto), pero al portar BENJAGEST
 * añadimos un classifier mínimo para que el asesor reciba propuestas
 * razonables en facturas emitidas — y así no tener que cambiar manualmente
 * cada factura de "Ventas de mercaderías" a la cuenta correcta.
 *
 * <p>Reglas:
 * <ul>
 *   <li>700 — Ventas de mercaderías (catch-all explícito)</li>
 *   <li>701 — Ventas de productos terminados</li>
 *   <li>702 — Ventas de productos semiterminados</li>
 *   <li>704 — Ventas de envases y embalajes</li>
 *   <li>705 — Prestaciones de servicios</li>
 *   <li>706 — Descuentos sobre ventas por pronto pago</li>
 *   <li>708 — Devoluciones de ventas</li>
 *   <li>709 — Rappels sobre ventas</li>
 *   <li>752 — Ingresos por arrendamientos</li>
 *   <li>759 — Ingresos por servicios diversos</li>
 *   <li>755 — Ingresos por servicios al personal</li>
 * </ul>
 */
@Service
public class IncomeAccountClassifierService {

    private record Rule(Pattern pattern, String code) {
        Rule(String regex, String code) { this(Pattern.compile(regex), code); }
    }

    private static final List<Rule> RULES = List.of(
        // Devoluciones (708) — antes que cualquier "VENTA"
        new Rule("DEVOLUCION\\s*(DE\\s*)?(VENTA|MERCADERIA|CLIENTE)|ABONO\\s*(DE\\s*)?(CLIENTE|VENTA)|NOTA\\s*(DE\\s*)?CREDITO\\s*(CLIENTE|VENTA)|RECTIFICATIVA\\s*(CLIENTE|VENTA)", "708"),
        // Descuentos pronto pago (706)
        new Rule("DESCUENTO\\s*(POR\\s*)?(PRONTO\\s*PAGO|PAGO\\s*ANTICIPADO)\\s*(VENTA|CLIENTE)?", "706"),
        // Rappels (709)
        new Rule("RAPPEL(S)?\\s*(SOBRE\\s*)?VENTA(S)?|BONIFICACION\\s*(SOBRE\\s*)?VENTA", "709"),
        // Prestaciones de servicios (705) — explícito.
        // ASI-3 (2026-07-15): el "(S)?" tras SERVICIO faltaba, así que
        // "SERVICIOS DE REPARACION" (plural, la forma natural) NO matcheaba y
        // la factura acababa en la 700. Salió al pasarle el `concept` al
        // classifier, que antes ni le llegaba.
        new Rule("PRESTACION\\s*(DE\\s*)?SERVICIO|SERVICIO(S)?\\s*(DE\\s*)?(REPARACION|MANTENIMIENTO|INSTALACION|MONTAJE|ASESORIA|CONSULTOR|FORMACION|DISENO|DESARROLLO|PROGRAMACION|FOTOGRAF)|HONORARIO(S)?\\s*(POR|DE)?", "705"),
        // Arrendamiento de inmuebles propios (752)
        new Rule("ALQUILER\\s*(LOCAL|OFICINA|NAVE|VIVIENDA|INMUEBLE)|ARRENDAMIENTO\\s*(LOCAL|OFICINA|NAVE|VIVIENDA|INMUEBLE)|RENTA\\s*(MENSUAL|LOCAL|OFICINA|NAVE)", "752"),
        // Envases y embalajes (704)
        new Rule("\\b(ENVASE(S)?|EMBALAJE(S)?|EMPAQUETADO)\\b", "704"),
        // Productos terminados (701)
        new Rule("PRODUCTO(S)?\\s*TERMINADO|VENTA\\s*(DE\\s*)?PRODUCTO\\s*TERMINADO", "701"),
        // Productos semiterminados (702)
        new Rule("PRODUCTO(S)?\\s*SEMITERMINADO|VENTA\\s*(DE\\s*)?PRODUCTO\\s*SEMITERMINADO", "702"),
        // Servicios diversos (759)
        new Rule("SERVICIO(S)?\\s*DIVERSO|OTROS\\s*SERVICIO", "759"),
        // Mercaderías catch-all (700)
        new Rule("MERCADERIA|MERCANCIA|GENERO|ARTICULO|PRODUCTO|VENTA", "700")
    );

    public Optional<String> classify(String description, String customerName) {
        if ((description == null || description.isBlank())
                && (customerName == null || customerName.isBlank())) {
            return Optional.empty();
        }
        String text = normalize((description == null ? "" : description)
                + " " + (customerName == null ? "" : customerName));
        for (Rule r : RULES) {
            if (r.pattern.matcher(text).find()) return Optional.of(r.code);
        }
        return Optional.empty();
    }

    private static String normalize(String s) {
        String t = s.toUpperCase(Locale.ROOT);
        return java.text.Normalizer.normalize(t, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{InCombiningDiacriticalMarks}+", "");
    }
}
