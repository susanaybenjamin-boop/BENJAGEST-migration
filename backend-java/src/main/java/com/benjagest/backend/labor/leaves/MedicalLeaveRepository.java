package com.benjagest.backend.labor.leaves;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Repositorio JdbcTemplate para {@link MedicalLeave}.
 *
 * RGPD (2026-07-08): la columna {@code notes} (texto libre que puede
 * contener diagnostico — dato de salud, art. 9 RGPD) se guarda CIFRADA
 * via {@link com.benjagest.backend.config.FieldCipher} (prefijo ENC:;
 * las filas legacy en claro se leen tal cual y se cifran al proximo
 * guardado). leave_type y fechas quedan en claro a proposito: los usa
 * el calculo de nomina y el calendario.
 */
@Repository
public class MedicalLeaveRepository {

    private final JdbcTemplate jdbcTemplate;
    private final com.benjagest.backend.config.FieldCipher fieldCipher;
    private final RowMapper<MedicalLeave> mapper;

    public MedicalLeaveRepository(JdbcTemplate jdbcTemplate,
                                  com.benjagest.backend.config.FieldCipher fieldCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.fieldCipher = fieldCipher;
        this.mapper = (rs, i) -> new MedicalLeave(
                rs.getString("id"),
                rs.getString("company_id"),
                rs.getString("employee_id"),
                rs.getString("leave_type"),
                rs.getObject("start_date", LocalDate.class),
                rs.getObject("end_date", LocalDate.class),
                rs.getString("status"),
                fieldCipher.decrypt(rs.getString("notes")),
                toInstant(rs.getTimestamp("created_at")),
                toInstant(rs.getTimestamp("updated_at"))
        );
    }

    /** Lista todas las bajas de la empresa, más recientes primero. */
    public List<MedicalLeave> listByCompany(String companyId) {
        return jdbcTemplate.query("""
                SELECT id, company_id, employee_id, leave_type, start_date,
                       end_date, status, notes, created_at, updated_at
                  FROM medical_leaves
                 WHERE company_id = ?
                 ORDER BY start_date DESC, created_at DESC
                """, mapper, companyId);
    }

    /** Lista las bajas de un empleado concreto. */
    public List<MedicalLeave> listByEmployee(String companyId, String employeeId) {
        return jdbcTemplate.query("""
                SELECT id, company_id, employee_id, leave_type, start_date,
                       end_date, status, notes, created_at, updated_at
                  FROM medical_leaves
                 WHERE company_id = ? AND employee_id = ?
                 ORDER BY start_date DESC
                """, mapper, companyId, employeeId);
    }

    public Optional<MedicalLeave> findById(String id) {
        List<MedicalLeave> rows = jdbcTemplate.query("""
                SELECT id, company_id, employee_id, leave_type, start_date,
                       end_date, status, notes, created_at, updated_at
                  FROM medical_leaves
                 WHERE id = ?
                """, mapper, id);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    public void insert(MedicalLeave m) {
        jdbcTemplate.update("""
                INSERT INTO medical_leaves
                       (id, company_id, employee_id, leave_type, start_date,
                        end_date, status, notes, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())
                """,
                m.id(), m.companyId(), m.employeeId(), m.leaveType(),
                m.startDate(), m.endDate(), m.status(),
                fieldCipher.encrypt(m.notes()));
    }

    public void update(MedicalLeave m) {
        jdbcTemplate.update("""
                UPDATE medical_leaves
                   SET leave_type = ?, start_date = ?, end_date = ?,
                       status = ?, notes = ?, updated_at = NOW()
                 WHERE id = ?
                """,
                m.leaveType(), m.startDate(), m.endDate(),
                m.status(), fieldCipher.encrypt(m.notes()), m.id());
    }

    public void delete(String id) {
        jdbcTemplate.update("DELETE FROM medical_leaves WHERE id = ?", id);
    }

    private static Instant toInstant(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }
}
