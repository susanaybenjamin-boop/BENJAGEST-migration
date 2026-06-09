package com.benjagest.backend.labor.leaves;

import java.time.Instant;
import java.time.LocalDate;

/**
 * Record que refleja una fila de {@code medical_leaves} (Incapacidad
 * Temporal / IT en el sistema español de Seguridad Social).
 *
 * <p>Tipos típicos en {@link #leaveType()} (texto libre — el catálogo
 * cerrado lo provee la UI con i18n):
 * <ul>
 *   <li>{@code COMMON_DISEASE} — enfermedad común (la habitual).</li>
 *   <li>{@code WORK_ACCIDENT} — accidente de trabajo (parte CAT a la
 *       mutua / AT-1).</li>
 *   <li>{@code MATERNITY} / {@code PATERNITY} — bajas por nacimiento.</li>
 *   <li>{@code OTHER} — risgos durante embarazo, lactancia, etc.</li>
 * </ul>
 *
 * <p>Estados en {@link #status()}: {@code OPEN} (en curso, sin
 * end_date), {@code CLOSED} (con end_date), {@code DRAFT} (registro
 * provisional sin notificar a SS).
 */
public record MedicalLeave(
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
}
