package com.benjagest.backend.tax;

import com.benjagest.backend.accounting.FiscalYearGuardService;
import com.benjagest.backend.auth.CurrentUserService;
import com.benjagest.backend.tenant.TenantContext;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * LIQ-303 (2026-07-15) — Los asientos contables de los modelos AEAT.
 *
 * <p><b>El hueco que tapa.</b> Lo encontró Benjamin mirando su cuenta 477: no
 * cuadraba. Diagnóstico: el módulo fiscal LEÍA la contabilidad para calcular las
 * casillas, pero NUNCA le escribía. Ni al presentar, ni al pagar, ni nunca — no
 * existía un solo {@code INSERT} de asiento fiscal en todo el proyecto. Por eso
 * la 477 acumulaba las facturas de todo el año y no se vaciaba jamás. Sus
 * palabras: <i>"cuando le cambio el estado de presentado a pagado no se está
 * generando el asiento contra la hacienda"</i>. No era una regresión: era una
 * funcionalidad que nunca se hizo, y que nadie había anotado.
 *
 * <p><b>Modelo 303 (IVA).</b> Dos asientos:
 * <ol>
 *   <li><b>Al PRESENTAR</b> — la liquidación, fechada el último día del periodo:
 *       <pre>
 *       Debe   477  HP IVA repercutido       [saldo del periodo]
 *          Haber   472  HP IVA soportado      [saldo del periodo]
 *          Haber  4750  HP acreedora por IVA  [diferencia, si a ingresar]
 *       </pre>
 *       (o {@code Debe 4700 HP deudora} si sale a devolver/compensar).
 *       Este es el asiento que VACÍA la 477 cada trimestre.</li>
 *   <li><b>Al PAGAR</b>: {@code Debe 4750 / Haber 572 Bancos}.</li>
 * </ol>
 *
 * <p><b>Modelo 130 (pago fraccionado IRPF).</b> Solo asiento de pago:
 * {@code Debe 473 / Haber 572}. No lleva liquidación previa: no hay nada que
 * cancelar, porque el IRPF no se va acumulando en ninguna cuenta durante el
 * trimestre. La 473 recoge lo que Benjamin adelanta a Hacienda.
 * <b>Pendiente (cierre)</b>: a fin de año esa 473 hay que saldarla contra la
 * cuenta del titular (550) — el IRPF de un autónomo es un impuesto PERSONAL, no
 * un gasto de la actividad. Eso NO lo hace este service.
 *
 * <p><b>Por qué 4750/4700 y no la 475 genérica</b>: decisión de Benjamin, para no
 * mezclar el IVA con retenciones y otros conceptos fiscales en la misma cuenta.
 * Las crea la V178 — antes NO EXISTÍAN (la plantilla permanente solo llegaba a 3
 * dígitos), así que este asiento habría sido imposible.
 *
 * <p><b>Idempotencia</b>: cada asiento se enlaza al filing por
 * {@code (source_type, source_id)}. Si ya existe uno vivo, no se crea otro —
 * cambiar dos veces de estado no duplica el asiento.
 */
@Service
public class TaxLedgerService {

    private static final org.slf4j.Logger log =
            org.slf4j.LoggerFactory.getLogger(TaxLedgerService.class);

    /** El asiento de liquidación del 303 (al presentar). */
    public static final String SRC_LIQUIDATION = "TAX_LIQUIDATION";
    /** El asiento del pago de un modelo (al pagar). */
    public static final String SRC_PAYMENT = "TAX_PAYMENT";

    private final JdbcTemplate jdbcTemplate;
    private final TenantContext tenantContext;
    private final CurrentUserService currentUserService;
    private final FiscalYearGuardService fiscalGuard;

    public TaxLedgerService(JdbcTemplate jdbcTemplate,
                              TenantContext tenantContext,
                              CurrentUserService currentUserService,
                              FiscalYearGuardService fiscalGuard) {
        this.jdbcTemplate = jdbcTemplate;
        this.tenantContext = tenantContext;
        this.currentUserService = currentUserService;
        this.fiscalGuard = fiscalGuard;
    }

