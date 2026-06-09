package com.benjagest.backend.labor.ss;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Record que refleja {@code social_security_contributions}. Cada fila
 * representa una cuota mensual de Seguridad Social (TC1 / RED) por
 * empresa o por empleado. El campo {@code employee_id} es NULL cuando
 * es una cuota a nivel de empresa (cuotas patronales agregadas).
 *
 * <p>Tipos típicos en {@link #contributionType()}:
 * <ul>
 *   <li>{@code EMPLOYEE_COMMON} — aportación trabajador contingencias comunes.</li>
 *   <li>{@code EMPLOYER_COMMON} — aportación empresa contingencias comunes.</li>
 *   <li>{@code EMPLOYER_AT_EP} — accidente trabajo y enfermedad profesional.</li>
 *   <li>{@code EMPLOYER_FOGASA} — Fondo de Garantía Salarial.</li>
 *   <li>{@code EMPLOYER_TRAINING} — formación profesional.</li>
 *   <li>{@code EMPLOYER_UNEMPLOYMENT} — desempleo (parte empresa).</li>
 *   <li>{@code EMPLOYEE_UNEMPLOYMENT} — desempleo (parte trabajador).</li>
 *   <li>{@code MEI} — Mecanismo de Equidad Intergeneracional (Ley 21/2021).</li>
 * </ul>
 *
 * <p>Estados en {@link #status()}: {@code DRAFT} (calculado, sin
 * enviar), {@code FILED} (enviado al sistema RED), {@code PAID}
 * (cuota pagada).
 */
public record SocialSecurityContribution(
        String id,
        String companyId,
        String employeeId,           // NULL si es agregada de empresa
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
