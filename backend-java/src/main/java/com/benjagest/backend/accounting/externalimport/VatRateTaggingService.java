package com.benjagest.backend.accounting.externalimport;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * IVA-COMP / migración (2026-07-09) — etiqueta el tipo de IVA (vat_rate)
 * en las líneas de asiento de facturas IMPORTADAS que se contabilizaron
 * SIN etiquetar (el CSV de CONTENDO/A3/Sage trae cuentas + importes, no
 * el tipo por línea).
 *
 * <p>El 303 saca las bases de IVA de las líneas 7xx/6xx etiquetadas; sin
 * esto, las ventas/gastos importados no aparecían y el 303 salía a cero.
 * La FACTURA sí lleva el tipo, así que lo copiamos al asiento. Se llama al
 * terminar cada importación (y hay migración V171 para el histórico ya
 * existente). Solo toca líneas sin etiquetar → idempotente.
 */
@Service
public class VatRateTaggingService {

    private final JdbcTemplate jdbc;

    public VatRateTaggingService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Etiqueta las líneas 7xx/477 (ventas) y 6xx/472 (compras) sin
     * vat_rate de una empresa, tomando el tipo de la factura vinculada.
     * Ventas: solo facturas de tipo de IVA ÚNICO (una sola línea de
     * asiento no se puede desglosar en varios tipos). Devuelve el número
     * de líneas etiquetadas.
     */
    @Transactional
    public int tagFromInvoices(String companyId) {
        int sales = jdbc.update("""
                UPDATE journal_entry_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id AND e.source_type = 'SALES_INVOICE'
                  JOIN accounting_accounts a ON a.id = l.account_id
                  JOIN (
                      SELECT invoice_id, MAX(vat_percent) rate, COUNT(DISTINCT vat_percent) nrates
                        FROM sales_invoice_lines GROUP BY invoice_id
                  ) r ON r.invoice_id = e.source_id
                   SET l.vat_rate = r.rate
                 WHERE e.company_id = ?
                   AND l.vat_rate IS NULL
                   AND r.nrates = 1
                   AND (a.code LIKE '7%' OR a.code LIKE '477%')
                """, companyId);
        int purchases = jdbc.update("""
                UPDATE journal_entry_lines l
                  JOIN journal_entries e ON e.id = l.journal_entry_id AND e.source_type = 'PURCHASE_INVOICE'
                  JOIN accounting_accounts a ON a.id = l.account_id
                  JOIN purchase_invoices p ON p.id = e.source_id
                   SET l.vat_rate = p.vat_percent
                 WHERE e.company_id = ?
                   AND l.vat_rate IS NULL
                   AND p.vat_percent IS NOT NULL
                   AND (a.code LIKE '6%' OR a.code LIKE '472%')
                """, companyId);
        return sales + purchases;
    }
}
