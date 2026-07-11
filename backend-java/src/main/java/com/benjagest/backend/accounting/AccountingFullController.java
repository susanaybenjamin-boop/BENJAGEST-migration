package com.benjagest.backend.accounting;

import com.benjagest.backend.auth.RequiresRole;
import com.benjagest.backend.modules.RequiresModule;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Endpoints REST del bloque contable de asesoría:
 *
 * <ul>
 *   <li>{@code /bank-accounts} → CRUD cuentas bancarias.</li>
 *   <li>{@code /bank-movements} → movimientos, cobros/pagos enlazados a factura.</li>
 *   <li>{@code /bank-imports} → importación de extractos N43/CSV.</li>
 *   <li>{@code /loans} → préstamos y cuadros amortización.</li>
 *   <li>{@code /assets/{id}/...-entry} → asientos de inmovilizado.</li>
 *   <li>{@code /templates} → plantillas asiento recurrente.</li>
 *   <li>{@code /reports/balance-sheet} → Balance de situación.</li>
 *   <li>{@code /reports/profit-and-loss} → PyG.</li>
 *   <li>{@code /reports/equity-changes} → ECPN.</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/accounting")
@RequiresModule("accounting")
@RequiresRole({"OWNER", "ADMIN", "ACCOUNTANT", "EMPLOYEE"})
public class AccountingFullController {

    private final BankAccountService bankAccounts;
    private final BankMovementService bankMovements;
    private final BankImportService bankImport;
    private final LoanService loans;
    private final FixedAssetEntriesService assetEntries;
    private final AccountingTemplateService templates;
    private final FinancialReportsService reports;
    private final ClientFinancialsService clientFinancials;
    private final FinancialDashboardPdfService financialsPdf;
    private final AccountingReportsPdfService reportsPdf;

    public AccountingFullController(BankAccountService bankAccounts,
                                      BankMovementService bankMovements,
                                      BankImportService bankImport,
                                      LoanService loans,
                                      FixedAssetEntriesService assetEntries,
                                      AccountingTemplateService templates,
                                      FinancialReportsService reports,
                                      ClientFinancialsService clientFinancials,
                                      FinancialDashboardPdfService financialsPdf,
                                      AccountingReportsPdfService reportsPdf) {
        this.bankAccounts = bankAccounts;
        this.bankMovements = bankMovements;
        this.bankImport = bankImport;
        this.loans = loans;
        this.assetEntries = assetEntries;
        this.templates = templates;
        this.reports = reports;
        this.clientFinancials = clientFinancials;
        this.financialsPdf = financialsPdf;
        this.reportsPdf = reportsPdf;
    }

    // ===== BANK ACCOUNTS =====

    @GetMapping("/bank-accounts")
    public List<BankAccountService.BankAccountView> listBankAccounts(
            @RequestParam(value = "active", required = false) Boolean active) {
        return bankAccounts.list(active);
    }

    @GetMapping("/bank-accounts/{id}")
    public BankAccountService.BankAccountView getBankAccount(@PathVariable("id") String id) {
        return bankAccounts.get(id);
    }

    @PostMapping("/bank-accounts")
    public BankAccountService.BankAccountView createBankAccount(
            @RequestBody BankAccountService.BankAccountRequest req) {
        return bankAccounts.create(req);
    }

    @PutMapping("/bank-accounts/{id}")
    public BankAccountService.BankAccountView updateBankAccount(
            @PathVariable("id") String id,
            @RequestBody BankAccountService.BankAccountRequest req) {
        return bankAccounts.update(id, req);
    }

    @DeleteMapping("/bank-accounts/{id}")
    public Map<String, Object> archiveBankAccount(@PathVariable("id") String id) {
        bankAccounts.setActive(id, false);
        return Map.of("id", id, "active", false);
    }

    // ===== BANK MOVEMENTS =====

    @GetMapping("/bank-movements")
    public List<BankMovementService.BankMovementView> listMovements(
            @RequestParam(value = "bankAccountId", required = false) String bankAccountId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return bankMovements.list(bankAccountId, status, from, to);
    }

    @PostMapping("/bank-movements")
    public BankMovementService.BankMovementView createMovement(
            @RequestBody BankMovementService.MovementRequest req) {
        return bankMovements.createManual(req);
    }

    @PostMapping("/bank-movements/{id}/link")
    public BankMovementService.BankMovementView linkMovement(
            @PathVariable("id") String id,
            @RequestBody BankMovementService.LinkRequest req) {
        return bankMovements.linkToInvoice(id, req);
    }

