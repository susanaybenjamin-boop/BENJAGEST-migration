package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Asientos manuales libres — la pieza fundamental que permite al asesor
 * llevar contabilidad completa más allá de las facturas. Es la base sobre
 * la que se montan:
 *
 * <ul>
 *   <li>Periodificaciones (gastos/ingresos anticipados).</li>
 *   <li>Provisiones (insolvencias, IS estimado, vacaciones devengadas).</li>
 *   <li>Ajustes de cierre (revalorizaciones, deterioros).</li>
 *   <li>Movimientos bancarios manuales (préstamos, transferencias).</li>
 *   <li>Cualquier asiento atípico del PGC.</li>
 * </ul>
 *
 * <p>Reglas de negocio:
 * <ul>
 *   <li><b>Balance obligatorio</b>: sum(Debe) == sum(Haber) hasta 1 céntimo.</li>
 *   <li><b>Mínimo 2 líneas</b>: un asiento con 1 línea es contable inválido.</li>
 *   <li><b>Fiscal guard</b>: si la fecha cae en LOCKED/CLOSED, lanza 409.</li>
 *   <li><b>Cuentas activas</b>: las account_id referenciadas deben existir,
 *       pertenecer a la empresa actual y estar activas.</li>
 *   <li><b>Idempotencia de número</b>: entry_number se asigna desde el
 *       servidor, no lo aporta el cliente. Race conditions toleradas para
 *       el caso 95% (PYMES) — el slice contable serio usará secuencia
 *       reservada FOR UPDATE.</li>
 * </ul>
 *
 * <p>Estados:
 * <ul>
 *   <li>{@code DRAFT}: editable, sin efecto en libros oficiales.</li>
 *   <li>{@code POSTED}: validado, computa en libros. Solo se puede anular
 *       con un asiento de signo opuesto (no se borra).</li>
 *   <li>{@code VOIDED}: anulado. La línea original queda visible pero
 *       no computa en saldos.</li>
 * </ul>
 *
 * <p>Trazabilidad: source_type=null para distinguirlos de los asientos
 * auto-generados (SALES_INVOICE/PURCHASE_INVOICE/YEAR_CLOSE_*).
 */
