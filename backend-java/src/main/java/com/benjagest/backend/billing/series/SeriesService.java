package com.benjagest.backend.billing.series;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de gestion de series y emision de numero.
 *
 * Cuatro responsabilidades:
 *   - crear/actualizar/borrar series.
 *   - listar las series activas de la empresa.
 *   - emitir el siguiente numero (claimNextNumber), thread-safe via
 *     SELECT ... FOR UPDATE en el Repository.
 *   - formatear el numero segun el template (placeholders {YYYY} y
 *     {0000}, {00000}, etc.).
 */
@Service
public class SeriesService {

    private static final Pattern PADDING_PATTERN = Pattern.compile("\\{(0+)\\}");

    private final SeriesRepository repository;

    public SeriesService(SeriesRepository repository) {
        this.repository = repository;
    }

    public List<Series> list() {
        return repository.findAllActive();
    }

    public Series get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Serie no encontrada"));
    }

    @Transactional
    public Series create(SeriesUpsertRequest request) {
        String id = UUID.randomUUID().toString();
        int initial = request.initialNextNumber() == null ? 1 : request.initialNextNumber();
        Integer currentYear = "BY_YEAR".equals(request.numberingType()) ? LocalDate.now().getYear() : null;
        try {
            repository.insert(
                    id,
                    request.code().trim(),
                    request.invoiceKind(),
                    request.numberingType(),
                    request.formatTemplate(),
                    initial,
                    currentYear
            );
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una serie con ese codigo en esta empresa");
        }
        return get(id);
    }

    @Transactional
    public Series update(String id, SeriesUpsertRequest request) {
        // Defensa de continuidad legal: si esta serie ya tiene >=1 factura
        // VALIDATED en el ano actual, queda bloqueada hasta que cambie el
        // ano. Cambiar codigo/formato/tipo a media numeracion rompe la
        // cadena legal (saltos, duplicados, lios fiscales).
        Series existing = get(id);
        int currentYear = LocalDate.now().getYear();
        boolean lockedByEmission = repository.countValidatedInYear(id, currentYear) > 0;
        if (lockedByEmission) {
            if (!existing.code().equals(request.code().trim())
                    || !nullSafe(existing.invoiceKind()).equals(nullSafe(request.invoiceKind()))
                    || !nullSafe(existing.numberingType()).equals(nullSafe(request.numberingType()))
                    || !nullSafe(existing.formatTemplate()).equals(nullSafe(request.formatTemplate()))) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "Esta serie ya emitio facturas validadas en " + currentYear
                        + ". No puedes cambiar codigo, formato ni tipo hasta el cierre del ano. "
                        + "Si necesitas migrar desde otro programa, usa POST /migrate.");
            }
        }
        try {
            int affected = repository.update(
                    id,
                    request.code().trim(),
                    request.invoiceKind(),
                    request.numberingType(),
                    request.formatTemplate(),
                    request.locked() != null && request.locked()
            );
            if (affected == 0) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Serie no encontrada");
            }
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe otra serie con ese codigo en esta empresa");
        }
        return get(id);
    }

    /**
     * Indica si una serie esta bloqueada por la regla de continuidad
     * legal (>=1 factura VALIDATED en el ano actual). Lo usa la UI
     * para deshabilitar campos antes de probar a guardar.
     */
    public boolean isLockedByEmission(String seriesId) {
        return repository.countValidatedInYear(seriesId, LocalDate.now().getYear()) > 0;
    }

    /**
     * Importacion desde otro programa de facturacion: el OWNER/ADMIN
     * declara que su ultima factura emitida fue NUM y que se hace
     * responsable de la continuidad (no es una conversion automatica,
     * es una afirmacion legal del usuario).
     *
     * Permitido cuando:
     *   - No hay facturas VALIDATED de esta serie en BENJAGEST todavia
     *     (es el caso natural: el primer arranque), O
     *   - El cliente envia acknowledged=true asumiendo el corte.
     *
     * En ambos casos se requiere acknowledged=true: este endpoint nunca
     * cambia un correlativo en silencio.
     */
    @Transactional
    public Series migrate(String seriesId, int nextNumber, boolean acknowledged) {
        if (!acknowledged) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Para importar el correlativo desde otro programa debes aceptar la responsabilidad");
        }
        if (nextNumber < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El proximo numero debe ser >= 1");
        }
        Series series = get(seriesId);
        int year = LocalDate.now().getYear();
        repository.updateCounter(seriesId, nextNumber, year);
        return get(seriesId);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @Transactional
    public void delete(String id) {
        int affected = repository.softDelete(id);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Serie no encontrada");
        }
    }

    /**
     * Emite el siguiente numero de la serie y devuelve {numero, formato}.
     * Atomico: el SELECT FOR UPDATE bloquea la fila hasta que la
     * transaccion confirme, asi dos llamadas paralelas reciben numeros
     * distintos.
     *
     * Si la serie esta BY_YEAR y el ano natural cambio respecto a
     * current_year, resetea el correlativo a 1 antes de emitir.
     *
     * Si la serie esta locked=TRUE, devuelve 409 (es estado defensivo
     * para una serie que ya no debe usarse — p.ej. cuando se cambia a
     * VeriFactu y dejas la vieja como referencia).
     */
    @Transactional
    public ClaimedNumber claimNextNumber(String seriesId) {
        Series series = repository.findByIdForUpdate(seriesId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Serie no encontrada o inactiva"));
        if (series.locked()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La serie esta bloqueada");
        }

        int year = LocalDate.now().getYear();
        int numberToEmit = series.nextNumber();
        Integer newCurrentYear = series.currentYear();
        boolean yearReset = false;

        if ("BY_YEAR".equals(series.numberingType())) {
            if (series.currentYear() == null || series.currentYear() != year) {
                numberToEmit = 1;
                newCurrentYear = year;
                yearReset = true;
            }
        }

        int newNext = numberToEmit + 1;
        repository.updateCounter(series.id(), newNext, newCurrentYear);

        String formatted = formatNumber(series.formatTemplate(), numberToEmit, year);
        return new ClaimedNumber(series.id(), series.code(), numberToEmit, year, formatted, yearReset);
    }

    /**
     * Reemplaza {YYYY} y {0000} en el template por los valores reales.
     * Si el template es null o vacio, devuelve "<code>-<num>" como
     * fallback.
     */
    String formatNumber(String template, int number, int year) {
        if (template == null || template.isBlank()) {
            return String.valueOf(number);
        }
        String result = template.replace("{YYYY}", String.valueOf(year));
        Matcher matcher = PADDING_PATTERN.matcher(result);
        StringBuilder out = new StringBuilder();
        while (matcher.find()) {
            int width = matcher.group(1).length();
            matcher.appendReplacement(out, String.format("%0" + width + "d", number));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    public record ClaimedNumber(
            String seriesId,
            String seriesCode,
            int sequence,
            int year,
            String formatted,
            boolean yearReset
    ) {
    }
}
