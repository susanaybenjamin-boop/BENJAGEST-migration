-- =============================================================================
-- V184__job_supervision.sql
--
-- SUPERV (2026-11-21). Benjamin, tras el caso del BOE: "lo vamos a montar, pero
-- no antes de analizar la app y ver en que puntos lo vamos a poner a que
-- supervise". El analisis esta en docs/plan-supervision.md.
--
-- EL PROBLEMA QUE RESUELVE: el 18-oct-2026 el barrido del BOE dejo de
-- funcionar y nadie se entero hasta el 20-nov. No fallaba la logica: fallaba
-- la conexion, el error iba a un log de 5 MB que no mira nadie, y el servicio
-- seguia. De 7 tareas automaticas del backend, solo UNA (los recurrentes)
-- avisaba al fallar.
--
-- Lo importante del diseño: el caso del BOE NO se habria cazado vigilando
-- errores, porque desde fuera no habia ningun error visible. Se habria cazado
-- vigilando AUSENCIA de resultados: "esta tarea lleva N dias sin terminar
-- bien". Por eso se guarda el HISTORICO de ejecuciones y no solo el ultimo
-- fallo.
--
-- Aditiva: dos tablas nuevas, nada que tocar de lo existente.
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1) Historico de ejecuciones de las tareas automaticas.
--
--    Mismo espiritu que `recurring_task_runs` (que ya existia solo para los
--    recurrentes), pero generico: cualquier tarea escribe aqui.
--
--    company_id es NULL para las tareas GLOBALES del sistema (la copia de
--    seguridad, el barrido del BOE, la comprobacion de actualizaciones): no
--    son de una empresa, son de la instalacion. Las que si son por empresa lo
--    rellenan.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_runs (
    id           CHAR(36)     NOT NULL,
    -- Contador monotono. NO es decoracion: ordenar por started_at no vale
    -- porque TIMESTAMP tiene precision de segundo, y dos ejecuciones en el
    -- mismo segundo (un barrido manual justo despues del automatico) quedan
    -- en orden indefinido. Con eso, "cual fue el resultado anterior" podia
    -- devolver el equivocado y el aviso de fallo se repetia. Lo cazo el test.
    seq          BIGINT       NOT NULL AUTO_INCREMENT,
    -- Nombre corto y estable de la tarea (ver el enum SupervisedJob en Java).
    -- No se usa el nombre de la clase a proposito: renombrar una clase no debe
    -- romper el historico ni los avisos.
    job_name     VARCHAR(60)  NOT NULL,
    company_id   CHAR(36)     NULL,
    started_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at  TIMESTAMP    NULL,
    -- OK | ERROR | SKIPPED. SKIPPED = corrio y decidio no hacer nada (p.ej. el
    -- backup de arranque cuando el ultimo es reciente): NO es un fallo, pero
    -- tampoco cuenta como exito para el aviso por ausencia.
    status       VARCHAR(20)  NOT NULL,
    message      TEXT         NULL,
    duration_ms  INT          NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_runs_seq (seq),
    KEY ix_job_runs_name_seq (job_name, seq DESC),
    KEY ix_job_runs_name_started (job_name, started_at DESC),
    KEY ix_job_runs_name_status (job_name, status, started_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- -----------------------------------------------------------------------------
-- 2) De que se ha avisado ya, para NO repetir el aviso.
--
--    Sin esto, "lleva 3 dias sin funcionar" se convertiria en un aviso cada
--    dia hasta que alguien lo arregle, y el usuario aprenderia a ignorarlos —
--    que es exactamente como se pierde un aviso importante. Es la misma
--    precaucion que ya toma el aviso de recurrentes ("solo en la transicion a
--    fallo").
--
--    last_alert_status guarda el ESTADO por el que se aviso (ERROR o STALE), no
--    el estado actual: cuando la tarea se recupera se emite el aviso de
--    recuperacion y se limpia.
-- -----------------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS job_supervision (
    job_name          VARCHAR(60) NOT NULL,
    last_alert_status VARCHAR(20) NULL,
    last_alert_at     TIMESTAMP   NULL,
    updated_at        TIMESTAMP   NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (job_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