    @PostMapping("/bank-movements/{id}/ignore")
    public BankMovementService.BankMovementView ignoreMovement(
            @PathVariable("id") String id,
            @RequestParam(value = "reason", required = false) String reason) {
        return bankMovements.ignore(id, reason);
    }

    @GetMapping("/bank-movements/{id}/match-suggestions")
    public List<BankMovementService.MatchCandidate> suggestMatches(
            @PathVariable("id") String id) {
        return bankMovements.suggestMatches(id);
    }

    // F1-BANCO-REVIEW — filas de revisión: cada movimiento pendiente con su
    // factura candidata, estado y pagos existentes (para marcar con checkbox).
    @GetMapping("/bank-movements/reconcile-review")
    public List<BankMovementService.ReconcileRow> reconcileReview(
            @RequestParam("bankAccountId") String bankAccountId) {
        return bankMovements.reconcileReview(bankAccountId);
    }

    // F1-BANCO-REVIEW — concilia solo los movimientos marcados. Cada enlace es
    // su propia transacción: un fallo puntual no tumba los demás (éxito parcial).
    @PostMapping("/bank-movements/reconcile")
    public BankMovementService.ReconcileResult reconcile(
            @RequestBody List<BankMovementService.ReconcileSelection> selections) {
        int ok = 0;
        int fail = 0;
        List<String> errors = new java.util.ArrayList<>();
        if (selections != null) {
            for (BankMovementService.ReconcileSelection s : selections) {
                try {
                    // Llamada DIRECTA al método @Transactional (vía proxy): cada
                    // enlace es su propia transacción atómica.
                    bankMovements.linkToInvoice(s.movementId(),
                            new BankMovementService.LinkRequest(
                                    s.invoiceKind(), s.invoiceId(), null));
                    ok++;
                } catch (Exception ex) {
                    fail++;
                    errors.add(ex.getMessage() == null ? ex.toString() : ex.getMessage());
                }
            }
        }
        return new BankMovementService.ReconcileResult(ok, fail, errors);
    }

    @PostMapping("/bank-imports")
    public BankImportService.BatchResult importExtract(
            @RequestBody BankImportService.ImportRequest req) {
        return bankImport.importContent(req);
    }

    // ===== LOANS =====

    @GetMapping("/loans")
    public List<LoanService.LoanView> listLoans(
            @RequestParam(value = "status", required = false) String status) {
        return loans.list(status);
    }

    @GetMapping("/loans/{id}")
    public LoanService.LoanView getLoan(@PathVariable("id") String id) {
        return loans.get(id);
    }

    @PostMapping("/loans")
    public LoanService.LoanView createLoan(@RequestBody LoanService.LoanRequest req) {
        return loans.create(req);
    }

    @GetMapping("/loans/{id}/installments")
    public List<LoanService.InstallmentView> listInstallments(@PathVariable("id") String id) {
        return loans.listInstallments(id);
    }

    @PostMapping("/loans/installments/{installmentId}/pay")
    public LoanService.InstallmentView payInstallment(
            @PathVariable("installmentId") String installmentId,
            @RequestBody(required = false) LoanService.PaymentRequest req) {
        return loans.payInstallment(installmentId,
                req == null ? new LoanService.PaymentRequest(null) : req);
    }

    // El listado GET /fixed-assets lo expone FixedAssetService.FixedAssetController
    // (slice anterior ALTA/V34). No duplicamos aquí — el controller
    // existente ya cubre el caso. Sólo añadimos los endpoints de asientos
    // (acquisition/depreciation/disposal) que no estaban antes.

    @PostMapping("/assets/{id}/acquisition-entry")
    public Map<String, Object> assetAcquisition(
            @PathVariable("id") String assetId,
            @RequestBody AssetAcquisitionRequest req) {
        String entryId = assetEntries.createAcquisitionEntry(assetId,
                req == null ? null : req.vatAmount(),
                req == null ? null : req.paymentAccountId());
        return Map.of("entryId", entryId);
    }

    @PostMapping("/assets/{id}/depreciation-entry")
    public Map<String, Object> assetDepreciation(
            @PathVariable("id") String assetId,
            @RequestParam("year") int year,
            @RequestParam(value = "month", required = false) Integer month) {
        String entryId = assetEntries.createDepreciationEntry(assetId, year, month);
        return Map.of("entryId", entryId);
    }

    @PostMapping("/assets/{id}/disposal-entry")
    public Map<String, Object> assetDisposal(
            @PathVariable("id") String assetId,
            @RequestBody AssetDisposalRequest req) {
        String entryId = assetEntries.createDisposalEntry(assetId,
                req.disposalDate(), req.saleAmount(), req.bankAccountId());
        return Map.of("entryId", entryId);
    }