    // ====================================================================
    //  Puntos de entrada (los llama TaxFilingService al cambiar de estado)
    // ====================================================================

    /**
     * Asiento de LIQUIDACIÓN al presentar. Hoy solo el 303: es el único modelo
     * que cancela saldos acumulados en la contabilidad (477/472). El 130 no
     * tiene liquidación, y el 111/190 no están cubiertos todavía.
     *
     * <p>Nunca lanza: si no se puede (faltan cuentas, ejercicio cerrado, ya
     * existe), lo deja en el log y devuelve null. Cambiar el estado de una
     * declaración NO debe fallar porque la contabilidad no acompañe — el hecho
     * fiscal (la presentación) ocurrió igual.
     */
    @Transactional
    public String onPresented(String filingId) {
        Map<String, Object> f = loadFiling(filingId);
        if (f == null) return null;
        if (!"303".equals(f.get("tax_model_code"))) return null;
        return safely("liquidación", filingId, () -> createLiquidation303(f));
    }

    /** Asiento de PAGO al pasar a pagado. 303 → 4750; 130 → 473; 111 → 4751. */
    @Transactional
    public String onPaid(String filingId) {
        Map<String, Object> f = loadFiling(filingId);
        if (f == null) return null;
        String model = String.valueOf(f.get("tax_model_code"));
        if (!"303".equals(model) && !"130".equals(model) && !"111".equals(model)) return null;
        // Flujo normal: se marca PAGADO ahora -> el dinero sale hoy.
        return safely("pago", filingId, () -> createPayment(f, LocalDate.now()));
    }

    // ====================================================================
    //  Los asientos
    // ====================================================================

    private String createLiquidation303(Map<String, Object> f) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (existingEntry(SRC_LIQUIDATION, (String) f.get("id")) != null) {
            log.info("LIQ-303: el filing {} ya tiene asiento de liquidación; no creo otro.", f.get("id"));
            return null;
        }
        LocalDate date = periodEnd(f);
        fiscalGuard.requireOpenForDate(date, "contabilizar la liquidación del 303");

        // Saldos ACUMULADOS del periodo, de los asientos que computan. Se calculan
        // desde el diario (no desde las casillas del modelo) a propósito: lo que
        // hay que cancelar es lo que la contabilidad tiene, no lo que el modelo
        // dice. Si difieren, el asiento deja el descuadre a la vista en la 4750
        // en vez de esconderlo.
        BigDecimal saldo477 = periodBalance(companyId, "477", date);   // haber - debe
        BigDecimal saldo472 = periodBalance(companyId, "472", date).negate(); // debe - haber
        if (saldo477.signum() == 0 && saldo472.signum() == 0) {
            log.info("LIQ-303: filing {} sin saldos de IVA en el periodo; no hay nada que liquidar.", f.get("id"));
            return null;
        }

        String acc477 = accountByPrefix(companyId, "477");
        String acc472 = accountByPrefix(companyId, "472");
        if (acc477 == null || acc472 == null) {
            throw new IllegalStateException("faltan las cuentas 477/472 en el plan contable");
        }

        BigDecimal resultado = saldo477.subtract(saldo472); // >0 a ingresar
        String accHacienda = resultado.signum() >= 0
                ? accountByCode(companyId, "4750")   // HP acreedora por IVA
                : accountByCode(companyId, "4700");  // HP deudora por IVA
        if (accHacienda == null) {
            throw new IllegalStateException("falta la cuenta "
                    + (resultado.signum() >= 0 ? "4750" : "4700")
                    + " (la crea la V178; ¿migración sin aplicar?)");
        }

