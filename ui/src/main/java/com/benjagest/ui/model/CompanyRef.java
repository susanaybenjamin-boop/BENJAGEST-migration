package com.benjagest.ui.model;

/** CONSOL-1 — Referencia a una empresa (miembro o candidato de un grupo). */
public record CompanyRef(String companyId, String legalName, String taxIdentifier) {}
