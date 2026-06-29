-- CONSOL-1 (2026-06-29) — Grupo empresarial definido por el usuario.
--
-- Cimiento de la consolidación contable intragrupo: el OWNER (asesoría o
-- empresario) crea un "grupo" y le asigna las empresas que lo componen. Los
-- slices siguientes (CONSOL-2..5) agregan y consolidan los libros de estas
-- empresas. NO usa parent_company_id (eso es la relación asesoría↔cliente, otra
-- cosa): el grupo es una agrupación de negocio que el usuario decide.
--
-- COLLATE explícito utf8mb4_unicode_ci: FK contra companies en MariaDB 11.4.
CREATE TABLE company_groups (
    id               CHAR(36)     NOT NULL,
    owner_company_id CHAR(36)     NOT NULL,        -- quién es dueño del grupo (activeCompanyId)
    name             VARCHAR(160) NOT NULL,
    active           BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at       TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    CONSTRAINT fk_cg_owner FOREIGN KEY (owner_company_id) REFERENCES companies(id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

CREATE TABLE company_group_members (
    id         CHAR(36)  NOT NULL,
    group_id   CHAR(36)  NOT NULL,
    company_id CHAR(36)  NOT NULL,                 -- empresa miembro del grupo
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_cgm_group_company (group_id, company_id),
    CONSTRAINT fk_cgm_group   FOREIGN KEY (group_id)   REFERENCES company_groups(id) ON DELETE CASCADE,
    CONSTRAINT fk_cgm_company FOREIGN KEY (company_id) REFERENCES companies(id)
) DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;
