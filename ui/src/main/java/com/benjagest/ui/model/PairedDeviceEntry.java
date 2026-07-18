package com.benjagest.ui.model;

/**
 * Equipo emparejado por PIN (multi-puesto de la asesoría). Lo lista Configuración
 * → Sesión para poder VER y REVOCAR los equipos (antes no había forma, y al
 * llegar al límite de 5 no se podía emparejar ninguno más).
 *
 * <p>{@code tokenPrefix} es un fragmento no sensible del token, para distinguir
 * equipos con el mismo nombre. {@code pairedAt}/{@code lastSeenAt} en ISO.
 */
public record PairedDeviceEntry(
        String id,
        String name,
        String tokenPrefix,
        String pairedAt,
        String lastSeenAt
) {}
