package com.benjagest.ui.model;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Vista UI de {@code social_security_contributions} (cotizaciones SS).
 *
 * <p>Cada fila es una cuota mensual de Seguridad Social (TC1 / sistema
 * RED). Las cuotas a nivel de empresa tienen {@code employeeId} NULL
 * (cuotas patronales agregadas); las cuotas individuales del trabajador
 * sí lo llevan.
 *
 * <p>Tipos en {@link #contributionType()}: EMPLOYEE_COMMON,
 * EMPLOYER_COMMON, EMPLOYER_AT_EP, EMPLOYER_FOGASA, EMPLOYER_TRAINING,
 * EMPLOYER_UNEMPLOYMENT, EMPLOYEE_UNEMPLOYMENT, MEI (Ley 21/2021).
 *
 * <p>Estados: DRAFT (calculado, sin enviar) / FILED (enviado al sistema
 * RED) / PAID (cuota pagada). Backend bloquea DELETE si !=DRAFT.
 */
public record SocialSecurityContributionEntry(
        String id,
        String companyId,
        String employeeId,
        int periodYear,
        int periodMonth,
        String contributionType,
        BigDecimal baseAmount,
        BigDecimal contributionAmount,
        String status,
        Instant createdAt
) {
    public static final String STATUS_DRAFT = "DRAFT";
    public static final String STATUS_FILED = "FILED";
    public static final String STATUS_PAID  = "PAID";
}
