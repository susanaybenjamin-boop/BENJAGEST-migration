package com.benjagest.backend.billing.series;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
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
    private final com.benjagest.backend.tenant.TenantContext tenant;
    private final com.benjagest.backend.auth.CurrentUserService currentUser;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public SeriesService(SeriesRepository repository,
                          com.benjagest.backend.tenant.TenantContext tenant,
                          com.benjagest.backend.auth.CurrentUserService currentUser,
                          org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.repository = repository;
        this.tenant = tenant;
        this.currentUser = currentUser;
        this.jdbc = jdbc;
    }

    public List<Series> list() {
        return repository.findAllActive();
    }

    public Series get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Serie no encontrada"));
    }

    /**
     * Devuelve la serie activa que toca para un invoiceKind concreto en
     * la empresa actual. Lo usa SalesInvoiceService al crear/validar
     * facturas: el cliente solo manda el tipo (NORMAL/PROFORMA/...) y
     * el server elige la serie correcta. Asi la UI no tiene que ofrecer
     * combo de series — cumplimiento legal RD 1619/2012 Art.13 (las
     * rectificativas deben ir en serie separada) queda garantizado por
     * construccion, no por convencion del usuario.
     */
    public Series findActiveByKind(String invoiceKind) {
        String kind = mapInvoiceTypeToSeriesKind(invoiceKind);
        // TPB-2 (RD 1619/2012 art. 6.1.b): si la asesoría está operando
        // "como" el cliente y existe acuerdo activo de facturación por
        // tercero que cubre ventas, usamos la serie TPB específica de
        // ese par (asesoría, cliente). Solo aplica a STANDARD y
        // RECTIFYING — proformas y test no son legales y no requieren
        // serie distinta.
        if ("STANDARD".equals(kind) || "RECTIFYING".equals(kind)) {
            String clientId = tenant.getCurrentCompanyId();
            String advisoryId;
            try {
                advisoryId = currentUser.require().activeCompanyId();
            } catch (RuntimeException ex) {
                advisoryId = null;
            }
            if (advisoryId != null && !advisoryId.equals(clientId)
                    && hasActiveTpbCoveringSales(clientId, advisoryId)) {
                java.util.Optional<Series> tpb = repository.findTpbSeries(clientId, advisoryId);
                if (tpb.isPresent()) return tpb.get();
                // Caso defensivo: acuerdo activo sin serie creada todavía.
                // Lo creamos al vuelo y devolvemos.
                return ensureTpbSeries(clientId, advisoryId);
            }
        }
        return repository.findActiveByKind(kind)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.PRECONDITION_REQUIRED,
                        "No hay serie activa de tipo " + kind + " para esta empresa. "
                                + (kind.equals("STANDARD")
                                        ? "Configura tu serie de facturacion en Facturacion > Configuracion."
                                        : "La migracion V16 deberia haberla creado automaticamente; "
                                                + "revisa que se ejecuto.")));
    }

    /**
     * TPB-2 — Crea (si no existe) la serie de facturación por tercero
     * para el par (cliente, asesoría). Llamado desde
     * ThirdPartyBillingAgreementService al activar el acuerdo y como
     * fallback defensivo en findActiveByKind.
     *
     * <p>Code: {@code TPB-{NIF asesoría sanitizado}} para distinguir
     * visualmente del prefijo del cliente. Format: {@code T-{YYYY}-{0000}}.
     */
    /** Expone findTpbSeries para diagnostico — no crea, solo consulta. */
    public java.util.Optional<Series> findTpbSeriesPublic(
            String clientCompanyId, String advisoryCompanyId) {
        return repository.findTpbSeries(clientCompanyId, advisoryCompanyId);
    }

    public Series ensureTpbSeries(String clientCompanyId, String advisoryCompanyId) {
        java.util.Optional<Series> existing = repository.findTpbSeries(
                clientCompanyId, advisoryCompanyId);
        if (existing.isPresent()) return existing.get();
        String advisoryNif = jdbc.query(
                "SELECT tax_identifier FROM companies WHERE id = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                advisoryCompanyId);
        String code = "TPB-" + (advisoryNif == null ? "X" : advisoryNif.replaceAll("[^A-Za-z0-9]", ""));
        // Si por alguna razón el code chocara con otra serie, añadimos sufijo.
        for (int i = 0; ; i++) {
            String candidate = i == 0 ? code : code + "-" + i;
            Integer dup = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM invoice_series WHERE company_id = ? AND code = ?",
                    Integer.class, clientCompanyId, candidate);
            if (dup == null || dup == 0) { code = candidate; break; }
        }
        String id = java.util.UUID.randomUUID().toString();
        int currentYear = java.time.LocalDate.now().getYear();
        repository.insertForCompany(id, clientCompanyId, advisoryCompanyId,
                code, "STANDARD", "BY_YEAR", "T-{YYYY}-{0000}", 1, currentYear);
        // Releemos sin tenant filter — el caller puede no tener tenant
        // alineado con el cliente al que pertenece la serie.
        return repository.findTpbSeries(clientCompanyId, advisoryCompanyId)
                .orElseThrow(() ->
                new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                        "TPB series creation race"));
    }

    private boolean hasActiveTpbCoveringSales(String clientId, String advisoryId) {
        Integer n = jdbc.queryForObject("""
                SELECT COUNT(*) FROM third_party_billing_agreements
                 WHERE advisory_company_id = ? AND client_company_id = ?
                   AND status = 'ACTIVE' AND scope_sales = TRUE
                """, Integer.class, advisoryId, clientId);
        return n != null && n > 0;
    }

    /**
     * Mapea el invoice_type que viaja en la factura (NORMAL/PROFORMA/...) al
     * invoice_kind de la serie correspondiente. Permite que el cliente y
     * el modelo legal usen vocabularios ligeramente distintos.
     */
    private String mapInvoiceTypeToSeriesKind(String invoiceType) {
        if (invoiceType == null || invoiceType.isBlank() || "NORMAL".equals(invoiceType)) {
            return "STANDARD";
        }
        // PROFORMA, RECTIFYING, TEST coinciden 1:1.
        return invoiceType;
    }

    @Transactional
    public Series create(SeriesUpsertRequest request) {
        // El usuario solo puede crear/editar series STANDARD. Las series
        // PROFORMA/RECTIFYING las gestiona el sistema (semilla V16) para
        // garantizar el cumplimiento de RD 1619/2012 Art.13 sin que la
        // configuracion del usuario pueda saltarselo. Si llega un POST
        // con esos kinds, 422.
        requireUserManagedKind(request.invoiceKind());

        String code = request.code().trim();
        int initial = request.initialNextNumber() == null ? 1 : request.initialNextNumber();
        Integer currentYear = "BY_YEAR".equals(request.numberingType()) ? LocalDate.now().getYear() : null;

        // Si existe una serie soft-deleted con el mismo codigo, la
        // reactivamos en lugar de insertar. El UNIQUE company_id+code no
        // distingue inactivas: sin esto, el ciclo "borrar + crear con
        // mismo codigo" devolveria 1062 / 409 al usuario aunque
        // logicamente "no haya" ninguna serie con ese codigo.
        Optional<Series> dormant = repository.findInactiveByCode(code);
        if (dormant.isPresent()) {
            Series existing = dormant.get();
            repository.reactivateAndUpdate(
                    existing.id(),
                    code,
                    request.invoiceKind(),
                    request.numberingType(),
                    request.formatTemplate(),
                    initial,
                    currentYear
            );
            return get(existing.id());
        }

        String id = UUID.randomUUID().toString();
        try {
            repository.insert(
                    id,
                    code,
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
        // Las series reservadas (PROFORMA/RECTIFYING) NO se editan por
        // usuario; son del sistema. Bloqueamos antes incluso de mirar
        // emisiones.
        requireUserManagedKind(existing.invoiceKind());
        requireUserManagedKind(request.invoiceKind());
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
        // Validamos que la serie existe (404 si no) sin guardar el objeto.
        get(seriesId);
        int year = LocalDate.now().getYear();
        repository.updateCounter(seriesId, nextNumber, year);
        return get(seriesId);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }

    @Transactional
    public void delete(String id) {
        // Tampoco se permite borrar series reservadas — son del sistema y
        // su presencia garantiza el cumplimiento de Art.13 RD 1619/2012.
        Series existing = get(id);
        requireUserManagedKind(existing.invoiceKind());
        int affected = repository.softDelete(id);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Serie no encontrada");
        }
    }

    /**
     * Lanza 422 si el invoice_kind no es STANDARD. Las series PROFORMA y
     * RECTIFYING (y futura SIMPLIFIED) son reservadas por el sistema —
     * el usuario solo configura la suya de facturas normales.
     */
    private void requireUserManagedKind(String invoiceKind) {
        if (!"STANDARD".equals(invoiceKind)) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY,
                    "Las series de tipo " + invoiceKind + " las gestiona el sistema. "
                            + "Solo puedes definir la serie de tus facturas normales (STANDARD).");
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

        String formatted = formatNumber(series.formatTemplate(), series.code(), numberToEmit, year);
        return new ClaimedNumber(series.id(), series.code(), numberToEmit, year, formatted, yearReset);
    }

    /**
     * Reemplaza {CODE}, {YYYY} y {0000+} en el template por los valores
     * reales. Si el template es null o vacio, devuelve "<code>-<num>" como
     * fallback.
     *
     * El placeholder {CODE} se introdujo despues de detectar que el
     * editor de la UI ya lo asume (preview de proximo numero) pero el
     * backend no lo sustituia → la factura quedaba con literal
     * "{CODE}-2026-0001" en invoice_number.
     */
    String formatNumber(String template, String code, int number, int year) {
        if (template == null || template.isBlank()) {
            return (code == null ? "" : code + "-") + number;
        }
        String result = template
                .replace("{CODE}", code == null ? "" : code)
                .replace("{YYYY}", String.valueOf(year));
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
