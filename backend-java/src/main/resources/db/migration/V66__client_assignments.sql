-- =========================================================================
-- V66 — EQUIPO / Reparto de clientes (Slice 5A)
--
-- Permite que una asesoría con varios empleados reparta sus clientes
-- entre ellos. Cada empleado solo ve los clientes asignados a él en
-- "Mis clientes". El OWNER de la asesoría siempre ve todos (no
-- necesita aparecer en esta tabla).
--
-- Delegación: cuando un empleado se ausenta (vacaciones, baja), el
-- OWNER puede delegar temporalmente sus asignaciones a otro empleado
-- usando delegated_to_user_id + delegated_from/until. Durante el
-- rango activo, el delegado también ve esos clientes en su listado.
--
-- Audit_events ya graba user_id por evento (Slice AUDIT-CHAIN); con
-- esto cada acción queda firmada por el empleado real, no por un
-- usuario genérico de la asesoría.
-- =========================================================================

CREATE TABLE client_assignments (
    id CHAR(36) NOT NULL,
    advisory_company_id CHAR(36) NOT NULL,
    employee_user_id CHAR(36) NOT NULL,
    client_company_id CHAR(36) NOT NULL,
    role_in_client VARCHAR(40) NOT NULL DEFAULT 'ADVISOR',
    assigned_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    assigned_by_user_id CHAR(36) NULL,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    -- Delegación temporal: si delegated_to_user_id != NULL y hoy
    -- cae en [delegated_from, delegated_until], el delegado ve este
    -- cliente además del empleado original.
    delegated_to_user_id CHAR(36) NULL,
    delegated_from DATE NULL,
    delegated_until DATE NULL,
    notes TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
        ON UPDATE CURRENT_TIMESTAMP,

    CONSTRAINT pk_client_assignments PRIMARY KEY (id),
    CONSTRAINT fk_ca_advisory FOREIGN KEY (advisory_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_ca_employee FOREIGN KEY (employee_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT fk_ca_client FOREIGN KEY (client_company_id)
        REFERENCES companies (id),
    CONSTRAINT fk_ca_assigned_by FOREIGN KEY (assigned_by_user_id)
        REFERENCES user_accounts (id),
    CONSTRAINT fk_ca_delegated_to FOREIGN KEY (delegated_to_user_id)
        REFERENCES user_accounts (id),
    -- Un cliente solo se asigna una vez activa a un empleado dentro
    -- de la misma asesoría. Reasignar = update, no insert duplicado.
    CONSTRAINT uk_ca_one_per_client UNIQUE (advisory_company_id,
        employee_user_id, client_company_id),
    CONSTRAINT ck_ca_role CHECK (role_in_client IN (
        'ADVISOR', 'ACCOUNTANT', 'EMPLOYEE', 'VIEWER')),
    -- Si hay delegación, ambas fechas obligatorias y desde <= hasta.
    CONSTRAINT ck_ca_delegation_dates CHECK (
        (delegated_to_user_id IS NULL
         AND delegated_from IS NULL
         AND delegated_until IS NULL)
        OR
        (delegated_to_user_id IS NOT NULL
         AND delegated_from IS NOT NULL
         AND delegated_until IS NOT NULL
         AND delegated_from <= delegated_until)
    ),

    INDEX ix_ca_advisory (advisory_company_id),
    INDEX ix_ca_employee (employee_user_id, active),
    INDEX ix_ca_client (client_company_id),
    INDEX ix_ca_delegated (delegated_to_user_id, delegated_from, delegated_until)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
