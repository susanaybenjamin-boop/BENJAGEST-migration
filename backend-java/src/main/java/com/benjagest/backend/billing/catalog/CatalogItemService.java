package com.benjagest.backend.billing.catalog;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * CONC-1 — Conceptos de factura reutilizables.
 *
 * <p>El selector del editor mezcla DOS fuentes (ver {@link InvoiceConcept}):
 * los guardados en {@code catalog_items} y los que ya se usaron en facturas
 * anteriores. El histórico se agrega aquí en Java, en un solo recorrido de
 * las líneas ordenadas de reciente a antigua: la PRIMERA vez que aparece un
 * concepto trae los valores buenos (los del último uso) y las siguientes
 * solo suman al contador.
 */
@Service
public class CatalogItemService {

    /** Tipos admitidos por {@code ck_catalog_items_type} (V2). */
    private static final List<String> ITEM_TYPES =
            List.of("SERVICE", "PRODUCT", "WORK", "FEE", "OTHER");

    /** {@code catalog_items.name} es VARCHAR(180). */
    private static final int NAME_MAX = 180;

    private final CatalogItemRepository repository;

    public CatalogItemService(CatalogItemRepository repository) {
        this.repository = repository;
    }

    /** Catálogo guardado + histórico de facturas, en una sola lista. */
    public List<InvoiceConcept> listConcepts() {
        Map<String, Usage> history = aggregateHistory();
        List<InvoiceConcept> concepts = new ArrayList<>();
        Set<String> alreadyListed = new HashSet<>();

        for (CatalogItem item : repository.findActive()) {
            String text = item.invoiceText();
            String key = normalize(text);
            alreadyListed.add(key);
            // Un concepto guardado cuyo NOMBRE (no el texto largo) coincide
            // con el histórico tampoco debe salir dos veces.
            alreadyListed.add(normalize(item.name()));
            Usage usage = history.get(key);
            concepts.add(new InvoiceConcept(
                    item.id(),
                    InvoiceConcept.SOURCE_CATALOG,
                    item.name(),
                    text,
                    zeroIfNull(item.unitPrice()),
                    zeroIfNull(item.defaultVatPercent()),
                    zeroIfNull(item.defaultRetentionPercent()),
                    item.defaultVatRateId(),
                    usage == null ? 0 : usage.count,
                    usage == null ? null : usage.lastUsed));
        }

        for (Map.Entry<String, Usage> entry : history.entrySet()) {
            if (alreadyListed.contains(entry.getKey())) {
                continue;
            }
            Usage usage = entry.getValue();
            concepts.add(new InvoiceConcept(
                    null,
                    InvoiceConcept.SOURCE_HISTORY,
                    shortName(usage.text),
                    usage.text,
                    zeroIfNull(usage.unitPrice),
                    zeroIfNull(usage.vatPercent),
                    zeroIfNull(usage.retentionPercent),
                    usage.vatRateId,
                    usage.count,
                    usage.lastUsed));
        }
        return concepts;
    }

    /**
     * Guarda un concepto. IDEMPOTENTE por nombre: si ya existe uno activo
     * con el mismo nombre, se actualiza en vez de duplicarlo (pulsar dos
     * veces "Guardar concepto" no debe ensuciar el catálogo).
     */
    @Transactional
    public CatalogItem create(UpsertRequest req) {
        Normalized n = validate(req);
        Optional<CatalogItem> existing = repository.findActiveByName(n.name);
        if (existing.isPresent()) {
            return applyUpdate(existing.get().id(), n);
        }
        String id = UUID.randomUUID().toString();
        repository.insert(id, n.name, n.description, n.category, n.itemType,
                n.unitPrice, n.vatPercent, n.retentionPercent, n.vatRateId);
        return repository.findById(id).orElseThrow();
    }

    @Transactional
    public CatalogItem update(String id, UpsertRequest req) {
        repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Concepto no encontrado"));
        return applyUpdate(id, validate(req));
    }

