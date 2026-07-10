package com.benjagest.backend.billing.invoices;

import com.benjagest.backend.billing.pdf.InvoicePdfGenerator;
import com.benjagest.backend.billing.pdf.InvoiceQrService;
import com.benjagest.backend.billing.pdf.InvoiceStorageService;
import com.benjagest.backend.billing.series.SeriesService;
import com.benjagest.backend.billing.sif.SifEventService;
import com.benjagest.backend.billing.texts.InvoiceTextsController.InvoiceTextsService;
import com.benjagest.backend.billing.verifactu.VerifactuRegistryService;
import com.benjagest.backend.settings.CompanyDataResponse;
import com.benjagest.backend.settings.CompanyDataService;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ResponseStatusException;

/**
 * Logica de negocio de facturas emitidas. Aqui viven los calculos de
 * totales, el cambio de estado DRAFT -> VALIDATED, y la integracion con
 * SeriesService para emitir el numero.
 *
 * Reglas:
 *   - Solo DRAFT se puede editar o cancelar (softCancelDraft).
 *   - validate exige series_id (sin serie no hay numero).
 *   - Al validar, los totales del header se recalculan a partir de las
 *     lineas; no se respeta lo que envie el cliente (defensa contra
 *     desincronizacion).
 *   - Los totales se redondean a 2 decimales (HALF_UP, lo estandar
 *     en facturacion ES).
 */
@Service
public class SalesInvoiceService {

    private final SalesInvoiceRepository repository;
    private final SeriesService seriesService;
    private final VerifactuRegistryService verifactuRegistryService;
    private final SifEventService sifEventService;
    private final InvoicePdfGenerator pdfGenerator;
    private final InvoiceQrService qrService;
    private final InvoiceStorageService storageService;
    private final CompanyDataService companyDataService;
    private final InvoiceTextsService invoiceTextsService;
    private final com.benjagest.backend.billing.verifactu.VerifactuConfigRepository verifactuConfigRepository;
    private final SalesJournalEntryService salesJournalService;
    private final com.benjagest.backend.auth.CurrentUserService currentUserService;
    private final com.benjagest.backend.tenant.TenantContext tenantContext;
    private final org.springframework.jdbc.core.JdbcTemplate jdbcForTpb;
    private final com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService reflectionService;
    private final com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard;
    private final com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard;

    public SalesInvoiceService(SalesInvoiceRepository repository,
                               SeriesService seriesService,
                               VerifactuRegistryService verifactuRegistryService,
                               SifEventService sifEventService,
                               InvoicePdfGenerator pdfGenerator,
                               InvoiceQrService qrService,
                               InvoiceStorageService storageService,
                               CompanyDataService companyDataService,
                               InvoiceTextsService invoiceTextsService,
                               com.benjagest.backend.billing.verifactu.VerifactuConfigRepository verifactuConfigRepository,
                               SalesJournalEntryService salesJournalService,
                               com.benjagest.backend.auth.CurrentUserService currentUserService,
                               com.benjagest.backend.tenant.TenantContext tenantContext,
                               org.springframework.jdbc.core.JdbcTemplate jdbcForTpb,
                               com.benjagest.backend.billing.reflection.CrossInvoiceReflectionService reflectionService,
                               com.benjagest.backend.billing.tpb.BillingAgreementGuard billingAgreementGuard,
                               com.benjagest.backend.accounting.FiscalYearGuardService fiscalGuard) {
        this.repository = repository;
        this.seriesService = seriesService;
        this.verifactuRegistryService = verifactuRegistryService;
        this.sifEventService = sifEventService;
        this.pdfGenerator = pdfGenerator;
        this.qrService = qrService;
        this.storageService = storageService;
        this.companyDataService = companyDataService;
        this.invoiceTextsService = invoiceTextsService;
        this.verifactuConfigRepository = verifactuConfigRepository;
        this.salesJournalService = salesJournalService;
        this.currentUserService = currentUserService;
        this.tenantContext = tenantContext;
        this.jdbcForTpb = jdbcForTpb;
        this.reflectionService = reflectionService;
        this.billingAgreementGuard = billingAgreementGuard;
        this.fiscalGuard = fiscalGuard;
    }

    public List<SalesInvoice> list(String statusFilter,
                                   String paymentStatusFilter,
                                   String customerIdFilter,
                                   String invoiceTypeFilter,
                                   int limit) {
        return repository.findAll(statusFilter, paymentStatusFilter, customerIdFilter,
                invoiceTypeFilter, limit);
    }

    /**
     * BLOQUE REFLEJO — facturas emitidas por la empresa actual que se reflejaron
     * como gasto en otra empresa de la cartera. Devuelve [salesInvoiceId →
     * nombre de la empresa donde se reflejó], para que el listado de Facturación
     * marque "↪ reflejada en {cliente}".
     */
    public List<java.util.Map<String, String>> reflectionsForIssuer() {
        String companyId = tenantContext.getCurrentCompanyId();
        if (companyId == null) return java.util.List.of();
        return jdbcForTpb.query("""
                SELECT pi.source_sales_invoice_id AS sid, c.legal_name AS cname
                  FROM purchase_invoices pi
                  JOIN companies c ON c.id = pi.company_id
                 WHERE pi.source_company_id = ?
                   AND pi.source_sales_invoice_id IS NOT NULL
                """,
                (rs, n) -> java.util.Map.of(
                        "salesInvoiceId", rs.getString("sid") == null ? "" : rs.getString("sid"),
                        "companyName", rs.getString("cname") == null ? "" : rs.getString("cname")),
                companyId);
    }

    public SalesInvoice get(String id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Factura no encontrada"));
    }

