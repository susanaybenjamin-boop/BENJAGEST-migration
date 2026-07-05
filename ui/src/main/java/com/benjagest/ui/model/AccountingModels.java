package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * DTOs del módulo Contabilidad UI. Reúne en un único archivo todos los
 * records que la UI necesita para hablar con {@code /api/accounting/*}
 * — más simple que un archivo por record cuando son ~6 estructuras
 * cohesionadas.
 */
public final class AccountingModels {

    private AccountingModels() {}

    /** Fila del Libro Diario (lista). */
    public record DiaryEntry(
            String id,
            int entryNumber,
            LocalDate entryDate,
            String concept,
            String sourceType,
            String status,
            boolean autoProposed,
            BigDecimal proposedConfidence,
            BigDecimal totalDebit,
            BigDecimal totalCredit,
            int numLines,
            /** SHA-256 del PDF importado. Usado para detectar duplicados
             *  en la UI (2+ asientos con mismo SHA = mismo PDF metido
             *  varias veces). null si el asiento no viene de PDF. */
            String sourcePdfSha256
    ) {
        /** Constructor de compatibilidad con callsites antiguos. */
        public DiaryEntry(String id, int entryNumber, LocalDate entryDate,
                          String concept, String sourceType, String status,
                          boolean autoProposed, BigDecimal proposedConfidence,
                          BigDecimal totalDebit, BigDecimal totalCredit,
                          int numLines) {
            this(id, entryNumber, entryDate, concept, sourceType, status,
                    autoProposed, proposedConfidence, totalDebit, totalCredit,
                    numLines, null);
        }
    }

    /** Detalle de un asiento con líneas. */
    public record JournalEntryDetail(
            String id,
            int entryNumber,
            LocalDate entryDate,
            String concept,
            String sourceType,
            String status,
            boolean autoProposed,
            BigDecimal proposedConfidence,
            List<JournalLine> lines
    ) {}

    public record JournalLine(
            String id,
            String accountId,
            String accountCode,
            String accountName,
            String description,
            BigDecimal debit,
            BigDecimal credit
    ) {}

    /** Cuenta del PGC para combos. */
    public record AccountSummary(
            String id, String code, String name, String accountType
    ) {}

    /** Regla aprendida del módulo de aprendizaje contable. */
    public record LearningRule(
            String id,
            String ruleKind,
            String matchSupplierNif,
            String matchCustomerNif,
            String matchKeyword,
            String targetAccountCode,
            int timesApplied,
            int timesOverridden,
            BigDecimal confidence,
            boolean active
    ) {}

    /** Tarea recurrente (motor de cron contable). */
    public record RecurringTask(
            String id,
            String kind,
            String name,
            String description,
            String frequency,
            Integer dayOfMonth,
            Integer dayOfWeek,
            int monthsBetween,
            LocalDate nextRunDate,
            LocalDate lastRunDate,
            String lastRunStatus,
            int timesRun,
            int timesFailed,
            boolean active,
            /** JSON serializado del payload (datos cliente/proveedor,
             *  importes, líneas…). Lo usa el editor en modo edición
             *  para prellenar los campos. Puede ser null en respuestas
             *  antiguas. */
            String payloadJson,
            /** Slice 3R — TRUE cuando la plantilla fue creada por un
             *  user que no pertenece al tenant donde vive la tarea.
             *  Caso típico: la asesoría actuando como cliente. El UI
             *  muestra "Creada por tu asesoría" cuando el viewer NO es
             *  la asesoría. */
            boolean createdByAdvisor
    ) {
        /** Constructor de compatibilidad con callsites antiguos. */
        public RecurringTask(String id, String kind, String name, String description,
                             String frequency, Integer dayOfMonth, Integer dayOfWeek,
                             int monthsBetween, LocalDate nextRunDate, LocalDate lastRunDate,
                             String lastRunStatus, int timesRun, int timesFailed,
                             boolean active) {
            this(id, kind, name, description, frequency, dayOfMonth, dayOfWeek,
                    monthsBetween, nextRunDate, lastRunDate, lastRunStatus,
                    timesRun, timesFailed, active, null, false);
        }
        /** Constructor sin createdByAdvisor (compat 3Q). */
        public RecurringTask(String id, String kind, String name, String description,
                             String frequency, Integer dayOfMonth, Integer dayOfWeek,
                             int monthsBetween, LocalDate nextRunDate, LocalDate lastRunDate,
                             String lastRunStatus, int timesRun, int timesFailed,
                             boolean active, String payloadJson) {
            this(id, kind, name, description, frequency, dayOfMonth, dayOfWeek,
                    monthsBetween, nextRunDate, lastRunDate, lastRunStatus,
                    timesRun, timesFailed, active, payloadJson, false);
        }
    }

    /**
     * Candidato a recurrencia detectado por el backend (Slice 3D).
     * Representa un grupo de facturas con mismo NIF + mismo importe
     * repetidas en la ventana de análisis.
     */
    public record RecurringCandidate(
            String kind,              // "SALES_INVOICE" | "PURCHASE"
            String partyId,           // customer_id o supplier_id
            String partyNif,
            String partyName,
            BigDecimal totalAmount,
            int occurrences,
            LocalDate firstDate,
            LocalDate lastDate,
            String sampleInvoiceId,   // una de las facturas del grupo (para sacar concepto/líneas)
            String suggestedFrequency // WEEKLY / MONTHLY / QUARTERLY / CUSTOM / YEARLY
    ) {}

    /** Movimiento histórico de una tarea recurrente. */
    public record RecurringTaskRun(
            String id,
            LocalDate scheduledDate,
            String status,
            String generatedId,
            String generatedKind,
            String message,
            int durationMs
    ) {}

    public record BankAccountView(
            String id, String alias, String iban, String bankName,
            String currency, BigDecimal openingBalance, boolean active
    ) {}

    public record BankMovementRow(
            String id, String bankAccountId,
            LocalDate operationDate, String description,
            String counterpartyName, String counterpartyNif,
            BigDecimal amount, BigDecimal balanceAfter,
            String status, String linkedInvoiceId, String linkedInvoiceKind
    ) {}

    public record LoanView(
            String id, String code, String description,
            String lenderName, BigDecimal principalAmount,
            BigDecimal interestRate, int termMonths,
            LocalDate startDate, BigDecimal installmentAmount,
            String method, String status
    ) {}

    public record InstallmentView(
            String id, int installmentNumber, LocalDate dueDate,
            BigDecimal principalAmount, BigDecimal interestAmount,
            BigDecimal totalAmount, BigDecimal remainingPrincipal,
            String status
    ) {}

    public record FixedAssetRow(
            String id, String code, String name, String category,
            LocalDate acquisitionDate, BigDecimal acquisitionCost,
            BigDecimal usefulLifeYears, String depreciationMethod,
            boolean active
    ) {}

    // ==== REPORTS-UI — Informes contables (Mayor, Sumas y Saldos, Balance, PyG) ====

    /** Movimiento del Libro Mayor de una cuenta (con saldo corriente). */
    public record LedgerLineView(
            String entryId, int entryNumber, LocalDate entryDate,
            String concept, String status, String lineDescription,
            BigDecimal debit, BigDecimal credit, BigDecimal runningBalance
    ) {}

    /** Libro Mayor de una cuenta: saldo de apertura + movimientos + saldo final. */
    public record LedgerView(
            String accountId, String accountCode, String accountName,
            BigDecimal openingBalance, BigDecimal closingBalance,
            List<LedgerLineView> movements
    ) {}

    /** Fila del Balance de Sumas y Saldos. */
    public record TrialBalanceRow(
            String accountId, String code, String name,
            BigDecimal totalDebit, BigDecimal totalCredit,
            BigDecimal saldoDeudor, BigDecimal saldoAcreedor
    ) {}

    /** Línea de un informe por masas (Balance de Situación / PyG). */
    public record ReportItem(String code, String name, BigDecimal amount) {}

    /** Sección (masa) de un informe con sus líneas y total. */
    public record ReportSection(String name, List<ReportItem> items, BigDecimal total) {}

    /** Balance de Situación a una fecha (Activo / Pasivo + PN por masas). */
    public record BalanceSheetView(
            LocalDate asOf,
            List<ReportSection> activo, BigDecimal totalActivo,
            List<ReportSection> pasivo, BigDecimal totalPasivo
    ) {}

    /** Fila del Estado de Cambios en el Patrimonio Neto (ECPN). */
    public record EquityMovementRow(
            String code, String name,
            java.math.BigDecimal openingBalance,
            java.math.BigDecimal closingBalance,
            java.math.BigDecimal variation
    ) {}

    /** Resultado de un import (extracto bancario / contable). */
    public record ImportResult(
            int rowsTotal, int rowsImported, int rowsSkipped, int rowsAutoMatched
    ) {}

    /** Resultado del import del diario historico CONTENDO (IMP-H). */
    public record ContendoImportResult(
            int asientosTotal, int asientosImportados, int asientosSaltados,
            int facturasVenta, int rectificativas, int gastos,
            int clientesCreados, int proveedoresCreados, int cuentasCreadas,
            int cobrosVinculados, int pagosVinculados, int errores,
            List<String> avisos
    ) {}

    /** Cuenta de Pérdidas y Ganancias de un periodo. */
    public record ProfitAndLossView(
            LocalDate from, LocalDate to,
            List<ReportSection> ingresos, BigDecimal totalIngresos,
            List<ReportSection> gastos, BigDecimal totalGastos,
            BigDecimal resultadoExplotacion
    ) {}

    // ====================================================================
    //  ACC-TEMPLATES — Plantillas de asiento manual recurrente
    // ====================================================================

    /** Cabecera + líneas de una plantilla de asiento (GET /templates/{id}). */
    public record EntryTemplate(
            String id, String code, String name, String category,
            String defaultConcept, String description, boolean active,
            int timesUsed, String lastUsedAt,
            List<EntryTemplateLine> lines
    ) {}

    /**
     * Una línea de la plantilla. El importe se resuelve al aplicar según
     * {@code amountKind}: FIXED (importe fijo), VARIABLE (lo teclea el
     * usuario al aplicar, identificado por {@code variableName}) o FORMULA
     * (referencia a otra variable, por ahora).
     */
    public record EntryTemplateLine(
            String accountCode, String description,
            String side,        // DEBIT | CREDIT
            String amountKind,  // FIXED | VARIABLE | FORMULA
            BigDecimal fixedAmount, String formula, String variableName
    ) {}

    // ====================================================================
    //  FIN-1 — Cuadro de mando financiero del cliente
    // ====================================================================

    public record ClientFinancials(
            LocalDate from, LocalDate to,
            BigDecimal income, BigDecimal expenses, BigDecimal result,
            BigDecimal personnelCost, BigDecimal vatCharged, BigDecimal vatBorne,
            BigDecimal model303Estimated,
            BigDecimal pendingCollections, int overdueInvoices,
            BigDecimal pendingPayments,
            BigDecimal marginPct, BigDecimal expenseRatioPct, BigDecimal personnelRatioPct,
            int draftCount
    ) {}

    /** FIN-2 — un punto de la serie mensual (mes 1-12). */
    public record MonthPoint(int month, BigDecimal income, BigDecimal expenses, BigDecimal result) {}

    /** FIN-3 — proyección de cierre + IS estimado. */
    public record ClosingProjection(
            int year, int monthsElapsed,
            BigDecimal resultToDate, BigDecimal projectedResult,
            BigDecimal estimatedCorporateTax, BigDecimal projectedAfterTax
    ) {}

    /** ME-2 — factura pendiente de un tercero (cobro/pago manual). */
    public record OpenInvoice(String kind, String number, LocalDate date,
                              BigDecimal total, BigDecimal pending) {}

    /** ME-3 — cuenta sugerida para el asiento (histórico/reglas). */
    public record SuggestedAccount(String code, String name, int score, String reason) {}
}