    public record AssetAcquisitionRequest(BigDecimal vatAmount, String paymentAccountId) {}
    public record AssetDisposalRequest(LocalDate disposalDate, BigDecimal saleAmount, String bankAccountId) {}

    // ===== TEMPLATES =====

    @GetMapping("/templates")
    public List<AccountingTemplateService.TemplateView> listTemplates(
            @RequestParam(value = "category", required = false) String category,
            @RequestParam(value = "active", required = false) Boolean active) {
        return templates.list(category, active);
    }

    @GetMapping("/templates/{id}")
    public AccountingTemplateService.TemplateView getTemplate(@PathVariable("id") String id) {
        return templates.get(id);
    }

    @PostMapping("/templates")
    public AccountingTemplateService.TemplateView createTemplate(
            @RequestBody AccountingTemplateService.TemplateRequest req) {
        return templates.create(req);
    }

    @PutMapping("/templates/{id}")
    public AccountingTemplateService.TemplateView updateTemplate(
            @PathVariable("id") String id,
            @RequestBody AccountingTemplateService.TemplateRequest req) {
        return templates.update(id, req);
    }

    @DeleteMapping("/templates/{id}")
    public Map<String, Object> archiveTemplate(@PathVariable("id") String id) {
        templates.setActive(id, false);
        return Map.of("id", id, "active", false);
    }

    // ===== FIN-1 — Cuadro de mando financiero del cliente =====

    @GetMapping("/financials")
    public ClientFinancialsService.ClientFinancials financials(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return clientFinancials.compute(from, to);
    }

    @GetMapping("/financials/monthly")
    public List<ClientFinancialsService.MonthPoint> financialsMonthly(
            @RequestParam("year") int year) {
        return clientFinancials.monthlySeries(year);
    }

    @GetMapping("/financials/projection")
    public ClientFinancialsService.ClosingProjection financialsProjection(
            @RequestParam("year") int year) {
        return clientFinancials.projectYearEnd(year);
    }

    @GetMapping(value = "/financials/export.pdf", produces = "application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> financialsPdf(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam("year") int year) {
        byte[] body = financialsPdf.exportPdf(from, to, year);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition",
                        "attachment; filename=\"cuadro-mando-" + from + "_" + to + ".pdf\"")
                .body(body);
    }

    @PostMapping("/templates/{id}/apply")
    public ManualJournalEntryService.ManualEntryView applyTemplate(
            @PathVariable("id") String id,
            @RequestBody AccountingTemplateService.ApplyRequest req) {
        return templates.apply(id, req);
    }

    // ===== REPORTS =====

    @GetMapping("/reports/balance-sheet")
    public FinancialReportsService.BalanceSheet balanceSheet(
            @RequestParam(value = "asOf", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        return reports.balanceSheet(asOf);
    }

    @GetMapping("/reports/profit-and-loss")
    public FinancialReportsService.ProfitAndLoss profitAndLoss(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.profitAndLoss(from, to);
    }

    @GetMapping(value = "/reports/balance-sheet/export.pdf", produces = "application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> balanceSheetPdf(
            @RequestParam("asOf") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
        byte[] body = reportsPdf.balanceSheetPdf(asOf);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"balance-" + asOf + ".pdf\"")
                .body(body);
    }

    @GetMapping(value = "/reports/profit-and-loss/export.pdf", produces = "application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> profitAndLossPdf(
            @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] body = reportsPdf.profitAndLossPdf(from, to);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"pyg-" + from + "_" + to + ".pdf\"")
                .body(body);
    }

    @GetMapping(value = "/ledger/{accountId}/export.pdf", produces = "application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> ledgerPdf(
            @PathVariable("accountId") String accountId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        byte[] body = reportsPdf.ledgerPdf(accountId, from, to);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"mayor-" + accountId + ".pdf\"")
                .body(body);
    }

    @GetMapping(value = "/balance/export.pdf", produces = "application/pdf")
    public org.springframework.http.ResponseEntity<byte[]> trialBalancePdf(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(value = "prefix", required = false) String prefix) {
        byte[] body = reportsPdf.trialBalancePdf(from, to, prefix);
        return org.springframework.http.ResponseEntity.ok()
                .header("Content-Disposition", "attachment; filename=\"sumas-y-saldos.pdf\"")
                .body(body);
    }

    @GetMapping("/reports/equity-changes")
    public List<FinancialReportsService.EquityMovementRow> equityChanges(
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reports.equityChanges(from, to);
    }
}
