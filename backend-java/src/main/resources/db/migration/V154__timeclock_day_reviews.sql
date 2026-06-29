-- FICHA-REVIEW (2026-06-29) — "Dar por bueno" un día de fichaje sospechoso.
--
-- El informe Planificado-vs-Real (JOR-4) marca como incidencia los días donde lo
-- fichado no cuadra con lo planificado (faltan horas / no fichó). Esta tabla
-- guarda qué (empleado, día) ha revisado y dado por bueno el admin, para que
-- dejen de aparecer como pendientes. NO altera los fichajes (RD 8/2019 art.34.9):
-- es una marca de revisión aparte.
--
-- COLLATE explícito utf8mb4_unicode_ci: en MariaDB 11.4 el default del servidor
-- es distinto y una FK contra companies/employees (unicode_ci) daría errno 150.
CREATE TABLE time_clock_day_reviews (
    id                  CHAR(36)     NOT NULL,
    company_id          CHAR(36)     NOT NULL,
    employee_id         CHAR(36)     NOT NULL,
    review_date         DATE         NOT NULL,
    reviewed_by_user_id CHAR(36)     NULL,
    reviewed_at         TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    note                VARCHAR(300) NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_tcdr_company_emp_day (company_id, employee_id, review_date),
    CONSTRAINT fk_tcdr_company  FOREIGN KEY (company_id)  REFERENCES companies(id),
    CONSTRAINT fk_tcdr_employee FOREIGN KEY (employee_id) REFERENCES employees(id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
