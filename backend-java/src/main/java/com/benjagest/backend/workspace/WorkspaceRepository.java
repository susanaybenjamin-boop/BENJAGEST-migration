package com.benjagest.backend.workspace;

import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * Acceso JDBC del dashboard + modulos genericos. Hereda 27 queries que
 * Pablo escribio con el companyId hardcodeado en `currentCompanyId()` para
 * salir del paso mientras no existia TenantContext.
 *
 * Refactor 2026-06-01: cada query pasa a leer el companyId del
 * TenantContext (request-scoped, alimentado por el JWT o por el header
 * X-Company-Id en tests). El metodo `findEmployeeByPinHash` queda intacto
 * porque el PIN se busca global (no tiene companyId antes del login).
 *
 * Esto es la pieza fundacional para que las pantallas de dominio (cuando
 * lleguen facturacion real, contabilidad, etc.) sirvan a la vez al modo
 * Empresario y al modo Asesoria (un asesor con switch-company ve la
 * misma pantalla con datos de su cliente, sin codigo duplicado).
 */
@Repository
public class WorkspaceRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;

    public WorkspaceRepository(JdbcTemplate jdbcTemplate, TenantContext tenantContext) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
    }

    private String currentCompanyId() {
        return tenantContext.getCurrentCompanyId();
    }

    public Optional<PinLoginResponse> findEmployeeByPinHash(String pinHash) {
        List<PinLoginResponse> matches = jdbcTemplate.query("""
                SELECT e.id,
                       e.full_name,
                       e.company_id,
                       c.legal_name AS company_name,
                       c.company_type
                FROM employees e
                JOIN companies c ON c.id = e.company_id
                WHERE e.pin_hash = ?
                  AND e.active = TRUE
                LIMIT 1
                """,
                (rs, rowNum) -> pinLoginResponse(rs),
                pinHash
        );
        return matches.stream().findFirst();
    }

    private PinLoginResponse pinLoginResponse(ResultSet rs) throws SQLException {
        boolean internalCompany = "INTERNAL".equalsIgnoreCase(rs.getString("company_type"));
        return new PinLoginResponse(
                rs.getString("id"),
                rs.getString("full_name"),
                rs.getString("company_id"),
                rs.getString("company_name"),
                "EMPLOYEE",
                UUID.randomUUID().toString(),
                internalCompany ? "ADVISORY" : "BUSINESS",
                internalCompany ? List.of("ADVISORY", "BUSINESS") : List.of("BUSINESS")
        );
    }

    public DashboardResponse dashboard(String mode) {
        return new DashboardResponse(
                count("customers", "company_id = ? AND active = TRUE"),
                count("sales_invoices", "company_id = ?"),
                count("employees", "company_id = ? AND active = TRUE"),
                count("notifications", "company_id = ? AND status <> 'READ'"),
                amount("SELECT COALESCE(SUM(total), 0) FROM sales_invoices WHERE company_id = ?", currentCompanyId()),
                amount("SELECT COALESCE(SUM(total - paid_amount), 0) FROM sales_invoices WHERE company_id = ? AND payment_status <> 'PAID'", currentCompanyId()),
                amount("SELECT COALESCE(SUM(total_amount), 0) FROM purchase_invoices WHERE company_id = ?", currentCompanyId()),
                amount("SELECT COALESCE(SUM(net_amount), 0) FROM payrolls WHERE company_id = ?", currentCompanyId()),
                dashboardItems("""
                        SELECT invoice_number AS title,
                               CONCAT(customer.legal_name, ' - ', payment_status) AS subtitle,
                               CONCAT(FORMAT(total, 2), ' EUR') AS value
                        FROM sales_invoices invoice
                        JOIN customers customer ON customer.id = invoice.customer_id
                        WHERE invoice.company_id = ?
                        ORDER BY invoice.invoice_date DESC
                        LIMIT 5
                        """),
                dashboardItems("""
                        SELECT title,
                               COALESCE(body, '') AS subtitle,
                               severity AS value
                        FROM notifications
                        WHERE company_id = ?
                        ORDER BY created_at DESC
                        LIMIT 5
                        """),
                dashboardItems("""
                        SELECT title,
                               COALESCE(description, '') AS subtitle,
                               CAST(event_date AS CHAR) AS value
                        FROM calendar_events
                        WHERE company_id = ?
                        ORDER BY event_date
                        LIMIT 5
                        """)
        );
    }

    public ModuleSummary module(String module) {
        return module(module, "BUSINESS");
    }

    public ModuleSummary module(String module, String mode) {
        return switch (module) {
            case "customers" -> new ModuleSummary(module, moduleTitle(module, mode), customers());
            case "billing" -> new ModuleSummary(module, moduleTitle(module, mode), invoices());
            case "purchases" -> new ModuleSummary(module, moduleTitle(module, mode), purchases());
            case "labor" -> new ModuleSummary(module, moduleTitle(module, mode), labor());
            case "tax" -> new ModuleSummary(module, moduleTitle(module, mode), tax());
            case "settings" -> new ModuleSummary(module, moduleTitle(module, mode), settings());
            case "calendar" -> new ModuleSummary(module, moduleTitle(module, mode), calendar());
            default -> new ModuleSummary(module, module, List.of());
        };
    }

    private String moduleTitle(String module, String mode) {
        boolean advisory = "ADVISORY".equalsIgnoreCase(mode);
        return switch (module) {
            case "customers" -> advisory ? "Cartera de clientes" : "Clientes";
            case "billing" -> advisory ? "Facturacion de clientes" : "Facturacion";
            case "purchases" -> advisory ? "Compras y gastos revisados" : "Compras y gastos";
            case "labor" -> advisory ? "Laboral clientes" : "Laboral y fichajes";
            case "tax" -> advisory ? "Fiscal clientes" : "Fiscal";
            case "settings" -> advisory ? "Equipo de asesoria" : "Configuracion";
            case "calendar" -> advisory ? "Agenda de asesoria" : "Agenda";
            default -> module;
        };
    }

    @Transactional
    public ModuleRecord create(String module, ModuleCreateRequest request) {
        return switch (module) {
            case "customers" -> createCustomer(request);
            case "billing" -> createInvoice(request);
            // "purchases" eliminado en PURCHASES-CLEANUP-V2: la creación
            // pasa por PurchaseInvoiceService (flujo PDF + validación
            // contable). El dashboard solo lee, no escribe.
            case "labor" -> createLaborRecord(request);
            case "tax" -> createTaxFiling(request);
            case "settings" -> createEmployee(request);
            case "calendar" -> createCalendarEvent(request);
            default -> throw new IllegalArgumentException("Modulo no soportado");
        };
    }

    @Transactional
    public ModuleRecord update(String module, String recordId, ModuleCreateRequest request) {
        return switch (module) {
            case "customers" -> updateCustomer(recordId, request);
            case "billing" -> updateInvoice(recordId, request);
            // "purchases" eliminado en PURCHASES-CLEANUP-V2 (ver create).
            case "labor" -> updateLaborRecord(recordId, request);
            case "tax" -> updateTaxFiling(recordId, request);
            case "settings" -> updateEmployee(recordId, request);
            case "calendar" -> updateCalendarEvent(recordId, request);
            default -> throw new IllegalArgumentException("Modulo no soportado");
        };
    }

    @Transactional
    public void delete(String module, String recordId) {
        switch (module) {
            case "calendar" -> jdbcTemplate.update(
                    "DELETE FROM calendar_events WHERE id = ? AND company_id = ?",
                    recordId,
                    currentCompanyId()
            );
            default -> throw new IllegalArgumentException("Eliminacion no soportada para el modulo " + module);
        }
    }

    private List<ModuleRecord> customers() {
        return rows("""
                SELECT c.id,
                       c.legal_name AS nombre,
                       c.tax_identifier AS nif,
                       COALESCE(pc.full_name, '') AS contacto,
                       COALESCE(pc.email, '') AS email,
                       COALESCE(pc.phone, '') AS telefono
                FROM customers c
                LEFT JOIN customer_contacts pc ON pc.customer_id = c.id AND pc.primary_contact = TRUE
                WHERE c.company_id = ? AND c.active = TRUE
                ORDER BY c.legal_name
                LIMIT 50
                """);
    }

    private List<ModuleRecord> invoices() {
        return rows("""
                SELECT i.id,
                       i.invoice_number AS factura,
                       c.legal_name AS cliente,
                       CAST(i.invoice_date AS CHAR) AS fecha,
                       i.status AS estado,
                       i.payment_status AS cobro,
                       i.total AS total
                FROM sales_invoices i
                JOIN customers c ON c.id = i.customer_id
                WHERE i.company_id = ?
                ORDER BY i.invoice_date DESC
                LIMIT 50
                """);
    }

    private List<ModuleRecord> purchases() {
        // PURCHASES-CLEANUP-V2: ya no leemos `total`/`category`/`payment_status`
        // (columnas legacy de V2 retiradas en V45). Tampoco hacemos JOIN a
        // `suppliers` porque purchase_invoices ahora solo guarda supplier_name
        // + supplier_nif (el catálogo de suppliers no se usa todavía).
        return rows("""
                SELECT p.id,
                       COALESCE(p.invoice_number, '') AS factura,
                       COALESCE(p.supplier_name, '') AS proveedor,
                       CAST(p.invoice_date AS CHAR) AS fecha,
                       COALESCE(p.supplier_nif, '') AS nif,
                       COALESCE(p.status, '') AS estado,
                       p.total_amount AS total
                FROM purchase_invoices p
                WHERE p.company_id = ?
                ORDER BY p.invoice_date DESC
                LIMIT 50
                """);
    }

    private List<ModuleRecord> labor() {
        return rows("""
                SELECT wl.id,
                       e.full_name AS empleado,
                       c.legal_name AS cliente,
                       CAST(wl.work_date AS CHAR) AS fecha,
                       wl.minutes AS minutos,
                       COALESCE(wl.description, '') AS trabajo,
                       COALESCE(wl.payment_status, '') AS estado
                FROM work_logs wl
                JOIN employees e ON e.id = wl.employee_id
                LEFT JOIN customers c ON c.id = wl.customer_id
                WHERE wl.company_id = ?
                ORDER BY wl.work_date DESC
                LIMIT 50
                """);
    }

    private List<ModuleRecord> tax() {
        return rows("""
                SELECT f.id,
                       m.code AS modelo,
                       m.name AS nombre,
                       f.period_year AS ejercicio,
                       f.period_code AS periodo,
                       f.status AS estado,
                       f.amount_due AS importe
                FROM tax_filings f
                JOIN tax_models m ON m.id = f.tax_model_id
                WHERE f.company_id = ?
                ORDER BY f.period_year DESC, f.period_code DESC
                LIMIT 50
                """);
    }

    private List<ModuleRecord> settings() {
        return rows("""
                SELECT e.id,
                       e.full_name AS empleado,
                       COALESCE(e.email, '') AS email,
                       COALESCE(e.phone, '') AS telefono,
                       COALESCE(e.work_type, '') AS tipo,
                       CASE WHEN e.pin_hash IS NULL THEN 'Sin PIN' ELSE 'PIN activo' END AS acceso
                FROM employees e
                WHERE e.company_id = ?
                ORDER BY e.full_name
                LIMIT 50
                """);
    }

    private List<ModuleRecord> calendar() {
        return rows("""
                SELECT id,
                       title AS evento,
                       CAST(event_date AS CHAR) AS fecha,
                       event_type AS tipo,
                       COALESCE(description, '') AS detalle
                FROM calendar_events
                WHERE company_id = ?
                ORDER BY event_date
                LIMIT 50
                """);
    }

    private ModuleRecord createCustomer(ModuleCreateRequest request) {
        String customerId = id();
        jdbcTemplate.update("""
                INSERT INTO customers (id, company_id, legal_name, trade_name, tax_identifier, customer_type)
                VALUES (?, ?, ?, ?, ?, 'COMPANY')
                """,
                customerId,
                currentCompanyId(),
                text(request.legalName(), "Cliente sin nombre"),
                blankToNull(request.tradeName()),
                text(request.taxIdentifier(), "SIN-" + customerId.substring(0, 8))
        );
        jdbcTemplate.update("""
                INSERT INTO customer_contacts (id, customer_id, full_name, email, phone, primary_contact)
                VALUES (?, ?, ?, ?, ?, TRUE)
                """,
                id(),
                customerId,
                text(request.contactName(), request.legalName()),
                blankToNull(request.email()),
                blankToNull(request.phone())
        );
        return findRecord("customers", customerId);
    }

    private ModuleRecord createInvoice(ModuleCreateRequest request) {
        String invoiceId = id();
        BigDecimal base = amountOrDefault(request.amount(), BigDecimal.valueOf(100));
        BigDecimal vatPercent = amountOrDefault(request.vatPercent(), BigDecimal.valueOf(21));
        BigDecimal vat = base.multiply(vatPercent).divide(BigDecimal.valueOf(100));
        BigDecimal total = base.add(vat);
        String customerId = firstId("customers", "company_id = ? AND active = TRUE");
        String number = "F-2026-DEMO-" + invoiceId.substring(0, 6).toUpperCase();

        jdbcTemplate.update("""
                INSERT INTO sales_invoices (id, company_id, issuer_id, customer_id, series_id, invoice_number, invoice_date,
                    due_date, status, payment_status, subtotal, vat_total, retention_total, total, paid_amount, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'DRAFT', 'PENDING', ?, ?, 0, ?, 0, ?)
                """,
                invoiceId,
                currentCompanyId(),
                "40000000-0000-0000-0000-000000000001",
                valueOrDefault(request.customerId(), customerId),
                "42000000-0000-0000-0000-000000000001",
                number,
                dateOrToday(request.date()),
                dateOrToday(request.date()).plusDays(30),
                base,
                vat,
                total,
                blankToNull(request.description())
        );
        jdbcTemplate.update("""
                INSERT INTO sales_invoice_lines (id, invoice_id, description, quantity, unit_price, vat_percent, line_subtotal, line_vat, line_total)
                VALUES (?, ?, ?, 1, ?, ?, ?, ?, ?)
                """,
                id(),
                invoiceId,
                text(request.title(), "Servicio facturable"),
                base,
                vatPercent,
                base,
                vat,
                total
        );
        return findRecord("billing", invoiceId);
    }

    // PURCHASES-CLEANUP-V2 (2026-06-05): los métodos createPurchase y
    // updatePurchase del dashboard genérico se han eliminado. La
    // creación/edición de facturas recibidas pasa exclusivamente por
    // PurchaseInvoiceService (subida de PDF + extracción + asiento
    // contable). Las columnas que estos métodos rellenaban
    // (supplier_id, category, subtotal, vat_total, total,
    // payment_status) se han retirado en V45.

    private ModuleRecord createLaborRecord(ModuleCreateRequest request) {
        String employeeId = valueOrDefault(request.employeeId(), firstId("employees", "company_id = ? AND active = TRUE"));
        if (StringUtils.hasText(request.eventType())) {
            String eventId = id();
            jdbcTemplate.update("""
                    INSERT INTO time_clock_events (id, company_id, employee_id, event_type, event_time, origin, status)
                    VALUES (?, ?, ?, ?, ?, 'PIN_KIOSK', 'VALID')
                    """,
                    eventId,
                    currentCompanyId(),
                    employeeId,
                    request.eventType(),
                    LocalDateTime.now()
            );
        }

        String workLogId = id();
        jdbcTemplate.update("""
                INSERT INTO work_logs (id, company_id, employee_id, customer_id, work_date, minutes, description, value_amount, payment_status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'PENDING')
                """,
                workLogId,
                currentCompanyId(),
                employeeId,
                valueOrDefault(request.customerId(), firstId("customers", "company_id = ? AND active = TRUE")),
                dateOrToday(request.date()),
                request.minutes() == null ? 60 : request.minutes(),
                text(request.description(), "Parte de trabajo"),
                amountOrDefault(request.amount(), BigDecimal.ZERO)
        );
        return findRecord("labor", workLogId);
    }

    private ModuleRecord createTaxFiling(ModuleCreateRequest request) {
        String filingId = id();
        String modelId = request.title() != null && request.title().contains("111")
                ? "70000000-0000-0000-0000-000000000002"
                : "70000000-0000-0000-0000-000000000001";
        jdbcTemplate.update("""
                INSERT INTO tax_filings (id, company_id, tax_model_id, period_year, period_code, status, amount_due)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                filingId,
                currentCompanyId(),
                modelId,
                dateOrToday(request.date()).getYear(),
                "A" + filingId.substring(0, 8).toUpperCase(),
                text(request.status(), "DRAFT"),
                amountOrDefault(request.amount(), BigDecimal.ZERO)
        );
        return findRecord("tax", filingId);
    }

    private ModuleRecord createEmployee(ModuleCreateRequest request) {
        String employeeId = id();
        jdbcTemplate.update("""
                INSERT INTO employees (id, company_id, full_name, tax_identifier, email, phone, pin_hash, work_type, max_shift_minutes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                employeeId,
                currentCompanyId(),
                text(request.legalName(), "Empleado demo"),
                blankToNull(request.taxIdentifier()),
                blankToNull(request.email()),
                blankToNull(request.phone()),
                StringUtils.hasText(request.pin()) ? sha256(request.pin().trim()) : null,
                text(request.category(), "FULL_TIME"),
                request.minutes() == null ? 480 : request.minutes()
        );
        return findRecord("settings", employeeId);
    }

    private ModuleRecord createCalendarEvent(ModuleCreateRequest request) {
        String eventId = id();
        jdbcTemplate.update("""
                INSERT INTO calendar_events (id, company_id, event_date, title, description, event_type)
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                eventId,
                currentCompanyId(),
                dateOrToday(request.date()),
                text(request.title(), "Evento"),
                blankToNull(request.description()),
                text(request.category(), "GENERAL")
        );
        return findRecord("calendar", eventId);
    }

    private ModuleRecord updateCustomer(String recordId, ModuleCreateRequest request) {
        jdbcTemplate.update("""
                UPDATE customers
                SET legal_name = ?,
                    trade_name = ?,
                    tax_identifier = ?
                WHERE id = ? AND company_id = ?
                """,
                text(request.legalName(), "Cliente sin nombre"),
                blankToNull(request.tradeName()),
                text(request.taxIdentifier(), "SIN-" + recordId.substring(0, 8)),
                recordId,
                currentCompanyId()
        );
        jdbcTemplate.update("""
                UPDATE customer_contacts
                SET full_name = ?,
                    email = ?,
                    phone = ?
                WHERE customer_id = ? AND primary_contact = TRUE
                """,
                text(request.contactName(), request.legalName()),
                blankToNull(request.email()),
                blankToNull(request.phone()),
                recordId
        );
        return findRecord("customers", recordId);
    }

    private ModuleRecord updateInvoice(String recordId, ModuleCreateRequest request) {
        BigDecimal total = amountOrDefault(request.amount(), BigDecimal.ZERO);
        jdbcTemplate.update("""
                UPDATE sales_invoices
                SET invoice_date = ?,
                    due_date = ?,
                    status = ?,
                    payment_status = ?,
                    total = CASE WHEN ? > 0 THEN ? ELSE total END,
                    notes = ?
                WHERE id = ? AND company_id = ?
                """,
                dateOrToday(request.date()),
                dateOrToday(request.date()).plusDays(30),
                allowed(request.status(), List.of("DRAFT", "VALIDATED", "CANCELLED", "VOIDED"), "DRAFT"),
                allowed(request.category(), List.of("PENDING", "PARTIAL", "PAID", "OVERDUE"), "PENDING"),
                total,
                total,
                blankToNull(request.description()),
                recordId,
                currentCompanyId()
        );
        if (StringUtils.hasText(request.title())) {
            jdbcTemplate.update("""
                    UPDATE sales_invoice_lines
                    SET description = ?
                    WHERE invoice_id = ?
                    LIMIT 1
                    """,
                    request.title().trim(),
                    recordId
            );
        }
        return findRecord("billing", recordId);
    }

    // updatePurchase eliminado en PURCHASES-CLEANUP-V2. Ver create.

    private ModuleRecord updateLaborRecord(String recordId, ModuleCreateRequest request) {
        jdbcTemplate.update("""
                UPDATE work_logs
                SET work_date = ?,
                    minutes = ?,
                    description = ?,
                    value_amount = ?,
                    payment_status = ?
                WHERE id = ? AND company_id = ?
                """,
                dateOrToday(request.date()),
                request.minutes() == null ? 60 : request.minutes(),
                text(request.description(), "Parte de trabajo"),
                amountOrDefault(request.amount(), BigDecimal.ZERO),
                allowed(request.status(), List.of("PENDING", "PARTIAL", "PAID", "OVERDUE"), "PENDING"),
                recordId,
                currentCompanyId()
        );
        return findRecord("labor", recordId);
    }

    private ModuleRecord updateTaxFiling(String recordId, ModuleCreateRequest request) {
        jdbcTemplate.update("""
                UPDATE tax_filings
                SET period_year = ?,
                    status = ?,
                    amount_due = ?
                WHERE id = ? AND company_id = ?
                """,
                dateOrToday(request.date()).getYear(),
                text(request.status(), "DRAFT"),
                amountOrDefault(request.amount(), BigDecimal.ZERO),
                recordId,
                currentCompanyId()
        );
        return findRecord("tax", recordId);
    }

    private ModuleRecord updateEmployee(String recordId, ModuleCreateRequest request) {
        jdbcTemplate.update("""
                UPDATE employees
                SET full_name = ?,
                    tax_identifier = ?,
                    email = ?,
                    phone = ?,
                    work_type = ?,
                    max_shift_minutes = ?,
                    pin_hash = CASE WHEN ? IS NULL THEN pin_hash ELSE ? END
                WHERE id = ? AND company_id = ?
                """,
                text(request.legalName(), "Empleado demo"),
                blankToNull(request.taxIdentifier()),
                blankToNull(request.email()),
                blankToNull(request.phone()),
                text(request.category(), "FULL_TIME"),
                request.minutes() == null ? 480 : request.minutes(),
                StringUtils.hasText(request.pin()) ? sha256(request.pin().trim()) : null,
                StringUtils.hasText(request.pin()) ? sha256(request.pin().trim()) : null,
                recordId,
                currentCompanyId()
        );
        return findRecord("settings", recordId);
    }

    private ModuleRecord updateCalendarEvent(String recordId, ModuleCreateRequest request) {
        jdbcTemplate.update("""
                UPDATE calendar_events
                SET event_date = ?,
                    title = ?,
                    description = ?,
                    event_type = ?
                WHERE id = ? AND company_id = ?
                """,
                dateOrToday(request.date()),
                text(request.title(), "Evento"),
                blankToNull(request.description()),
                text(request.category(), "GENERAL"),
                recordId,
                currentCompanyId()
        );
        return findRecord("calendar", recordId);
    }

    private ModuleRecord findRecord(String module, String id) {
        return module(module).records().stream()
                .filter(record -> record.id().equals(id))
                .findFirst()
                .orElseGet(() -> new ModuleRecord(id, Map.of("estado", "Creado")));
    }

    private List<ModuleRecord> rows(String sql) {
        return jdbcTemplate.query(sql, this::mapRecord, currentCompanyId());
    }

    private ModuleRecord mapRecord(ResultSet rs, int rowNum) throws SQLException {
        Map<String, Object> fields = new LinkedHashMap<>();
        int columns = rs.getMetaData().getColumnCount();
        String id = rs.getString("id");
        for (int index = 1; index <= columns; index++) {
            String column = rs.getMetaData().getColumnLabel(index);
            if (!"id".equalsIgnoreCase(column)) {
                fields.put(column, rs.getObject(index));
            }
        }
        return new ModuleRecord(id, fields);
    }

    private long count(String table, String where) {
        Long value = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE " + where,
                Long.class,
                currentCompanyId()
        );
        return value == null ? 0 : value;
    }

    private BigDecimal amount(String sql, Object... args) {
        BigDecimal value = jdbcTemplate.queryForObject(sql, BigDecimal.class, args);
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<DashboardItem> dashboardItems(String sql) {
        return jdbcTemplate.query(sql,
                (rs, rowNum) -> new DashboardItem(
                        rs.getString("title"),
                        rs.getString("subtitle"),
                        rs.getString("value")
                ),
                currentCompanyId()
        );
    }

    private String firstId(String table, String where) {
        return jdbcTemplate.queryForObject(
                "SELECT id FROM " + table + " WHERE " + where + " ORDER BY created_at LIMIT 1",
                String.class,
                currentCompanyId()
        );
    }

    private String supplierName(String supplierId) {
        return jdbcTemplate.queryForObject(
                "SELECT legal_name FROM suppliers WHERE id = ?",
                String.class,
                supplierId
        );
    }

    private String id() {
        return UUID.randomUUID().toString();
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String valueOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private String allowed(String value, List<String> allowedValues, String fallback) {
        if (!StringUtils.hasText(value)) {
            return fallback;
        }
        String normalized = value.trim().toUpperCase();
        return allowedValues.contains(normalized) ? normalized : fallback;
    }

    private String blankToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }

    private LocalDate dateOrToday(LocalDate value) {
        return value == null ? LocalDate.now() : value;
    }

    private BigDecimal amountOrDefault(BigDecimal value, BigDecimal fallback) {
        return value == null ? fallback : value;
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hash = new StringBuilder(bytes.length * 2);
            for (byte current : bytes) {
                hash.append(String.format("%02x", current));
            }
            return hash.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 no disponible", exception);
        }
    }
}