    @Transactional
    public SalesInvoice createDraft(InvoiceUpsertRequest request) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
        // RECT-F2: la simplificada no identifica al destinatario (art. 7
        // RD 1619/2012) — cliente opcional SOLO en ese tipo. El @NotBlank
        // del request se relajó; la obligatoriedad para el resto vive aquí.
        boolean simplified = "SIMPLIFIED".equals(request.invoiceType());
        if (!simplified && (request.customerId() == null || request.customerId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El cliente es obligatorio (solo la factura simplificada puede ir sin cliente)");
        }
        String id = UUID.randomUUID().toString();
        LocalDate invoiceDate = request.invoiceDate() == null ? LocalDate.now() : request.invoiceDate();
        // LOCK (2026-07-07): fallo temprano — un borrador fechado en un
        // ejercicio LOCKED/CLOSED nunca podria validarse; mejor avisar al
        // crear que al final del flujo.
        fiscalGuard.requireOpenForDate(invoiceDate, "crear una factura con esa fecha");
        LocalDate dueDate = request.dueDate() == null ? invoiceDate.plusDays(30) : request.dueDate();

        List<InvoiceLine> lines = computeLines(id, request.lines());
        Totals totals = aggregateTotals(lines);
        if (simplified) {
            requireSimplifiedWithinLegalLimit(totals.total());
        }

        // Resolvemos la serie en el servidor por el invoice_type
        // (NORMAL → STANDARD, RECTIFYING → RECT, etc). Ignoramos lo que
        // mande el cliente: si el editor de la UI dejara de mandar
        // seriesId, sigue funcionando; si lo manda mal, no podemos darle
        // una serie incongruente con el kind. Decision 2026-06-02 — el
        // server elige, el usuario no.
        String resolvedSeriesId = seriesService.findActiveByKind(request.invoiceType()).id();

        SalesInvoice header = new SalesInvoice(
                id,
                null,
                request.customerId(),
                null,
                null,
                resolvedSeriesId,
                null,
                invoiceDate,
                dueDate,
                request.invoiceType(),
                "DRAFT",
                "PENDING",
                totals.subtotal(),
                totals.vatTotal(),
                totals.retentionTotal(),
                totals.total(),
                BigDecimal.ZERO,
                "EUR",
                blankToNull(request.originalInvoiceId()),
                null,
                null,            // rectificationCode — solo lo fijan void/rectify.
                null,            // rectificationScope — idem.
                blankToNull(request.concept()),
                blankToNull(request.notes()),
                null,            // pdfPath — solo se rellena al validar.
                null,
                null,
                null,
                lines
        );
        repository.insertHeader(header);
        for (InvoiceLine line : lines) {
            repository.insertLine(line);
        }
        return get(id);
    }

