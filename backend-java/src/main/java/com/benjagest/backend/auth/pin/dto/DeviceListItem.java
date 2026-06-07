package com.benjagest.backend.auth.pin.dto;

import java.time.Instant;

/**
 * Fila que el OWNER ve en "Mis equipos". No expone el hash del token —
 * solo el prefijo (8 chars del secret en plano) para que el OWNER
 * pueda identificar visualmente qué PC está revocando sin tener que
 * cogerlo físicamente.
 */
public record DeviceListItem(
        String id,
        String name,
        String tokenPrefix,
        Instant pairedAt,
        String pairedByUserId,
        Instant lastSeenAt
) {}