@Service
public class ManualJournalEntryService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(ManualJournalEntryService.class);

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final FiscalYearGuardService fiscalGuard;
    private final TerceroAccountResolverService terceroResolver;
    private final AccountingLearningService learning;
    private final com.benjagest.backend.accounting.importpdf.ImportedPdfStorageService pdfStorage;
    private final com.benjagest.backend.purchases.pdfimport.PdfTextExtractor pdfTextExtractor;
    private final com.benjagest.backend.purchases.pdfimport.InvoiceFieldsExtractor invoiceExtractor;

    public ManualJournalEntryService(JdbcTemplate jdbcTemplate,
                                       TenantContext tenantContext,
                                       CurrentUserService currentUserService,
                                       FiscalYearGuardService fiscalGuard,
                                       TerceroAccountResolverService terceroResolver,
                                       AccountingLearningService learning,
                                       com.benjagest.backend.accounting.importpdf.ImportedPdfStorageService pdfStorage,
                                       com.benjagest.backend.purchases.pdfimport.PdfTextExtractor pdfTextExtractor,
                                       com.benjagest.backend.purchases.pdfimport.InvoiceFieldsExtractor invoiceExtractor) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.fiscalGuard = fiscalGuard;
        this.terceroResolver = terceroResolver;
        this.learning = learning;
        this.pdfStorage = pdfStorage;
        this.pdfTextExtractor = pdfTextExtractor;
        this.invoiceExtractor = invoiceExtractor;
    }

    /**
     * Actualiza solo el concepto de un asiento. Pensado para que el
     * panel de avisos pueda corregir un nº de factura olvidado.
     */
    public int updateConcept(String entryId, String concept) {
        if (concept == null) return 0;
        String c = concept.length() > 240 ? concept.substring(0, 240) : concept;
        String companyId = tenantContext.getCurrentCompanyId();
        return jdbcTemplate.update("""
                UPDATE journal_entries
                   SET concept = ?
                 WHERE id = ? AND company_id = ?
                """, c, entryId, companyId);
    }

    /**
     * Re-ejecuta el extractor sobre el PDF asociado a un asiento.
     * Devuelve los campos extraídos como Map para que la UI los
     * proponga como edición — NO actualiza el asiento aquí.
     */
    public java.util.Map<String, Object> reExtractFromPdf(String entryId) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<String> paths = jdbcTemplate.query("""
                SELECT source_pdf_path FROM journal_entries
                 WHERE id = ? AND company_id = ? LIMIT 1
                """, (rs, n) -> rs.getString("source_pdf_path"), entryId, companyId);
        if (paths.isEmpty() || paths.get(0) == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,
                    "Este asiento no tiene PDF asociado");
        }
        byte[] pdf;
        try {
            pdf = pdfStorage.read(paths.get(0));
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "No se pudo leer el PDF: " + e.getMessage());
        }
        try {
            var layout = pdfTextExtractor.extractLayout(pdf);
            var result = invoiceExtractor.extractFromLayout(layout, pdf);
            java.util.Map<String, Object> out = new java.util.LinkedHashMap<>();
            out.put("invoiceNumber", result.invoiceNumber());
            out.put("invoiceDate", result.invoiceDate() == null ? null
                    : result.invoiceDate().toString());
            out.put("supplierName", result.supplierName());
            out.put("emitterNif", result.emitterNif());
            out.put("receiverName", result.receiverName());
            out.put("receiverNif", result.receiverNif());
            out.put("baseAmount", result.baseAmount());
            out.put("vatAmount", result.vatAmount());
            out.put("totalAmount", result.totalAmount());
            return out;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "Error al re-extraer: " + e.getMessage());
        }
    }

    // ====================================================================
    //  Crear / actualizar / postear
    // ====================================================================

    @Transactional
    /**
     * Slice 3O — Variante de createDraft pensada para que los
     * recurrentes JOURNAL_ENTRY marquen el asiento con
     * {@code auto_proposed=TRUE} y un {@code source_type} reconocible.
     * Sin esto el sub-tab "Por validar" de Contabilidad (que filtra
     * por autoProposed=TRUE) no mostraba los DRAFT generados por
     * el cron y el asesor pensaba que no se había creado nada.
     *
     * @param sourceTypeTag  marca de origen (ej. "RECURRING_TASK")
     * @param sourceId       id de la tarea recurrente que lo generó
     */
    public ManualEntryView createDraftAutoProposed(ManualEntryRequest req,
                                                     String sourceTypeTag,
                                                     String sourceId) {
        return createDraftInternal(req, true, sourceTypeTag, sourceId);
    }

    public ManualEntryView createDraft(ManualEntryRequest req) {
        return createDraftInternal(req, false, null, null);
    }

    /**
     * IMP-H — asiento importado del diario historico CONTENDO. Entra ya como
     * POSTED (los datos historicos vienen cuadrados y revisados, no requieren
     * validacion manual) con una marca de origen ({@code sourceTypeTag}:
     * SALES_INVOICE / PURCHASE_INVOICE / DUE_DATE_PAYMENT / HISTORICAL_IMPORT)
     * y {@code auto_proposed=FALSE} (no es una propuesta automatica: no debe
     * aparecer en el sub-tab "Por validar"). El {@code req.postNow()} debe ser
     * {@code true}; asi el asiento recibe entry_number correlativo POSTED al
     * insertarse, como cualquier asiento contabilizado.
     *
     * <p>Sin {@code @Transactional} a proposito: se invoca SIEMPRE dentro de la
     * transaccion de {@code ContendoImportService.importDiario}, que procesa
     * asiento por asiento con try/catch. Un boundary transaccional anidado
     * marcaria la transaccion como rollback-only ante el primer asiento que
     * fallara — aunque el import lo capturase — tirando el lote entero. Igual
     * que {@link #createDraft}.
     */
    public ManualEntryView createImportedPosted(ManualEntryRequest req,
                                                 String sourceTypeTag,
                                                 String sourceId) {
        return createDraftInternal(req, false, sourceTypeTag, sourceId);
    }

    private ManualEntryView createDraftInternal(ManualEntryRequest req,
                                                  boolean autoProposed,
                                                  String sourceTypeTag,
                                                  String sourceId) {
        validateRequest(req);
        String companyId = tenantContext.getCurrentCompanyId();
        fiscalGuard.requireOpenForDate(req.entryDate(), "crear asiento contable");

        String fiscalYearId = resolveFiscalYearId(companyId, req.entryDate());

        // entry_number: si entra en POSTED directamente, asignar; si entra
        // en DRAFT, dejar NULL hasta que se valide. Así el Diario solo
        // numera los POSTED y van en orden de validación, sin huecos.
        boolean postingNow = req.postNow();
        Integer entryNumber = postingNow
                ? nextPostedEntryNumber(companyId, fiscalYearId)
                : null;
        String entryId = UUID.randomUUID().toString();
        String userId = safeUserId();

        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE, ?, ?)
                """,
                entryId, companyId, fiscalYearId, entryNumber,
                Date.valueOf(req.entryDate()),
                truncate(req.concept(), 240),
                sourceTypeTag, sourceId,
                postingNow ? "POSTED" : "DRAFT",
                autoProposed,
                userId);
        insertLines(entryId, req.lines(), companyId);
        return get(entryId);
    }

    /**
     * Reemplaza completamente las líneas y la cabecera de un asiento DRAFT.
     * No permite editar asientos POSTED — para corregirlos hay que anular
     * y crear uno nuevo (norma contable básica).
     */
    @Transactional
    public ManualEntryView updateDraft(String entryId, ManualEntryRequest req) {
        validateRequest(req);
        ManualEntryView current = get(entryId);
        if (!"DRAFT".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se pueden editar asientos en DRAFT. Para corregir uno POSTED, créa un asiento de signo opuesto o usa la opción Anular.");
        }
        fiscalGuard.requireOpenForDate(req.entryDate(), "modificar asiento contable");

        String companyId = tenantContext.getCurrentCompanyId();
        // DUP-VALIDAR fix (2026-07-10): el modal del editor valida por ESTE
        // camino (postNow=true pone POSTED directo y el post() posterior ya
        // no comprueba nada) — Benjamin validó el duplicado de Loren sin
        // aviso. El chequeo de duplicados corre en TODOS los caminos a POSTED.
        if (req.postNow()) {
            checkDuplicateExpense(entryId, companyId);
        }
        String newFiscalYearId = resolveFiscalYearId(companyId, req.entryDate());

        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET entry_date = ?, concept = ?,
                       fiscal_year_id = ?,
                       status = ?
                 WHERE id = ? AND company_id = ?
                """,
                Date.valueOf(req.entryDate()), truncate(req.concept(), 240),
                newFiscalYearId,
                req.postNow() ? "POSTED" : "DRAFT",
                entryId, companyId);

        jdbcTemplate.update("""
                DELETE FROM journal_entry_lines WHERE journal_entry_id = ?
                """, entryId);
        insertLines(entryId, req.lines(), companyId);
        return get(entryId);
    }

    /**
     * Valida un lote de asientos DRAFT pasándolos a POSTED. Ideal para
     * el flujo "el sistema auto-propuso 20 asientos esta semana, voy a
     * validar los 10 que ya estaban bien": el asesor marca varios y
     * dispara el endpoint batch.
     *
     * <p>Recorre cada id y reusa {@link #post(String)}. Si uno falla
     * (fecha en periodo CLOSED, validaciones de cuenta, etc.) lo cuenta
     * como error y sigue con los demás — la respuesta lleva el detalle
     * por id.
     */
    @Transactional
    public BatchPostResult postBatch(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new BatchPostResult(0, 0, 0, 0, List.of());
        }
        int total = ids.size();
        int posted = 0, skipped = 0, errors = 0;
        java.util.List<BatchPostItem> items = new java.util.ArrayList<>();
        for (String id : ids) {
            try {
                ManualEntryView cur = get(id);
                if (!"DRAFT".equals(cur.status())) {
                    skipped++;
                    items.add(new BatchPostItem(id, "SKIPPED",
                            "status=" + cur.status(), cur.entryNumber()));
                    continue;
                }
                ManualEntryView posted_ = post(id);
                posted++;
                items.add(new BatchPostItem(id, posted_.status(), null,
                        posted_.entryNumber()));
            } catch (Exception ex) {
                errors++;
                items.add(new BatchPostItem(id, "ERROR",
                        ex.getMessage() == null ? ex.toString() : ex.getMessage(),
                        0));
            }
        }
        return new BatchPostResult(total, posted, skipped, errors, items);
    }

    public record BatchPostRequest(List<String> ids) {}
    public record BatchPostItem(
            String id, String status, String message, int entryNumber
    ) {}
    public record BatchPostResult(
            int total, int posted, int skipped, int errors,
            List<BatchPostItem> items
    ) {}

    /** Pasa el asiento de DRAFT a POSTED. */
    @Transactional
    public ManualEntryView post(String entryId) {
        return post(entryId, false);
    }

    /**
     * DUP-VALIDAR (2026-07-10, pedido Benjamin tras duplicar Talleres Loren):
     * al validar el asiento de un gasto, comprobar que NO exista otro gasto
     * POSTED del mismo proveedor con la MISMA base y el MISMO total (el IVA
     * puede diferir — el caso real venía de un IVA mal tecleado). Si lo hay,
     * 409 con marcador estructurado "DUPLICADO|deleteId=<id>|<detalle>": la
     * UI pregunta al usuario y puede eliminar el que NO esté incluido en una
     * declaración presentada, o validar igualmente con {@code force}.
     */
    @Transactional
    public ManualEntryView post(String entryId, boolean force) {
        ManualEntryView current = get(entryId);
        if ("POSTED".equals(current.status())) return current;
        if ("VOIDED".equals(current.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "El asiento está anulado, no se puede postear.");
        }
        fiscalGuard.requireOpenForDate(current.entryDate(), "validar asiento contable");
        String companyId = tenantContext.getCurrentCompanyId();
        if (!force) {
            checkDuplicateExpense(entryId, companyId);
        }

        // Asignar entry_number AHORA — el siguiente correlativo entre los
        // POSTED del fiscal year. Así el Diario queda en orden de
        // validación, sin huecos vacíos por DRAFTs intermedios.
        int newNumber = nextPostedEntryNumber(companyId, current.fiscalYearId());
        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'POSTED', reviewed = TRUE, entry_number = ?
                 WHERE id = ? AND company_id = ?
                """, newNumber, entryId, companyId);
        // GAS-7: sincronizar el gasto enlazado. Al validar en "Por validar" el
        // asiento de una factura recibida, marcar tambien el gasto (purchase_
        // invoices) como POSTED — antes quedaban descuadrados (asiento POSTED,
        // gasto DRAFT). Solo aplica a asientos con source_type=PURCHASE_INVOICE.
        jdbcTemplate.update("""
                UPDATE purchase_invoices pi
                  JOIN journal_entries je ON je.source_id = pi.id
                   SET pi.status = 'POSTED'
                 WHERE je.id = ?
                   AND je.company_id = ?
                   AND je.source_type = 'PURCHASE_INVOICE'
                   AND pi.company_id = ?
                   AND pi.status <> 'POSTED'
                """, entryId, companyId, companyId);
        return get(entryId);
    }

    /**
     * DUP-VALIDAR — busca otro gasto POSTED del mismo proveedor con misma
     * base y total que el gasto del asiento a validar. Determina cuál de los
     * dos está "incluido en una declaración presentada" (existía ANTES de la
     * última presentación PRESENTED/PAID de su periodo): ese se conserva; el
     * otro es el candidato a eliminar. Lanza 409 con marcador parseable.
     */
    private void checkDuplicateExpense(String entryId, String companyId) {
        List<java.util.Map<String, Object>> src = jdbcTemplate.queryForList("""
                SELECT p.id, p.supplier_nif, p.supplier_name, p.invoice_number,
                       p.base_amount, p.vat_amount, p.total_amount,
                       p.invoice_date, p.created_at
                  FROM journal_entries je
                  JOIN purchase_invoices p ON p.id = je.source_id
                 WHERE je.id = ? AND je.company_id = ?
                   AND je.source_type = 'PURCHASE_INVOICE'
                """, entryId, companyId);
        if (src.isEmpty()) return; // no es asiento de gasto
        var mine = src.get(0);
        // Condiciones AJUSTADAS al caso real de Benjamin (2026-07-10): el
        // duplicado viejo venía de un alta manual con el IVA mal tecleado —
        // NIF vacío y BASE distinta, pero MISMO TOTAL y MISMA FECHA. Regla:
        //   mismo TOTAL  +  (misma fecha O mismo nº)  +  NIF igual o AUSENTE
        // La base/IVA pueden diferir (es justo el error de importación). Dos
        // compras legítimas con nº y fecha distintos siguen sin avisar.
        List<java.util.Map<String, Object>> dups = jdbcTemplate.queryForList("""
                SELECT p.id, p.invoice_number, p.vat_amount, p.invoice_date, p.created_at
                  FROM purchase_invoices p
                 WHERE p.company_id = ? AND p.id <> ?
                   AND p.status = 'POSTED'
                   AND p.total_amount = ?
                   AND (p.supplier_nif IS NULL OR ? IS NULL OR p.supplier_nif = ?)
                   AND ((p.invoice_number IS NOT NULL AND p.invoice_number <> ''
                         AND UPPER(p.invoice_number) = UPPER(COALESCE(?, '')))
                        OR p.invoice_date = ?)
                 ORDER BY p.created_at LIMIT 1
                """, companyId, mine.get("id"), mine.get("total_amount"),
                mine.get("supplier_nif"), mine.get("supplier_nif"),
                mine.get("invoice_number"), mine.get("invoice_date"));
        if (dups.isEmpty()) return;
        var other = dups.get(0);
        boolean mineIncluded = includedInPresentedFiling(companyId,
                (java.sql.Date) mine.get("invoice_date"), (java.sql.Timestamp) mine.get("created_at"));
        boolean otherIncluded = includedInPresentedFiling(companyId,
                (java.sql.Date) other.get("invoice_date"), (java.sql.Timestamp) other.get("created_at"));
        // Se elimina el que NO esté en modelos. Si AMBOS parecen incluidos
        // (caso real Benjamin 2026-07-10: marcar el trimestre como PAGADO
        // renueva updated_at del modelo y la heurística temporal ve a los
        // dos como "anteriores a la presentación"), gana la regla simple:
        // se CONSERVA la copia MÁS ANTIGUA (la única que pudo entrar en la
        // declaración) y se propone eliminar la más nueva. Siempre con
        // confirmación del usuario.
        String deleteId;
        if (otherIncluded && !mineIncluded) deleteId = String.valueOf(mine.get("id"));
        else if (mineIncluded && !otherIncluded) deleteId = String.valueOf(other.get("id"));
        else deleteId = newerOf(mine.get("id"), (java.sql.Timestamp) mine.get("created_at"),
                other.get("id"), (java.sql.Timestamp) other.get("created_at"));
        String detalle = String.format(
                "Posible gasto DUPLICADO de %s (fra. %s): base %s y total %s idénticos a otro gasto "
                + "ya validado (IVA de este: %s · IVA del existente: %s). %s",
                mine.get("supplier_name") == null ? mine.get("supplier_nif") : mine.get("supplier_name"),
                mine.get("invoice_number") == null ? "s/n" : mine.get("invoice_number"),
                mine.get("base_amount"), mine.get("total_amount"),
                mine.get("vat_amount"), other.get("vat_amount"),
                deleteId.isEmpty()
                        ? "Ambos están incluidos en declaraciones presentadas: revisa a mano."
                        : "Se propone eliminar el que NO está en una declaración presentada.");
        throw new ResponseStatusException(HttpStatus.CONFLICT,
                "DUPLICADO|deleteId=" + deleteId + "|" + detalle);
    }

    /**
     * DUP-SCAN (2026-07-10, Benjamin) — barrido de gastos DUPLICADOS ya
     * POSTED (por si alguno se coló antes del chequeo al validar). Mismas
     * condiciones que checkDuplicateExpense; devuelve por cada pareja el
     * candidato a eliminar (el NO incluido en declaración presentada).
     */
    public List<java.util.Map<String, Object>> findExpenseDuplicates() {
        String companyId = tenantContext.getCurrentCompanyId();
        List<java.util.Map<String, Object>> pairs = jdbcTemplate.queryForList("""
                SELECT a.id AS id_a, a.created_at AS ca, a.invoice_date AS da,
                       b.id AS id_b, b.created_at AS cb, b.invoice_date AS db,
                       a.supplier_name, a.invoice_number, a.base_amount,
                       a.total_amount, a.vat_amount AS vat_a, b.vat_amount AS vat_b
                  FROM purchase_invoices a
                  JOIN purchase_invoices b
                    ON b.company_id = a.company_id AND b.id > a.id
                   AND b.status = 'POSTED' AND a.status = 'POSTED'
                   AND (b.supplier_nif = a.supplier_nif
                        OR a.supplier_nif IS NULL OR b.supplier_nif IS NULL)
                   AND b.total_amount = a.total_amount
                   AND (UPPER(COALESCE(b.invoice_number,'')) = UPPER(COALESCE(a.invoice_number,'!'))
                        OR b.invoice_date = a.invoice_date)
                 WHERE a.company_id = ?
                 LIMIT 20
                """, companyId);
        List<java.util.Map<String, Object>> out = new java.util.ArrayList<>();
        for (var p : pairs) {
            boolean aInc = includedInPresentedFiling(companyId,
                    (java.sql.Date) p.get("da"), (java.sql.Timestamp) p.get("ca"));
            boolean bInc = includedInPresentedFiling(companyId,
                    (java.sql.Date) p.get("db"), (java.sql.Timestamp) p.get("cb"));
            String deleteId;
            if (aInc && !bInc) deleteId = String.valueOf(p.get("id_b"));
            else if (bInc && !aInc) deleteId = String.valueOf(p.get("id_a"));
            else deleteId = newerOf(p.get("id_a"), (java.sql.Timestamp) p.get("ca"),
                    p.get("id_b"), (java.sql.Timestamp) p.get("cb"));
            out.add(java.util.Map.of(
                    "deleteId", deleteId,
                    "detail", String.format(
                            "Gasto duplicado de %s (fra. %s): base %s, total %s (IVA %s vs %s).",
                            p.get("supplier_name"), p.get("invoice_number") == null ? "s/n" : p.get("invoice_number"),
                            p.get("base_amount"), p.get("total_amount"),
                            p.get("vat_a"), p.get("vat_b"))));
        }
        return out;
    }

    /** De una pareja de duplicados, el id de la copia MÁS NUEVA (la que se propone eliminar). */
    private static String newerOf(Object idA, java.sql.Timestamp ca,
                                   Object idB, java.sql.Timestamp cb) {
        if (ca == null) return String.valueOf(idA);
        if (cb == null) return String.valueOf(idB);
        return ca.after(cb) ? String.valueOf(idA) : String.valueOf(idB);
    }

    /** ¿El gasto existía ANTES de la última presentación de su periodo? */
    private boolean includedInPresentedFiling(String companyId,
                                               java.sql.Date invoiceDate,
                                               java.sql.Timestamp createdAt) {
        if (invoiceDate == null) return false;
        java.time.LocalDate d = invoiceDate.toLocalDate();
        int q = (d.getMonthValue() - 1) / 3 + 1;
        java.sql.Timestamp lastPresented = jdbcTemplate.query("""
                SELECT MAX(updated_at) FROM tax_filings
                 WHERE company_id = ? AND status IN ('PRESENTED', 'PAID')
                   AND period_year = ?
                   AND (period_quarter IS NULL OR period_quarter >= ?)
                """, rs -> rs.next() ? rs.getTimestamp(1) : null,
                companyId, d.getYear(), q);
        if (lastPresented == null) return false;
        return createdAt == null || createdAt.before(lastPresented);
    }

    /**
     * Borra FÍSICAMENTE asientos importados de PDF (duplicados). Solo
     * acepta ids cuyo {@code source_type IN ('SALES_PDF_IMPORT',
     * 'PURCHASE_INVOICE')} y cuyo periodo fiscal NO está cerrado.
     *
     * <p>Por qué eliminación física (no voiding):
     * un duplicado es un error operativo, no una rectificativa
     * contable. VOID + contraasiento contaminaría el Libro Diario
     * con eventos que no corresponden a la realidad económica. El
     * registro de auditoría queda en el log de la asesoría (qué
     * usuario borró qué asiento, cuándo).
     *
     * @return número de asientos efectivamente borrados.
     */
    @Transactional
    public int deleteImportedByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) return 0;
        String companyId = tenantContext.getCurrentCompanyId();
        int deleted = 0;
        for (String id : ids) {
            if (id == null || id.isBlank()) continue;
            // Solo borramos si es de origen importado.
            List<Object[]> srcs = jdbcTemplate.query("""
                    SELECT source_type, entry_date FROM journal_entries
                     WHERE id = ? AND company_id = ?
                     LIMIT 1
                    """, (rs, n) -> new Object[]{rs.getString("source_type"),
                            rs.getDate("entry_date")}, id, companyId);
            if (srcs.isEmpty()) continue;
            String src = (String) srcs.get(0)[0];
            if (!"SALES_PDF_IMPORT".equals(src)
                    && !"PURCHASE_INVOICE".equals(src)) continue;
            // LOCK (2026-07-07): tampoco los importados se borran si su
            // fecha cae en un ejercicio LOCKED/CLOSED. Antes este era el
            // único camino de borrado de asientos SIN comprobar el cierre.
            java.sql.Date entryDate = (java.sql.Date) srcs.get(0)[1];
            if (entryDate != null) {
                fiscalGuard.requireOpenForDate(entryDate.toLocalDate(),
                        "borrar este asiento importado");
            }
            // Borrar líneas + asiento (CASCADE manual por seguridad).
            jdbcTemplate.update(
                    "DELETE FROM journal_entry_lines WHERE journal_entry_id = ?", id);
            int n = jdbcTemplate.update(
                    "DELETE FROM journal_entries WHERE id = ? AND company_id = ?",
                    id, companyId);
            deleted += n;
        }
        // NOTA: NO renumeramos entry_number tras borrar. Los huecos
        // son normales contablemente (auditoría: el asiento Nº 5 que
        // se eliminó deja constancia de que existió). Renumerar
        // POSTED es mala práctica porque rompe trazabilidad con
        // copias impresas/PDFs antiguos del Libro Diario.
        // El "Nº" del listado de Ventas en UI muestra un índice
        // visual de fila (1, 2, 3…), no el entry_number real, para
        // que el asesor no vea huecos extraños sin romper la BD.
        return deleted;
    }

    /**
     * Anula un asiento POSTED creando un asiento espejo de signo opuesto
     * (contraasiento). El asiento original se marca como VOIDED para que
     * no compute en saldos, pero queda visible en el Libro Diario por
     * trazabilidad legal. El asiento NUNCA se borra.
     *
     * <p><b>ANUL-2 (2026-07-15, decisión Benjamin) — se quitó el contraasiento.</b>
     * Este método marcaba el original VOIDED <i>y además</i> creaba un
     * contraasiento POSTED de signo opuesto. Las dos cosas a la vez restan DOS
     * veces: todos los informes y saldos filtran {@code status = 'POSTED'}
     * (AEAT 303/190/347, libros...), así que un asiento VOIDED ya deja de
     * computar por sí solo; el contraasiento volvía a restar lo mismo.
     *
     * <p>Medido en ejecución antes del arreglo (BD sandbox, 2026-07-15): anular
     * un gasto duplicado de 500 € dejaba <b>600 Compras con saldo -500</b> y
     * <b>400 Proveedores con +500</b>, cuando lo correcto es 0 y 0. Era la nota
     * #1 del backlog (el Diario en -X) manifestándose en la vía de los asientos
     * manuales. Llevaba ahí desde siempre y no saltó nunca porque ningún botón
     * llamaba a este método — ASI-2 fue el primero (y por eso se probó).
     *
     * <p>Modelo elegido: <b>VOIDED = anulado, visible, no computa</b>, que es lo
     * que el resto del código ya asumía. Nada se borra y el asiento sigue en el
     * Libro Diario con todas sus líneas y su marca. Ojo con la tentación de
     * "devolver" el contraasiento: sin quitar antes el VOIDED, vuelve el -X.
     *
     * <p>No confundir con {@code SalesInvoiceService.voidValidated} (ANUL-1):
     * allí SÍ hay dos asientos vivos, porque la rectificativa es un documento
     * real con su propio asiento negativo. Aquí no hay segundo documento: el
     * asiento simplemente no debió existir.
     */
    @Transactional
    public ManualEntryView voidEntry(String entryId, String reason) {
        ManualEntryView original = get(entryId);
        if ("VOIDED".equals(original.status())) return original;
        fiscalGuard.requireOpenForDate(original.entryDate(), "anular asiento contable");

        String companyId = tenantContext.getCurrentCompanyId();

        // El motivo se guarda en el propio concepto: es lo único que explica,
        // a un inspector o a nosotros dentro de seis meses, por qué se anuló.
        String newConcept = truncate(safe(original.concept())
                + " [ANULADO" + (reason == null || reason.isBlank() ? "" : " — " + reason) + "]", 240);

        jdbcTemplate.update("""
                UPDATE journal_entries
                   SET status = 'VOIDED', concept = ?
                 WHERE id = ? AND company_id = ?
                """, newConcept, entryId, companyId);

        return get(entryId);
    }

    /**
     * ASI-4 (2026-07-15, decisión Benjamin) — Reclasifica la cuenta de UNA
     * línea de un asiento ya validado, por la vía legal: anular + reasentar.
     * Es lo que hacen A3/Sage/ContaPlus y lo único compatible con la
     * inalterabilidad de un asiento POSTED, que no se edita ni se borra.
     *
     * <p>Caso que lo motiva: una venta contabilizada en "700 Ventas de
     * mercaderías" cuando en realidad era "705 Prestaciones de servicios".
     * ASI-3 impide que vuelva a pasar en facturas nuevas, pero no arregla las
     * ya contabilizadas — para esas está esto.
     *
     * <p>Resultado (2 asientos, ninguno borrado):
     * <ol>
     *   <li>el original pasa a VOIDED (deja de contar, sigue visible entero);</li>
     *   <li>nace el asiento CORRECTO, POSTED, idéntico salvo la cuenta.</li>
     * </ol>
     * Neto en los libros: el importe queda donde debía estar desde el principio,
     * una sola vez. Verificado en ejecución (ver ANUL-2 en {@link #voidEntry}:
     * añadir aquí un contraasiento haría que el importe se restara dos veces).
     *
     * <p>El asiento nuevo conserva {@code source_type}/{@code source_id}, así
     * que la factura sigue enlazada con su asiento vivo. Eso deja DOS asientos
     * con el mismo source_id (el VOIDED y el nuevo): los resolvers que hacen
     * "el asiento de esta factura" filtran {@code status <> 'VOIDED'} desde
     * este mismo bloque de cambios — sin eso reescribirían el muerto.
     *
     * @param saveRule si true, aprende "para este tercero, esta cuenta" y las
     *                 próximas facturas suyas nacen ya bien.
     */
    @Transactional
    public ManualEntryView reclassifyPostedAccount(String entryId, String lineId,
                                                     String newAccountId, String reason,
                                                     boolean saveRule) {
        ManualEntryView original = get(entryId);
        if (!"POSTED".equals(original.status())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Solo se reclasifica un asiento VALIDADO. Este está en "
                    + original.status() + ": si es un borrador, edítalo directamente.");
        }
        if (reason == null || reason.isBlank()) {
            throw bad("El motivo es obligatorio: queda en el concepto del "
                    + "contraasiento y es lo que explica por qué existen los dos asientos.");
        }
        fiscalGuard.requireOpenForDate(original.entryDate(), "reclasificar este asiento");

        String companyId = tenantContext.getCurrentCompanyId();
        String userId = safeUserId();

        // La línea a reclasificar tiene que ser de ESTE asiento (si no, un id
        // de otro asiento colaría un cambio silencioso en un tercero).
        ManualEntryLine target = original.lines().stream()
                .filter(l -> l.id().equals(lineId))
                .findFirst()
                .orElseThrow(() -> bad("La línea " + lineId + " no es de este asiento."));

        // La cuenta destino tiene que existir, estar activa y ser de la empresa.
        Integer ok = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM accounting_accounts
                 WHERE id = ? AND company_id = ? AND active = TRUE
                """, Integer.class, newAccountId, companyId);
        if (ok == null || ok == 0) {
            throw bad("La cuenta destino no existe o no está activa en esta empresa.");
        }
        if (newAccountId.equals(target.accountId())) {
            throw bad("La línea ya está en esa cuenta: no hay nada que reclasificar.");
        }

        // 1) Anular el original. Reutilizamos voidEntry: la misma vía que el
        // botón "Anular" del Diario, un solo sitio que sepa anular. Estamos ya
        // dentro de la transacción de este método, así que el todo-o-nada
        // cubre los dos asientos.
        voidEntry(entryId, "reclasificación de cuenta — " + reason);

        // 2) El asiento correcto: mismo contenido, misma fecha, misma factura
        // de origen; solo cambia la cuenta de la línea reclasificada.
        String newId = UUID.randomUUID().toString();
        int newNumber = nextEntryNumber(companyId, original.fiscalYearId());
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', TRUE, FALSE, ?)
                """,
                newId, companyId, original.fiscalYearId(), newNumber,
                Date.valueOf(original.entryDate()),
                truncate(safe(original.concept()) + " (reclasificado — " + reason + ")", 240),
                original.sourceType(), original.sourceId(), userId);

        for (ManualEntryLine ln : original.lines()) {
            String accountId = ln.id().equals(lineId) ? newAccountId : ln.accountId();
            jdbcTemplate.update("""
                    INSERT INTO journal_entry_lines (
                        id, journal_entry_id, account_id, description, debit, credit
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), newId, accountId,
                    ln.description(), ln.debit(), ln.credit());
        }

        if (saveRule) {
            learnFromReclassify(original, target, newAccountId, userId);
        }
        return get(newId);
    }

    /**
     * Registra la corrección para que el chain de cuenta (regla aprendida →
     * histórico → classifier) proponga la cuenta buena la próxima vez.
     *
     * <p>Reutiliza {@code AccountingLearningService.recordCorrection}, el mismo
     * camino que ya usa el botón "Crear regla" de gastos: registra el evento
     * ACCOUNT_CORRECTED y, si hay NIF del tercero, crea la regla
     * INCOME_ACCOUNT_BY_CUSTOMER_NIF. Es idempotente (no duplica reglas).
     *
     * <p>Best-effort: si no se puede aprender (el asiento no viene de una
     * factura, o esta ya no está), la reclasificación NO se cae por eso — el
     * asiento corregido vale por sí solo y es lo que el usuario pidió.
     */
    private void learnFromReclassify(ManualEntryView original, ManualEntryLine oldLine,
                                       String newAccountId, String userId) {
        try {
            String newCode = jdbcTemplate.query("""
                    SELECT code FROM accounting_accounts WHERE id = ? LIMIT 1
                    """, (rs, n) -> rs.getString("code"), newAccountId)
                    .stream().findFirst().orElse(null);

            // NIF del tercero: sin él, recordCorrection registra la corrección
            // pero no crea regla (no tiene criterio con el que matchear).
            String customerNif = null;
            if ("SALES_INVOICE".equals(original.sourceType()) && original.sourceId() != null) {
                customerNif = jdbcTemplate.query("""
                        SELECT c.tax_identifier
                          FROM sales_invoices si
                          JOIN customers c ON c.id = si.customer_id
                         WHERE si.id = ? AND si.company_id = ?
                         LIMIT 1
                        """, (rs, n) -> rs.getString("tax_identifier"),
                        original.sourceId(), original.companyId())
                        .stream().findFirst().orElse(null);
            }

            learning.recordCorrection(new AccountingLearningService.CorrectionRequest(
                    original.id(), oldLine.id(),
                    oldLine.accountId(), newAccountId, newCode,
                    null, customerNif, null, null,
                    "Reclasificación ASI-4 del asiento " + original.entryNumber()), userId);
        } catch (Exception ex) {
            log.warn("ASI-4: no se pudo registrar el aprendizaje de la reclasificación "
                    + "del asiento {} (la reclasificación SÍ se aplicó): {}",
                    original.id(), ex.getMessage());
        }
    }

    // ====================================================================
    //  Consulta
    // ====================================================================

    public ManualEntryView get(String entryId) {
        String companyId = tenantContext.getCurrentCompanyId();
        List<ManualEntryHeader> headers = jdbcTemplate.query("""
                SELECT je.id, je.company_id, je.fiscal_year_id, je.entry_number,
                       je.entry_date, je.concept, je.source_type, je.source_id,
                       je.status, je.reviewed, je.auto_proposed,
                       je.proposed_confidence, je.created_by,
                       je.created_at, je.updated_at
                  FROM journal_entries je
                 WHERE je.id = ? AND je.company_id = ?
                """, this::mapHeader, entryId, companyId);
        if (headers.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Asiento no encontrado");
        }
        ManualEntryHeader h = headers.get(0);
        List<ManualEntryLine> lines = jdbcTemplate.query("""
                SELECT l.id, l.account_id, a.code AS account_code, a.name AS account_name,
                       l.description, l.debit, l.credit
                  FROM journal_entry_lines l
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE l.journal_entry_id = ?
                 ORDER BY l.created_at, l.id
                """,
                (rs, n) -> new ManualEntryLine(
                        rs.getString("id"), rs.getString("account_id"),
                        rs.getString("account_code"), rs.getString("account_name"),
                        rs.getString("description"),
                        rs.getBigDecimal("debit"), rs.getBigDecimal("credit")),
                entryId);
        return new ManualEntryView(
                h.id(), h.companyId(), h.fiscalYearId(), h.entryNumber(),
                h.entryDate(), h.concept(), h.sourceType(), h.sourceId(),
                h.status(), h.reviewed(), h.autoProposed(),
                h.proposedConfidence(), h.createdBy(),
                h.createdAt(), h.updatedAt(), lines);
    }

    // ====================================================================
    //  Validación
    // ====================================================================

    private void validateRequest(ManualEntryRequest req) {
        if (req.entryDate() == null) {
            throw bad("La fecha del asiento es obligatoria.");
        }
        if (req.concept() == null || req.concept().isBlank()) {
            throw bad("El concepto del asiento es obligatorio.");
        }
        if (req.lines() == null || req.lines().size() < 2) {
            throw bad("Un asiento contable debe tener al menos 2 líneas.");
        }
        BigDecimal totalDebit = BigDecimal.ZERO;
        BigDecimal totalCredit = BigDecimal.ZERO;
        int idx = 0;
        for (LineRequest ln : req.lines()) {
            idx++;
            if (ln.accountId() == null || ln.accountId().isBlank()) {
                throw bad("Línea " + idx + ": falta la cuenta.");
            }
            BigDecimal d = ln.debit() == null ? BigDecimal.ZERO : ln.debit();
            BigDecimal c = ln.credit() == null ? BigDecimal.ZERO : ln.credit();
            if (d.signum() < 0 || c.signum() < 0) {
                throw bad("Línea " + idx + ": importes negativos no permitidos. Usa la cuenta opuesta.");
            }
            if (d.signum() > 0 && c.signum() > 0) {
                throw bad("Línea " + idx + ": no puede tener Debe y Haber simultáneamente.");
            }
            if (d.signum() == 0 && c.signum() == 0) {
                throw bad("Línea " + idx + ": la línea está vacía.");
            }
            totalDebit = totalDebit.add(d);
            totalCredit = totalCredit.add(c);
        }
        if (totalDebit.subtract(totalCredit).abs().compareTo(new BigDecimal("0.01")) > 0) {
            throw bad("El asiento no cuadra: Debe=" + totalDebit + " Haber=" + totalCredit
                    + " (diferencia " + totalDebit.subtract(totalCredit) + ")");
        }
    }

    private void insertLines(String entryId, List<LineRequest> lines, String companyId) {
        // Concepto del asiento — lo usamos como nombre del tercero si la
        // UI mandó un código 4000/4300 desconocido y hay que auto-crear.
        String concept = jdbcTemplate.query("""
                SELECT concept FROM journal_entries WHERE id = ?
                """, (rs, n) -> rs.getString("concept"), entryId)
                .stream().findFirst().orElse(null);

        for (LineRequest ln : lines) {
            String accountId = resolveAccountId(ln, companyId, concept);
            BigDecimal d = ln.debit() == null ? BigDecimal.ZERO : ln.debit();
            BigDecimal c = ln.credit() == null ? BigDecimal.ZERO : ln.credit();
            jdbcTemplate.update("""
                    INSERT INTO journal_entry_lines (
                        id, journal_entry_id, account_id, description, debit, credit
                    ) VALUES (?, ?, ?, ?, ?, ?)
                    """,
                    UUID.randomUUID().toString(), entryId, accountId,
                    truncate(ln.description(), 240), d, c);
        }
    }

    /**
     * Resuelve el id de cuenta de una línea con tolerancia y autocreación:
     * <ol>
     *   <li>Si {@code accountId} es UUID válido y existe activa → úsalo.</li>
     *   <li>Si no, intenta resolver por {@code accountCode} (el código que
     *       el asesor tecleó en el combo).</li>
     *   <li>Si tampoco existe por código y el código pinta a tercero
     *       (4000xxx proveedor / 4300xxx cliente) → llama al
     *       {@link TerceroAccountResolverService} con el concepto del
     *       asiento como nombre del tercero. Esto autocrea
     *       4000NNN / 4300NNN sin pedir nada al asesor.</li>
     *   <li>Si no es tercero y no existe → 400 BAD_REQUEST con mensaje
     *       claro.</li>
     * </ol>
     */
    private String resolveAccountId(LineRequest ln, String companyId, String concept) {
        // 1. Por UUID (caso normal cuando la UI ya resolvió la cuenta).
        if (ln.accountId() != null && !ln.accountId().isBlank()) {
            Integer ok = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*) FROM accounting_accounts
                     WHERE id = ? AND company_id = ? AND active = TRUE
                    """, Integer.class, ln.accountId(), companyId);
            if (ok != null && ok > 0) return ln.accountId();
        }

        // 2. Por código (el asesor tecleó "628" o "4000007").
        String rawCode = ln.accountCode() != null && !ln.accountCode().isBlank()
                ? ln.accountCode().trim()
                : (ln.accountId() != null ? ln.accountId().trim() : null);
        if (rawCode != null && !rawCode.isBlank()) {
            List<String> ids = jdbcTemplate.query("""
                    SELECT id FROM accounting_accounts
                     WHERE company_id = ? AND code = ? AND active = TRUE
                     LIMIT 1
                    """,
                    (rs, n) -> rs.getString("id"),
                    companyId, rawCode);
            if (!ids.isEmpty()) return ids.get(0);

            // 3. Si pinta a tercero, autocrear via resolver.
            if (looksLikeTerceroCode(rawCode)) {
                TerceroAccountResolverService.TerceroType type =
                        rawCode.startsWith("4000")
                                ? TerceroAccountResolverService.TerceroType.PROVEEDOR
                                : TerceroAccountResolverService.TerceroType.CLIENTE;
                // Nombre del tercero: el concepto del asiento (sin
                // "Fra. 123 - " si aparece). El resolver normaliza para
                // futuras búsquedas.
                String terceroName = guessTerceroName(concept);
                TerceroAccountResolverService.ResolvedAccount r =
                        terceroResolver.getOrCreate(type, null, terceroName);
                if (r != null && r.accountId() != null) return r.accountId();
            }
        }

        throw bad("Cuenta " + (rawCode != null ? rawCode : ln.accountId())
                + " no existe en esta empresa o no está activa.");
    }

    private static boolean looksLikeTerceroCode(String code) {
        if (code == null) return false;
        String c = code.trim();
        if (c.length() < 4) return false;
        if (!c.startsWith("4000") && !c.startsWith("4300")) return false;
        return c.chars().allMatch(Character::isDigit);
    }

    /**
     * Extrae un nombre razonable de tercero a partir del concepto del
     * asiento. Ejemplos:
     *   "Fra. 2024-001 - Mapfre" → "Mapfre"
     *   "Venta 2024-007 a Marcos SL" → "Marcos SL"
     *   "Compra Amazon EU" → "Amazon EU"
     * Si no detecta separador, devuelve el concepto completo (mejor algo
     * que nada — el asesor lo podrá renombrar después).
     */
    private static String guessTerceroName(String concept) {
        if (concept == null || concept.isBlank()) return "Tercero";
        // " - " separador habitual en Fra./Compra.
        int dash = concept.indexOf(" - ");
        if (dash > 0 && dash < concept.length() - 3) {
            return concept.substring(dash + 3).trim();
        }
        // " a " separador habitual en Venta a X.
        int aSep = concept.toLowerCase().indexOf(" a ");
        if (aSep > 0 && aSep < concept.length() - 3) {
            return concept.substring(aSep + 3).trim();
        }
        return concept.trim();
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    private String resolveFiscalYearId(String companyId, LocalDate date) {
        List<String> ids = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ?
                   AND start_date <= ?
                   AND end_date >= ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                companyId, Date.valueOf(date), Date.valueOf(date));
        if (ids.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "No existe ejercicio fiscal para la fecha " + date
                            + ". Crea uno en Configuración → Contabilidad → Ejercicios.");
        }
        return ids.get(0);
    }

    private int nextEntryNumber(String companyId, String fiscalYearId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                """, Integer.class, companyId, fiscalYearId);
        return (max == null ? 0 : max) + 1;
    }

    /**
     * Siguiente entry_number para un asiento que se está validando. Cuenta
     * TODOS los números ya asignados, sin mirar el estado; los DRAFT no
     * consumen número porque desde la V58 lo tienen NULL (se les asigna justo
     * al validar), y MAX ignora los NULL.
     *
     * <p><b>ANUL-2 (2026-07-15).</b> Antes filtraba {@code status = 'POSTED'},
     * y eso rompía en cuanto existía un asiento ANULADO: un VOIDED CONSERVA su
     * entry_number, pero al no contarlo el MAX devolvía un número ya ocupado y
     * la UK (company_id, fiscal_year_id, entry_number) reventaba con
     * "Duplicate entry". No saltaba nunca porque los asientos anulados no
     * existían en la práctica — ningún botón llamaba a voidEntry. Al arreglar
     * ANUL-2 pasaron a ser normales y el e2e lo cazó: validar una factura tras
     * anular un asiento daba HTTP 500.
     *
     * <p>Los huecos en la numeración son normales y NO se renumera (ver la nota
     * de {@code deleteImportedByIds}): el nº 5 anulado deja constancia de que
     * existió.
     */
    private int nextPostedEntryNumber(String companyId, String fiscalYearId) {
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0)
                  FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ?
                   AND entry_number IS NOT NULL
                """, Integer.class, companyId, fiscalYearId);
        return (max == null ? 0 : max) + 1;
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private static String safe(String s) { return s == null ? "" : s; }

    private static ResponseStatusException bad(String msg) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, msg);
    }

    private ManualEntryHeader mapHeader(ResultSet rs, int n) throws SQLException {
        java.sql.Timestamp ca = rs.getTimestamp("created_at");
        java.sql.Timestamp ua = rs.getTimestamp("updated_at");
        BigDecimal pc = rs.getBigDecimal("proposed_confidence");
        return new ManualEntryHeader(
                rs.getString("id"), rs.getString("company_id"),
                rs.getString("fiscal_year_id"), rs.getInt("entry_number"),
                rs.getDate("entry_date").toLocalDate(),
                rs.getString("concept"),
                rs.getString("source_type"), rs.getString("source_id"),
                rs.getString("status"), rs.getBoolean("reviewed"),
                rs.getBoolean("auto_proposed"), pc,
                rs.getString("created_by"),
                ca == null ? null : ca.toInstant(),
                ua == null ? null : ua.toInstant());
    }

    // ====================================================================
    //  DTOs públicos
    // ====================================================================

    public record ManualEntryRequest(
            LocalDate entryDate,
            String concept,
            List<LineRequest> lines,
            boolean postNow
    ) {}

    public record LineRequest(
            String accountId,
            String accountCode,
            String description,
            BigDecimal debit,
            BigDecimal credit
    ) {
        // Constructor compacto retro-compatible: si solo viene accountId.
        public LineRequest(String accountId, String description,
                            BigDecimal debit, BigDecimal credit) {
            this(accountId, null, description, debit, credit);
        }
    }

    public record ManualEntryLine(
            String id,
            String accountId,
            String accountCode,
            String accountName,
            String description,
            BigDecimal debit,
            BigDecimal credit
    ) {}

    public record ManualEntryView(
            String id, String companyId, String fiscalYearId, int entryNumber,
            LocalDate entryDate, String concept,
            String sourceType, String sourceId,
            String status, boolean reviewed, boolean autoProposed,
            BigDecimal proposedConfidence, String createdBy,
            Instant createdAt, Instant updatedAt,
            List<ManualEntryLine> lines
    ) {}

    private record ManualEntryHeader(
            String id, String companyId, String fiscalYearId, int entryNumber,
            LocalDate entryDate, String concept,
            String sourceType, String sourceId,
            String status, boolean reviewed, boolean autoProposed,
            BigDecimal proposedConfidence, String createdBy,
            Instant createdAt, Instant updatedAt
    ) {}

    /** Sentinel para futura validación cruzada con CO/CA. */
    @SuppressWarnings("unused")
    private List<Object> _sentinel() { return new ArrayList<>(); }
}