    @Transactional
    public SalesInvoice updateDraft(String id, InvoiceUpsertRequest request) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
        SalesInvoice existing = get(id);
        // Editable: DRAFT (cualquier tipo) o PROFORMA VALIDATED (la
        // proforma no es documento fiscal y sigue siendo borrador
        // comercial hasta que se convierte a factura normal).
        boolean isProformaValidated = "PROFORMA".equals(existing.invoiceType())
                && "VALIDATED".equals(existing.status());
        if (!"DRAFT".equals(existing.status()) && !isProformaValidated) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se editan facturas en borrador o proformas (status DRAFT o PROFORMA VALIDATED)");
        }

        LocalDate invoiceDate = request.invoiceDate() == null ? existing.invoiceDate() : request.invoiceDate();
        // LOCK (2026-07-07): no dejar mover un borrador a una fecha de
        // ejercicio LOCKED/CLOSED (fallo temprano, como en createDraft).
        fiscalGuard.requireOpenForDate(invoiceDate, "poner esa fecha a la factura");
        LocalDate dueDate = request.dueDate() == null ? existing.dueDate() : request.dueDate();
        List<InvoiceLine> lines = computeLines(id, request.lines());
        Totals totals = aggregateTotals(lines);

        // RECT-F2: mismo criterio que createDraft — cliente obligatorio
        // salvo simplificada, y tope legal si es simplificada.
        boolean simplifiedDraft = "SIMPLIFIED".equals(existing.invoiceType());
        if (!simplifiedDraft && (request.customerId() == null || request.customerId().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El cliente es obligatorio (solo la factura simplificada puede ir sin cliente)");
        }
        if (simplifiedDraft) {
            requireSimplifiedWithinLegalLimit(totals.total());
        }

        // El invoice_type se PRESERVA del borrador existente. Si el cliente
        // intenta cambiar el tipo (p.ej. "NORMAL" en una rectificativa
        // creada via /void), lo ignoramos. Solo createDraft fija el tipo;
        // una vez creado, no se cambia. Asi la cascada de anulacion
        // (RECTIFYING → original VOIDED al validar) no se puede esquivar
        // editando el tipo del borrador.
        String preservedType = existing.invoiceType();
        String resolvedSeriesId = seriesService.findActiveByKind(preservedType).id();

        SalesInvoice header = new SalesInvoice(
                id, null, request.customerId(), null, null,
                resolvedSeriesId, existing.invoiceNumber(),
                invoiceDate, dueDate,
                preservedType,
                existing.status(), existing.paymentStatus(),
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total(),
                existing.paidAmount(), existing.currency(),
                // El vinculo con la factura original (si lo hay) tampoco se
                // edita desde aqui — solo lo fija voidValidated() al crear
                // el borrador rectificativa.
                existing.originalInvoiceId(), existing.rectifyingInvoiceId(),
                existing.rectificationCode(), existing.rectificationScope(),
                blankToNull(request.concept()),
                blankToNull(request.notes()),
                existing.pdfPath(),       // pdfPath se preserva (borrador siempre null).
                existing.validatedAt(),
                existing.createdAt(), existing.updatedAt(),
                lines
        );

        int affected = repository.updateHeader(id, header);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La factura ya no esta en DRAFT");
        }
        repository.deleteLinesForInvoice(id);
        for (InvoiceLine line : lines) {
            repository.insertLine(line);
        }
        return get(id);
    }

    @Transactional
    public SalesInvoice validate(String id) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
        return validateInternal(id);
    }

    /**
     * Regenera el asiento contable de una factura (desglose de IVA por tipo)
     * sin alterar su estado/numeración. Útil para actualizar facturas ya
     * validadas cuyo asiento se creó antes del desglose.
     */
    @Transactional
    public void regenerateJournal(String id) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
        SalesInvoice invoice = get(id);
        // AUDIT-T3 resto (2026-07-09): regenerar reescribe las líneas del
        // asiento (etiquetado vat_rate incluido) — si el trimestre de la
        // factura ya está declarado (303/130 PRESENTED/PAID), cambiaría la
        // base de una declaración presentada. Mismo guard que en compras.
        requirePeriodNotPresented(invoice.invoiceDate(), "regenerar el asiento de esta factura");
        String userId;
        try { userId = currentUserService.require().userId(); }
        catch (Exception ex) { userId = null; }
        salesJournalService.regenerateForSales(invoice, userId);
    }

    /**
     * AUDIT-T3 (2026-07-09) — espejo de
     * {@code PurchaseInvoiceService.requirePeriodNotPresented}: 409 si la
     * fecha cae en un periodo cuyo 303/130 (o un modelo anual 390/347/349)
     * ya está PRESENTADO/PAID. El 130 es acumulado: bloquea también si hay
     * presentación del año con trimestre &gt;= al de la fecha, o una anual.
     */
    private void requirePeriodNotPresented(java.time.LocalDate date, String action) {
        if (date == null) return;
        int q = (date.getMonthValue() - 1) / 3 + 1;
        Integer hits = jdbcForTpb.queryForObject("""
                SELECT COUNT(*) FROM tax_filings
                 WHERE company_id = ? AND status IN ('PRESENTED', 'PAID')
                   AND period_year = ?
                   AND (period_quarter IS NULL OR period_quarter >= ?)
                """, Integer.class, tenantContext.getCurrentCompanyId(),
                date.getYear(), q);
        if (hits != null && hits > 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No se puede " + action + ": su periodo (" + q + "T " + date.getYear()
                    + ") ya está incluido en una declaración presentada. Registra la "
                    + "corrección/rectificación en el periodo corriente.");
        }
    }

    /**
     * Cuerpo de la validación. Está separado del método público para
     * poder ser reutilizado desde {@link #voidValidated(String)} sin
     * que self-invocation rompa el proxy AOP de @Transactional (Spring
     * NO aplica el aspecto cuando un método de la misma clase llama
     * directamente a otro). Al llamarse desde voidValidated, que ya
     * lleva su propio @Transactional, todo viaja en la misma tx.
     */
    private SalesInvoice validateInternal(String id) {
        SalesInvoice existing = get(id);
        if (!"DRAFT".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se valida una factura en DRAFT");
        }
        // LOCK (2026-07-07): validar emite numero fiscal + hash VeriFactu +
        // asiento. Nada de eso puede nacer dentro de un ejercicio
        // LOCKED/CLOSED (RD 1007/2023 — inalterabilidad del periodo).
        // La rectificativa de voidValidated pasa por aqui con fecha de HOY,
        // asi que el flujo legal de anulacion sigue funcionando.
        fiscalGuard.requireOpenForDate(existing.invoiceDate(), "validar esta factura");
        if (existing.seriesId() == null || existing.seriesId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta serie de numeracion: asigna una serie antes de validar");
        }
        if (existing.lines() == null || existing.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Una factura sin lineas no se puede validar");
        }
        // RECT: una rectificativa sin causa legal (R1..R5) no es valida —
        // el TipoFactura de la huella VeriFactu sale de ahi. Solo afecta a
        // borradores nuevos: las rectificativas historicas ya estan
        // VALIDATED y nunca vuelven a pasar por aqui.
        if ("RECTIFYING".equals(existing.invoiceType())
                && (existing.rectificationCode() == null || existing.rectificationCode().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Falta la causa de rectificacion (R1-R5). Crea la rectificativa "
                            + "desde Anular o Rectificar para que quede fijada.");
        }
        // RECT-F2: tope legal de la simplificada sobre los totales FINALES.
        if ("SIMPLIFIED".equals(existing.invoiceType())) {
            requireSimplifiedWithinLegalLimit(aggregateTotals(existing.lines()).total());
        }

        Totals totals = aggregateTotals(existing.lines());
        // Emite el numero. Como SeriesService.claimNextNumber es
        // @Transactional, el FOR UPDATE forma parte de la misma
        // transaccion que esta validacion.
        SeriesService.ClaimedNumber claimed = seriesService.claimNextNumber(existing.seriesId());

        // TPB-3: si la asesoría está emitiendo por tercero (acuerdo
        // ACTIVE con scope_sales) la factura queda en
        // PENDING_CLIENT_APPROVAL en lugar de VALIDATED directo —
        // PERO SOLO si el cliente PUEDE aprobarla (es tenant real con
        // login propio). Si el cliente es una ficha gestionada sin
        // login (shadow company, parent_company_id NOT NULL), NO tiene
        // forma de aprobar nada (no usa BENJAGEST), asi que la factura
        // se valida directamente — la asesoria la emite materialmente
        // en su nombre con la cobertura del acuerdo TPB firmado.
        // Fix Benjamin 2026-06-13: antes TODA emision TPB quedaba en
        // PENDING_CLIENT_APPROVAL, dejando atascadas las facturas de
        // clientes sin vinculo (imposibles de aprobar).
        String tpbClientId = tenantContext.getCurrentCompanyId();
        if (isThirdPartyEmission() && clientCanSelfApprove(tpbClientId)) {
            int aff = repository.markPendingClientApproval(
                    id, claimed.formatted(),
                    totals.subtotal(), totals.vatTotal(),
                    totals.retentionTotal(), totals.total());
            if (aff == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La factura ya no esta en DRAFT");
            }
            return get(id);
        }

        int affected = repository.markValidated(
                id, claimed.formatted(),
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total()
        );
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "La factura ya no esta en DRAFT");
        }

        // Hook VeriFactu: si la empresa tiene mode != OFF, registramos
        // la huella encadenada. Si el modo es OFF, no se crea registro
        // (la cadena solo existe a partir del momento en que se activa).
        //
        // EXCEPCIÓN PROFORMA: no entra en la cadena VeriFactu (no es
        // documento fiscal — RD 1007/2023 solo aplica a facturas, no a
        // documentos comerciales). La proforma se valida para tener
        // número de control interno (PROF-XXXX) pero no genera hash ni
        // se envia a AEAT.
        SalesInvoice validated = get(id);
        if (!"PROFORMA".equals(validated.invoiceType())) {
            verifactuRegistryService.registerIfActive(validated);
        }

        // Cascada de anulacion: si esta factura es una RECTIFYING que
        // apunta a una original, al validarla la original pasa a VOIDED
        // y se rellena su rectifying_invoice_id apuntando a esta.
        // Este es el momento legal en que la factura original queda
        // "anulada con vinculo" — ni antes ni despues, ni con cancelar
        // sueltos.
        boolean cascadedVoid = false;
        // RECT: la cascada de anulacion SOLO aplica a rectificativas de
        // anulacion total. Una rectificativa PARCIAL corrige importes y la
        // original sigue VALIDATED (scope null = historico = anulacion).
        if ("RECTIFYING".equals(validated.invoiceType())
                && !"PARTIAL".equals(validated.rectificationScope())
                && validated.originalInvoiceId() != null
                && !validated.originalInvoiceId().isBlank()) {
            repository.markVoided(validated.originalInvoiceId());
            repository.setRectifyingInvoiceId(validated.originalInvoiceId(), id);
            cascadedVoid = true;
            // REFLEJO-3a: si la original estaba reflejada como gasto en un cliente
            // de la cartera, revertir ese gasto + asiento (best-effort).
            try {
                reflectionService.reflectVoid(validated.originalInvoiceId(),
                        currentUserService.require().userId());
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                        .warn("No se pudo revertir el reflejo al anular {}",
                                validated.originalInvoiceId(), ex);
            }
        }

        // Slice F-STORAGE: generar el PDF y guardarlo en disco aqui, no
        // bajo demanda. La copia almacenada ES la legalmente vinculante
        // (RD 1007/2023 + LGT art.70: minimo 4 anyos). Descargas
        // posteriores releen este archivo en vez de regenerar.
        //
        // Si la escritura a disco falla, NO rompemos la transaccion: la
        // factura ya esta validada y numerada legalmente. El operador
        // puede reconstruir manualmente con el boton "Guardar PDF" del
        // listado (endpoint /store-pdf).
        try {
            generateAndStorePdf(validated);
        } catch (IOException ioe) {
            org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                    .warn("No se pudo guardar el PDF de la factura {} en disco", id, ioe);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                    .warn("Error inesperado guardando PDF de la factura {}", id, ex);
        }

        // SALES-JOURNAL (2026-06-05): generar asiento contable
        // automatico. Espejo del flujo de purchases pero con cuentas
        // de venta (430 Clientes / 700 Ventas / 477 IVA repercutido /
        // 473 retenciones IRPF si aplica). Si la empresa no tiene el
        // PGC sembrado o no hay fiscal_year OPEN, devuelve null y
        // continuamos — la validacion legal de la factura es
        // independiente del asiento contable.
        try {
            String currentUserId = currentUserService.require().userId();
            String journalEntryId = salesJournalService.createForSales(validated, currentUserId);
            if (journalEntryId != null) {
                org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                        .info("Asiento {} creado para factura {}", journalEntryId, id);
            }
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                    .warn("No se pudo crear el asiento contable de la factura {}", id, ex);
        }

        // Registro de Eventos del SIF (RD 1007/2023 + Orden HAC/1177/2024):
        // emitimos INVOICE_VALIDATED al cerrar una validacion. El servicio
        // de eventos filtra internamente por modalidad — en VeriFactu no
        // hace nada porque AEAT ya tiene los datos en tiempo real; en
        // NO VeriFactu encadena este evento al hash de la cadena de
        // eventos del SIF. Si la validacion ademas anulo una original
        // (RECTIFYING), emitimos tambien INVOICE_VOIDED apuntando a esa
        // original para tener traza explicita.
        String payload = "{\"invoiceId\":\"" + id + "\",\"invoiceNumber\":\""
                + (validated.invoiceNumber() == null ? "" : validated.invoiceNumber()) + "\"}";
        sifEventService.record("INVOICE_VALIDATED", payload);
        if (cascadedVoid) {
            String voidPayload = "{\"originalInvoiceId\":\"" + validated.originalInvoiceId()
                    + "\",\"byRectifyingInvoiceId\":\"" + id + "\"}";
            sifEventService.record("INVOICE_VOIDED", voidPayload);
        }

        // BLOQUE REFLEJO (2026-06-23): si el cliente de esta factura es otra
        // empresa de la cartera (match por NIF), reflejarla como gasto +
        // asiento POR VALIDAR en sus libros. Best-effort y NUNCA lanza: un
        // fallo del reflejo no afecta a la validación legal ni a la cadena SIF.
        // Va al final, después de todo lo legal, para no dejar reflejos
        // huérfanos si algo previo hubiera fallado.
        try {
            String reflectUserId = currentUserService.require().userId();
            reflectionService.reflectSalesInvoice(validated, reflectUserId);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                    .warn("No se pudo reflejar la factura {} como gasto del cliente", id, ex);
        }

        return get(id);
    }

    /**
     * Anulación con vínculo (decisión 2026-06-03): dada una factura
     * VALIDATED, emitimos en UNA SOLA TRANSACCIÓN una factura rectificativa
     * ya VALIDATED enlazada a la original. La original pasa a VOIDED.
     *
     * Por qué ya validada y no como borrador editable:
     *   - Una vez validada la original, su rectificativa es un acto
     *     legal: emitirla como borrador abriría una ventana en la que
     *     el usuario podría manipular cifras antes de "confirmar". Eso
     *     rompe el sentido de la rectificativa por anulación.
     *   - La rectificativa parcial (cambiar líneas concretas) será un
     *     flujo aparte cuando lleguemos al slice "Rectificativa parcial
     *     R1-R5" — ahí sí tiene sentido revisar antes de validar.
     *
     * Las líneas se generan como copia de las originales con quantity
     * invertida → totales negativos. El número y el hash VeriFactu se
     * emiten ya. La original queda VOIDED con rectifying_invoice_id
     * apuntando a la nueva.
     */
    @Transactional
    public SalesInvoice voidValidated(String validatedId, String rectificationCode) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
        SalesInvoice original = get(validatedId);
        // RECT: causa legal de la anulacion. La UI la pide con selector
        // (default R4 "resto de causas"); si la API no la manda, R4. Si la
        // original es una simplificada, la ley obliga a R5.
        String code = normalizeRectificationCode(rectificationCode, original);
        if (!"VALIDATED".equals(original.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se puede anular una factura VALIDATED. Para borrar borradores usa DELETE.");
        }
        // IMP-H: las facturas historicas importadas de CONTENDO no se anulan
        // desde aqui. Pertenecen a la contabilidad del sistema anterior y no
        // estan en la cadena VeriFactu/SIF; anularlas emitiria una
        // rectificativa real (con numero de serie y registro VeriFactu) sobre
        // un documento que nunca entro en esa cadena.
        if ("HISTORICAL".equals(original.invoiceType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Las facturas historicas importadas no se anulan desde aqui: "
                    + "pertenecen a la contabilidad del sistema anterior.");
        }
        if (original.rectifyingInvoiceId() != null && !original.rectifyingInvoiceId().isBlank()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Esta factura ya tiene una rectificativa creada: " + original.rectifyingInvoiceId());
        }

        // SALES-JOURNAL: borra fisicamente el asiento de la factura
        // original (hueco en el Diario, mismo trato que compras). La
        // rectificativa que creamos a continuación generará su propio
        // asiento espejo (con cantidades negativas → totales negativos →
        // asiento de signo invertido) cuando pase por validateInternal.
        //
        // LOCK (2026-07-07): si la original es de un ejercicio
        // LOCKED/CLOSED, reverseForSales lanza 409 y este catch lo
        // ABSORBE a propósito: el asiento del ejercicio cerrado se
        // CONSERVA intacto (la venta ocurrió y sus libros están
        // cerrados) y la anulación sigue el único camino legal — la
        // rectificativa negativa con fecha de HOY que contrarresta en
        // el ejercicio corriente.
        try {
            salesJournalService.reverseForSales(validatedId);
        } catch (Exception ex) {
            org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                    .warn("No se borró el asiento de la factura {} (se conserva): {}",
                            validatedId, ex.getMessage());
        }

        // Resolvemos la serie RECTIFYING del cliente activo. La V16
        // garantiza que existe; si no, el server responde 428 (claro)
        // en vez de NPE confuso.
        String rectSeriesId = seriesService.findActiveByKind("RECTIFYING").id();

        String newId = UUID.randomUUID().toString();
        LocalDate today = LocalDate.now();
        List<InvoiceLineInput> negatedInputs = new ArrayList<>();
        for (InvoiceLine line : original.lines()) {
            // Ojo: el record InvoiceLineInput espera description ANTES de
            // catalogItemId. El bug previo intercambiaba los dos primeros
            // → description llegaba null y computeLines reventaba con NPE
            // en .trim().
            negatedInputs.add(new InvoiceLineInput(
                    line.description(),
                    line.catalogItemId(),
                    line.quantity() == null ? null : line.quantity().negate(),
                    line.unitPrice(),
                    line.vatPercent(),
                    line.retentionPercent(),
                    // FAC-IVA: la rectificativa conserva el TIPO de IVA de la
                    // línea original (su texto legal sale igual en el PDF).
                    line.vatRateId()
            ));
        }
        List<InvoiceLine> lines = computeLines(newId, negatedInputs);
        Totals totals = aggregateTotals(lines);

        // Insertamos primero como DRAFT (mismo formato que createDraft)
        // para reusar el flujo de validate. La factura nunca queda en
        // DRAFT visible para el usuario: la validamos en la misma
        // transacción inmediatamente despues — todo o nada.
        SalesInvoice draft = new SalesInvoice(
                newId,
                null,
                original.customerId(),
                null,
                null,
                rectSeriesId,
                null,
                today,
                today.plusDays(30),
                "RECTIFYING",
                "DRAFT",
                "PENDING",
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total(),
                BigDecimal.ZERO,
                "EUR",
                original.id(),
                null,
                code,
                "ANNULMENT",
                original.concept(),
                "Rectificativa (" + code + ") por anulacion de "
                        + (original.invoiceNumber() == null ? original.id() : original.invoiceNumber()),
                null,            // pdfPath — se rellena al validar.
                null,
                null,
                null,
                lines
        );
        repository.insertHeader(draft);
        for (InvoiceLine line : lines) {
            repository.insertLine(line);
        }

        // Validamos YA mismo, en la misma transacción → la rectificativa
        // sale numerada (claimNextNumber), VeriFactu registra el hash, la
        // original pasa a VOIDED, y devolvemos el documento legal final.
        // Llamamos a validateInternal (sin proxy) para evitar el problema
        // de self-invocation con @Transactional.
        return validateInternal(newId);
    }

    /**
     * RECT — Rectificativa PARCIAL: corrige importes de una factura
     * validada SIN anularla. Crea un borrador RECTIFYING (scope PARTIAL)
     * con las líneas de la original en negativo como punto de partida;
     * el usuario las edita en el editor (deja solo la diferencia) y
     * valida por el flujo normal. Al validar NO hay cascada de anulación:
     * la original sigue VALIDATED con el vínculo original_invoice_id.
     *
     * A diferencia de la anulación (una sola rectificativa por factura,
     * controlado por rectifying_invoice_id), una factura puede tener
     * varias rectificativas parciales — la ley no lo impide y pasa en la
     * práctica (dos correcciones en meses distintos).
     */
    @Transactional
    public SalesInvoice rectifyPartial(String originalId, String rectificationCode) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
        SalesInvoice original = get(originalId);
        if (!"VALIDATED".equals(original.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se rectifica una factura VALIDATED.");
        }
        if ("PROFORMA".equals(original.invoiceType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Una proforma no es documento fiscal: edítala directamente.");
        }
        if ("HISTORICAL".equals(original.invoiceType())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Las facturas historicas importadas no se rectifican desde aqui: "
                            + "pertenecen a la contabilidad del sistema anterior.");
        }
        String code = normalizeRectificationCode(rectificationCode, original);

        String rectSeriesId = seriesService.findActiveByKind("RECTIFYING").id();
        String newId = UUID.randomUUID().toString();
        LocalDate today = LocalDate.now();
        List<InvoiceLineInput> negatedInputs = new ArrayList<>();
        for (InvoiceLine line : original.lines()) {
            negatedInputs.add(new InvoiceLineInput(
                    line.description(),
                    line.catalogItemId(),
                    line.quantity() == null ? null : line.quantity().negate(),
                    line.unitPrice(),
                    line.vatPercent(),
                    line.retentionPercent(),
                    // FAC-IVA: la rectificativa conserva el TIPO de IVA de la
                    // línea original (su texto legal sale igual en el PDF).
                    line.vatRateId()
            ));
        }
        List<InvoiceLine> lines = computeLines(newId, negatedInputs);
        Totals totals = aggregateTotals(lines);

        SalesInvoice draft = new SalesInvoice(
                newId, null,
                original.customerId(), null, null,
                rectSeriesId, null,
                today, today.plusDays(30),
                "RECTIFYING", "DRAFT", "PENDING",
                totals.subtotal(), totals.vatTotal(), totals.retentionTotal(), totals.total(),
                BigDecimal.ZERO, "EUR",
                original.id(), null,
                code, "PARTIAL",
                original.concept(),
                "Rectificativa parcial (" + code + ") de "
                        + (original.invoiceNumber() == null ? original.id() : original.invoiceNumber())
                        + " — ajusta las lineas a la diferencia antes de validar",
                null, null, null, null,
                lines
        );
        repository.insertHeader(draft);
        for (InvoiceLine line : lines) {
            repository.insertLine(line);
        }
        // Se devuelve el BORRADOR (no se valida): el usuario debe dejar en
        // las líneas solo la corrección. La UI abre el editor sobre él.
        return get(newId);
    }

    /**
     * RECT — normaliza/valida la causa legal de rectificacion.
     * R1 error fundado en derecho (art. 80.Uno/Dos/Seis LIVA), R2 concurso
     * (80.Tres), R3 credito incobrable (80.Cuatro), R4 resto de causas,
     * R5 rectificativa de factura simplificada (obligada si la original
     * es SIMPLIFIED). Sin causa → R4 (default de la UI).
     */
    private String normalizeRectificationCode(String code, SalesInvoice original) {
        if ("SIMPLIFIED".equals(original.invoiceType())) {
            return "R5";
        }
        String c = code == null ? "" : code.trim().toUpperCase();
        if (c.isEmpty()) return "R4";
        if (!c.matches("R[1-4]")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Causa de rectificacion invalida: " + code
                            + " (validas: R1-R4; R5 se aplica sola si la original es simplificada)");
        }
        return c;
    }

    /**
     * RECT-F2 — tope legal de la factura simplificada: 400 EUR IVA
     * incluido (art. 4.1 RD 1619/2012). El supuesto de 3.000 EUR del
     * art. 4.2 (comercio minorista, hosteleria, transporte...) no esta
     * soportado todavia — se dice en el mensaje para no confundir.
     */
    private void requireSimplifiedWithinLegalLimit(BigDecimal total) {
        if (total != null && total.abs().compareTo(new BigDecimal("400")) > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Una factura simplificada no puede superar 400 EUR (IVA incluido), "
                            + "art. 4.1 RD 1619/2012. El supuesto de hasta 3.000 EUR para comercio "
                            + "minorista y similares (art. 4.2) no esta soportado aun: usa una "
                            + "factura completa con el cliente identificado.");
        }
    }

    /**
     * Endpoint público para "Guardar PDF" — fuerza la generación y
     * escritura en disco de una factura ya VALIDATED que aún no tiene
     * archivo (porque se validó antes del slice F-STORAGE o porque
     * fallo la escritura en su día). Devuelve la ruta absoluta para
     * que la UI la muestre al usuario.
     *
     * - Rechaza con 400 si la factura no está VALIDATED.
     * - Si ya tiene pdf_path y el archivo existe, reescribe (el
     *   resultado es idéntico bit a bit si los datos canónicos no han
     *   cambiado — caso normal).
     */
    @Transactional
    public String storePdfNow(String invoiceId) {
        SalesInvoice invoice = get(invoiceId);
        if (!"VALIDATED".equals(invoice.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se guardan PDFs de facturas VALIDATED. Estado actual: " + invoice.status());
        }
        // IMP-H: una factura historica importada no tiene datos de emisor/QR
        // VeriFactu propios (se emitio en el sistema anterior); no generamos
        // PDF oficial. Si el usuario necesita el documento, tiene el original
        // escaneado del sistema de origen.
        if ("HISTORICAL".equals(invoice.invoiceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Las facturas historicas importadas no generan PDF: "
                    + "usa el documento original del sistema anterior.");
        }
        try {
            return generateAndStorePdf(invoice);
        } catch (IOException ioe) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo escribir el PDF en disco: " + ioe.getMessage());
        }
    }

    /**
     * Lógica común a {@link #validateInternal} y {@link #storePdfNow}:
     * genera el PDF (con QR oficial AEAT + huella VeriFactu si aplica),
     * lo escribe en la ruta de almacenamiento configurada (creando
     * subcarpetas año/trimestre si no existen) y actualiza
     * sales_invoices.pdf_path. Devuelve la ruta absoluta.
     */
    private String generateAndStorePdf(SalesInvoice validated) throws IOException {
        CompanyDataResponse company = companyDataService.getCurrent();
        String currentHash = verifactuRegistryService
                .findCurrentHashForPdf(validated.id()).orElse(null);
        com.benjagest.backend.billing.verifactu.VerifactuConfig vfConfig =
                verifactuConfigRepository.findCurrent().orElse(null);
        byte[] qrPng = qrService.generatePng(validated, company, vfConfig);
        String complianceLabel = qrService.complianceLabel(vfConfig);
        byte[] pdfBytes = pdfGenerator.generate(
                validated, company, invoiceTextsService.get(),
                currentHash, qrPng, complianceLabel);
        String storageRoot = vfConfig == null ? null : vfConfig.invoiceStorageRoot();
        String absPath = storageService.writePdf(
                storageRoot, company.id(),
                validated.invoiceDate(), validated.invoiceNumber(),
                pdfBytes);
        repository.setPdfPath(validated.id(), absPath);
        return absPath;
    }

    /**
     * Convierte una PROFORMA en factura NORMAL. Acepta dos estados de
     * partida:
     *
     *   1. PROFORMA DRAFT — cambia el invoice_type y la serie; si
     *      validate=true, valida (emite siguiente FRA-XXXX).
     *   2. PROFORMA VALIDATED — cambia el invoice_type, asigna la serie
     *      STANDARD, emite el siguiente número STANDARD reemplazando el
     *      PROF-XXXX que tuviera, y registra en VeriFactu. Es como un
     *      "re-validate" pero pasando por el cambio de tipo en el mismo
     *      paso.
     *
     * El usuario llega aquí desde el botón "Convertir y validar" del
     * listado. validate=false en proforma VALIDATED no aplicaría (no se
     * puede "des-validar"); el controller lo permite pero internamente
     * lo tratamos como true en ese caso.
     */
    @Transactional
    public SalesInvoice convertProformaToStandard(String invoiceId, boolean validate) {
        SalesInvoice existing = get(invoiceId);
        if (!"PROFORMA".equals(existing.invoiceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se convierten proformas. Tipo actual: " + existing.invoiceType());
        }
        if (!"DRAFT".equals(existing.status()) && !"VALIDATED".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Solo se convierten proformas en DRAFT o VALIDATED. Estado actual: "
                            + existing.status());
        }
        boolean fromValidated = "VALIDATED".equals(existing.status());

        // Cambiamos el tipo + la serie. Si parte de VALIDATED, también
        // limpiamos invoice_number para que claimNextNumber emita el
        // siguiente STANDARD (FRA-XXXX) en lugar de mantener el PROF-XXXX.
        String newSeriesId = seriesService.findActiveByKind("NORMAL").id();
        SalesInvoice mutated = new SalesInvoice(
                existing.id(), null, existing.customerId(), null, null,
                newSeriesId,
                fromValidated ? null : existing.invoiceNumber(),
                existing.invoiceDate(), existing.dueDate(),
                "NORMAL",
                fromValidated ? "DRAFT" : existing.status(),
                existing.paymentStatus(),
                existing.subtotal(), existing.vatTotal(), existing.retentionTotal(), existing.total(),
                existing.paidAmount(), existing.currency(),
                existing.originalInvoiceId(), existing.rectifyingInvoiceId(),
                existing.rectificationCode(), existing.rectificationScope(),
                existing.concept(), existing.notes(), existing.pdfPath(),
                fromValidated ? null : existing.validatedAt(),
                existing.createdAt(), existing.updatedAt(),
                existing.lines()
        );
        // Si venimos de VALIDATED, hay que regresar a DRAFT primero para
        // que updateHeader y luego validateInternal funcionen. El paso
        // intermedio es invisible al cliente (todo dentro de la misma tx).
        if (fromValidated) {
            int reverted = repository.revertProformaToDraft(invoiceId);
            if (reverted == 0) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "La proforma ya no esta en VALIDATED");
            }
        }
        int affected = repository.updateHeader(invoiceId, mutated);
        if (affected == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La proforma no se pudo actualizar (estado inconsistente)");
        }

        if (validate || fromValidated) {
            return validateInternal(invoiceId);
        }
        return get(invoiceId);
    }

    @Transactional
    public void deleteDraft(String id) {
        billingAgreementGuard.requireAgreementOrOwn(
                com.benjagest.backend.billing.tpb.BillingAgreementGuard.Scope.SALES);
        SalesInvoice existing = get(id);
        boolean isDraft = "DRAFT".equals(existing.status());
        boolean isProforma = "PROFORMA".equals(existing.invoiceType());
        if (!isDraft && !isProforma) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden borrar facturas en DRAFT o proformas. Para una factura validada hay que anular (RECTIFYING).");
        }
        // MÓDULO TRABAJOS — si esta factura facturó trabajos (work_logs), revertirlos a
        // pendientes (vuelven a estar disponibles para facturar). SQL directo para no
        // acoplar billing -> worklogs como bean (evita ciclo: WorkLogService ya depende
        // de SalesInvoiceService). El FK work_logs.invoice_id queda limpio.
        try {
            jdbcForTpb.update("""
                    UPDATE work_logs
                       SET status = 'APPROVED', invoice_id = NULL, billed_invoice_line_id = NULL
                     WHERE invoice_id = ? AND company_id = ?
                    """, id, tenantContext.getCurrentCompanyId());
        } catch (Exception ignored) { /* tabla work_logs siempre existe; defensivo */ }
        repository.softCancelDraft(id);
    }

    /**
     * Calcula los importes derivados de cada linea (subtotal, vat,
     * retention, total) a partir de quantity * unit_price y los
     * porcentajes. Lo hace el backend para evitar que el cliente
     * mande totales manipulados.
     */
    List<InvoiceLine> computeLines(String invoiceId, List<InvoiceLineInput> inputs) {
        List<InvoiceLine> result = new ArrayList<>();
        for (InvoiceLineInput input : inputs) {
            BigDecimal qty = input.quantity() == null ? BigDecimal.ONE : input.quantity();
            BigDecimal unit = input.unitPrice() == null ? BigDecimal.ZERO : input.unitPrice();
            BigDecimal vatPct = input.vatPercent() == null ? BigDecimal.ZERO : input.vatPercent();
            BigDecimal retPct = input.retentionPercent() == null ? BigDecimal.ZERO : input.retentionPercent();

            BigDecimal subtotal = qty.multiply(unit).setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = subtotal.multiply(vatPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal retention = subtotal.multiply(retPct).divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal total = subtotal.add(vat).subtract(retention);

            result.add(new InvoiceLine(
                    UUID.randomUUID().toString(),
                    invoiceId,
                    blankToNull(input.catalogItemId()),
                    input.description().trim(),
                    qty,
                    unit,
                    vatPct,
                    retPct,
                    subtotal,
                    vat,
                    retention,
                    total,
                    // FAC-IVA: identidad del tipo elegido en el combo (opcional).
                    blankToNull(input.vatRateId())
            ));
        }
        return result;
    }

    Totals aggregateTotals(List<InvoiceLine> lines) {
        BigDecimal subtotal = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        BigDecimal retention = BigDecimal.ZERO;
        BigDecimal total = BigDecimal.ZERO;
        for (InvoiceLine line : lines) {
            subtotal = subtotal.add(line.lineSubtotal());
            vat = vat.add(line.lineVat());
            retention = retention.add(line.lineRetention());
            total = total.add(line.lineTotal());
        }
        return new Totals(
                subtotal.setScale(2, RoundingMode.HALF_UP),
                vat.setScale(2, RoundingMode.HALF_UP),
                retention.setScale(2, RoundingMode.HALF_UP),
                total.setScale(2, RoundingMode.HALF_UP)
        );
    }

    public record Totals(BigDecimal subtotal, BigDecimal vatTotal, BigDecimal retentionTotal, BigDecimal total) {
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    // ====================================================================
    //  TPB-3 — Aceptación factura-a-factura por el cliente
    // ====================================================================

    /**
     * Detecta si la sesión está emitiendo facturas por tercero: el
     * usuario real (activeCompanyId del JWT) opera "como" el cliente
     * (tenantContext) y hay acuerdo ACTIVE con scope_sales.
     */
    private boolean isThirdPartyEmission() {
        String clientId = tenantContext.getCurrentCompanyId();
        String advisoryId;
        try {
            advisoryId = currentUserService.require().activeCompanyId();
        } catch (RuntimeException ex) {
            return false;
        }
        if (advisoryId == null || advisoryId.equals(clientId)) return false;
        Integer n = jdbcForTpb.queryForObject("""
                SELECT COUNT(*) FROM third_party_billing_agreements
                 WHERE advisory_company_id = ? AND client_company_id = ?
                   AND status = 'ACTIVE' AND scope_sales = TRUE
                """, Integer.class, advisoryId, clientId);
        return n != null && n > 0;
    }

    /**
     * ¿El cliente puede aprobar por sí mismo una factura emitida en su
     * nombre? Solo si es un tenant real (company con
     * {@code parent_company_id IS NULL}) — esos tienen login propio y
     * pueden entrar a BENJAGEST a aprobar/rechazar. Las fichas
     * gestionadas por la asesoría (shadow companies,
     * {@code parent_company_id NOT NULL}) NO tienen login ni programa,
     * asi que no pueden aprobar nada: sus facturas TPB se validan
     * directamente.
     */
    private boolean clientCanSelfApprove(String clientCompanyId) {
        if (clientCompanyId == null) return false;
        Integer realTenant = jdbcForTpb.queryForObject("""
                SELECT COUNT(*) FROM companies
                 WHERE id = ? AND parent_company_id IS NULL
                """, Integer.class, clientCompanyId);
        return realTenant != null && realTenant > 0;
    }

    /**
     * El cliente aprueba la factura pendiente. La factura pasa a
     * VALIDATED y se ejecutan los hooks que el flujo TPB había aplazado:
     * Verifactu, PDF, asiento contable. Solo el cliente (su tenant)
     * puede ejecutar este método — si llega desde la asesoría, el
     * markApprovedByClient devuelve 0 filas por el AND company_id.
     */
    @Transactional
    public SalesInvoice approveByClient(String id) {
        SalesInvoice existing = get(id);
        if (!"PENDING_CLIENT_APPROVAL".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se aprueba una factura en PENDING_CLIENT_APPROVAL");
        }
        String userId;
        try { userId = currentUserService.require().userId(); }
        catch (Exception ex) { userId = null; }
        int aff = repository.markApprovedByClient(id, userId);
        if (aff == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La factura ya no está pendiente o no eres parte del acuerdo");
        }
        SalesInvoice validated = get(id);
        // Replicamos el hook normal de validate: Verifactu + PDF.
        if (!"PROFORMA".equals(validated.invoiceType())) {
            try { verifactuRegistryService.registerIfActive(validated); }
            catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                        .warn("VeriFactu falló al aprobar TPB id={}", id, ex);
            }
        }
        try { generateAndStorePdf(validated); }
        catch (IOException ioe) {
            org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                    .warn("PDF falló al aprobar TPB id={}", id, ioe);
        } catch (RuntimeException ex) {
            org.slf4j.LoggerFactory.getLogger(SalesInvoiceService.class)
                    .warn("PDF error inesperado TPB id={}", id, ex);
        }
        return validated;
    }

    /**
     * El cliente rechaza. La factura vuelve a DRAFT y la asesoría
     * puede corregirla. El número queda asignado (se reusa en el
     * siguiente intento — RD 1619/2012 no prohíbe esto siempre que la
     * factura rechazada nunca se haya entregado al destinatario).
     */
    @Transactional
    public SalesInvoice rejectByClient(String id, String reason) {
        SalesInvoice existing = get(id);
        if (!"PENDING_CLIENT_APPROVAL".equals(existing.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se rechaza una factura en PENDING_CLIENT_APPROVAL");
        }
        String userId;
        try { userId = currentUserService.require().userId(); }
        catch (Exception ex) { userId = null; }
        int aff = repository.markRejectedByClient(id, userId,
                reason == null || reason.isBlank() ? null : reason.trim());
        if (aff == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "La factura ya no está pendiente o no eres parte del acuerdo");
        }
        return get(id);
    }

    /** Lista de facturas pendientes de aprobación del cliente actual. */
    public List<SalesInvoice> listPendingClientApproval() {
        List<String> ids = repository.findPendingClientApprovalIds();
        List<SalesInvoice> out = new java.util.ArrayList<>(ids.size());
        for (String id : ids) {
            try { out.add(get(id)); }
            catch (RuntimeException ex) { /* ignora individuales */ }
        }
        return out;
    }
}
