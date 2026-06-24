package com.benjagest.ui.model;

/** Un aviso (AVISOS) de la cartera, con la empresa a la que pertenece. */
public record PendingCompanyBucket(
        String companyId, String companyName,
        String type, int count, String severity) {}
