package com.benjagest.ui.model;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Vista UI de {@code labor.leaves.MedicalLeave} (Incapacidad Temporal).
 *
 * <p>El backend persiste estos registros sin lógica fiscal —
 * la prestación la calcula la mutua / INSS. Aquí solo damos CRUD para
 * que aparezcan en el listado del empleado y para que el módulo de
 * nóminas pueda descontar los días IT del salario neto.
 *
 * <p>Tipos típicos:
 * <ul>
 *   <li>{@code COMMON_DISEASE} — enfermedad común (lo más frecuente).</li>
 *   <li>{@code WORK_ACCIDENT} — accidente laboral (parte AT-1).</li>
 *   <li>{@code MATERNITY} / {@code PATERNITY} — bajas por nacimiento.</li>
 *   <li>{@code OTHER} — riesgos durante embarazo/lactancia, etc.</li>
 * </ul>
 *
 * <p>Estados: OPEN (en curso) / CLOSED (con fecha alta) / DRAFT
 * (registro provisional sin notificar a SS).
 */
public record MedicalLeaveEntry(
        String id,
        String companyId,
        String employeeId,
        String leaveType,
        LocalDate startDate,
        LocalDate endDate,
        String status,
        String notes,
        Instant createdAt,
        Instant updatedAt
) {
    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_CLOSED = "CLOSED";
    public static final String STATUS_DRAFT = "DRAFT";

    public static final String TYPE_COMMON_DISEASE = "COMMON_DISEASE";
    public static final String TYPE_WORK_ACCIDENT = "WORK_ACCIDENT";
    public static final String TYPE_MATERNITY = "MATERNITY";
    public static final String TYPE_PATERNITY = "PATERNITY";
    public static final String TYPE_OTHER = "OTHER";
}
