package com.benjagest.ui.model;

/**
 * Una membership del usuario logueado. Si tiene varias, la pantalla
 * post-login le permite elegir cual es la empresa activa.
 */
public record Membership(
        String companyId,
        String companyLegalName,
        String companyTradeName,
        String companyType,
        String roleName
) {
}
