package com.benjagest.backend.supervision;

import java.util.Arrays;
import java.util.Optional;

/**
 * SUPERV — las tareas automáticas que la app vigila.
 *
 * <p>Elegidas por Benjamin el 2026-11-21 sobre el análisis de
 * {@code docs/plan-supervision.md}, por orden de daño si fallan en silencio.
 *
 * <p>El nombre corto ({@link #jobName()}) es lo que se guarda en
 * {@code job_runs} y lo que ve el usuario en "Estado del sistema". No se usa
 * el nombre de la clase a propósito: renombrar una clase no debe romper el
 * histórico ni los avisos.
 */
public enum SupervisedJob {

    /**
     * Copia de seguridad semanal (lunes 03:00) y la de arranque cuando han
     * pasado más de 7 días. <b>Es lo único irrecuperable</b>: si falla y nadie
     * se entera, el daño se descubre el día que hace falta la copia.
     *
     * <p>Umbral generoso (10 días) porque es semanal: 7 de ciclo normal más
     * margen para un lunes con el equipo apagado.
     */
    BACKUP("backup", "Copia de seguridad", 10),

    /**
     * Avisos de contratos: fin de periodo de prueba, fin de contrato a 30 y 60
     * días, cumpleaños. Hoy es <b>el peor de todos</b>: su único {@code catch}
     * se traga el fallo sin escribir ni una línea de log, y lo que se pierde
     * son plazos con consecuencias legales.
     */
    CONTRACT_ALERTS("contract-alerts", "Avisos de contratos", 2),

    /**
     * Barrido diario del BOE. El caso que originó todo esto: 34 días caído
     * (18-oct a 20-nov de 2026) sin que nadie lo supiera.
     */
    BOE_ALERTS("boe-alerts", "Alertas BOE", 2),

    /**
     * Comprobación de si hay una versión nueva. La ejecuta la UI al arrancar,
     * no el backend.
     *
     * <p><b>Umbral 0 = no se vigila la ausencia</b>, y es a propósito: esta
     * tarea solo corre cuando alguien abre la aplicación. Avisar de que "lleva
     * 5 días sin comprobarse" sería mentira si simplemente ha sido fin de
     * semana. Aquí lo que importa es el FALLO: que haya podido preguntar y no
     * haya podido.
     */
    UPDATE_CHECK("update-check", "Comprobación de actualizaciones", 0);

    private final String jobName;
    private final String label;
    private final int maxDaysWithoutSuccess;

    SupervisedJob(String jobName, String label, int maxDaysWithoutSuccess) {
        this.jobName = jobName;
        this.label = label;
        this.maxDaysWithoutSuccess = maxDaysWithoutSuccess;
    }

    public String jobName() {
        return jobName;
    }

    /** Nombre para el usuario. La UI lo traduce por {@code superv.job.<jobName>}. */
    public String label() {
        return label;
    }

    /**
     * Días que pueden pasar sin una ejecución correcta antes de avisar.
     * <b>0 = no se vigila la ausencia</b> (solo los fallos).
     */
    public int maxDaysWithoutSuccess() {
        return maxDaysWithoutSuccess;
    }

    public boolean watchesAbsence() {
        return maxDaysWithoutSuccess > 0;
    }

    public static Optional<SupervisedJob> byName(String jobName) {
        return Arrays.stream(values())
                .filter(j -> j.jobName.equals(jobName))
                .findFirst();
    }
}
