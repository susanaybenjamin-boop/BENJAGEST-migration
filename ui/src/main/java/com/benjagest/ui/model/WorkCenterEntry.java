package com.benjagest.ui.model;

import java.math.BigDecimal;

/**
 * Centro de trabajo. Geolocalizacion opcional para validar fichajes.
 */
public record WorkCenterEntry(
        String id,
        String name,
        String address,
        String city,
        String province,
        String postalCode,
        BigDecimal lat,
        BigDecimal lng,
        int radioM,
        String geoPolicy,
        String notes,
        boolean active
) {}
