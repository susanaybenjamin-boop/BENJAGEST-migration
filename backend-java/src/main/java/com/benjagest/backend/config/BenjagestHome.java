package com.benjagest.backend.config;

import java.nio.file.Path;

/**
 * WINSVC (2026-07-12) — raiz de datos MACHINE-WIDE de la instalacion.
 *
 * <p>El backend puede arrancarlo la UI (como el usuario de Windows) o un
 * SERVICIO de Windows (como LocalSystem). Esas dos cuentas tienen
 * {@code user.home} DISTINTO (el perfil del usuario vs. el de SYSTEM), asi
 * que anclar los datos a {@code user.home} haria que el servicio viera una
 * BD vacia y no los datos del usuario. Para que TODO (BD embebida, su
 * {@code .dbkey}, PDFs, backups, fotos) sea el mismo lo arranque quien lo
 * arranque, cuelga de una raiz comun independiente de la cuenta.
 *
 * <p>Resolucion, en orden (mismo patron que {@link MasterKeyResolver}, que ya
 * guarda {@code secret\master.key} aqui):
 * <ol>
 *   <li>{@code -Dbenjagest.home} si se define (smoke / tests aislados).</li>
 *   <li>{@code %ProgramData%\BENJAGEST} en Windows.</li>
 *   <li>{@code ~/.benjagest} fuera de Windows (dev).</li>
 * </ol>
 */
public final class BenjagestHome {

    private BenjagestHome() {}

    /** Raiz de datos de la instalacion (no crea nada; solo calcula la ruta). */
    public static Path root() {
        String override = System.getProperty("benjagest.home");
        if (override != null && !override.isBlank()) {
            return Path.of(override);
        }
        String programData = System.getenv("ProgramData");
        if (programData != null && !programData.isBlank()) {
            return Path.of(programData, "BENJAGEST");
        }
        return Path.of(System.getProperty("user.home"), ".benjagest");
    }

    /** Subcarpeta bajo la raiz de datos. */
    public static Path resolve(String subdir) {
        return root().resolve(subdir);
    }
}