        String concept = "Liquidación IVA " + periodLabel(f) + " (modelo 303)";
        String entryId = insertEntry(companyId, date, concept, SRC_LIQUIDATION, (String) f.get("id"));

        // Debe 477 por su saldo acreedor, Haber 472 por su saldo deudor: los dos
        // quedan a cero. La diferencia va contra Hacienda.
        if (saldo477.signum() != 0) {
            insertLine(entryId, acc477, "IVA repercutido del periodo", saldo477, BigDecimal.ZERO);
        }
        if (saldo472.signum() != 0) {
            insertLine(entryId, acc472, "IVA soportado del periodo", BigDecimal.ZERO, saldo472);
        }
        if (resultado.signum() > 0) {
            insertLine(entryId, accHacienda, "IVA a ingresar " + periodLabel(f), BigDecimal.ZERO, resultado);
        } else if (resultado.signum() < 0) {
            insertLine(entryId, accHacienda, "IVA a compensar/devolver " + periodLabel(f),
                    resultado.negate(), BigDecimal.ZERO);
        }
        log.info("LIQ-303: asiento de liquidación {} creado para el filing {} (477={}, 472={}, resultado={})",
                entryId, f.get("id"), saldo477, saldo472, resultado);
        return entryId;
    }

    /**
     * Asiento de pago del modelo.
     *
     * @param date fecha de caja. En el flujo normal (marcar PAGADO ahora) es
     *     HOY: el dinero sale del banco hoy. Regularizando hacia atrás
     *     (LIQ-130-BF) es el PLAZO del modelo — ver {@link #backfillPaymentDate}.
     *     Se pasa por parámetro a propósito: cuando estaba clavado a
     *     {@code LocalDate.now()} dentro, el backfill de un 1T habría fechado el
     *     pago en el trimestre en que se pulsó el botón.
     */
    private String createPayment(Map<String, Object> f, LocalDate date) {
        String companyId = tenantContext.getCurrentCompanyId();
        if (existingEntry(SRC_PAYMENT, (String) f.get("id")) != null) {
            log.info("LIQ-303: el filing {} ya tiene asiento de pago; no creo otro.", f.get("id"));
            return null;
        }
        BigDecimal importe = (BigDecimal) f.get("total_amount");
        if (importe == null || importe.signum() <= 0) {
            log.info("LIQ-303: filing {} sin importe a pagar (total={}); no genero asiento de pago.",
                    f.get("id"), importe);
            return null;
        }
        fiscalGuard.requireOpenForDate(date, "contabilizar el pago del modelo");

        String model = String.valueOf(f.get("tax_model_code"));
        // 303 -> cancela la deuda de la 4750 (HP acreedora por IVA).
        // 130 -> pago a cuenta del IRPF del titular (473, activo).
        // 111 -> vacía la retención practicada, que la nómina fue abonando a la
        //        4751 o, si esa subcuenta no existe, a su padre 475.
        String accDebe = paymentDebitAccount(companyId, model);
        String accBanco = accountByPrefix(companyId, "572");
        if (accDebe == null || accBanco == null) {
            throw new IllegalStateException("faltan cuentas para el pago ("
                    + paymentAccountCode(model) + " / 572)");
        }

        String concept = "Pago modelo " + model + " " + periodLabel(f);
        String entryId = insertEntry(companyId, date, concept, SRC_PAYMENT, (String) f.get("id"));
        insertLine(entryId, accDebe, concept, importe, BigDecimal.ZERO);
        insertLine(entryId, accBanco, concept, BigDecimal.ZERO, importe);
        log.info("LIQ-303: asiento de pago {} creado para el filing {} (modelo {}, importe {})",
                entryId, f.get("id"), model, importe);
        return entryId;
    }

    // ====================================================================
    //  LIQ-BACKFILL — regularizar trimestres anteriores (decisión Benjamin
    //  2026-07-15: con vista previa y confirmación, NUNCA por migración
    //  silenciosa: son sus libros y yo no puedo ver su BD, que está blindada)
    // ====================================================================

    /**
     * Un asiento fiscal que falta, tal y como se le enseña al usuario ANTES de
     * crearlo.
     *
     * <p>LIQ-130-BF (2026-07-16): antes esto solo sabía de LIQUIDACIONES del
     * 303 y por eso Benjamin veía que "el 130 no responde al regularizar". Un
     * mismo trimestre puede generar DOS filas (la liquidación y su pago), así
     * que la clave de una fila es (filingId, kind), no filingId.
     *
     * @param modelCode "303" o "130".
     * @param kind {@link #KIND_LIQUIDATION} o {@link #KIND_PAYMENT}. Clave, no
     *     texto: la UI lo traduce (par ES+EN).
     * @param saldo477 solo tiene sentido en la liquidación; null en un pago.
     * @param saldo472 idem.
     * @param resultado en la liquidación, 477-472; en el pago, lo que se paga.
     * @param cuentaHacienda 4750/4700 en la liquidación; 4750 (303) o 473 (130)
     *     en el pago.
     * @param fechaAsiento con qué fecha nacerá. En un pago regularizado es el
     *     PLAZO del modelo, no hoy.
     */
    public record PendingLiquidation(
            String filingId, String periodLabel, int year, int quarter,
            String modelCode, String kind,
            BigDecimal saldo477, BigDecimal saldo472, BigDecimal resultado,
            String cuentaHacienda, LocalDate fechaAsiento,
            boolean puedeAplicarse, String motivo) {}

    public static final String KIND_LIQUIDATION = "LIQUIDATION";
    public static final String KIND_PAYMENT = "PAYMENT";

    /**
     * Qué liquidaciones del 303 faltan, en ORDEN cronológico.
     *
     * <p>Ojo con el orden: los importes que se muestran son los movimientos
     * PROPIOS de cada trimestre. Coinciden con lo que creará
     * {@link #createLiquidation303} <b>siempre que se apliquen en orden</b>,
     * porque cada liquidación deja la 477/472 a cero y la siguiente solo ve lo
     * suyo. Aplicarlas desordenadas daría importes distintos — por eso
     * {@link #applyBackfill} las ordena.
     */
    public List<PendingLiquidation> previewPendingLiquidations(int year) {
        String companyId = tenantContext.getCurrentCompanyId();
        // LIQ-130-BF (2026-07-16): esta consulta llevaba '303' CLAVADO y solo
        // buscaba liquidaciones. Dos agujeros:
        //   1) El 130 no aparecía JAMÁS -> "el 130 no responde al regularizar".
        //   2) Ni siquiera para el 303 se creaba el asiento de PAGO: un 303 ya
        //      PAGADO de antes se regularizaba, nacía su liquidación (Haber
        //      4750) y nadie cancelaba esa deuda -> la 4750 se quedaba debiendo
        //      para siempre dinero YA pagado, y el banco nunca reflejaba la
        //      salida. Mismo agujero que el de la 477, una capa más abajo.
        // Nota: el 130 no tiene liquidación, solo pago (no acumula nada que
        // cancelar; su 473 se salda contra el titular en el cierre).
        List<Map<String, Object>> filings = jdbcTemplate.queryForList("""
                SELECT id, tax_model_code, period_year, period_quarter, period_month,
                       total_amount, status, deadline_at
                  FROM tax_filings
                 WHERE company_id = ? AND tax_model_code IN ('303', '130', '111')
                   AND period_year = ?
                   AND status IN ('PRESENTED', 'PAID')
                   AND period_quarter IS NOT NULL
                 ORDER BY period_quarter, tax_model_code
                """, companyId, year);

        List<PendingLiquidation> out = new java.util.ArrayList<>();
        for (Map<String, Object> f : filings) {
            String filingId = (String) f.get("id");
            String model = String.valueOf(f.get("tax_model_code"));
            int q = ((Number) f.get("period_quarter")).intValue();
            String label = q + "T " + year;

            if ("303".equals(model) && existingEntry(SRC_LIQUIDATION, filingId) == null) {
                LocalDate from = LocalDate.of(year, (q - 1) * 3 + 1, 1);
                LocalDate to = periodEnd(f);
                BigDecimal s477 = rangeBalance(companyId, "477", from, to);
                BigDecimal s472 = rangeBalance(companyId, "472", from, to).negate();
                BigDecimal resultado = s477.subtract(s472);
                String cuenta = resultado.signum() >= 0 ? "4750" : "4700";

                String motivo = null;
                if (s477.signum() == 0 && s472.signum() == 0) {
                    motivo = "El trimestre no tiene movimientos de IVA: no hay nada que liquidar.";
                } else if (accountByCode(companyId, cuenta) == null) {
                    motivo = "Falta la cuenta " + cuenta + " en el plan contable (la crea la V178).";
                } else if (!"OPEN".equals(fiscalYearStatus(companyId, to))) {
                    motivo = "El ejercicio de " + to + " no está abierto.";
                }
                out.add(new PendingLiquidation(filingId, label, year, q, model,
                        KIND_LIQUIDATION, s477, s472, resultado, cuenta, to,
                        motivo == null, motivo));
            }

            // El pago: solo si está PAGADA y aún no tiene su asiento.
            if ("PAID".equals(String.valueOf(f.get("status")))
                    && existingEntry(SRC_PAYMENT, filingId) == null) {
                BigDecimal importe = (BigDecimal) f.get("total_amount");
                LocalDate fecha = backfillPaymentDate(f);
                String cuenta = paymentAccountCode(model);

                String motivo = null;
                if (importe == null || importe.signum() <= 0) {
                    motivo = "La declaración no sale a pagar: no hay pago que contabilizar.";
                } else if (paymentDebitAccount(companyId, model) == null) {
                    motivo = "Falta la cuenta " + cuenta + " en el plan contable.";
                } else if (accountByPrefix(companyId, "572") == null) {
                    motivo = "Falta la cuenta 572 (bancos) en el plan contable.";
                } else if (!"OPEN".equals(fiscalYearStatus(companyId, fecha))) {
                    motivo = "El ejercicio de " + fecha + " no está abierto.";
                }
                out.add(new PendingLiquidation(filingId, label, year, q, model,
                        KIND_PAYMENT, null, null, importe, cuenta, fecha,
                        motivo == null, motivo));
            }
        }
        return out;
    }

    /** Cuenta de cargo del asiento de pago de cada modelo (contra 572). Es el
     *  código CANÓNICO, para etiquetas y mensajes; la resolución real (que tolera
     *  subcuentas ausentes) la hace {@link #paymentDebitAccount}. */
    private static String paymentAccountCode(String model) {
        return switch (model) {
            case "303" -> "4750"; // HP acreedora por IVA
            case "111" -> "4751"; // HP acreedora por retenciones practicadas
            default    -> "473";  // 130: HP retenciones y pagos a cuenta (activo)
        };
    }

    /**
     * Resuelve la cuenta de cargo REAL del pago en el plan de la empresa. Para el
     * 111 tolera que no exista la subcuenta 4751 y cae a su padre 475 ("HP
     * acreedora por conceptos fiscales") — que es lo que la plantilla permanente
     * (V147) siembra y a lo que la nómina abona la retención cuando no hay 4751.
     * Devuelve null si no hay ninguna, y el caller decide.
     */
    private String paymentDebitAccount(String companyId, String model) {
        return switch (model) {
            case "303" -> accountByCode(companyId, "4750");
            case "111" -> {
                String a = accountByPrefix(companyId, "4751");
                yield a != null ? a : accountByPrefix(companyId, "475");
            }
            default -> accountByPrefix(companyId, "473"); // 130
        };
    }

    /**
     * Crea las liquidaciones que faltan. SIEMPRE en orden cronológico: la del 2T
     * depende de que exista la del 1T (ver {@link #previewPendingLiquidations}).
     *
     * @return cuántas se crearon.
     */
    @Transactional
    public int applyBackfill(List<String> filingIds) {
        if (filingIds == null || filingIds.isEmpty()) return 0;
        // Reordenar por (año, trimestre) pase lo que pase: si el caller los manda
        // desordenados, los importes saldrían mal.
        // LIQ-130-BF: antes esto filtraba tax_model_code='303' (el 130 se caía
        // aquí aunque llegara su id) y solo llamaba a createLiquidation303 — el
        // asiento de PAGO no lo creaba NUNCA, ni para el 303.
        //
        // La lista puede traer el mismo id dos veces (un trimestre con
        // liquidación Y pago pendientes son dos filas en la vista previa). Se
        // deduplica: por cada declaración se crea TODO lo que le falte, en el
        // orden correcto — primero la liquidación (deja la 4750 debiendo) y
        // luego el pago (la cancela). Al revés el pago cancelaría una deuda que
        // aún no existe.
        List<String> distinct = filingIds.stream().distinct().toList();
        List<Map<String, Object>> ordered = jdbcTemplate.queryForList("""
                SELECT id FROM tax_filings
                 WHERE company_id = ? AND tax_model_code IN ('303', '130', '111')
                   AND id IN (%s)
                 ORDER BY period_year, period_quarter, tax_model_code
                """.formatted(distinct.stream().map(x -> "?").collect(java.util.stream.Collectors.joining(","))),
                concat(tenantContext.getCurrentCompanyId(), distinct));

        int n = 0;
        for (Map<String, Object> row : ordered) {
            String filingId = (String) row.get("id");
            Map<String, Object> f = loadFiling(filingId);
            if (f == null) continue;
            String model = String.valueOf(f.get("tax_model_code"));

            if ("303".equals(model)) {
                String liq = safely("liquidación (backfill)", filingId,
                        () -> createLiquidation303(f));
                if (liq != null) n++;
            }
            if ("PAID".equals(String.valueOf(f.get("status")))) {
                // Con la fecha del PLAZO, no la de hoy: hoy metería el pago del
                // 1T en el trimestre en que se pulsa el botón.
                LocalDate fecha = backfillPaymentDate(f);
                String pago = safely("pago (backfill)", filingId,
                        () -> createPayment(f, fecha));
                if (pago != null) n++;
            }
        }
        return n;
    }

    private static Object[] concat(String first, List<String> rest) {
        Object[] args = new Object[rest.size() + 1];
        args[0] = first;
        for (int i = 0; i < rest.size(); i++) args[i + 1] = rest.get(i);
        return args;
    }

    private String fiscalYearStatus(String companyId, LocalDate date) {
        return jdbcTemplate.query("""
                SELECT status FROM fiscal_years
                 WHERE company_id = ? AND year_number = ? LIMIT 1
                """, (rs, x) -> rs.getString("status"), companyId, date.getYear())
                .stream().findFirst().orElse("OPEN"); // sin fila = permisivo (igual que FiscalYearGuardService)
    }

    /** Saldo acreedor (haber - debe) de una cuenta por prefijo en un rango concreto. */
    private BigDecimal rangeBalance(String companyId, String prefix, LocalDate from, LocalDate to) {
        BigDecimal v = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(l.credit) - SUM(l.debit), 0)
                  FROM journal_entry_lines l
                  JOIN journal_entries je ON je.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE je.company_id = ?
                   AND je.status = 'POSTED'
                   AND je.entry_date BETWEEN ? AND ?
                   AND a.code LIKE ?
                """, BigDecimal.class, companyId, Date.valueOf(from), Date.valueOf(to), prefix + "%");
        return v == null ? BigDecimal.ZERO : v;
    }

    // ====================================================================
    //  Helpers
    // ====================================================================

    /**
     * Saldo ACREEDOR (haber - debe) de una cuenta por prefijo, en el periodo que
     * va desde el inicio del ejercicio hasta {@code end}, contando solo asientos
     * POSTED y descontando lo ya liquidado en periodos anteriores (las propias
     * liquidaciones son asientos POSTED sobre las mismas cuentas, así que se
     * cancelan solas: si el T1 ya se liquidó, su 477 quedó a cero y el T2 solo ve
     * lo suyo).
     */
    private BigDecimal periodBalance(String companyId, String prefix, LocalDate end) {
        LocalDate start = LocalDate.of(end.getYear(), 1, 1);
        BigDecimal v = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(l.credit) - SUM(l.debit), 0)
                  FROM journal_entry_lines l
                  JOIN journal_entries je ON je.id = l.journal_entry_id
                  JOIN accounting_accounts a ON a.id = l.account_id
                 WHERE je.company_id = ?
                   AND je.status = 'POSTED'
                   AND je.entry_date BETWEEN ? AND ?
                   AND a.code LIKE ?
                """, BigDecimal.class, companyId, Date.valueOf(start), Date.valueOf(end), prefix + "%");
        return v == null ? BigDecimal.ZERO : v;
    }

    private Map<String, Object> loadFiling(String filingId) {
        // status y deadline_at los añadió LIQ-130-BF: el backfill necesita saber
        // si la declaración está PAGADA (para crear su asiento de pago) y con qué
        // fecha (la de su plazo, NO hoy).
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
                SELECT id, tax_model_code, period_year, period_quarter, period_month,
                       total_amount, status, deadline_at
                  FROM tax_filings
                 WHERE id = ? AND company_id = ?
                """, filingId, tenantContext.getCurrentCompanyId());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * Fecha del asiento de pago cuando se regulariza HACIA ATRÁS (LIQ-130-BF).
     *
     * <p>Hoy NO vale: metería el pago del 1T en el trimestre en que se pulsa el
     * botón y descuadraría el banco contra el extracto. Se usa el
     * <b>plazo del modelo</b> (1T 20-abr, 2T 20-jul, 3T 20-oct, 4T 30-ene),
     * que es cuando la AEAT carga los domiciliados y por tanto lo que
     * normalmente coincide con el apunte real del banco. Decisión de Benjamin
     * (2026-07-16).
     *
     * <p>Se prefiere el {@code deadline_at} que la declaración ya tiene
     * guardado; si falta (declaraciones viejas o creadas a mano) se calcula con
     * {@link TaxFilingService#computeDeadline} — el MISMO método que usa el
     * módulo al crearlas, para no tener dos verdades sobre los plazos.
     */
    private LocalDate backfillPaymentDate(Map<String, Object> f) {
        java.sql.Date stored = (java.sql.Date) f.get("deadline_at");
        if (stored != null) return stored.toLocalDate();
        Number q = (Number) f.get("period_quarter");
        Number m = (Number) f.get("period_month");
        LocalDate computed = TaxFilingService.computeDeadline(
                String.valueOf(f.get("tax_model_code")),
                ((Number) f.get("period_year")).intValue(),
                q == null ? null : q.intValue(),
                m == null ? null : m.intValue());
        // Último recurso: el fin del periodo. Nunca hoy.
        return computed != null ? computed : periodEnd(f);
    }

    private String existingEntry(String sourceType, String filingId) {
        return jdbcTemplate.query("""
                SELECT id FROM journal_entries
                 WHERE company_id = ? AND source_type = ? AND source_id = ?
                   AND status <> 'VOIDED'
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"),
                tenantContext.getCurrentCompanyId(), sourceType, filingId)
                .stream().findFirst().orElse(null);
    }

    /** Último día del periodo del filing (trimestre, mes o año). */
    private LocalDate periodEnd(Map<String, Object> f) {
        int year = ((Number) f.get("period_year")).intValue();
        Number q = (Number) f.get("period_quarter");
        Number m = (Number) f.get("period_month");
        if (m != null) return LocalDate.of(year, m.intValue(), 1).withDayOfMonth(
                LocalDate.of(year, m.intValue(), 1).lengthOfMonth());
        if (q != null) {
            int lastMonth = q.intValue() * 3;
            LocalDate d = LocalDate.of(year, lastMonth, 1);
            return d.withDayOfMonth(d.lengthOfMonth());
        }
        return LocalDate.of(year, 12, 31);
    }

    private String periodLabel(Map<String, Object> f) {
        int year = ((Number) f.get("period_year")).intValue();
        Number q = (Number) f.get("period_quarter");
        Number m = (Number) f.get("period_month");
        if (q != null) return q + "T " + year;
        if (m != null) return String.format("%02d/%d", m.intValue(), year);
        return String.valueOf(year);
    }

    private String insertEntry(String companyId, LocalDate date, String concept,
                                 String sourceType, String filingId) {
        String fiscalYearId = jdbcTemplate.query("""
                SELECT id FROM fiscal_years
                 WHERE company_id = ? AND year_number = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, date.getYear())
                .stream().findFirst().orElse(null);
        if (fiscalYearId == null) {
            throw new IllegalStateException("no hay ejercicio fiscal " + date.getYear());
        }
        // Numeración: MAX sobre TODOS los estados. Un asiento ANULADO conserva su
        // entry_number y la UK (company, fiscal_year, entry_number) chocaría si
        // reutilizásemos su hueco (ver ANUL-2b).
        Integer max = jdbcTemplate.queryForObject("""
                SELECT COALESCE(MAX(entry_number), 0) FROM journal_entries
                 WHERE company_id = ? AND fiscal_year_id = ? AND entry_number IS NOT NULL
                """, Integer.class, companyId, fiscalYearId);
        int number = (max == null ? 0 : max) + 1;

        String id = UUID.randomUUID().toString();
        jdbcTemplate.update("""
                INSERT INTO journal_entries (
                    id, company_id, fiscal_year_id, entry_number,
                    entry_date, concept, source_type, source_id,
                    status, reviewed, auto_proposed, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, 'POSTED', TRUE, FALSE, ?)
                """,
                id, companyId, fiscalYearId, number, Date.valueOf(date),
                truncate(concept, 240), sourceType, filingId, safeUserId());
        return id;
    }

    private void insertLine(String entryId, String accountId, String description,
                              BigDecimal debit, BigDecimal credit) {
        jdbcTemplate.update("""
                INSERT INTO journal_entry_lines (
                    id, journal_entry_id, account_id, description, debit, credit
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID().toString(), entryId, accountId,
                truncate(description, 240), debit, credit);
    }

    private String accountByPrefix(String companyId, String prefix) {
        return jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code LIKE ?
                 ORDER BY LENGTH(code), code
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, prefix + "%")
                .stream().findFirst().orElse(null);
    }

    private String accountByCode(String companyId, String code) {
        return jdbcTemplate.query("""
                SELECT id FROM accounting_accounts
                 WHERE company_id = ? AND active = TRUE AND code = ?
                 LIMIT 1
                """, (rs, n) -> rs.getString("id"), companyId, code)
                .stream().findFirst().orElse(null);
    }

    private String safeUserId() {
        try { return currentUserService.require().userId(); }
        catch (Exception ex) { return null; }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max);
    }

    private interface Op { String run(); }

    /** Ver el javadoc de {@link #onPresented}: la contabilidad no bloquea el estado fiscal. */
    private String safely(String what, String filingId, Op op) {
        try {
            return op.run();
        } catch (Exception ex) {
            log.warn("LIQ-303: no se pudo crear el asiento de {} del filing {}: {}",
                    what, filingId, ex.getMessage());
            return null;
        }
    }
}
