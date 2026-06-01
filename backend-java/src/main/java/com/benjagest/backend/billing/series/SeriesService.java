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