    /** Baja lógica: el concepto desaparece del selector, el histórico no se toca. */
    @Transactional
    public void delete(String id) {
        repository.findById(id).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "Concepto no encontrado"));
        repository.deactivate(id);
    }

    private CatalogItem applyUpdate(String id, Normalized n) {
        repository.update(id, n.name, n.description, n.category, n.itemType,
                n.unitPrice, n.vatPercent, n.retentionPercent, n.vatRateId);
        return repository.findById(id).orElseThrow();
    }

    private Map<String, Usage> aggregateHistory() {
        Map<String, Usage> byConcept = new LinkedHashMap<>();
        for (CatalogItemRepository.HistoryLine line : repository.findRecentLines()) {
            String text = line.description() == null ? "" : line.description().trim();
            if (text.isEmpty()) {
                continue;
            }
            String key = normalize(text);
            Usage usage = byConcept.get(key);
            if (usage == null) {
                // Primera aparición = uso más reciente (la consulta viene
                // ordenada de reciente a antigua): sus valores son los buenos.
                usage = new Usage(text, line.unitPrice(), line.vatPercent(),
                        line.retentionPercent(), line.vatRateId(), line.invoiceDate());
                byConcept.put(key, usage);
            }
            usage.count++;
            if (usage.lastUsed == null
                    || (line.invoiceDate() != null && line.invoiceDate().isAfter(usage.lastUsed))) {
                usage.lastUsed = line.invoiceDate();
            }
        }
        return byConcept;
    }

    private Normalized validate(UpsertRequest req) {
        if (req == null || !StringUtils.hasText(req.name())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El concepto necesita un nombre");
        }
        String itemType = StringUtils.hasText(req.itemType())
                ? req.itemType().trim().toUpperCase(Locale.ROOT) : "SERVICE";
        if (!ITEM_TYPES.contains(itemType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "itemType debe ser uno de " + ITEM_TYPES);
        }
        return new Normalized(
                shortName(req.name()),
                blankToNull(req.description()),
                blankToNull(req.category()),
                itemType,
                requirePositive(req.unitPrice(), "unitPrice"),
                requirePercent(req.vatPercent(), "vatPercent"),
                requirePercent(req.retentionPercent(), "retentionPercent"),
                blankToNull(req.vatRateId()));
    }

    private BigDecimal requirePositive(BigDecimal value, String field) {
        BigDecimal v = zeroIfNull(value);
        if (v.signum() < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " no puede ser negativo");
        }
        return v;
    }

    private BigDecimal requirePercent(BigDecimal value, String field) {
        BigDecimal v = requirePositive(value, field);
        if (v.compareTo(BigDecimal.valueOf(100)) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    field + " debe estar entre 0 y 100");
        }
        return v;
    }

    /**
     * Etiqueta corta del concepto: la primera línea, recortada al ancho de
     * la columna {@code name}. Una línea de factura puede ser un párrafo
     * entero; el selector necesita algo que quepa.
     */
    private static String shortName(String text) {
        if (text == null) {
            return "";
        }
        String firstLine = text.trim();
        int br = firstLine.indexOf('\n');
        if (br >= 0) {
            firstLine = firstLine.substring(0, br).trim();
        }
        return firstLine.length() <= NAME_MAX ? firstLine : firstLine.substring(0, NAME_MAX).trim();
    }

    /** Clave de comparación: sin mayúsculas ni espacios/saltos sobrantes. */
    private static String normalize(String text) {
        if (text == null) {
            return "";
        }
        return text.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static BigDecimal zeroIfNull(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /** Acumulador mutable del agregado por concepto (uso interno). */
    private static final class Usage {
        private final String text;
        private final BigDecimal unitPrice;
        private final BigDecimal vatPercent;
        private final BigDecimal retentionPercent;
        private final String vatRateId;
        private LocalDate lastUsed;
        private int count;

        private Usage(String text, BigDecimal unitPrice, BigDecimal vatPercent,
                      BigDecimal retentionPercent, String vatRateId, LocalDate lastUsed) {
            this.text = text;
            this.unitPrice = unitPrice;
            this.vatPercent = vatPercent;
            this.retentionPercent = retentionPercent;
            this.vatRateId = vatRateId;
            this.lastUsed = lastUsed;
        }
    }

    private record Normalized(
            String name,
            String description,
            String category,
            String itemType,
            BigDecimal unitPrice,
            BigDecimal vatPercent,
            BigDecimal retentionPercent,
            String vatRateId
    ) {}

    public record UpsertRequest(
            String name,
            String description,
            String category,
            String itemType,
            BigDecimal unitPrice,
            BigDecimal vatPercent,
            BigDecimal retentionPercent,
            String vatRateId
    ) {}
}
