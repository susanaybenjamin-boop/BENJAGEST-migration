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
            int numLines
    ) {}

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
            boolean active
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
}
